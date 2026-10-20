package dev.birb.wgpu.mixin.chunk;

import dev.birb.wgpu.chunk.RustChunkBake;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Hands the section rebuild to the Rust terrain baker, and records whether Rust took it.
 *
 * <p>26.2 rebuilds a section in
 * {@code SectionRenderDispatcher$RenderSection$CompileTask#doTask(SectionBufferBuilderPack)} - the
 * same task 26.1 named {@code RebuildTask}, renamed and reshaped to carry the transparency resort
 * under one class - on the chunk-build worker thread, from the {@code RenderSectionRegion} snapshot
 * the task was created with. The target string is the only thing that moved: the task still holds
 * its {@code region} field and still answers a {@code SectionTaskResult}, so the injected hooks are
 * the ones 26.1 injected.
 *
 * <p><b>The answer is recorded, and it is what decides whether Minecraft meshes this section at all.</b>
 * The task goes on to build the game's own mesh, upload it into an uber buffer and hand it to a pass that
 * has been taken over - work that is paid for and never read. {@code SectionCompilerMixin} drops that
 * mesh, and it asks {@link RustChunkBake#meshesInRust()} rather than the setting, because the question
 * is not "is the path on" but "does Rust have *this* section". A bake that failed - no block cache yet,
 * an exception, a registry that is not ready - answers no, and Minecraft's mesh is the fallback for that
 * one section instead of a hole in the world.
 */
@Mixin(targets = "net.minecraft.client.renderer.chunk.SectionRenderDispatcher$RenderSection$CompileTask")
public class RustChunkBakeMixin {

    @Shadow
    @Final
    protected RenderSectionRegion region;

    @Inject(method = "doTask", at = @At("HEAD"))
    private void wgpuMc$bakeInRust(SectionBufferBuilderPack pack, CallbackInfoReturnable<Object> cir) {
        // **The rebuild context first, the bake second.** The compile callbacks read the region this
        // thread is rebuilding (they capture none of compile's own arguments - the two loaders ship
        // different compile signatures), and it has to be in place before any compile can run inside
        // this task. bake() records whether Rust took the section, which is what decides whether
        // Minecraft meshes it at all.
        //
        // **No second `noteTookSection` here.** `bake` records the answer itself, where the tables it
        // depends on are; this used to pass `bake(region)`'s return value in, and that value was "did it
        // not throw" - true for every refusal inside, so a section Rust had refused was still dropped
        // from Minecraft's mesh and drawn by neither side.
        RustChunkBake.noteRebuildRegion(this.region);
        RustChunkBake.bake(this.region);
    }

    @Inject(method = "doTask", at = @At("RETURN"))
    private void wgpuMc$forgetTheRebuildRegion(CallbackInfoReturnable<Object> cir) {
        // The context is cleared rather than left behind so that a thread whose last task is done
        // does not answer a compile that reached SectionCompiler some other way with a stale region's
        // position - the callbacks decline on a null, which is the safe answer.
        RustChunkBake.noteRebuildRegion(null);
    }
}
