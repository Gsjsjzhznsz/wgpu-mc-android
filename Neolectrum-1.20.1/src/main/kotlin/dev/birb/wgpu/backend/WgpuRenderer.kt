package dev.birb.wgpu.backend

import dev.birb.wgpu.WgpuMcMod
import dev.birb.wgpu.rust.WmJni
import dev.birb.wgpu.rust.WgpuNative
import net.minecraft.client.Minecraft
import org.lwjgl.glfw.GLFW
import org.lwjgl.glfw.GLFWNativeCocoa
import org.lwjgl.glfw.GLFWNativeWayland
import org.lwjgl.glfw.GLFWNativeWin32
import org.lwjgl.glfw.GLFWNativeX11

/**
 * The native renderer itself: created once, against the window the game already made.
 *
 * ## Why the handles are passed at creation rather than looked up later
 *
 * `createWmRendererOnWindow` takes the window so that Rust can create the **surface before it asks for
 * an adapter**, and therefore ask for an adapter that can actually present to it. A renderer created
 * without a window picks an adapter on its own and then finds out it cannot present, which on a machine
 * with two GPUs is the usual outcome rather than the rare one.
 *
 * ## Where this runs on 1.21.1, and why not earlier
 *
 * 26.1 hands the renderer creation to `Minecraft`'s own backend selection - the game asks a `GpuBackend`
 * for its device during construction. **1.21.1 has no such seam**: it creates a GL context in `Window`
 * and never asks anyone. So the renderer is created from the client setup work queue instead, which runs
 * on the first tick - after `Minecraft`'s constructor has finished and therefore after there is a window
 * with a handle, and before the first frame is drawn.
 *
 * The GL context that 1.21.1 creates is **taken away before it exists**: `GLFW_NO_API` is asked for by
 * `WindowMixin`, switched on by `-Pwgpu_mc_no_gl_window`, because a window whose HWND already carries a
 * WGL swapchain refuses a second one - measured as `0x80070005` from D3D12 and `Native window is in use`
 * from Vulkan. See that mixin for the whole of it. With the switch off the game keeps its context and
 * this renderer has no surface to present to, which is the state this class was first written in.
 */
object WgpuRenderer {
	/** What the game is told the largest texture is when there is no device to ask. */
	private const val FALLBACK_MAX_TEXTURE_SIZE = 8192

	/** The alignment every wgpu backend this renderer targets reports, and the floor GL names. */
	private const val FALLBACK_UNIFORM_OFFSET_ALIGNMENT = 256

	/** The renderer pointer, or `0` before [create] has run and after a failed one. */
	@Volatile
	private var handle: Long = 0L

	/** Whether [create] has been tried, so a second tick is not a second renderer. */
	@Volatile
	private var attempted: Boolean = false

	/** The renderer pointer, or `0`. The bridge is handed this for every call that needs a renderer. */
	@JvmStatic
	fun handle(): Long = handle

	/**
	 * Creates the renderer once, or reports why it could not be.
	 *
	 * @return whether there is a renderer now
	 */
	@JvmStatic
	fun create(): Boolean {
		val client = Minecraft.getInstance() ?: run {
			WgpuMcMod.LOGGER.error("wgpu-mc: there is no client to take a window from")
			return false
		}

		return createOnWindow(client.window.window)
	}

	/**
	 * Creates the renderer on a window handle this side was given, for the one caller that has the
	 * handle earlier than anybody else: the window's own constructor.
	 *
	 * The client setup event is too late, and that is what this exists for. Its work is *queued* to
	 * the client thread, and the client thread is the one running `Minecraft`'s constructor, which
	 * reaches GL from inside it - `MainTarget` is built at line 482 and `LevelRenderer.createStars()`
	 * uploads a vertex buffer at 568. A GL call answered by this mod before the library is loaded is
	 * an `UnsatisfiedLinkError` from `WmJni`, and before the renderer exists it is a native call with
	 * a null renderer. Both are avoided by doing this where the window becomes real, which is the
	 * tail of `Window`'s constructor.
	 */
	@JvmStatic
	fun createOnWindow(windowHandle: Long): Boolean {
		if (attempted) {
			return handle != 0L
		}

		attempted = true

		// Loaded here rather than only from the client setup event, for the reason above: that event
		// has not run yet when the game first reaches GL. `System.load` on the same path twice is a
		// no-op, so the later call is still free to happen.
		try {
			WgpuNative.loadWm()
		} catch (throwable: Throwable) {
			WgpuMcMod.LOGGER.error(
				"wgpu-mc: the native renderer library could not be loaded, so this client will run on " +
					"Minecraft's own renderer: {}",
				throwable.toString(),
			)
			WgpuMcMod.LOGGER.debug("wgpu-mc native library load failure", throwable)
			return false
		}

		if (windowHandle == 0L) {
			WgpuMcMod.LOGGER.error("wgpu-mc: there is no window to put a renderer on")
			return false
		}

		val (display, window) = windowHandles(windowHandle)

		// The framebuffer size, in pixels, read from GLFW rather than from the game's window object:
		// this runs before the game's own sizes have settled, and a surface configured for the wrong
		// size is a stretched picture until the first resize.
		val width = IntArray(1)
		val height = IntArray(1)
		GLFW.glfwGetFramebufferSize(windowHandle, width, height)

		return try {
			val created = WgpuNative.createWmRendererOnWindow(display, window, width[0], height[0])

			if (created == 0L) {
				WgpuMcMod.LOGGER.error(
					"wgpu-mc: the renderer could not be created on this window, so nothing will be " +
						"drawn by it; the log above says which step failed",
				)
				false
			} else {
				handle = created
				WgpuMcMod.LOGGER.info(
					"wgpu-mc: renderer created on a {}x{} window",
					width[0],
					height[0],
				)
				true
			}
		} catch (throwable: Throwable) {
			WgpuMcMod.LOGGER.error("wgpu-mc: creating the renderer failed: {}", throwable.toString())
			WgpuMcMod.LOGGER.debug("wgpu-mc renderer creation failure", throwable)
			false
		}
	}

	/**
	 * Whether there is a renderer to talk to.
	 *
	 * Every engine call in `dev.birb.wgpu.gl` asks this first, because the alternative is a native
	 * call with a null renderer - which is not a Java exception but a Rust panic on a JNI frame, and
	 * a panic there aborts the process rather than unwinding. The renderer is created from the
	 * window's constructor, so this is false only when that failed.
	 */
	@JvmStatic
	fun isLive(): Boolean = handle != 0L

	/**
	 * The largest texture the device can hold.
	 *
	 * This exists because the game asks the question in a way that cannot be answered without a device:
	 * `RenderSystem.maxSupportedTextureSize` reads `GL_MAX_TEXTURE_SIZE` and then proves the answer by
	 * allocating a texture at each power of two down from 32768, so a window with no GL context does not
	 * get a wrong number from it - it reaches into a driver that is not there. The adapter was asked the
	 * same question when the device was created, and this is that answer.
	 *
	 * The fallback is what the pre-26.1 mod used, and it is only reached before [create] has run: the
	 * game asks once, during window construction, and caches whatever it is told for the session.
	 */
	@JvmStatic
	fun maxTextureSize(): Int {
		val current = handle

		if (current == 0L) {
			return FALLBACK_MAX_TEXTURE_SIZE
		}

		return try {
			WmJni.maxTextureSize(current)
		} catch (throwable: Throwable) {
			WgpuMcMod.LOGGER.warn(
				"wgpu-mc: the device could not be asked for its largest texture, so the game is told {}: {}",
				FALLBACK_MAX_TEXTURE_SIZE,
				throwable.toString(),
			)
			FALLBACK_MAX_TEXTURE_SIZE
		}
	}

	/**
	 * The alignment a uniform offset has to be a multiple of.
	 *
	 * `glGetInteger(GL_UNIFORM_BUFFER_OFFSET_ALIGNMENT)` is a question about the driver, and on this
	 * side the number belongs to the device - it is what decides how a uniform block is padded when
	 * several of them share one buffer. The engine was asked it when the device was created.
	 */
	@JvmStatic
	fun uniformOffsetAlignment(): Int {
		val current = handle

		if (current == 0L) {
			return FALLBACK_UNIFORM_OFFSET_ALIGNMENT
		}

		return try {
			WmJni.minUniformOffsetAlignment(current)
		} catch (throwable: Throwable) {
			WgpuMcMod.LOGGER.warn(
				"wgpu-mc: the device could not be asked for its uniform offset alignment, so the game is told {}: {}",
				FALLBACK_UNIFORM_OFFSET_ALIGNMENT,
				throwable.toString(),
			)
			FALLBACK_UNIFORM_OFFSET_ALIGNMENT
		}
	}

	/**
	 * Translates a GLFW window handle into the display/window pair the surface needs.
	 *
	 * Two of the four platforms answer with a display and two do not, which is the whole reason this is
	 * a pair rather than one number: on Windows the display is not part of the identity of a window - the
	 * instance is read back from the window itself - while on X11 the window id is only meaningful
	 * together with the `Display` it belongs to. Rust discards whichever of the two it does not need.
	 */
	private fun windowHandles(windowHandle: Long): Pair<Long, Long> = when (GLFW.glfwGetPlatform()) {
		GLFW.GLFW_PLATFORM_WIN32 -> 0L to GLFWNativeWin32.glfwGetWin32Window(windowHandle)
		GLFW.GLFW_PLATFORM_X11 ->
			GLFWNativeX11.glfwGetX11Display() to GLFWNativeX11.glfwGetX11Window(windowHandle)
		GLFW.GLFW_PLATFORM_COCOA ->
			GLFWNativeCocoa.glfwGetCocoaWindow(windowHandle) to 0L
		GLFW.GLFW_PLATFORM_WAYLAND ->
			0L to GLFWNativeWayland.glfwGetWaylandWindow(windowHandle)
		else -> throw IllegalStateException(
			"wgpu-mc: unsupported GLFW platform ${GLFW.glfwGetPlatform()}",
		)
	}
}