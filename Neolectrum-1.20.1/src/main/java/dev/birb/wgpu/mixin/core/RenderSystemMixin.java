package dev.birb.wgpu.mixin.core;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.birb.wgpu.backend.WgpuRenderer;
import dev.birb.wgpu.render.ElectrumFrame;
import dev.birb.wgpu.render.GlSeam;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The three places the game asks GL a question whose answer this renderer owns.
 *
 * <p>1.21.1 has no backend seam: {@code RenderSystem} <em>is</em> the seam, and it is GL all the way
 * down. These injections are the first three of them, and they are the three the game reaches before
 * it can draw anything at all.
 *
 * <h2>Who presents the frame</h2>
 *
 * {@code Minecraft#runTick} ends with {@code window.updateDisplay()}, which calls
 * {@link RenderSystem#flipFrame} once a frame; that method polls events, replays the recorded calls,
 * clears the tesselator and then swaps buffers. Four of those five things the game needs whatever
 * draws the frame, so this does not overwrite the method - it redirects the swap alone. The
 * pre-26.1 mod overwrote {@code flipFrame} outright for the same purpose and had to give up the
 * other four with it, which is why its version reads as a list of things it turned off.
 *
 * <p>A wgpu surface and a GL context are two independent swapchains on one window, and nothing
 * synchronises them with each other: whichever swaps last is what the compositor shows, so both
 * presenting is a picture that alternates between two frames that were never the same frame.
 * Presenting here therefore means not swapping there.
 *
 * <h2>How large a texture the game may make</h2>
 *
 * {@link RenderSystem#maxSupportedTextureSize} is not a getter. It reads {@code GL_MAX_TEXTURE_SIZE}
 * and then <b>proves</b> it by allocating a texture at each power of two down from 32768 until the
 * driver accepts one - so on a window with no context it does not return a wrong number, it reaches
 * into a driver that is not there. The renderer knows the real answer, because it asked the adapter
 * for it, so the question is answered from there instead.
 *
 * <p>This one matters beyond the constructor: the value is a {@code static} that caches its first
 * answer for the life of the process, so whatever is returned the first time is what the game
 * believes about texture atlases for the whole session.
 *
 * <h2>What version of GL this is</h2>
 *
 * {@code initRenderer} asks the context for its version string, and there is no context to ask. The
 * string is what the F3 overlay prints and what a crash report records, so it is worth more than a
 * null: it is where a reader finds out that this client is not running on GL at all.
 *
 * <p>{@code remap = false} is on the injection points that name lwjgl, because {@code GLFW} and
 * {@code GL} are not game classes and are not in any mapping set - the method names still remap with
 * everything else.
 */
@Mixin(RenderSystem.class)
public class RenderSystemMixin {

	/** What the game is told the largest texture is when there is no device to ask. */
	private static final int FALLBACK_MAX_TEXTURE_SIZE = 8192;

	@Redirect(
		method = "flipFrame",
		at = @At(
			value = "INVOKE",
			target = "Lorg/lwjgl/glfw/GLFW;glfwSwapBuffers(J)V",
			remap = false
		)
	)
	private static void wgpuSwapBuffers(long window) {
		if (ElectrumFrame.isEnabled() && ElectrumFrame.present(window)) {
			return;
		}

		GLFW.glfwSwapBuffers(window);
	}

	@Inject(method = "maxSupportedTextureSize", at = @At("HEAD"), cancellable = true)
	private static void wgpuMaxSupportedTextureSize(CallbackInfoReturnable<Integer> cir) {
		if (GlSeam.isGlWindowDisabled()) {
			cir.setReturnValue(WgpuRenderer.maxTextureSize());
		}
	}

	@Redirect(
		method = "initRenderer",
		at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/platform/GLX;_init(IZ)V")
	)
	private static void wgpuSkipGlInit(int debugVerbosity, boolean debugSync) {
		if (!GlSeam.isGlWindowDisabled()) {
			com.mojang.blaze3d.platform.GLX._init(debugVerbosity, debugSync);
		}
	}

	@Redirect(
		method = "initRenderer",
		at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/platform/GLX;getOpenGLVersionString()Ljava/lang/String;")
	)
	private static String wgpuApiDescription() {
		if (GlSeam.isGlWindowDisabled()) {
			return "wgpu-mc (no GL context)";
		}

		return com.mojang.blaze3d.platform.GLX.getOpenGLVersionString();
	}
}