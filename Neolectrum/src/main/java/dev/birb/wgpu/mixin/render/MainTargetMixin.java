package dev.birb.wgpu.mixin.render;

import com.mojang.blaze3d.pipeline.MainTarget;
import dev.birb.wgpu.render.RenderScale;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Sizes the main render target for the render scale, at the one place its construction goes
 * through.
 *
 * <p>{@code MainTarget} does not inherit the sizing path it looks like it would: its constructor
 * calls {@code createFrameBuffer} directly, which allocates the attachments itself and bypasses
 * {@code RenderTarget#resize} entirely - {@code Minecraft}'s own startup does
 * {@code new MainTarget(window.getWidth(), window.getHeight())}, and without this mixin the
 * first frames would render un-scaled until the game's first real window resize. Scaling here
 * is what makes the very first frame the scaled one.
 *
 * <p>The unscaled dimensions are recorded by the same handlers that scale, before they scale: a
 * handler's parameter is the value the method was called with, which at this call site is the
 * window's size (see {@link RenderScale}). One dimension per handler, with the other left alone,
 * so no ordering assumption about same-point callbacks is needed.
 */
@Mixin(MainTarget.class)
public abstract class MainTargetMixin {

    @ModifyVariable(method = "createFrameBuffer(II)V", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private int wgpuMc$scaleWidth(int width) {
        RenderScale.noteWindowSize(width, -1);
        return RenderScale.scaleDimension(width);
    }

    @ModifyVariable(method = "createFrameBuffer(II)V", at = @At("HEAD"), argsOnly = true, ordinal = 1)
    private int wgpuMc$scaleHeight(int height) {
        RenderScale.noteWindowSize(-1, height);
        return RenderScale.scaleDimension(height);
    }
}
