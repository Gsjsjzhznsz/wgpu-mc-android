package dev.birb.wgpu

import dev.birb.wgpu.backend.DisplayMode
import dev.birb.wgpu.backend.WgpuRenderer
import dev.birb.wgpu.render.Wgpu
import dev.birb.wgpu.rust.WgpuNative
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.event.TickEvent
import net.minecraftforge.fml.common.Mod
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent
import net.minecraftforge.fml.loading.FMLEnvironment
import net.minecraftforge.fml.loading.FMLPaths
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * The mod entry point, on Forge 1.20.1 - the loader this line has instead of NeoForge, because
 * NeoForge forked from Forge at 1.20.2 and does not exist here.
 *
 * `mods.toml` names `javafml` as the mod loader, so this class is handed to FML's own language
 * adapter, which instantiates it with `getDeclaredConstructor().newInstance()` - the same
 * reflective construction Fabric's default adapter does, and the same reason this is a plain
 * `class` and not an `object`: a Kotlin `object`'s no-argument constructor is private, and that
 * reflection cannot touch it. A Kotlin class carries the public constructor the adapter needs,
 * which is the whole reason the entry can be Kotlin at all without Kotlin for Forge on the line.
 *
 * **One class, both halves.** The 26.x lines carry a second `@Mod` class for the client; this
 * one folds it in, behind a dist gate - a dedicated server constructs this class too, and the
 * only thing it may do there is log. The client half registers on the two buses from the
 * constructor (the mod bus for the lifecycle event, the game bus for the tick), exactly where
 * the NeoForge entries registered theirs.
 *
 * The client half's sequence: the native library comes up first (a client that cannot load the
 * cdylib should still reach the title screen and say so, not crash during mod loading with no
 * log line naming the library it wanted), the run directory goes to it, the panic hook and the
 * settings readback follow. The renderer itself is not started from the setup event - it is
 * started from `Minecraft`'s own window constructor (`WindowMixin`'s tail, the first moment the
 * window handle is real), because 1.20.1 creates its GL context there and the window is claimed
 * before that. [onClientSetup] still calls [WgpuRenderer.create] as a second attempt, for the
 * launch whose window mixin never fired - the create call guards itself with an `attempted`
 * flag, so the second attempt is free.
 */
@Mod(WgpuMcMod.MOD_ID)
class WgpuMcMod {
        init {
                LOGGER.info("Neolectrum {} on Minecraft 1.20.1 (Forge, Java 17)", MOD_ID)

                if (FMLEnvironment.dist == Dist.CLIENT) {
                        val modEventBus = net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext.get().modEventBus
                        modEventBus.addListener(::onClientSetup)
                        MinecraftForge.EVENT_BUS.addListener(::onClientTick)
                }
        }

        /**
         * Puts the window into the mode the renderer's settings name, once, on the first tick.
         *
         * **From the tick rather than from setup, because setup runs before there is a window to put into
         * it** - the window is built by `Minecraft`'s constructor, and the client setup event is queued to
         * work that runs after it, but the mode is a GLFW call against the game's own `Window` object and
         * that object is only reachable once the game exists. It applies itself exactly once per session:
         * see [DisplayMode.applyOnFirstFrame].
         */
        private fun onClientTick(event: TickEvent.ClientTickEvent) {
                if (event.phase == TickEvent.Phase.END) {
                        DisplayMode.applyOnFirstFrame()
                }
        }

        private fun onClientSetup(event: FMLClientSetupEvent) {
                event.enqueueWork {
                        try {
                                WgpuNative.loadWm()
                                WgpuNative.sendRunDirectory(FMLPaths.GAMEDIR.get().toAbsolutePath().normalize().toString())
                                WgpuNative.setPanicHook()
                                LOGGER.info("wgpu-mc native bridge initialised")

                                // The same readback the Fabric entry logs, for the same reason: the renderer
                                // read the config before `setPanicHook` installed `env_logger`, and this line is
                                // what makes "my setting did not survive the restart" answerable from the log.
                                LOGGER.info(
                                        "wgpu-mc renderer settings as loaded: {}",
                                        WgpuNative.getSettings(),
                                )

                                // And the renderer, which the window's own constructor has usually built by now;
                                // this is the fallback for the launch where it did not. It reports its own failure
                                // rather than throwing: a client whose renderer could not be created still has
                                // Minecraft's own renderer to fall back on, and the reason is worth more in the log
                                // than in an exception that ends the launch.
                                if (WgpuRenderer.create()) {
                                        Wgpu.setRendererLive(true)
                                }
                        } catch (throwable: Throwable) {
                                LOGGER.error(
                                        "wgpu-mc could not bring the native renderer library up, so this client will run on " +
                                                "Minecraft's own renderer: {}",
                                        throwable.toString(),
                                )
                                LOGGER.debug("wgpu-mc native bootstrap failure", throwable)
                        }
                }
        }

        companion object {
                const val MOD_ID = "wgpu_mc"

                /**
                 * Named the same as the Fabric branch's logger on purpose: the two loaders share their log
                 * lines, and a line that says `wgpu-mc:` on one and `Neolectrum:` on the other is a line
                 * nobody can grep for across both.
                 */
                @JvmField
                val LOGGER: Logger = LoggerFactory.getLogger("wgpu-mc")
        }
}
