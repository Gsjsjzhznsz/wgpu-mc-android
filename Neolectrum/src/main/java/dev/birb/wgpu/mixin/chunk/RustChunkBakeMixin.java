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
 * <p>26.1 rebuilds a section in
 * {@code SectionRenderDispatcher$RenderSection$RebuildTask#doTask(SectionBufferBuilderPack)}, on the
 * chunk-build worker thread, from the {@code RenderSectionRegion} snapshot the task was created
 * with. Hooking its head means the Rust bake reads the same snapshot, on the same thread, for the
 * same section - no second copy of the world and no extra scheduling.
 *
 * <p><b>The answer is recorded, and it is what decides whether Minecraft meshes this section at all.</b>
 * The task goes on to build the game's own mesh, upload it into an uber buffer and hand it to a pass that
 * has been taken over - work that is paid for and never read. {@code SectionCompilerMixin} drops that
 * mesh, and it asks {@link RustChunkBake#meshesInRust()} rather than the setting, because the question
 * is not "is the path on" but "does Rust have *this* section". A bake that failed - no block cache yet,
 * an exception, a registry that is not ready - answers no, and Minecraft's mesh is the fallback for that
 * one section instead of a hole in the world.
 *
 * <p>What is left of the setting when it is off: this hook is a boolean check and the mixin that drops
 * the mesh returns immediately, so both halves cost nothing while the path is Minecraft's.
 */
@Mixin(targets = "net.minecraft.client.renderer.chunk.SectionRenderDispatcher$RenderSection$RebuildTask")
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
