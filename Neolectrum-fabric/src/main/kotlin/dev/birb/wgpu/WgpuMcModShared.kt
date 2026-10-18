package dev.birb.wgpu

import com.mojang.logging.LogUtils
import java.util.concurrent.atomic.LongAdder
import org.slf4j.Logger

/**
 * **The shared constants, under their NeoForge twin's name.** (The file is named
 * `WgpuMcModShared` rather than `WgpuMcMod` for one mechanical reason: the shared sources'
 * exclude patterns name the NeoForge `WgpuMcMod.kt` by path, and a file of the same name here
 * would be caught by the same pattern - the class inside is the one every reference resolves.)
 *
 * The mod's code reads `WgpuMcMod.LOGGER`, `WgpuMcMod.MOD_ID` and the timing counters from
 * everywhere - Kotlin objects, Java mixins - and on NeoForge those live on the `@Mod` class. On
 * Fabric there is no `@Mod` entrypoint to hang a companion on, but the Java mixins read these as
 * *static* fields (`WgpuMcMod.LOGGER`), which is the shape a companion's `@JvmField` gives - an
 * `object` would put them on the singleton instead and every Java mixin would stop compiling. So
 * this is the same class shape with the same companion, minus the constructor the NeoForge loader
 * alone calls; `WgpuMcFabric` does the work that constructor did.
 */
class WgpuMcMod {
        companion object {
                const val MOD_ID = "wgpu_mc"

                @JvmField
                val LOGGER: Logger = LogUtils.getLogger()

                @JvmField
                var ENTITIES_UPLOADED: Boolean = false

                @JvmField
                var MAY_INJECT_PART_IDS: Boolean = false

                @JvmField
                var TIME_SPENT_ENTITIES: Long = 0

                @JvmField
                var ENTRIES: Long = 0

                /**
                 * The section feed's phases, in nanoseconds, and how many offers they came from.
                 *
                 * The same shape as [TIME_SPENT_ENTITIES]: a running total per phase, with a count to divide
                 * it by, so what the F3 screen shows is an average per section rebuild rather than a number
                 * that depends on how long the game has been running. Filled only while the section-timing
                 * switch is on - see `Diagnostics.sectionTimingEnabled` - and the clock is not read at all
                 * when it is off.
                 *
                 * [java.util.concurrent.atomic.LongAdder] rather than the plain `long` the entity upload uses:
                 * the entity models are uploaded once, from the render thread, while this is written by every
                 * chunk-build thread at once - and `+=` on a plain `long` loses updates under contention, which
                 * is how the first version of this reported a total that went *down* between two reports.
                 *
                 * The three phases are the three things a rebuild does on Minecraft's chunk-build thread:
                 * look up the light of the 27 sections around it, read the block data out of the container
                 * (palette, storage and the comparison that decides whether it has to be sent), and hand the
                 * payload over in one call.
                 */
                @JvmField
                val TIME_SPENT_SECTION_LIGHT: LongAdder = LongAdder()

                @JvmField
                val TIME_SPENT_SECTION_BLOCKS: LongAdder = LongAdder()

                @JvmField
                val TIME_SPENT_SECTION_CALL: LongAdder = LongAdder()

                @JvmField
                val SECTION_OFFERS: LongAdder = LongAdder()

                /** The payload bytes the feed has sent, so the cost of a diff can be read against its size. */
                @JvmField
                val SECTION_PAYLOAD_BYTES: LongAdder = LongAdder()
        }
}
