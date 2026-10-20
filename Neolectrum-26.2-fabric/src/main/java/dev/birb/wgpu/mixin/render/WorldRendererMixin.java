package dev.birb.wgpu.mixin.render;

import dev.birb.wgpu.chunk.RustChunkBake;
import dev.birb.wgpu.rust.WgpuNative;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.extract.LevelExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Forgets the section bake on both sides when the world changes. The 26.2 shape of the same mixin
 * against the 26.1 {@code LevelRenderer}: a new world, a dimension change and the way back to the
 * title screen all arrive through {@code LevelExtractor#setLevel} now - the last one as
 * {@code setLevel(null)} - so this is the one place that covers every way the ground under the
 * renderer can be replaced.
 *
 * <p>Without it the two sides keep describing the world that was left behind: the native side bakes
 * against a cache of sections that no longer exist, and this side believes sections it has already
 * sent are there - and nothing rebuilds a section whose blocks have not changed, so the new world
 * would draw the old world's ground wherever the two share coordinates.
 */
@Mixin(LevelExtractor.class)
public abstract class WorldRendererMixin {

    @Inject(method = "setLevel", at = @At("HEAD"))
    private void wgpuMc$forgetTheSectionBake(ClientLevel level, CallbackInfo ci) {
        RustChunkBake.forgetAll(WgpuNative.clearSections());
    }

    /**
     * Waits for the block registry before a world is meshed, so Rust bakes every section on its
     * first build instead of Minecraft meshing the world and Rust replacing it a few seconds
     * later. {@code setLevel} runs on the render thread, which is the thread that meshes, so
     * blocking here is what makes the registry ready in time. {@code setLevel(null)} is the way
     * back to the title screen and has no world to mesh, so it is skipped and pays nothing.
     */
    @Inject(method = "setLevel", at = @At("HEAD"))
    private void wgpuMc$waitForTheBlockCache(ClientLevel level, CallbackInfo ci) {
        if (level == null) {
            return;
        }
        dev.birb.wgpu.BlockCache.awaitCached();
    }
}
