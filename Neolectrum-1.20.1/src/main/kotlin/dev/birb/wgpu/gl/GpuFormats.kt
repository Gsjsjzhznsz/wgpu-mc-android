package dev.birb.wgpu.gl

/**
 * The GL pixel formats, translated into the engine's `GpuFormat`.
 *
 * The two vocabularies are not the same shape: GL describes a texture by an *internal* format - the
 * layout it will be stored in - and separately by the format and type of the pixels being handed to
 * it, while the engine has one enum naming the storage. So this takes all three and answers with
 * the storage, which is the only one of the three wgpu can be told about.
 *
 * The discriminants are the engine's own (`blaze.rs`, `GpuFormat`) and are written out rather than
 * derived, because they are an ABI: a number that drifts is a texture of the wrong format, not a
 * compile error.
 */
object GpuFormats {
    const val NONE = 0
    const val R8_UNORM = 1
    const val R8_SNORM = 2
    const val RG8_UNORM = 3
    const val RG8_SNORM = 4
    const val RGB8_UNORM = 5
    const val RGB8_SNORM = 6
    const val RGBA8_UNORM = 7
    const val RGBA8_SNORM = 8
    const val R16_UNORM = 9
    const val RG16_UNORM = 11
    const val RGB16_UNORM = 13
    const val RGBA16_UNORM = 15
    const val R8_UINT = 17
    const val RG8_UINT = 19
    const val RGB8_UINT = 21
    const val RGBA8_UINT = 23
    const val R16_FLOAT = 41
    const val RG16_FLOAT = 42
    const val RGB16_FLOAT = 43
    const val RGBA16_FLOAT = 44
    const val R32_FLOAT = 45
    const val RG32_FLOAT = 46
    const val RGB32_FLOAT = 47
    const val RGBA32_FLOAT = 48
    const val RGB10A2_UNORM = 49
    const val RG11B10_FLOAT = 51
    const val D32_FLOAT = 52
    const val D32_FLOAT_S8_UINT = 53
    const val D24_UNORM_S8_UINT = 54
    const val D16_UNORM = 55
    const val S8_UINT = 56

    // ---- GL internal formats ---------------------------------------------------------------------
    private const val GL_DEPTH_COMPONENT = 6402
    private const val GL_RED = 6403
    private const val GL_RGB = 6407
    private const val GL_RGBA = 6408
    private const val GL_LUMINANCE = 6409
    private const val GL_ALPHA = 6406
    private const val GL_LUMINANCE_ALPHA = 6410
    private const val GL_RG = 33319
    private const val GL_BGR = 32992
    private const val GL_BGRA = 32993
    private const val GL_R3_G3_B2 = 10768
    private const val GL_RGB4 = 32847
    private const val GL_RGB5 = 32848
    private const val GL_RGB8 = 32849
    private const val GL_RGB10 = 32850
    private const val GL_RGB12 = 32851
    private const val GL_RGB16 = 32852
    private const val GL_RGBA2 = 32853
    private const val GL_RGBA4 = 32854
    private const val GL_RGB5_A1 = 32855
    private const val GL_RGBA8 = 32856
    private const val GL_RGB10_A2 = 32857
    private const val GL_RGBA12 = 32858
    private const val GL_RGBA16 = 32859
    private const val GL_DEPTH_COMPONENT16 = 33189
    private const val GL_DEPTH_COMPONENT24 = 33190
    private const val GL_DEPTH_COMPONENT32 = 33191
    private const val GL_DEPTH_COMPONENT32F = 36012
    private const val GL_R8 = 33321
    private const val GL_RG8 = 33323
    private const val GL_R16F = 33325
    private const val GL_RG16F = 33327
    private const val GL_RGB16F = 34843
    private const val GL_RGBA16F = 34842
    private const val GL_R32F = 33326
    private const val GL_RG32F = 33328
    private const val GL_RGB32F = 34837
    private const val GL_RGBA32F = 34836
    private const val GL_DEPTH24_STENCIL8 = 35056
    private const val GL_DEPTH32F_STENCIL8 = 36013
    private const val GL_DEPTH_STENCIL = 34041
    private const val GL_SRGB8_ALPHA8 = 35907

    // ---- GL pixel types --------------------------------------------------------------------------
    private const val GL_UNSIGNED_BYTE = 5121
    private const val GL_BYTE = 5120
    private const val GL_UNSIGNED_SHORT = 5123
    private const val GL_SHORT = 5122
    private const val GL_UNSIGNED_INT = 5125
    private const val GL_INT = 5124
    private const val GL_FLOAT = 5126
    private const val GL_HALF_FLOAT = 5131

    /**
     * The storage a GL texture description asks for.
     *
     * Minecraft only ever builds seven kinds of texture - the block and GUI atlases, the lightmap,
     * the sky and cloud targets, the main target and its depth buffer, and a handful of
     * single-channel masks - so the table is written from what it actually asks for, and anything
     * outside it falls back to RGBA8 rather than to nothing: a wrong-looking texture is a bug to
     * chase, and a texture the engine refuses is a crash.
     */
    @JvmStatic
    fun fromGl(internalFormat: Int, format: Int, type: Int): Int {
        return when (internalFormat) {
            GL_R8, GL_RED, GL_ALPHA, GL_LUMINANCE, GL_R3_G3_B2 -> R8_UNORM
            GL_RG8, GL_RG, GL_LUMINANCE_ALPHA -> RG8_UNORM
            GL_RGB, GL_RGB4, GL_RGB5, GL_RGB8, GL_RGB10, GL_RGB12, GL_RGB16 -> RGB8_UNORM
            GL_RGBA, GL_RGBA2, GL_RGBA4, GL_RGB5_A1, GL_RGBA8, GL_RGB10_A2, GL_RGBA12, GL_RGBA16 -> RGBA8_UNORM
            GL_SRGB8_ALPHA8 -> RGBA8_UNORM
            GL_RGB16F -> RGB16_FLOAT
            GL_RGBA16F -> RGBA16_FLOAT
            GL_R16F -> R16_FLOAT
            GL_RG16F -> RG16_FLOAT
            GL_R32F -> R32_FLOAT
            GL_RG32F -> RG32_FLOAT
            GL_RGB32F -> RGB32_FLOAT
            GL_RGBA32F -> RGBA32_FLOAT
            GL_DEPTH_COMPONENT16 -> D16_UNORM
            GL_DEPTH_COMPONENT24 -> D24_UNORM_S8_UINT
            GL_DEPTH_COMPONENT32, GL_DEPTH_COMPONENT32F -> D32_FLOAT
            GL_DEPTH24_STENCIL8, GL_DEPTH_STENCIL -> D24_UNORM_S8_UINT
            GL_DEPTH32F_STENCIL8 -> D32_FLOAT_S8_UINT
            else -> fromPixelFormat(format, type)
        }
    }

    /** The storage implied by the pixels alone, for a call that named no internal format. */
    private fun fromPixelFormat(format: Int, type: Int): Int {
        val depth = format == GL_DEPTH_COMPONENT || format == GL_DEPTH_STENCIL
        if (depth) {
            return when (type) {
                GL_UNSIGNED_SHORT -> D16_UNORM
                GL_UNSIGNED_INT -> D32_FLOAT
                GL_FLOAT -> D32_FLOAT
                else -> D24_UNORM_S8_UINT
            }
        }
        if (type == GL_FLOAT) return RGBA32_FLOAT
        if (type == GL_HALF_FLOAT) return RGBA16_FLOAT
        if (type == GL_UNSIGNED_SHORT) return RGBA16_UNORM
        return when (format) {
            GL_RED, GL_ALPHA, GL_LUMINANCE -> R8_UNORM
            GL_RG, GL_LUMINANCE_ALPHA -> RG8_UNORM
            GL_RGB, GL_BGR -> RGB8_UNORM
            else -> RGBA8_UNORM
        }
    }

    /**
     * How many bytes one pixel of an upload is.
     *
     * This is the *source* layout, not the storage: `GL_BGRA` with `GL_UNSIGNED_INT_8_8_8_8_REV` is
     * four bytes in R,G,B,A order - which is why a straight copy into an `RGBA8_UNORM` texture is
     * correct for it, and the reversal in the type name describes the *word*, not the bytes.
     *
     * Zero means "not a layout this side can copy", and the caller drops the upload rather than
     * reading past the end of somebody's buffer.
     */
    @JvmStatic
    fun bytesPerPixel(format: Int, type: Int): Int {
        val components = when (format) {
            GL_RED, GL_ALPHA, GL_LUMINANCE, GL_DEPTH_COMPONENT -> 1
            GL_RG, GL_LUMINANCE_ALPHA -> 2
            GL_RGB, GL_BGR -> 3
            GL_RGBA, GL_BGRA, GL_DEPTH_STENCIL -> 4
            else -> return 0
        }
        val bytesPerComponent = when (type) {
            GL_UNSIGNED_BYTE, GL_BYTE -> 1
            GL_UNSIGNED_SHORT, GL_SHORT, GL_HALF_FLOAT -> 2
            GL_UNSIGNED_INT, GL_INT, GL_FLOAT -> 4
            else -> return 0
        }
        return components * bytesPerComponent
    }

    /** How many levels a texture of this size can have, which is what a full mip chain means. */
    @JvmStatic
    fun mipChain(width: Int, height: Int): Int {
        val largest = if (width > height) width else height
        if (largest <= 0) return 1
        var levels = 1
        var size = largest
        while (size > 1) {
            size = size shr 1
            levels++
        }
        return levels
    }

    /** True when the format is one a depth attachment can use. */
    @JvmStatic
    fun isDepth(format: Int): Boolean =
        format == D32_FLOAT || format == D32_FLOAT_S8_UINT || format == D24_UNORM_S8_UINT || format == D16_UNORM || format == S8_UINT
}