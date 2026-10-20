package dev.birb.wgpu.render

import dev.birb.wgpu.WgpuMcMod
import dev.birb.wgpu.backend.WgpuRenderer
import dev.birb.wgpu.gl.GlFramebuffers
import dev.birb.wgpu.rust.WmJni
import org.lwjgl.glfw.GLFW
import org.lwjgl.system.MemoryStack

/**
 * Presents the frame the game drew, in place of the one GLFW would have swapped.
 *
 * ## What the frame is, on 1.21.1
 *
 * The frame ends up in `GlFramebuffers`' window target, not here, because 1.21.1 does not end a frame
 * with a `glBlitFramebuffer`: `RenderTarget._blitToScreen` binds the blit shader, points
 * `DiffuseSampler` at the main target's colour texture and **draws** a screen-sized quad with
 * framebuffer 0 bound. That quad is an ordinary draw and lands in the ordinary place a draw with
 * framebuffer 0 bound lands - the window target - so by the time `flipFrame` runs, the frame is
 * already drawn and all that is left is to hand it to the surface.
 *
 * ## Why the frame has to be taken away rather than added to
 *
 * A wgpu surface and a GL context cannot both present to one window: whichever swaps last is what
 * the compositor shows, and the two are not synchronised with each other. So a frame presented here
 * is a frame GL must not also present, and the redirect in `RenderSystemMixin` is what makes the
 * choice - it is the same place the pre-26.1 mod overwrote, for the same reason. The redirect only
 * stands aside when [isEnabled] says this side owns the window, which is the same flag the mixin
 * that keeps a GL context from being made reads - a window cannot have a GL context *and* be
 * presented from here, and the two places that decide it have to agree.
 *
 * `gradlew runClient -Pwgpu_mc_no_gl_window=true`
 */
object ElectrumFrame {
    /** Zero is "follow the vsync setting", so a reconfigure here cannot disagree with creation. */
    private const val PRESENT_MODE_FROM_SETTINGS: Int = 0

    private var presents: Long = 0L

    /** The size the surface was last configured for, so a frame that changed nothing does not reconfigure. */
    private var configuredWidth: Int = 0
    private var configuredHeight: Int = 0

    /** How many frames reached each way out, so a window that stops moving says which one it took. */
    private var noRenderer: Long = 0L
    private var noWindowView: Long = 0L
    private var noSurface: Long = 0L
    private var reported: Boolean = false

    /** Whether a frame presented here is the frame the window shows. */
    @JvmStatic
    fun isEnabled(): Boolean = GlSeam.isGlWindowDisabled

    /**
     * Presents the frame the game drew, and says whether it did.
     *
     * `false` means the caller must swap buffers itself, which is what a launch that kept its GL
     * context wants. `true` means the window has been handed a frame and GL must not swap on top of
     * it - including the frames this side cannot present, because a swap on a window with no GL
     * context is an error in the log every frame and presents nothing either.
     *
     * It is deliberately not an exception: this runs inside `RenderSystem.flipFrame`, and a throw
     * there is a crash on the render thread rather than a frame that did not happen.
     *
     * @param window the GLFW window handle `flipFrame` was given
     * @return whether this presented the frame
     */
    @JvmStatic
    fun present(window: Long): Boolean {
        if (!isEnabled()) {
            return false
        }

        val renderer = WgpuRenderer.handle()
        if (renderer == 0L) {
            noRenderer++
            report()
            return false
        }

        var width = 0
        var height = 0
        MemoryStack.stackPush().use { stack ->
            val w = stack.mallocInt(1)
            val h = stack.mallocInt(1)
            GLFW.glfwGetFramebufferSize(window, w, h)
            width = w.get(0)
            height = h.get(0)
        }
        if (width <= 0 || height <= 0) {
            return true
        }

        // The surface is configured when the window's size moves and not otherwise, because a
        // window that has been resized has a swapchain of the old size and `acquire_next_texture`
        // cannot tell a stale configuration from a driver hiccup - it recovers to the size it was
        // last configured with. Reconfiguring a swapchain that is already the right size discards
        // the images in flight for nothing, which is a frame the surface would have to be asked for
        // twice.
        if (width != configuredWidth || height != configuredHeight) {
            WmJni.configureSurface(renderer, width, height, PRESENT_MODE_FROM_SETTINGS)
            configuredWidth = width
            configuredHeight = height
        }

        val view = GlFramebuffers.windowView()
        if (view == 0L) {
            noWindowView++
            report()
            return true
        }

        val surface = WmJni.acquireNextTexture(renderer)
        if (surface == 0L) {
            // The surface handed over nothing - the window is occluded, or the swapchain is being
            // rebuilt. It is still this side's frame, so the caller must not swap buffers.
            noSurface++
            report()
            return true
        }

        WmJni.blitFromTexture(renderer, view, surface)
        WmJni.presentSurface(renderer, surface)

        presents++
        if (presents == 1L || presents % 300L == 0L) {
            WgpuMcMod.LOGGER.info(
                "wgpu-mc: presented {} frame(s) wgpu drew, {}x{}",
                presents,
                width,
                height,
            )
            // The engine's own resource counters, at the one cadence this side already has. They are
            // the only way to tell a leak of textures apart from one of buffers or encoders - a
            // process's memory says only that something grew - and the Rust side is what decides
            // whether the line is worth printing, so asking costs one call per three hundred frames.
            WmJni.logRenderStats()
        }
        return true
    }

    /**
     * Says once where the frames are going when most of them are not being presented.
     *
     * A window that stops moving has three explanations and they look identical from outside the
     * process: no renderer, no window target, and a surface that will not hand an image over. The
     * counts are what tell them apart, and one line each is what keeps a broken frame from being a
     * broken log.
     */
    private fun report() {
        if (reported || presents == 0L) {
            return
        }
        if (noRenderer + noWindowView + noSurface < 60L) {
            return
        }
        reported = true
        WgpuMcMod.LOGGER.warn(
            "wgpu-mc: {} frame(s) reached the present and only {} went to the window: no renderer {}, " +
                "no window target {}, no swapchain image {}",
            noRenderer + noWindowView + noSurface + presents,
            presents,
            noRenderer,
            noWindowView,
            noSurface,
        )
    }
}
