package dev.birb.wgpu.api

import dev.birb.wgpu.rust.WgpuNative

/**
 * **The frame's channel: where another renderer's passes sit.**
 *
 * This is the pre-embedded half of the Iris story, offered to any mod that wants to drive a pass in
 * this renderer's frame - the eventual Iris/Oculus-style shader-pack port, a debug overlay, a capture
 * tool. A *pass* here is a pipeline the render graph compiled from its own `graph.yaml`: it brings its
 * shader, its bind groups and its state, and the frame runs it at the slot it was registered for,
 * drawn as six vertices the vertex stage positions itself (`@geo_channel` - no vertex buffers, the
 * same vertex-pull shape the terrain shaders use).
 *
 * The slots are the contract, and they are named after the frame's own shape: this renderer draws the
 * terrain as the solid layer, then the cutout one, then - after the game's own entities and weather -
 * the translucent layer, and the two group boundaries plus each pass boundary are where outside work
 * fits. `after_terrain_translucent` is the one a shader pack's post-processing wants: everything
 * after it sees a finished world.
 *
 * Registrations survive a shader reload (the graph rebuild re-applies them) and die only at
 * [clear]. A pipeline the rebuilt graph dropped costs its pass a warning line rather than a
 * silently empty slot.
 *
 * What this surface deliberately does **not** offer yet - per-pass textures, framebuffer
 * ping-ponging, the game's own uniform channels - is the next milestone, and the slot names it will
 * hang from are these. See `docs/iris-adaptation-research.md` for that road.
 */
object RenderChannels {
    /** Every slot the frame answers to, in frame order. The names are stable across the JNI. */
    val SLOTS: List<String> = listOf(
        "frame_start",
        "before_terrain_solid",
        "after_terrain_solid",
        "before_terrain_cutout",
        "after_terrain_cutout",
        "before_terrain_translucent",
        "after_terrain_translucent",
        "frame_end",
    )

    /**
     * Registers the pipeline [pipeline] to draw at the slot [slot], after whatever the slot already
     * holds.
     *
     * `false` means the registration was refused and said why in the log: an unknown slot, a
     * pipeline the graph never compiled, or a call that arrived before the world's first frame
     * built the graph - the last of which is worth a retry on `ClientTickEvent` or later, when the
     * renderer is certainly up.
     */
    @JvmStatic
    fun register(slot: String, pipeline: String): Boolean =
        WgpuNative.registerChannelPass(slot, pipeline)

    /**
     * Unregisters every pass, whatever slot they sat in. The frame is this renderer's own again -
     * the shape a resource reload or a "shaders off" toggle asks for.
     */
    @JvmStatic
    fun clear() = WgpuNative.clearChannelPasses()
}
