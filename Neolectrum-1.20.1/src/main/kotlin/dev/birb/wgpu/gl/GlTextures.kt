package dev.birb.wgpu.gl

import dev.birb.wgpu.WgpuMcMod
import dev.birb.wgpu.backend.WgpuRenderer
import dev.birb.wgpu.rust.WmJni
import org.lwjgl.system.MemoryUtil
import java.nio.IntBuffer

/**
 * The textures GL thinks it has.
 *
 * Minecraft names a texture with a number it got from `glGenTextures`, binds it on a unit, gives it
 * storage with `glTexImage2D` and fills it with `glTexSubImage2D`. wgpu has no such name and no such
 * lifecycle - a `wgpu::Texture` is created with its size, format and usage and is not redefined - so
 * this object is the translation between the two: it mints the names, and it keeps the engine texture
 * a name stands for.
 *
 * Two GL things have no wgpu equivalent and are handled rather than translated:
 *
 * - **A texture can be resized.** `glTexImage2D` on a name that already has storage replaces it, and
 *   Minecraft does exactly that when the window is resized (`TextureTarget`/`MainTarget` recreate
 *   their colour texture at the new size). The old engine texture is dropped and a new one is made,
 *   which is also what makes the name-to-handle map the only stable identity there is.
 * - **Sampler state is mutable.** `glTexParameteri` can be called at any time, while a
 *   `wgpu::Sampler` is immutable, so the parameters are recorded here and a sampler is minted from
 *   them when a draw needs one - see [samplerKey].
 *
 * Nothing here is on a hot path except the lookups, which are an array index: texture *creation* is
 * a resource reload and texture *uploads* are atlas builds, while the per-draw question - "what does
 * unit N have bound" - is answered by [GlState], not here.
 */
object GlTextures {
    // ---- GL enums --------------------------------------------------------------------------------
    const val GL_TEXTURE_2D = 3553
    const val GL_TEXTURE_CUBE_MAP = 34067

    const val GL_TEXTURE_MAG_FILTER = 10240
    const val GL_TEXTURE_MIN_FILTER = 10241
    const val GL_TEXTURE_WRAP_S = 10242
    const val GL_TEXTURE_WRAP_T = 10243
    const val GL_TEXTURE_MAX_LEVEL = 33085
    const val GL_TEXTURE_MAX_LOD = 33083
    const val GL_TEXTURE_MIN_LOD = 33082
    const val GL_TEXTURE_LOD_BIAS = 34049
    const val GL_TEXTURE_BASE_LEVEL = 33084
    const val GL_TEXTURE_MAX_ANISOTROPY = 34046

    const val GL_TEXTURE_WIDTH = 4096
    const val GL_TEXTURE_HEIGHT = 4097
    const val GL_TEXTURE_INTERNAL_FORMAT = 4099
    const val GL_TEXTURE_BORDER_COLOR = 4100
    const val GL_TEXTURE_COMPARE_MODE = 34892

    const val GL_NEAREST = 9728
    const val GL_LINEAR = 9729
    const val GL_NEAREST_MIPMAP_NEAREST = 9984
    const val GL_LINEAR_MIPMAP_NEAREST = 9985
    const val GL_NEAREST_MIPMAP_LINEAR = 9986
    const val GL_LINEAR_MIPMAP_LINEAR = 9987

    const val GL_REPEAT = 10497
    const val GL_CLAMP_TO_EDGE = 33071
    const val GL_CLAMP_TO_BORDER = 33069
    const val GL_MIRRORED_REPEAT = 33648

    // ---- the usage bits the engine's `create_texture` reads ----------------------------------------
    /** `usage & 1`: the texture can be written into with `write_to_texture`. */
    const val USAGE_COPY_DST = 1
    /** `usage & 4`: the texture can be bound to a shader. */
    const val USAGE_TEXTURE_BINDING = 4
    /** `usage & 8`: the texture can be a render pass's colour or depth target. */
    const val USAGE_RENDER_ATTACHMENT = 8

    /** `create_texture_view`'s `usage & 16`, which asks for a cube view rather than a 2D one. */
    const val VIEW_USAGE_CUBE = 16

    /**
     * One GL texture name.
     *
     * The fields are the whole of what this side has to remember about it: the engine handles, the
     * storage description `glTexImage2D` last gave it, and the sampler parameters, which travel
     * with the *name* in GL and therefore here as well rather than with the bind.
     */
    class Entry(@JvmField val name: Int) {
        /** The engine's `wgpu::Texture`, or 0 while the name has no storage yet. */
        @JvmField var texture: Long = 0L

        /** A view over every level of [texture], which is what a draw binds. */
        @JvmField var view: Long = 0L

        /**
         * A view over level 0 alone, which is what a render pass may attach.
         *
         * wgpu refuses an attachment whose view spans more than one level - "This texture view has
         * more than one mipmap level" - while GL is happy to attach a mipped texture's level 0 and
         * ignores the rest. [view] spans the whole chain on purpose, because sampling an atlas
         * needs it, so the two cannot be the same view. Made on demand: most textures are never
         * drawn into.
         */
        @JvmField var attachmentView: Long = 0L

        @JvmField var width: Int = 0
        @JvmField var height: Int = 0
        @JvmField var levels: Int = 1
        @JvmField var gpuFormat: Int = GpuFormats.RGBA8_UNORM
        @JvmField var internalFormat: Int = 0

        /** True once [texture] exists, so a resize can be told from a first definition. */
        @JvmField var defined: Boolean = false

        @JvmField var deleted: Boolean = false

        @JvmField var minFilter: Int = GL_NEAREST_MIPMAP_LINEAR
        @JvmField var magFilter: Int = GL_LINEAR
        @JvmField var wrapS: Int = GL_REPEAT
        @JvmField var wrapT: Int = GL_REPEAT
        @JvmField var minLod: Float = -1000f
        @JvmField var maxLod: Float = 1000f
        @JvmField var lodBias: Float = 0f
        @JvmField var maxAnisotropy: Int = 1
        @JvmField var baseLevel: Int = 0
        @JvmField var maxLevel: Int = 1000

        val exists: Boolean get() = texture != 0L

        /** The sampler parameters as one comparable value, which is what a sampler cache is keyed by. */
        fun samplerKey(): Long {
            var key = (minFilter.toLong() and 0xFFFF) shl 48
            key = key or ((magFilter.toLong() and 0xFFFF) shl 32)
            key = key or ((wrapS.toLong() and 0xFFFF) shl 16)
            key = key or (wrapT.toLong() and 0xFFFF)
            return key
        }
    }

    private val entries = HashMap<Int, Entry>()
    private var nextName = 1

    /** GL names are this side's to hand out: the engine has no notion of one. */
    @JvmStatic
    fun genTexture(): Int {
        val name = nextName++
        entries[name] = Entry(name)
        return name
    }

    @JvmStatic
    fun genTextures(names: IntArray) {
        for (i in names.indices) names[i] = genTexture()
    }

    /**
     * The level-0-only view of a texture, for a pass to attach, or 0 if it has no storage.
     *
     * This is the view a colour or depth attachment must use: see [Entry.attachmentView] for why it
     * cannot be [Entry.view].
     */
    @JvmStatic
    fun attachmentView(name: Int): Long {
        val entry = entries[name] ?: return 0L
        if (!entry.exists) return 0L
        if (entry.attachmentView == 0L) {
            val wm = WgpuRenderer.handle()
            if (wm == 0L) return 0L
            entry.attachmentView = WmJni.createTextureView(wm, entry.texture, USAGE_RENDER_ATTACHMENT, 0, 1)
        }
        return entry.attachmentView
    }

    @JvmStatic
    fun deleteTexture(name: Int) {
        val entry = entries.remove(name) ?: return
        entry.deleted = true
        release(entry)
        GlState.forgetTexture(name)
    }

    @JvmStatic
    fun deleteTextures(names: IntArray) {
        for (name in names) deleteTexture(name)
    }

    @JvmStatic
    fun byName(name: Int): Entry? = entries[name]

    /** The texture bound to the selected unit, or null when there is none or it has no storage. */
    @JvmStatic
    fun bound(): Entry? {
        val name = GlState.boundTexture()
        if (name <= 0) return null
        val entry = entries[name] ?: return null
        return if (entry.exists) entry else null
    }

    // ---- storage ---------------------------------------------------------------------------------

    /**
     * `glTexImage2D`: give the bound texture storage, and upload into it when there is data.
     *
     * Level 0 defines the texture. A level above 0 only records that the level exists - the engine
     * texture was created with the whole mip chain the size allows, because GL lets a level be
     * defined after the one above it and wgpu does not.
     *
     * Redefining level 0 at a different size replaces the engine texture, which is how Minecraft
     * resizes a render target: the name stays, everything behind it is new.
     */
    @JvmStatic
    fun texImage2D(
        target: Int,
        level: Int,
        internalFormat: Int,
        width: Int,
        height: Int,
        border: Int,
        format: Int,
        type: Int,
        pixels: IntBuffer?,
    ) {
        val name = GlState.boundTexture()
        val entry = entries[name] ?: return
        if (level > 0) {
            // A level above zero only fills in the chain the level-0 call already allocated. Its
            // data, if any, still has to travel.
            if (pixels != null) {
                upload(entry, level, 0, 0, width, height, format, type, pixels)
            }
            return
        }

        val gpuFormat = GpuFormats.fromGl(internalFormat, format, type)
        if (entry.defined && (entry.width != width || entry.height != height || entry.gpuFormat != gpuFormat)) {
            WgpuMcMod.LOGGER.debug(
                "wgpu-mc: texture {} redefined {}x{} -> {}x{}",
                name, entry.width, entry.height, width, height,
            )
            release(entry)
        }
        if (!entry.defined) {
            create(entry, gpuFormat, width, height)
        }
        entry.internalFormat = internalFormat
        if (pixels != null) {
            upload(entry, 0, 0, 0, width, height, format, type, pixels)
        }
    }

    /** `glTexSubImage2D`: write a rectangle into a level that already has storage. */
    @JvmStatic
    fun texSubImage2D(
        target: Int,
        level: Int,
        xOffset: Int,
        yOffset: Int,
        width: Int,
        height: Int,
        format: Int,
        type: Int,
        pixels: Long,
    ) {
        val entry = entries[GlState.boundTexture()] ?: return
        if (!entry.exists) return
        val address = if (pixels == 0L) MemoryUtil.NULL else pixels
        write(entry, level, xOffset, yOffset, width, height, format, type, address, 0L)
    }

    @JvmStatic
    fun texSubImage2D(
        target: Int,
        level: Int,
        xOffset: Int,
        yOffset: Int,
        width: Int,
        height: Int,
        format: Int,
        type: Int,
        pixels: IntBuffer?,
    ) {
        if (pixels == null) return
        val entry = entries[GlState.boundTexture()] ?: return
        if (!entry.exists) return
        upload(entry, level, xOffset, yOffset, width, height, format, type, pixels)
    }

    private fun create(entry: Entry, gpuFormat: Int, width: Int, height: Int) {
        val wm = WgpuRenderer.handle()
        if (wm == 0L) return
        val levels = GpuFormats.mipChain(width, height)
        // Every texture this side makes is writable into, sampleable and usable as a target, because
        // GL has no way to say which of those a texture will be and Minecraft uses one name for all
        // three: an atlas is uploaded into and sampled, and a render target is drawn into and then
        // sampled by the GUI pass that blits it.
        val usage = USAGE_COPY_DST or USAGE_TEXTURE_BINDING or USAGE_RENDER_ATTACHMENT
        entry.texture = WmJni.createTexture(wm, gpuFormat, width, height, 1, usage, levels, "gl texture " + entry.name)
        entry.view = WmJni.createTextureView(wm, entry.texture, 0, 0, 0)
        entry.width = width
        entry.height = height
        entry.levels = levels
        entry.gpuFormat = gpuFormat
        entry.defined = true
    }

    private fun release(entry: Entry) {
        if (entry.attachmentView != 0L) {
            WmJni.dropTextureView(entry.attachmentView)
            entry.attachmentView = 0L
        }
        if (entry.view != 0L) {
            WmJni.dropTextureView(entry.view)
            entry.view = 0L
        }
        if (entry.texture != 0L) {
            WmJni.dropTexture(entry.texture)
            entry.texture = 0L
        }
        entry.defined = false
    }

    private fun upload(
        entry: Entry,
        level: Int,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        format: Int,
        type: Int,
        pixels: IntBuffer,
    ) {
        val address = MemoryUtil.memAddress(pixels)
        write(entry, level, x, y, width, height, format, type, address, pixels.remaining().toLong() * 4L)
    }

    /**
     * Hands one rectangle of pixels to the engine.
     *
     * The engine writes rows tightly packed, so the `GL_UNPACK_ROW_LENGTH`/skip state is honoured
     * here: a sub-rectangle of a larger image is copied into a scratch buffer one row at a time
     * rather than being described to the engine, which has no parameter for a source stride.
     */
    private fun write(
        entry: Entry,
        level: Int,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        format: Int,
        type: Int,
        address: Long,
        availableBytes: Long,
    ) {
        val wm = WgpuRenderer.handle()
        if (wm == 0L || address == 0L) return
        val bpp = GpuFormats.bytesPerPixel(format, type)
        if (bpp <= 0) return

        // GL's own default row length is the *sub-rectangle's* width, which is why a zero here means
        // `width` rather than "unset".
        val rowLength = if (GlState.unpackRowLength > 0) GlState.unpackRowLength else width
        // `GL_UNPACK_ALIGNMENT` pads every row out to a multiple of itself. It only bites when a row
        // is not already that long, which is why the game never trips it: `NativeImage#_upload` ends
        // with `format.setUnpackPixelStoreState()`, and that sets the alignment to the format's own
        // byte width. Reading it anyway is what makes this correct for an upload that does not.
        val alignment = if (GlState.unpackAlignment > 0) GlState.unpackAlignment else 1
        val packed = rowLength.toLong() * bpp.toLong()
        val stride = (packed + alignment - 1) / alignment * alignment
        val tight = GlState.unpackSkipPixels == 0 && GlState.unpackSkipRows == 0 && stride == width.toLong() * bpp.toLong()

        if (tight) {
            val size = width.toLong() * height.toLong() * bpp.toLong()
            if (availableBytes != 0L && size > availableBytes) {
                WgpuMcMod.LOGGER.warn("wgpu-mc: a {}x{} upload wants {} bytes and the buffer holds {}", width, height, size, availableBytes)
                return
            }
            WmJni.writeToTexture(wm, entry.texture, address, size, level, 0, x, y, width, height)
            return
        }

        val scratch = MemoryUtil.memAlloc(width * height * bpp)
        try {
            // The skip is in the source image's own coordinates: whole rows first, then pixels along
            // the row that starts there.
            val start = address + GlState.unpackSkipRows.toLong() * stride + GlState.unpackSkipPixels.toLong() * bpp.toLong()
            for (row in 0 until height) {
                MemoryUtil.memCopy(start + row.toLong() * stride, MemoryUtil.memAddress(scratch) + row.toLong() * width * bpp, width.toLong() * bpp)
            }
            val size = width.toLong() * height.toLong() * bpp.toLong()
            WmJni.writeToTexture(wm, entry.texture, MemoryUtil.memAddress(scratch), size, level, 0, x, y, width, height)
        } finally {
            MemoryUtil.memFree(scratch)
        }
    }

    // ---- parameters ------------------------------------------------------------------------------

    @JvmStatic
    fun texParameterf(target: Int, pname: Int, value: Float) {
        val entry = entries[GlState.boundTexture()] ?: return
        when (pname) {
            GL_TEXTURE_MIN_LOD -> entry.minLod = value
            GL_TEXTURE_MAX_LOD -> entry.maxLod = value
            GL_TEXTURE_LOD_BIAS -> entry.lodBias = value
            else -> Unit
        }
    }

    @JvmStatic
    fun texParameteri(target: Int, pname: Int, value: Int) {
        val entry = entries[GlState.boundTexture()] ?: return
        when (pname) {
            GL_TEXTURE_MIN_FILTER -> entry.minFilter = value
            GL_TEXTURE_MAG_FILTER -> entry.magFilter = value
            GL_TEXTURE_WRAP_S -> entry.wrapS = value
            GL_TEXTURE_WRAP_T -> entry.wrapT = value
            GL_TEXTURE_MAX_ANISOTROPY -> entry.maxAnisotropy = value
            GL_TEXTURE_BASE_LEVEL -> entry.baseLevel = value
            GL_TEXTURE_MAX_LEVEL -> entry.maxLevel = value
            else -> Unit
        }
    }

    /** The answers to the `glGetTexLevelParameteri` questions Minecraft asks about a texture. */
    @JvmStatic
    fun getTexLevelParameter(target: Int, level: Int, pname: Int): Int {
        val entry = entries[GlState.boundTexture()] ?: return 0
        val shift = if (level in 0 until 31) level else 0
        return when (pname) {
            GL_TEXTURE_WIDTH -> (entry.width shr shift).coerceAtLeast(1)
            GL_TEXTURE_HEIGHT -> (entry.height shr shift).coerceAtLeast(1)
            GL_TEXTURE_INTERNAL_FORMAT -> entry.internalFormat
            else -> 0
        }
    }

    /**
     * `glGetTexImage`: read a texture back into memory the caller owns.
     *
     * Minecraft asks this in one place, and it is not optional. `Screenshot`/`NativeImage#downloadTexture`
     * read the main render target's colour texture to make a world's icon the first time a singleplayer
     * level is entered - `GameRenderer#tryTakeScreenshotIfNeeded` does it a second after the player logs
     * in, and every frame after that until it succeeds. Left to LWJGL the call reaches an unbound GL
     * entry point, because a window created with `GLFW_NO_API` has no context to have bound one, and the
     * client dies with `EXCEPTION_ACCESS_VIOLATION` at `GL11C.nglGetTexImage(IIIIJ)V+0` the moment a
     * world is entered - which is what `runs/client/hs_err_pid20408.log` is.
     *
     * A wgpu texture cannot be read on the CPU, so this takes the route the renderer's own dump takes -
     * copy the texture to a buffer on the GPU, wait for it, then move the bytes - but the destination is
     * the game's own `NativeImage` memory rather than a file (see `WmJni.readTextureRgba`). Writing the
     * bytes out and reading them straight back worked and is what this did first; a screenshot is not a
     * reason for the renderer to depend on a writable working directory, and the round trip was a file
     * per frame for as long as the shot kept failing.
     *
     * `pixels` is what the game allocated and `entry.width * entry.height * 4` is what it holds, so the
     * native side is told the capacity and refuses rather than overrunning it. Rows come back
     * bottom-up, which is the OpenGL order `NativeImage#flipY` expects to turn over afterwards.
     *
     * `level` is accepted and ignored: level 0 is the only one anything asks for.
     */
    @JvmStatic
    fun getTexImage(level: Int, pixels: Long): Boolean {
        if (pixels == 0L) return false
        val wm = WgpuRenderer.handle()
        if (wm == 0L) return false
        val entry = entries[GlState.boundTexture()] ?: return false

        // The frame's draws may still be sitting in an encoder that has not been submitted, and a copy
        // taken now would read the texture as it was before them. This is the same reason a blit
        // flushes first.
        GlDraws.endFrame(wm, "readback")

        val capacity = entry.width.toLong() * entry.height * 4L
        if (capacity <= 0L || capacity > Int.MAX_VALUE) return false
        return WmJni.readTextureRgba(wm, entry.texture, pixels, capacity.toInt(), true)
    }

    @JvmStatic
    fun pixelStore(pname: Int, value: Int) {
        when (pname) {
            3314 -> GlState.unpackRowLength = value
            // `GL_UNPACK_SKIP_ROWS` is 3315 and `GL_UNPACK_SKIP_PIXELS` is 3316 - the opposite of what
            // the two names' order suggests, and crossed here until the bitmap font proved it.
            // `NativeImage#_upload` sets 3316 to the glyph's *column* and 3315 to its *row*, so with
            // them swapped every glyph off the sheet's diagonal was read from the wrong place: the
            // title screen drew clean, well-formed glyphs that spelled nothing.
            3315 -> GlState.unpackSkipRows = value
            3316 -> GlState.unpackSkipPixels = value
            3317 -> GlState.unpackAlignment = value
            else -> Unit
        }
    }

    /** Drops every texture, for a resource reload that renumbers them. */
    @JvmStatic
    fun clear() {
        for (entry in entries.values) {
            entry.deleted = true
            release(entry)
        }
        entries.clear()
    }
}
