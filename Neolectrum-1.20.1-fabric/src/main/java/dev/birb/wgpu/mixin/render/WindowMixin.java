package dev.birb.wgpu.mixin.render;

import com.mojang.blaze3d.platform.Window;
import dev.birb.wgpu.WgpuMcMod;
import dev.birb.wgpu.backend.WgpuRenderer;
import dev.birb.wgpu.render.GlSeam;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWErrorCallbackI;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.system.MemoryUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Creates the game's window without a GL context, and stops the game insisting on one afterwards.
 *
 * <p>This is the one change everything else in the 1.20.1 line depends on, and it is not optional. A
 * window that has a GL context has a <em>WGL swapchain</em> on its HWND (and its GLX/EGL
 * equivalents elsewhere), and no second graphics API can create one there. A wgpu surface and the
 * game's own GL context are two swapchains on one window, and nothing synchronises them with each
 * other - so the window is taken away from GL before the surface exists, which is what the
 * renderer's own design wants.
 *
 * <h2>The 1.20.1 injection point</h2>
 *
 * 1.20.1 has no backend seam: Blaze3D <em>is</em> GL. On the Fabric build the window is created by
 * the game's own {@code Window} constructor, which sets its hints and then hands the creation to
 * {@code GLFW.glfwCreateWindow} directly - so the {@code GLFW_NO_API} hint is set immediately
 * before that call, overriding whatever hints were set earlier (hints persist until the window is
 * created). The Forge build answers the same job with its own copy of this mixin, injected before
 * the {@code ImmediateWindowHandler} call its patched constructor hands creation to; the two
 * copies exist because the creation site is not the same on the two loaders, and every other line
 * of them is.
 *
 * <h2>Four things, because deleting the context is not one call</h2>
 *
 * The window has to be created without a context (the hint below), and then three places have to
 * be told that its absence is on purpose rather than a failure. The one that actually stops the
 * game is {@code GLFW.glfwMakeContextCurrent}: on a window created with {@code GLFW_NO_API} it is
 * a GLFW error, not a no-op, and the constructor installed {@code Window::bootCrash} as GLFW's
 * error callback earlier - a method that opens a <b>modal</b> {@code tinyfd_messageBox} and only
 * then throws. The render thread parks inside a dialog nobody can see. The error callback is
 * replaced at the same moment the hint is set, because a GLFW error from that point on is the
 * expected consequence of the hint rather than something to ask the player about.
 *
 * <p>{@code GL.createCapabilities()} then has no context to read, and LWJGL answers a call made
 * without capabilities by throwing from wherever the next GL call lands - which is far from here.
 * So that call is skipped too, and the renderer is created at the constructor's tail, the first
 * moment the window handle exists.
 *
 * <p>The seam switch behind every branch here is {@link GlSeam}, which defaults to on: a window
 * that has a GL context is one this renderer can never present to.
 */
@Mixin(Window.class)
public class WindowMixin {

        /**
         * The replacement error callback, held so it is not collected.
         *
         * LWJGL's callbacks are native objects with a lifetime: one that the JVM collects is freed, and a
         * freed callback that GLFW still holds is a crash rather than a missing warning.
         */
        private static GLFWErrorCallbackI wgpuBootErrorCallback;

        /**
         * The GLFW handle this constructor created.
         *
         * Read rather than passed: the injection at the tail of the constructor has no other way to name
         * the window, and capturing it from the {@code glfwMakeContextCurrent} redirect would depend on
         * a call this mixin is free to skip.
         */
        @Shadow
        private long window;

        @Inject(
                method = "<init>",
                at = @At(
                        value = "INVOKE",
                        target = "Lorg/lwjgl/glfw/GLFW;glfwCreateWindow(IILjava/lang/CharSequence;JJ)J",
                        remap = false,
                        shift = At.Shift.BEFORE
                )
        )
        private void wgpuCreateWindowWithoutGl(CallbackInfo ci) {
                if (!GlSeam.isGlWindowDisabled()) {
                        return;
                }

                GLFW.glfwWindowHint(GLFW.GLFW_CLIENT_API, GLFW.GLFW_NO_API);
                GLFW.glfwSetErrorCallback(wgpuBootErrorCallback());
        }

        /**
         * Brings the renderer up as soon as the window it draws into exists.
         *
         * <p>This is the earliest point that is also late enough. The native library has to be loaded and
         * the renderer created <em>before</em> {@code Minecraft}'s constructor reaches GL, which it does
         * from inside itself. {@code ClientModInitializer} runs earlier still, and that is where the
         * library is loaded; the renderer cannot be created there because there is no window yet.
         *
         * <p>The window is the bound rather than a choice: a wgpu surface is created on this window's
         * handle before the adapter is asked for, so nothing here can run any earlier.
         */
        @Inject(method = "<init>", at = @At("TAIL"))
        private void wgpuCreateRendererOnTheNewWindow(CallbackInfo ci) {
                if (!GlSeam.isGlWindowDisabled()) {
                        return;
                }

                WgpuRenderer.createOnWindow(this.window);
        }

        private static GLFWErrorCallbackI wgpuBootErrorCallback() {
                if (wgpuBootErrorCallback == null) {
                        wgpuBootErrorCallback = (error, description) -> WgpuMcMod.LOGGER.warn(
                                "wgpu-mc: GLFW error {}: {} - the window was created without a GL context on purpose, so this one is expected",
                                error,
                                MemoryUtil.memUTF8(description)
                        );
                }

                return wgpuBootErrorCallback;
        }

        @Redirect(
                method = "<init>",
                at = @At(
                        value = "INVOKE",
                        target = "Lorg/lwjgl/glfw/GLFW;glfwMakeContextCurrent(J)V",
                        remap = false
                )
        )
        private void wgpuSkipMakeContextCurrent(long window) {
                if (!GlSeam.isGlWindowDisabled()) {
                        GLFW.glfwMakeContextCurrent(window);
                }
        }

        @Redirect(
                method = "<init>",
                at = @At(
                        value = "INVOKE",
                        target = "Lorg/lwjgl/opengl/GL;createCapabilities()Lorg/lwjgl/opengl/GLCapabilities;",
                        remap = false
                )
        )
        private GLCapabilities wgpuSkipCreateCapabilities() {
                if (GlSeam.isGlWindowDisabled()) {
                        WgpuMcMod.LOGGER.info(
                                "wgpu-mc: the window has no GL context, so no capabilities are created for it; GL is answered by this mod from here on"
                        );
                        return null;
                }

                return GL.createCapabilities();
        }
}
