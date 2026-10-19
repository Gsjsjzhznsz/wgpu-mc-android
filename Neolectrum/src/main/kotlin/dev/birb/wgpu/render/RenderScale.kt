package dev.birb.wgpu.render

import dev.birb.wgpu.rust.WmNative
import kotlin.math.roundToInt

/**
 * The render scale: the fraction of the window's own resolution the frame is rendered at, and
 * the record of the *window's* size that the surface needs to present it.
 *
 * The two halves answer two different questions:
 *
 *  - `scaleDimension` is what the two target mixins (`RenderTargetMixin`, `MainTargetMixin`)
 *    size Minecraft's main render target by. The renderer's settings live in Rust, so this is a
 *    native call - made when a target is sized, which is the game's startup resize and every
 *    window resize after it, not per frame.
 *  - [windowWidth]/[windowHeight] is the size the main target *would* have at a scale of 1.0,
 *    which is also the window's size. `WgpuSurface.blitAndPresent` configures the swapchain with
 *    it: the swapchain is the window's, and the main target below a render scale of 1.0 is not -
 *    the native blit is what stretches the smaller frame back over it.
 *
 * The natives answer `1.0` while the settings have not been loaded yet, so a target sized before
 * the config arrived is the un-scaled one; the renderer asks the game to resize its main target
 * once more when the settings are applied (`OptionPages.apply`), which is also how a change made
 * on the options screen reaches the screen the same frame.
 */
object RenderScale {
    /** The window's width, as the last target sizing saw it. Negative until then. */
    @Volatile
    @JvmStatic
    var windowWidth: Int = -1
        private set

    /** The window's height, as the last target sizing saw it. Negative until then. */
    @Volatile
    @JvmStatic
    var windowHeight: Int = -1
        private set

    /** Records the window's size, from the arguments a target sizing was handed. */
    @JvmStatic
    fun noteWindowSize(width: Int, height: Int) {
        // A degenerate size is not a window size, and a zero would make `blitAndPresent` fall
        // back to the source's own dimensions - which is the safe answer, but not why this exists.
        if (width > 0) windowWidth = width
        if (height > 0) windowHeight = height
    }

    /** Scales one dimension of a render target by the current render scale. */
    @JvmStatic
    fun scaleDimension(size: Int): Int {
        if (size <= 1) return size
        val scale = currentScale()
        if (scale >= 0.999f) return size
        return (size * scale).roundToInt().coerceAtLeast(1)
    }

    /** The render scale as the renderer's settings hold it right now. */
    fun currentScale(): Float =
        try {
            WmNative.getRenderScale.invokeExact() as Float
        } catch (_: Throwable) {
            1f
        }
}
