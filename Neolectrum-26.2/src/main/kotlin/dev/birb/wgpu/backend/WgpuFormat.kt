package dev.birb.wgpu.backend

import com.mojang.blaze3d.GpuFormat
import com.mojang.blaze3d.vertex.VertexFormatElement
import dev.birb.wgpu.rust.WmNative

/**
 * Translates Blaze3D formats into the discriminants owned by the Rust C ABI.
 *
 * Rust owns the numbering (see `GpuFormat` in `rust/wgpu-mc-jni/bindings.h`), so this is the
 * only place a Blaze3D format becomes a native value. 26.2 collapsed `TextureFormat` into the
 * same `GpuFormat` enum the vertex elements carry, so both translations are `when`s over one
 * enum: the texture map names the formats Minecraft actually creates textures with, and the
 * element map names everything a vertex attribute can be. An unknown one is refused loudly
 * rather than mapped to a wrong native value.
 */
object WgpuFormat {

    /** The texture formats the game creates textures with, and their native discriminants. */
    fun nativeId(format: GpuFormat): Long = when (format) {
        GpuFormat.RGBA8_UNORM -> WmNative.RGBA8_UNORM
        GpuFormat.R8_UNORM -> WmNative.R8_UNORM
        GpuFormat.R8_UINT -> WmNative.R8_UINT
        GpuFormat.D32_FLOAT -> WmNative.D32_FLOAT
        GpuFormat.D24_UNORM_S8_UINT -> WmNative.D24_UNORM_S8_UINT
        GpuFormat.D32_FLOAT_S8_UINT -> WmNative.D32_FLOAT_S8_UINT
        else -> throw IllegalArgumentException(
            "wgpu-mc: the ${format.name} texture format has no native mapping; the game created " +
                "a texture this renderer cannot represent",
        )
    }

    /**
     * The native format for one vertex element.
     *
     * 26.2 spells an element as `(name, offset, GpuFormat)` - the element itself carries the same
     * `GpuFormat` enum the Rust side was generated from - so the 26.1 fold of `(type, normalized,
     * count)` into one value is gone; the element's format is mapped straight through.
     *
     * @throws IllegalArgumentException for a format the Rust discriminants have no answer for,
     *   which the exhaustive `when` of the 26.1 shape used to make a compile error.
     */
    fun nativeVertexId(element: VertexFormatElement): Long = nativeVertexFormatId(element.format())

    /** The vertex-element formats, and their native discriminants - a distinct map from the
     *  texture formats', because the two answer different questions about the same enum. */
    fun nativeVertexFormatId(elementFormat: GpuFormat): Long = when (elementFormat) {
        GpuFormat.R32_FLOAT -> WmNative.R32_FLOAT
        GpuFormat.RG32_FLOAT -> WmNative.RG32_FLOAT
        GpuFormat.RGB32_FLOAT -> WmNative.RGB32_FLOAT
        GpuFormat.RGBA32_FLOAT -> WmNative.RGBA32_FLOAT
        GpuFormat.R8_UNORM -> WmNative.R8_UNORM
        GpuFormat.RG8_UNORM -> WmNative.RG8_UNORM
        GpuFormat.RGB8_UNORM -> WmNative.RGB8_UNORM
        GpuFormat.RGBA8_UNORM -> WmNative.RGBA8_UNORM
        GpuFormat.R8_SNORM -> WmNative.R8_SNORM
        GpuFormat.RG8_SNORM -> WmNative.RG8_SNORM
        GpuFormat.RGB8_SNORM -> WmNative.RGB8_SNORM
        GpuFormat.RGBA8_SNORM -> WmNative.RGBA8_SNORM
        GpuFormat.R8_UINT -> WmNative.R8_UINT
        GpuFormat.RG8_UINT -> WmNative.RG8_UINT
        GpuFormat.RGB8_UINT -> WmNative.RGB8_UINT
        GpuFormat.RGBA8_UINT -> WmNative.RGBA8_UINT
        GpuFormat.R8_SINT -> WmNative.R8_SINT
        GpuFormat.RG8_SINT -> WmNative.RG8_SINT
        GpuFormat.RGB8_SINT -> WmNative.RGB8_SINT
        GpuFormat.RGBA8_SINT -> WmNative.RGBA8_SINT
        GpuFormat.R16_UNORM -> WmNative.R16_UNORM
        GpuFormat.RG16_UNORM -> WmNative.RG16_UNORM
        GpuFormat.RGB16_UNORM -> WmNative.RGB16_UNORM
        GpuFormat.RGBA16_UNORM -> WmNative.RGBA16_UNORM
        GpuFormat.R16_SNORM -> WmNative.R16_SNORM
        GpuFormat.RG16_SNORM -> WmNative.RG16_SNORM
        GpuFormat.RGB16_SNORM -> WmNative.RGB16_SNORM
        GpuFormat.RGBA16_SNORM -> WmNative.RGBA16_SNORM
        GpuFormat.R16_UINT -> WmNative.R16_UINT
        GpuFormat.RG16_UINT -> WmNative.RG16_UINT
        GpuFormat.RGB16_UINT -> WmNative.RGB16_UINT
        GpuFormat.RGBA16_UINT -> WmNative.RGBA16_UINT
        GpuFormat.R16_SINT -> WmNative.R16_SINT
        GpuFormat.RG16_SINT -> WmNative.RG16_SINT
        GpuFormat.RGB16_SINT -> WmNative.RGB16_SINT
        GpuFormat.RGBA16_SINT -> WmNative.RGBA16_SINT
        GpuFormat.R16_FLOAT -> WmNative.R16_FLOAT
        GpuFormat.RG16_FLOAT -> WmNative.RG16_FLOAT
        GpuFormat.RGB16_FLOAT -> WmNative.RGB16_FLOAT
        GpuFormat.RGBA16_FLOAT -> WmNative.RGBA16_FLOAT
        GpuFormat.R32_UINT -> WmNative.R32_UINT
        GpuFormat.RG32_UINT -> WmNative.RG32_UINT
        GpuFormat.RGB32_UINT -> WmNative.RGB32_UINT
        GpuFormat.RGBA32_UINT -> WmNative.RGBA32_UINT
        GpuFormat.R32_SINT -> WmNative.R32_SINT
        GpuFormat.RG32_SINT -> WmNative.RG32_SINT
        GpuFormat.RGB32_SINT -> WmNative.RGB32_SINT
        GpuFormat.RGBA32_SINT -> WmNative.RGBA32_SINT
        GpuFormat.RGB10A2_UNORM -> WmNative.RGB10A2_UNORM
        else -> throw IllegalArgumentException(
            "wgpu-mc: the ${elementFormat.name} vertex element format has no native mapping",
        )
    }
}
