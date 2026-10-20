package dev.birb.wgpu.rust

/**
 * The renderer bridge, declared for Java 21.
 *
 * On Minecraft 26.1 this half of the mod talks to `rust/wgpu-mc-jni` over the **FFM API** - see
 * `WmNative.kt`, which binds the crate's 64 C-ABI exports with `Linker` and `FunctionDescriptor`. That
 * cannot be done here: `java.lang.foreign` is **preview** on Java 21, and a mod cannot ask a launcher
 * for `--enable-preview`, so the same 64 functions are reached as **JNI entry points** instead. They
 * are implemented in `rust/wgpu-mc-jni/src/jni_abi.rs` on the engine's `all-jni` branch, and every one
 * of them resolves against this class - which is why it is called `WmJni` and not `WmNative`: a stack
 * trace, or an `UnsatisfiedLinkError`, says which of the two bindings failed.
 *
 * `WgpuNative` beside this file is the other bridge and is unchanged: the gameplay side (settings,
 * palettes, sections, entities) has always been JNI, on both branches.
 *
 * ## How the arguments travel
 *
 * Nothing is marshalled. A value this side holds - a texture, a buffer, a pipeline, a command encoder,
 * a sampled surface image - is a **pointer**, and travels as a `Long`. A *descriptor* (a draw call, a
 * render pipeline description, a render pass descriptor) is a struct, and this side lays it out in a
 * direct [java.nio.ByteBuffer] and passes the buffer's address as a `Long` - the layout is the one
 * `bindings.h` describes, so there is exactly one description of it rather than one per side. Strings
 * and booleans are the only things converted.
 *
 * ## What this side owns
 *
 * A `Long` this file returns is an allocation the engine has already handed over, and nothing
 * downstream can tell whether it was ever used. Two of them are *consumed* by the call that finishes
 * with them, and both are easy to leak:
 *
 * - `acquireNextTexture` returns a sampled surface image; `presentSurface` takes it back and presents
 *   it. Dropping one without presenting stalls the swapchain;
 * - `createCommandEncoder` returns an encoder; `submitCommandEncoder` takes it back and submits it.
 *   An encoder that is never submitted holds its recorded work and its pass resources forever.
 *
 * The `drop*` functions take the same kind of handle and free it. Every one of them tolerates being
 * called once and only once; none of them is a no-op on a null.
 */
object WmJni {
    external fun createSurface(wm: Long, display: Long, window: Long): Unit
    external fun configureSurface(wm: Long, width: Int, height: Int, present_mode: Int): Unit
    external fun dropSurface(wm: Long): Unit
    external fun acquireNextTexture(wm: Long): Long
    external fun presentSurface(wm: Long, surface_texture: Long): Unit
    external fun blitFromTexture(wm: Long, texture_view: Long, surface_texture: Long): Unit
    external fun createCommandEncoder(wm: Long): Long
    external fun submitCommandEncoder(wm: Long, encoder: Long): Unit
    external fun flushEncoder(wm: Long, encoder: Long): Unit
    external fun createRenderPass(encoder: Long, descriptor: Long, name: String): Long
    external fun dropRenderPass(pass: Long): Unit
    external fun setScissorRect(pass: Long, x: Int, y: Int, width: Int, height: Int): Unit
    external fun clearColorTexture(encoder: Long, texture: Long, clear_color: Int): Unit
    external fun clearDepthTexture(encoder: Long, texture: Long, clear_depth: Double): Unit
    external fun clearColorAndDepthTextures(encoder: Long, color_texture: Long, clear_color: Int, depth_texture: Long, clear_depth: Double): Unit
    external fun clearColorAndDepthTexturesRegion(encoder: Long, color_texture: Long, clear_color: Int, depth_texture: Long, clear_depth: Double, region_x: Int, region_y: Int, region_width: Int, region_height: Int): Unit
    external fun compileRenderPipeline(wm: Long, descriptor: Long): Long
    external fun createPipelineVariant(wm: Long, source: Long, depth_stencil: Long): Long
    external fun dropRenderPipeline(pipeline: Long): Unit
    external fun pipelineBindings(pipeline: Long, out: Long): Int
    external fun batchingPossible(): Boolean
    external fun drawCall(wm: Long, pass: Long, call: Long): Boolean
    external fun drawCallBatch(wm: Long, pass: Long, template: Long, records: Long): Int
    external fun renderTerrainPass(wm: Long, color: Long, depth: Long, translucent: Boolean): Boolean
    external fun createTexture(wm: Long, format_id: Int, width: Int, height: Int, depth_or_layers: Int, usage: Int, mip_levels: Int, name: String): Long
    external fun dropTexture(texture: Long): Unit
    external fun createTextureView(wm: Long, texture: Long, usage: Int, base_mip_level: Int, mip_levels: Int): Long
    external fun dropTextureView(view: Long): Unit
    external fun writeToTexture(wm: Long, destination: Long, source: Long, source_size: Long, mip_level: Int, depth_or_layer: Int, dest_x: Int, dest_y: Int, width: Int, height: Int): Unit
    external fun copyTextureToBuffer(wm: Long, encoder: Long, source: Long, dest: Long, offset: Long, mip: Int, x: Int, y: Int, width: Int, height: Int): Unit
    external fun copyTextureToTexture(encoder: Long, source: Long, destination: Long, mip: Int, dest_x: Int, dest_y: Int, src_x: Int, src_y: Int, width: Int, height: Int): Unit
    external fun copyBufferToTexture(wm: Long, encoder: Long, buffer: Long, buffer_start: Long, buffer_end: Long, source_x: Int, source_y: Int, source_width: Int, source_height: Int, destination: Long, destination_x: Int, destination_y: Int, copy_width: Int, copy_height: Int, mip_level: Int, depth_or_array_layers: Int): Unit
    external fun createSampler(wm: Long, address_mode_u: Int, address_mode_v: Int, min_filter: Int, mag_filter: Int, max_anisotropy: Int, max_lod: Double): Long
    external fun dropSampler(sampler: Long): Unit
    external fun bindGameBlockAtlas(wm: Long, texture: Long): Unit
    external fun bindGameLightmap(wm: Long, texture: Long): Unit
    external fun createBuffer(wm: Long, label: String, usage: Int, size: Long): Long
    external fun createBufferInit(wm: Long, label: String, usage: Int, data: Long, size: Long): Long
    external fun allocateGpuBufferMapped(wm: Long, size: Long, usages: Long): Long
    external fun dropBuffer(buffer: Long): Unit
    external fun writeToBuffer(wm: Long, buffer: Long, start: Long, length: Long, data: Long): Unit
    external fun writeBufferWith(wm: Long, buffer: Long, data: Long, len: Long): Unit
    external fun copyBufferToBuffer(wm: Long, encoder: Long, src: Long, dest: Long, src_offset: Long, dest_offset: Long, length: Long): Unit
    external fun readBuffer(wm: Long, buffer: Long, offset: Long, length: Long, destination: Long): Boolean
    external fun bufferUsages(buffer: Long): Long
    external fun maxTextureSize(wm: Long): Int
    external fun minUniformOffsetAlignment(wm: Long): Int
    external fun logRenderStats(): Unit
    external fun dumpTextureRgba(wm: Long, texture: Long, path: String, flip_y: Boolean): Boolean

    /**
     * Copies a texture's level 0 into [address] as tightly packed RGBA rows, and answers whether it
     * could.
     *
     * This is `glGetTexImage`'s readback. The game owns the destination - `NativeImage`'s pixels -
     * and [capacity] is what it holds, so nothing is written past it; a texture that does not fit
     * leaves the buffer untouched and answers false, which is what GL does when it refuses.
     *
     * [flip_y] writes the rows bottom-up, which is what a render target needs: this backend keeps
     * Minecraft's OpenGL clip space, so such a texture's first row is at the bottom.
     */
    external fun readTextureRgba(
        wm: Long,
        texture: Long,
        address: Long,
        capacity: Int,
        flip_y: Boolean,
    ): Boolean
    external fun dumpSurfaceTextureRgba(wm: Long, surface_texture: Long, path: String): Boolean
    external fun terrainPassReady(wm: Long): Boolean
    external fun terrainArenaCanGrow(): Boolean
    external fun terrainArenaSections(wm: Long): Int
    external fun terrainArenaSlots(): Int
    external fun terrainArenaUsed(): Int
    external fun terrainArenaLargestSection(): Int
    external fun terrainSectionsDrawn(): Int
    external fun terrainSectionsRefused(): Int
    external fun terrainSectionsRefusedReported(): Int
    external fun terrainSectionsRefusedDropped(): Int
    external fun terrainFluidBlocks(): Int
    external fun terrainFluidQuads(): Int
    external fun terrainLayerCounts(which: Int): Long
    external fun terrainWorldBounds(horizon: Int, min_section_y: Int, max_section_y: Int): Unit
}
