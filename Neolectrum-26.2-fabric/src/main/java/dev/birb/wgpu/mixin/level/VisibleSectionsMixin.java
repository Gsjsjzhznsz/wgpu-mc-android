package dev.birb.wgpu.mixin.level;

import dev.birb.wgpu.render.VisibleSectionsRevision;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.extract.LevelExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Counts the rebuilds of the list Minecraft's own occlusion culling fills - the game's own "the
 * list is new" signal, lifted out for the one thing that consumes the list. The 26.2 shape of the
 * same mixin against the 26.1 {@code LevelRenderer}: 26.2 moved the frustum walk, the list and the
 * whole extract path onto {@code LevelExtractor}, and {@code applyFrustum} moved with them.
 *
 * <p>The counter it bumps is what {@code TerrainPass#sendVisibleSections} compares against, and
 * that is the whole of the mechanism: a frame the game did not rebuild the list on does not walk
 * the list, does not allocate the keys and does not call across the bridge.
 */
@Mixin(LevelExtractor.class)
public abstract class VisibleSectionsMixin {

    @Inject(method = "applyFrustum", at = @At("TAIL"))
    private void wgpuMc$theVisibleListIsNew(Frustum frustum, CallbackInfo ci) {
        VisibleSectionsRevision.rebuilt();
    }
}
