package dev.birb.wgpu.mixin.render;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The vignette is this renderer's own draw. The 26.2 shape of the same mixin against the 26.1
 * {@code Gui}: 26.2 moved the camera overlays - the vignette with them - onto the new
 * {@code Hud} class, whose {@code extractVignette} this cancels exactly the way the old one was.
 */
@Mixin(Hud.class)
public class InGameHudMixin {

    @Inject(method = "extractVignette", cancellable = true, at = @At("HEAD"))
    private void pleaseDoNotTheVignette(GuiGraphicsExtractor context, Entity entity, CallbackInfo ci) {
        ci.cancel();
    }
}
