package dev.birb.wgpu

import net.neoforged.fml.common.Mod
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * The mod entry point.
 *
 * `neoforge.mods.toml` names `kotlinforforge` as the mod loader, so this class is handed to Kotlin
 * for Forge's language adapter rather than to FML's own - which is why `@Mod` sits on a Kotlin class
 * rather than on a Java one.
 *
 * There is deliberately no `modEventBus` constructor parameter yet. On the 26.1 branch this class
 * subscribes to the renderer's own setup events; here nothing is wired up until there is a renderer
 * to wire, and a class that takes the bus but never uses it hides the moment it starts mattering.
 */
@Mod(WgpuMcMod.MOD_ID)
class WgpuMcMod {
	init {
		LOGGER.info("Neolectrum {} on Minecraft 1.21.1 (NeoForge, Java 21)", MOD_ID)
	}

	companion object {
		const val MOD_ID = "wgpu_mc"

		/**
		 * Named the same as the 26.1 branch's logger on purpose: the two branches share their log
		 * lines, and a line that says `wgpu-mc:` on one and `Neolectrum:` on the other is a line
		 * nobody can grep for across both.
		 */
		@JvmField
		val LOGGER: Logger = LoggerFactory.getLogger("wgpu-mc")
	}
}
