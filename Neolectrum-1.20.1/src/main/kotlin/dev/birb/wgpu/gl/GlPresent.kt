package dev.birb.wgpu.gl

import dev.birb.wgpu.WgpuMcMod
import dev.birb.wgpu.backend.WgpuRenderer
import dev.birb.wgpu.rust.WmJni

/**
 * The frame boundary, which on this side is a `glBlitFramebuffer` into framebuffer 0.
 *
 * Minecraft renders a frame into its main target and then hands it to the window with
 * `MainTarget.blitToScreen`, which binds its own framebuffer for reading, binds 0 for drawing, and
 * blits. The window has no GL context here, so that blit *is* the present: the source is an engine
 * texture this side made, and the destination is the swapchain image the frame has to acquire,
 * fill and hand back.
 *
 * Nothing else in the game draws into framebuffer 0. That is a property of 1.21.1 rather than a
 * coincidence - every pass goes through a `RenderTarget` and only the final blit names 0 - and it
 * is what makes one `glBlitFramebuffer` a whole frame's worth of present rather than one of many
 * draws that would each need the swapchain image held open.
 *
 * The surface is reconfigured from the *source texture's* size rather than from the window's. They
 * are the same number in every frame that matters, because the main target is allocated from the
 * window's framebuffer size - but when a window is resized, the target is re-made and the swapchain
 * is not, and taking the size from the thing being presented is what keeps the two in step without
 * a second source of truth.
 */
object GlPresent {
	/**
	 * `configure_surface` reads 0 as "the present mode the vsync setting names", which is the same
	 * answer the renderer was created with, so a reconfigure here cannot disagree with it.
	 */
	private const val PRESENT_MODE_FROM_SETTINGS: Int = 0

	private var configuredWidth: Int = 0
	private var configuredHeight: Int = 0
	private var presents: Long = 0L
	private var emptyAcquires: Long = 0L
	private var lastPresentNanos: Long = 0L
	private var slowestNanos: Long = 0L

	/** How many frames have been handed to the window, which is what a stalled picture is measured against. */
	@JvmStatic
	fun presents(): Long = presents

	/**
	 * Blits one engine texture into an acquired swapchain image and presents it.
	 *
	 * @param view the colour view of the framebuffer `glBlitFramebuffer` was reading
	 * @param width the view's width, which is the size the swapchain is configured to
	 * @param height the view's height
	 */
	@JvmStatic
	fun present(view: Long, width: Int, height: Int) {
		val wm = WgpuRenderer.handle()
		if (wm == 0L || view == 0L || width <= 0 || height <= 0) {
			return
		}

		// The frame's draws are this side's own render passes, and the blit below is what reads their
		// result - so they are submitted first. This is the only moment a frame is known to be over:
		// the game has finished drawing into its main target and is handing it to the window, and the
		// next thing that happens is another frame's worth of draws into the same target.
		GlDraws.endFrame(wm, "present")

		if (width != configuredWidth || height != configuredHeight) {
			WmJni.configureSurface(wm, width, height, PRESENT_MODE_FROM_SETTINGS)
			configuredWidth = width
			configuredHeight = height
			WgpuMcMod.LOGGER.info("wgpu-mc: swapchain configured for the game's own target, {}x{}", width, height)
		}

		val started = System.nanoTime()
		val surface = WmJni.acquireNextTexture(wm)
		if (surface == 0L) {
			// The window is occluded, minimised, or the swapchain is being rebuilt. There is no
			// image to draw into this frame, and the frame is still this side's - so it is counted
			// and dropped rather than treated as a failure to render.
			emptyAcquires++
			if (emptyAcquires == 1L) {
				WgpuMcMod.LOGGER.info(
					"wgpu-mc: the surface handed over no image for a frame this side had already drawn; that is an occluded or minimised window, not a lost frame",
				)
			}
			return
		}

		WmJni.blitFromTexture(wm, view, surface)
		WmJni.presentSurface(wm, surface)

		val elapsed = System.nanoTime() - started
		if (elapsed > slowestNanos) {
			slowestNanos = elapsed
		}
		lastPresentNanos = elapsed
		presents++
		if (presents == 1L) {
			WgpuMcMod.LOGGER.info(
				"wgpu-mc: the game's own frame reached the window, {}x{}, drawn by this renderer through the GL seam",
				width,
				height,
			)
		}
	}

	/**
	 * The last present's cost in milliseconds, and the worst one seen.
	 *
	 * A present that blocks is the difference between "the game is slow" and "the game is waiting for
	 * the driver", and those two look identical from the frame rate alone.
	 */
	@JvmStatic
	fun lastPresentMillis(): Double = lastPresentNanos / 1_000_000.0

	@JvmStatic
	fun slowestPresentMillis(): Double = slowestNanos / 1_000_000.0

	/** A window that was resized or moved to another monitor presents into a surface of a new size. */
	@JvmStatic
	fun invalidateSurface() {
		configuredWidth = 0
		configuredHeight = 0
	}
}
