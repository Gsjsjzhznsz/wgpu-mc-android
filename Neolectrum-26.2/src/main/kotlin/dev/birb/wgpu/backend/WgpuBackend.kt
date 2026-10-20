package dev.birb.wgpu.backend

import com.mojang.blaze3d.GLFWErrorCapture
import com.mojang.blaze3d.shaders.GpuDebugOptions
import com.mojang.blaze3d.shaders.ShaderSource
import com.mojang.blaze3d.systems.BackendCreationException
import com.mojang.blaze3d.systems.GpuBackend
import com.mojang.blaze3d.systems.GpuDevice
import dev.birb.wgpu.AndroidRuntime
import dev.birb.wgpu.WgpuMcMod
import dev.birb.wgpu.gui.OptionPages
import dev.birb.wgpu.render.Wgpu
import dev.birb.wgpu.rust.WgpuNative
import java.lang.foreign.MemorySegment
import org.lwjgl.glfw.GLFW

/**
 * The entry point that makes Minecraft render through wgpu instead of OpenGL.
 *
 * [setWindowHints] is what actually prevents a GL context from ever being created: it is called
 * by `Window#createWindow` before `glfwCreateWindow`, so asking for `GLFW_NO_API` gives wgpu a
 * plain window it can attach a DX12/Vulkan/Metal surface to.
 *
 * Selected in Java by `dev.birb.wgpu.mixin.core.WgpuBackendSelectionMixin`, because mixins cannot
 * be written in Kotlin.
 */
class WgpuBackend : GpuBackend {

    override fun getName(): String = "wgpu"

    override fun setWindowHints() {
        GLFW.glfwWindowHint(GLFW.GLFW_CLIENT_API, GLFW.GLFW_NO_API)
    }

    override fun handleWindowCreationErrors(error: GLFWErrorCapture.Error) {
        // A failed window creation is reported by Minecraft itself; the default handling is fine.
        WgpuMcMod.LOGGER.error("wgpu: window creation failed: {}", error)
    }

    @Throws(BackendCreationException::class)
    override fun createDevice(
        window: Long,
        defaultShaderSource: ShaderSource,
        debugOptions: GpuDebugOptions,
        postDeviceCreated: Runnable,
    ): GpuDevice {
        // The 26.2 signature carries a post-create hook the game runs once the device wrapper is
        // up; the 26.1 flow built everything inside this call, so the hook runs here before the
        // answer is returned and there is nothing left for it to do.
        postDeviceCreated?.run()

        // The window is created before the device - Minecraft calls `setWindowHints`, creates the
        // window, and only then asks the backend for a device - so the native handles are
        // available here. Handing them to Rust is what lets the adapter be required to support
        // the surface; without that, wgpu may pick an adapter that cannot present to this window
        // and the game renders every frame into a swapchain that never reaches the screen.
        //
        // Android is the branch where "the native handles" do not exist: the launcher's GLFW is a
        // stub whose window pointer is its own structure and whose native accessors are not
        // implemented, so there is nothing `windowHandles` could produce. Two ways to the window,
        // in the order they can fail:
        //
        //  1. The Pojav-family launcher bridge (FCL, PojavLauncher and their forks). These
        //     launchers keep the game's `ANativeWindow` in a process-global native struct their
        //     own library exports - and carry **no framework classes on this JVM's classpath at
        //     all**, which is why the reflection route below cannot be the first choice. Rust
        //     resolves the window itself through the bridge; nothing Android-shaped ever crosses
        //     the JNI border here.
        //  2. The `android.view.Surface` reflection walk, for a launcher that does expose the
        //     framework but has no Pojav-family bridge. A 0 return from route 1 means "no
        //     bridge" (the window checks happen before any backend is selected), so this is the
        //     clean fallback rather than a second attempt at the same thing.
        val (framebufferWidth, framebufferHeight) = WgpuSurface.framebufferSize(window)
        val renderer = if (AndroidRuntime.isAndroid) {
            val viaBridge = WgpuNative.createWmRendererOnPojavWindow()
            if (viaBridge != 0L) {
                viaBridge
            } else {
                val surface = AndroidRuntime.findGameSurface()
                    ?: throw BackendCreationException(
                        "wgpu-mc could not find a window to present through on Android: this " +
                            "launcher offers neither the Pojav-family native bridge nor a game " +
                            "Surface in its view hierarchy. The log names what was searched.",
                        BackendCreationException.Reason.OTHER,
                    )
                WgpuNative.createWmRendererOnAndroidSurface(surface, framebufferWidth, framebufferHeight)
            }
        } else {
            val (display, nativeWindow) = try {
                WgpuSurface.windowHandles(window)
            } catch (error: IllegalStateException) {
                throw BackendCreationException(
                    error.message ?: "wgpu-mc cannot present to this window",
                    BackendCreationException.Reason.OTHER,
                )
            }
            WgpuNative.createWmRendererOnWindow(display, nativeWindow, framebufferWidth, framebufferHeight)
        }
        if (renderer == 0L) {
            // Rust has already logged which backend it tried and why each one failed. Throwing
            // rather than letting Minecraft carry on with a null device turns this into the
            // "No supported graphics backend was found" error screen instead of a crash later.
            throw BackendCreationException(
                "wgpu-mc could not create a renderer on any backend. See the log for the reason, " +
                    "then choose a different backend on the Electrum options page (or in " +
                    "config/wgpu-mc-renderer.json) and restart.",
                BackendCreationException.Reason.OTHER,
            )
        }

        val device = WgpuDevice(
            defaultShaderSource,
            MemorySegment.ofAddress(renderer),
            surfaceAttached = true,
        )
        // The renderer's own `vsync` setting owns the present mode; Minecraft's option of the same
        // name is kept in step with it, because the F3 overlay and other mods read that one. It goes
        // here rather than in the mod's client setup for a reason: changing it runs Minecraft's own
        // consumer, which asserts that it is on the *render* thread - and the setup event's work
        // runs on a loading worker, where that assertion is an exception.
        OptionPages.syncVanillaVsync()
        // The wgpu description rather than the device's `getBackendName`: that accessor answers
        // vanilla's question ("which API is this?"), while this line is the mod's own and should
        // name the wgpu build too.
        WgpuMcMod.LOGGER.info("wgpu-mc backend initialised through {}", WgpuNative.getBackendSafe())
        // The native renderer exists from here on. The block cache waits for this before building
        // the registry the Rust terrain baker reads; nothing else reads it, on purpose - see
        // `Wgpu#isRendererLive`.
        Wgpu.setRendererLive(true)
        // The pipeline precompile runs off the render thread and needs a device to compile against;
        // this is the only place one exists before the game has a pass open. See `PipelinePrecompiler`
        // for why the work is worth doing there rather than at the first draw.
        Wgpu.setDevice(device)
        return GpuDevice(device) { /* 26.2's post-create hook; the work already ran above */ }
    }
}
