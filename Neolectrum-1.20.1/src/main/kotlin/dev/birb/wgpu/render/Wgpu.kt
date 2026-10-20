package dev.birb.wgpu.render

import dev.birb.wgpu.WgpuMcMod
import org.lwjgl.glfw.GLFW
import org.lwjgl.glfw.GLFWNativeCocoa
import org.lwjgl.glfw.GLFWNativeWayland
import org.lwjgl.glfw.GLFWNativeWin32
import org.lwjgl.glfw.GLFWNativeX11

/**
 * The JVM half of the interface the native renderer calls back through, on Minecraft 1.20.1.
 *
 * **The name and the signatures are a contract with `rust/wgpu-mc-jni`, not a choice made here.** The
 * engine resolves this class by name while it loads - `setClassLoader` calls `resolve_tint_helpers`,
 * which is the one moment it has both the game's class loader and a `JNIEnv` - and it looks up the
 * four tint methods eagerly, by descriptor: `helperGetBlockColor (IIII)I`,
 * `helperGetFluidColor (III)I`, `helperGetSectionTints (III)[J`, `helperGetSectionFluidTints (III)[J`,
 * and the two sinks `helperSetBlockStateIndex` and `helperSetPartIndex` it reports registration
 * through. A method that is missing, or whose descriptor has drifted, fails the whole load. The
 * engine's `cargo test` is what keeps the two lists in step.
 *
 * The tint answers are the 1.20.1 spellings of the same questions the 26.x line answers:
 *
 * - a block tint is `BlockColors#getColor(state, level, pos, tintIndex)` - the one call, which is
 *   also the "does this state have a tint here" probe, answered `-1` for no. 26.x split it into
 *   `getTintSource` + `colorInWorld`; 1.20.1 still carries it whole.
 * - a fluid tint is the biome's water colour, read the way the game's own
 *   `LiquidBlockRenderer` reads it: water follows `level.getBiome(pos).value().waterColor`, and
 *   lava is untinted. 26.x routes the same answer through a `fluidTintSource()` channel that
 *   NeoForge patches onto the model classes; 1.20.1 has no such channel and no need of one.
 *
 * `helperSetBlockStateIndex` and `helperSetPartIndex` are declared because the engine calls them
 * by name, and are the two seams the terrain and entity ports land on. The 1.20.1 line does not
 * feed sections or entity models to the engine yet - the GL seam renders the game's own meshes -
 * so both sinks answer, log nothing, and wait.
 */
object Wgpu {
        @JvmField
        val keyStates: HashMap<Int, Int> = HashMap()

        /**
         * Whether the game has reached the main screen, which is when a resource reload can rebuild
         * pipelines and entity models can be uploaded.
         *
         * Set by the title screen once it is first drawn - a render hook would be too early and a
         * loading screen too late. The GL seam does not need it for the frame itself, but the
         * engine-callback plumbing reports through it.
         */
        @Volatile
        private var initialized: Boolean = false

        /**
         * Whether the native renderer has been created, which happens while the game's window is built.
         *
         * Kept apart from [isInitialized] on purpose: that one is about the *game* being ready, and this
         * one about the renderer existing, which is minutes earlier.
         */
        @Volatile
        private var rendererLive: Boolean = false

        @JvmStatic
        fun isRendererLive(): Boolean {
                return rendererLive
        }

        @JvmStatic
        fun setRendererLive(live: Boolean) {
                rendererLive = live
        }

        @Volatile
        private var mayInitialize: Boolean = false

        @JvmStatic
        fun isInitialized(): Boolean {
                return initialized
        }

        @JvmStatic
        fun setInitialized(initialized: Boolean) {
                this.initialized = initialized
        }

        @JvmStatic
        fun isMayInitialize(): Boolean {
                return mayInitialize
        }

        @JvmStatic
        fun setMayInitialize(mayInitialize: Boolean) {
                this.mayInitialize = mayInitialize
        }

        @JvmStatic
        fun probeNativeBackendOnce() {
                WgpuMcMod.LOGGER.info("wgpu-mc renderer active: {}", runCatching { dev.birb.wgpu.rust.WgpuNative.getBackend() }.getOrElse { "not initialised" })
        }
        @JvmStatic
        fun getTimesTexSubImageCalled(): Int {
                return 0
        }

        /**
         * Reports whether the RenderDoc capture layer could be loaded, **quietly when it is not installed**.
         *
         * RenderDoc is a developer's tool: it is present on a machine where somebody is profiling, and absent
         * on every other machine - including an ordinary player's and this project's own development
         * environment. `System.loadLibrary` is the all-or-nothing version of "load it if it is there", so an
         * absent library is an `UnsatisfiedLinkError` and nothing else, and logging that as a warning with a
         * full stack trace made every single launch look broken.
         */
        @JvmStatic
        fun linkRenderDoc() {
                val path = System.getProperty("java.library.path").orEmpty()
                        .split(java.io.File.pathSeparatorChar)
                        .filter { it.isNotBlank() }

                val found = path.firstOrNull { directory ->
                        val name = if (java.io.File.separatorChar == '\\') "renderdoc.dll" else "librenderdoc.so"
                        java.io.File(directory, name).isFile
                }

                if (found == null) {
                        WgpuMcMod.LOGGER.debug(
                                "wgpu: RenderDoc is not installed; the capture layer is off (looked in {} director{} on " +
                                        "java.library.path)",
                                path.size,
                                if (path.size == 1) "y" else "ies",
                        )
                        return
                }

                try {
                        System.loadLibrary("renderdoc")
                        WgpuMcMod.LOGGER.info("wgpu: the RenderDoc capture layer is loaded from {}", found)
                } catch (e: UnsatisfiedLinkError) {
                        WgpuMcMod.LOGGER.warn("wgpu: RenderDoc is installed at {} but could not be loaded", found, e)
                }
        }

        @JvmStatic
        fun rustPanic(message: String) {
                WgpuMcMod.LOGGER.error(message)
                throw IllegalStateException(message)
        }

        @JvmStatic
        fun rustDebug(message: String) {
                WgpuMcMod.LOGGER.info("[Engine] {}", message)
        }

        /**
         * The position the block-tint callback is asked about, reused per bake thread.
         *
         * A mutable position rests on one assumption: **a tint source reads the position and does not keep
         * it.** Every source the game ships does - `GrassColor` and its neighbours ask `level.getBiome(pos)`
         * and return - and a source that kept the reference would see the position move under it. Thread-local
         * because the bakes run on a pool: one position shared between two of them would be a wrong colour,
         * not a race that throws.
         */
        private val tintPos: ThreadLocal<net.minecraft.core.BlockPos.MutableBlockPos> =
                ThreadLocal.withInitial { net.minecraft.core.BlockPos.MutableBlockPos() }

        /** The answer for a section with no colour to give: no air, no tinted state, or no level yet. */
        private val EMPTY_TINTS = LongArray(0)

        /**
         * How many tint indices are probed per state.
         *
         * Models use `tintindex: 0` and a few use 1 or 2; the game keeps a state's sources in an array indexed by
         * the tint index, so a state with one source answers `-1` from index 1 on and the probe stops paying
         * after the first.
         */
        private const val TINT_INDEX_PROBES = 4

        /**
         * The packing every tint answer uses: the game's `ARGB` turned into the `BGR` order the Rust side
         * multiplies into a vertex.
         */
        private fun packTint(color: Int): Int {
                val r = color shr 16 and 0xFF
                val g = color shr 8 and 0xFF
                val b = color and 0xFF

                return r or (g shl 8) or (b shl 16)
        }

        /**
         * The colour a block's face is tinted by at a position, asked of the game itself.
         *
         * The game's tint is a property of the **position** - grass follows the biome under the block - so
         * nothing here is cached: the Rust baker asks once per colour a block needs and keeps the answer for
         * that block, which is exactly as far as it stays true.
         */
        @JvmStatic
        fun helperGetBlockColor(x: Int, y: Int, z: Int, tintIndex: Int): Int {
                val client = net.minecraft.client.Minecraft.getInstance()
                if (client == null || client.level == null) {
                        return 0xFFFFFFFF.toInt()
                }
                val level = client.level ?: return 0xFFFFFFFF.toInt()

                val pos = tintPos.get().set(x, y, z)
                // 1.20.1 carries the tint question whole: getColor answers the world-space colour for
                // the index, and `-1` where 26.x's getTintSource would have answered null.
                val state = level.getBlockState(pos)
                val color = client.blockColors.getColor(state, level, pos, tintIndex)
                if (color == -1) {
                        return 0xFFFFFFFF.toInt()
                }

                return packTint(color)
        }

        /**
         * Every biome tint one section needs, in **one** call, packed one colour per `Long`.
         *
         * The layout is shared with `wgpu-mc-jni`'s `TintHelpers::section_tints`, which unpacks it:
         *
         * ```text
         * bits 63..36  the position in the section, in Minecraft's own storage order
         *              (`x | z << 4 | y << 8`, which is the order the Rust baker walks blocks in)
         * bits 35..32  the model's tint index
         * bits 31..0   the colour, in the same packing `helperGetBlockColor` returns
         * ```
         */
        @JvmStatic
        fun helperGetSectionTints(x: Int, y: Int, z: Int): LongArray {
                val client = net.minecraft.client.Minecraft.getInstance()
                if (client == null || client.level == null) {
                        return EMPTY_TINTS
                }
                val level = client.level ?: return EMPTY_TINTS

                // 1.20.1 spells the section index lookup through the height mask: the helper is
                // handed a section Y, and getSectionIndexFromSectionY is the call that turns it
                // into the section array's index (26.x carries minSectionY as a property).
                val section = level.getChunk(x, z).getSection(level.getSectionIndexFromSectionY(y))
                if (section.hasOnlyAir()) {
                        return EMPTY_TINTS
                }

                var out = LongArray(0)
                var count = 0

                for (index in 0 until 4096) {
                        val state = section.getBlockState(index and 15, index shr 8, index shr 4 and 15)

                        for (tint in 0 until TINT_INDEX_PROBES) {
                                val at = tintPos.get().set(
                                        (x shl 4) + (index and 15),
                                        (y shl 4) + (index shr 8),
                                        (z shl 4) + (index shr 4 and 15),
                                )
                                val color = client.blockColors.getColor(state, level, at, tint)
                                if (color == -1) {
                                        continue
                                }

                                if (count == out.size) {
                                        out = out.copyOf(if (count == 0) 64 else count * 2)
                                }

                                out[count++] =
                                        (index.toLong() shl 36) or (tint.toLong() shl 32) or
                                                (packTint(color).toLong() and 0xFFFFFFFFL)
                        }
                }

                return if (count == out.size) out else out.copyOf(count)
        }

        /**
         * Every fluid tint one section needs, in **one** call, packed one colour per `Long`.
         *
         * The same shape as [helperGetSectionTints], with the 1.20.1 answer: water follows the biome's
         * water colour, lava has none. A section with no fluid in its palette pays nothing - `getFluidState`
         * on the state already in hand answers both questions without a second lookup.
         */
        @JvmStatic
        fun helperGetSectionFluidTints(x: Int, y: Int, z: Int): LongArray {
                val client = net.minecraft.client.Minecraft.getInstance()
                if (client == null || client.level == null) {
                        return EMPTY_TINTS
                }
                val level = client.level ?: return EMPTY_TINTS

                val section = level.getChunk(x, z).getSection(level.getSectionIndexFromSectionY(y))
                if (section.hasOnlyAir()) {
                        return EMPTY_TINTS
                }

                var out = LongArray(0)
                var count = 0

                for (index in 0 until 4096) {
                        val state = section.getBlockState(index and 15, index shr 8, index shr 4 and 15)
                        val fluidState = state.fluidState

                        if (fluidState.isEmpty) {
                                continue
                        }

                        // 1.20.1's own fluid renderer tints water with the biome the block sits in and
                        // leaves lava alone; the same answer, read the way LiquidBlockRenderer reads it.
                        val color = if (fluidState.`is`(net.minecraft.world.level.material.Fluids.WATER)) {
                                level.getBiome(tintPos.get().set(
                                        (x shl 4) + (index and 15),
                                        (y shl 4) + (index shr 8),
                                        (z shl 4) + (index shr 4 and 15),
                                )).value().waterColor
                        } else {
                                0xFFFFFFFF.toInt()
                        }

                        if (count == out.size) {
                                out = out.copyOf(if (count == 0) 64 else count * 2)
                        }

                        out[count++] = (index.toLong() shl 36) or (packTint(color).toLong() and 0xFFFFFFFFL)
                }

                return if (count == out.size) out else out.copyOf(count)
        }

        /**
         * The colour a **fluid** is tinted by at a position, which is a different question from
         * [helperGetBlockColor] and one that function cannot answer.
         *
         * Water follows the biome's water colour - an ocean, a swamp and a cold river are three
         * different colours in the game - and everything else draws untinted: `-1` is the game's own
         * "no tint" and is passed through unaltered, white in the packing the native side reads.
         */
        @JvmStatic
        fun helperGetFluidColor(x: Int, y: Int, z: Int): Int {
                val client = net.minecraft.client.Minecraft.getInstance()
                if (client == null || client.level == null) {
                        return 0xFFFFFFFF.toInt()
                }
                val level = client.level ?: return 0xFFFFFFFF.toInt()

                val pos = net.minecraft.core.BlockPos(x, y, z)
                val fluidState = level.getFluidState(pos)
                if (fluidState.isEmpty) {
                        return 0xFFFFFFFF.toInt()
                }

                return if (fluidState.`is`(net.minecraft.world.level.material.Fluids.WATER)) {
                        packTint(level.getBiome(pos).value().waterColor)
                } else {
                        0xFFFFFFFF.toInt()
                }
        }

        /**
         * The engine's registration sink for a block state's native index.
         *
         * The 26.x line hands the index to `RustBlockStateAccessor` and describes the face flags beside
         * it; both of those live in the terrain-feed code this line does not carry yet - the GL seam
         * renders the game's own meshes - so the sink answers and waits for the port that fills it.
         */
        @JvmStatic
        fun helperSetBlockStateIndex(state: Any?, blockstateKey: Int) {
        }

        /**
         * The engine's registration sink for an entity model part's matrix index, the seam the entity
         * port lands on. Same story as [helperSetBlockStateIndex]: declared, empty, waiting.
         */
        @JvmStatic
        fun helperSetPartIndex(entity: String, part: String, index: Int) {
        }

        /**
         * Translates a GLFW window handle into the display/window pair the surface needs.
         *
         * Two of the four platforms answer with a display and two do not, which is the whole reason this is
         * a pair rather than one number: on Windows the display is not part of the identity of a window - the
         * instance is read back from the window itself - while on X11 the window id is only meaningful
         * together with the `Display` it belongs to. Rust discards whichever of the two it does not need.
         */
        @JvmStatic
        fun windowHandles(windowHandle: Long): Pair<Long, Long> = when (GLFW.glfwGetPlatform()) {
                GLFW.GLFW_PLATFORM_WIN32 -> 0L to GLFWNativeWin32.glfwGetWin32Window(windowHandle)
                GLFW.GLFW_PLATFORM_X11 ->
                        GLFWNativeX11.glfwGetX11Display() to GLFWNativeX11.glfwGetX11Window(windowHandle)
                GLFW.GLFW_PLATFORM_COCOA ->
                        GLFWNativeCocoa.glfwGetCocoaWindow(windowHandle) to 0L
                GLFW.GLFW_PLATFORM_WAYLAND ->
                        0L to GLFWNativeWayland.glfwGetWaylandWindow(windowHandle)
                else -> throw IllegalStateException(
                        "wgpu-mc: unsupported GLFW platform ${GLFW.glfwGetPlatform()}",
                )
        }
}
