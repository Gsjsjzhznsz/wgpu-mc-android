package dev.birb.wgpu

import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * **The shared constants, under their Forge twin's name.** (The file is named `WgpuMcModShared`
 * rather than `WgpuMcMod` for one mechanical reason: the overlay sources' exclude patterns name
 * the Forge `WgpuMcMod.kt` by path, and a file of the same name here would be caught by the same
 * pattern - the class inside is the one every reference resolves.)
 *
 * The mod's code reads `WgpuMcMod.LOGGER` from everywhere - the GL seam, the native library
 * loader, this module's own entrypoint - and on Forge those live on the `@Mod` class. On Fabric
 * there is no `@Mod` entrypoint to hang a companion on, but the Java mixins read these as *static*
 * fields (`WgpuMcMod.LOGGER`), which is the shape a companion's `@JvmField` gives. So this is the
 * same class shape with the same companion, minus the constructor the Forge loader alone calls;
 * `WgpuMcFabric` does the work that constructor did.
 */
class WgpuMcMod {
        companion object {
                const val MOD_ID = "wgpu_mc"

                /**
                 * Named the same as the Forge branch's logger on purpose: the two loaders share their log
                 * lines, and a line that says `wgpu-mc:` on one and `Neolectrum:` on the other is a line
                 * nobody can grep for across both.
                 */
                @JvmField
                val LOGGER: Logger = LoggerFactory.getLogger("wgpu-mc")
        }
}
