package dev.birb.wgpu.mixin.render;

import com.mojang.blaze3d.pipeline.MainTarget;
import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.birb.wgpu.render.RenderScale;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Sizes the game's render targets for the render scale - but only the main one.
 *
 * <p>{@code RenderTarget#resize} is the funnel every target goes through when its size changes,
 * and most of the callers of it are <em>not</em> the main target: the panorama screenshot
 * resizes the main target with synthetic square dimensions, post-effect chains size their own
 * targets, and a scale applied to all of that would soften composites that were made to line
 * up. The {@code instanceof MainTarget} guard keeps the answer to the one target the fill rate
 * of a phone is actually spent on - and the main target is exactly the one that is the game's
 * whole frame: terrain, entities, water, sky and GUI are all drawn into it, and the present
 * blit stretches whatever it holds.
 *
 * <p>The two {@code @ModifyVariable} handlers run before the body does, so {@code createBuffers}
 * allocates at the scaled size and every view the render passes receive is scaled with it -
 * nothing else in the frame needs to know. The handlers do not cancel anything: at a scale of
 * 1.0 they return the size they were given, which is the un-scaled picture.
 *
 * <p>The unscaled dimensions are recorded by the same handlers that scale, before they scale:
 * a handler's parameter is the value the method was called with, so the record is the window's
 * size (see {@link RenderScale}) no matter what order Mixin runs same-point callbacks in. A
 * separate {@code @Inject} at the same point would race the modifiers for that order, and
 * losing it would record the scaled size and point the swapchain at the wrong half of the
 * difference.
 */
@Mixin(RenderTarget.class)
public abstract class RenderTargetMixin {

    @ModifyVariable(method = "resize(II)V", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private int wgpuMc$scaleWidth(int width) {
        if ((Object) this instanceof MainTarget) {
            RenderScale.noteWindowSize(width, -1);
            return RenderScale.scaleDimension(width);
        }
        return width;
    }

    @ModifyVariable(method = "resize(II)V", at = @At("HEAD"), argsOnly = true, ordinal = 1)
    private int wgpuMc$scaleHeight(int height) {
        if ((Object) this instanceof MainTarget) {
            RenderScale.noteWindowSize(-1, height);
            return RenderScale.scaleDimension(height);
        }
        return height;
    }
}
