package dev.birb.wgpu.mixin.core;

import dev.birb.wgpu.gl.GlState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * {@code glEnable} and {@code glDisable}, which are not in {@code GlStateManager} itself.
 *
 * <p>Every boolean capability - depth test, blend, cull, scissor, polygon offset, colour logic - is
 * one {@code BooleanState} holding its GL enum, and the only two driver calls in the whole of
 * {@code GlStateManager} that are not in an {@code _}-prefixed leaf are the pair inside this class.
 * They are also the only place the capability number is available without reaching into a private
 * field: {@code setEnabled} passes it to GL, so a redirect is handed exactly the enum the state was
 * built with.
 *
 * <p>The class is not public, so it is named by string rather than imported. That is also why this is
 * a separate mixin: a target named by string is a class this file cannot reference, and keeping it
 * apart leaves the main seam readable as a list of the calls it answers.
 */
@Mixin(targets = "com.mojang.blaze3d.platform.GlStateManager$BooleanState")
public class GlBooleanStateMixin {

	@Redirect(
		method = "setEnabled",
		at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glEnable(I)V", remap = false)
	)
	private void wgpuEnable(int capability) {
		GlState.setEnabled(capability, true);
	}

	@Redirect(
		method = "setEnabled",
		at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glDisable(I)V", remap = false)
	)
	private void wgpuDisable(int capability) {
		GlState.setEnabled(capability, false);
	}
}