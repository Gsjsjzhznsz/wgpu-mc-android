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
 * <p>This is the one change everything else on this branch depends on, and it is not optional. A
 * window that has a GL context has a <em>WGL swapchain</em> on its HWND, and no second graphics API
 * can create one there: measured, on this machine, both backends refuse with the same meaning in two
 * different spellings of it -
 *
 * <pre>
 * [ERROR wgpu_hal::dx12] SwapChain creation error: 拒绝访问。 (0x80070005)
 * [ERROR wgpu_core::device::resource] surface configuration failed: Native window is in use
 * </pre>
 *
 * <p>26.1 never meets this because its {@code GpuBackend} seam means the game asks a backend for a
 * window and the backend never makes a GL context. 1.21.1 has no such seam: Blaze3D <em>is</em> GL,
 * so a wgpu swapchain and the game's own renderer cannot share the window, and one of them has to
 * go. This mixin takes the window away from GL, which is what the renderer's own design wants.
 *
 * <h2>Four things, because deleting the context is not one call</h2>
 *
 * The window has to be created without one (the hint below), and then three places have to be told
 * that its absence is on purpose rather than a failure. The one that actually stopped the game is
 * {@code GLFW.glfwMakeContextCurrent}: on a window created with {@code GLFW_NO_API} it is a GLFW
 * error, not a no-op, and the constructor installed {@code Window::bootCrash} as GLFW's error
 * callback four lines further up - a method that opens a <b>modal</b> {@code tinyfd_messageBox} and
 * only then throws. The render thread parked inside a dialog nobody could see: a thread dump showed
 * {@code TinyFileDialogs.ntinyfd_messageBox <- Window.bootCrash(Window.java:200) <-
 * GLFW.glfwMakeContextCurrent(Window.java:104)}, with the game never reaching a frame.
 *
 * <p>{@code GL.createCapabilities()} then has no context to read, and LWJGL answers a call made
 * without capabilities by throwing from wherever the next GL call lands - which is far from here.
 * And the boot error callback is replaced at the same place, because a GLFW error from this point on
 * is the expected consequence of the hint below rather than something to ask the player about.
 *
 * <h2>Where all of that has to go</h2>
 *
 * {@code Window}'s constructor does not create the window. It sets its own hints and then hands the
 * creation to NeoForge's early-window handler
 * ({@code ImmediateWindowHandler.setupMinecraftWindow}), so the hint has to be set between those two
 * - which is what the injection point below is. The obvious-looking alternative, redirecting
 * {@code GLFW.glfwWindowHint}, cannot work here: this version never asks for a client API at all, it
 * takes GLFW's default, so there is no call to redirect.
 *
 * <p>The error callback is installed at the same point rather than by redirecting the private
 * {@code setBootErrorCallback} it belongs to. That was tried and does not work: a redirect aimed at
 * that method reports {@code Scanned 0 target(s)}, because what the method actually contains is a
 * call to {@code glfwSetErrorCallback} whose argument is a method reference, and Mixin's search for
 * the call does not find it. Injecting at an lwjgl call in the constructor is both simpler and one
 * fewer private method named from outside.
 *
 * <p>NeoForge's early loading window is the one thing that must not already exist when this runs -
 * it draws through GL. That is what {@code configureEarlyWindow} in the build script is for: it
 * writes {@code earlyWindowControl = false} into the run's {@code fml.toml}, so the first window
 * this game creates is this one.
 *
 * <p>Off by default, and switched by {@code -Pwgpu_mc_no_gl_window}. The seam answers every GL call
 * this game makes now - the client reaches its title screen through it and presents there, at
 * 854x480 and again after a resize - so this is the way the branch is started rather than a step of
 * the work in progress. It stays off by default until the seam's redirects are conditional: they
 * are not, so a launch without the property has a GL context and still sends the game's calls to a
 * renderer that was never created.
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
			target = "Lnet/minecraftforge/fml/loading/ImmediateWindowHandler;setupMinecraftWindow(Ljava/util/function/IntSupplier;Ljava/util/function/IntSupplier;Ljava/util/function/Supplier;Ljava/util/function/LongSupplier;)J",
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
	 * from inside itself: {@code MainTarget} is built at line 482 and {@code LevelRenderer.createStars}
	 * uploads a vertex buffer at 568. NeoForge's client setup event is too late for both - its work is
	 * queued to the client thread, and that thread is the one running that constructor.
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