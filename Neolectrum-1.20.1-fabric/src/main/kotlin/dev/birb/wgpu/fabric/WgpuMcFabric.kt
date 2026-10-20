package dev.birb.wgpu.fabric

import dev.birb.wgpu.WgpuMcMod
import dev.birb.wgpu.backend.DisplayMode
import dev.birb.wgpu.render.Wgpu
import dev.birb.wgpu.rust.WgpuNative
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.loader.api.FabricLoader

/**
 * The Fabric answer to the two `@Mod` classes the Forge build carries, on Minecraft 1.20.1.
 *
 * The sequence is the one the Forge client setup runs, ordered the same way for the same reasons:
 * the native library comes up first (a 1.20.1 client that cannot load the cdylib should still
 * reach the title screen and say so, not crash during mod loading with no log line naming the
 * library it wanted), the run directory goes to it, and the panic hook plus the settings readback
 * follow.
 *
 * **The renderer itself is not started from here** - this entrypoint runs before the game's
 * window exists. `WindowMixin` creates the renderer at the tail of the window's constructor, the
 * first moment the window handle is real, and that mixin is where a failure to create it reports
 * instead of throwing. What this class does bring up is everything the renderer needs before the
 * window: the library and the JVM-side panic/debug plumbing the engine resolves by name.
 *
 * **This is a `class`, not an `object`, and that is load-bearing.** Fabric's default language
 * adapter instantiates entrypoints with `Class.forName(...).getDeclaredConstructor().newInstance()`
 * - no `setAccessible` on the way - and a Kotlin `object` compiles to a class whose no-argument
 * constructor is *private*, so the reflective construction dies with
 * `IllegalAccessException ... modifiers "private"` before one line of `onInitializeClient` runs
 * (the every-launch crash android.43 shipped with on the 26.x line). A plain class carries the
 * public no-argument constructor the adapter needs.
 */
class WgpuMcFabric : ClientModInitializer {
        override fun onInitializeClient() {
                WgpuMcMod.LOGGER.info("Initializing wgpu-mc Fabric module (1.20.1)")

                try {
                        WgpuNative.loadWm()
                        WgpuNative.sendRunDirectory(
                                FabricLoader.getInstance().gameDir.toAbsolutePath().normalize().toString()
                        )
                        WgpuNative.setPanicHook()
                        WgpuMcMod.LOGGER.info("wgpu-mc native bridge initialised")

                        // The same readback the Forge setup logs, for the same reason: the renderer
                        // read the config before `setPanicHook` installed `env_logger`, and this line
                        // is what makes "my setting did not survive the restart" answerable from the
                        // log.
                        WgpuMcMod.LOGGER.info(
                                "wgpu-mc renderer settings as loaded: {}",
                                WgpuNative.getSettings(),
                        )
                } catch (throwable: Throwable) {
                        WgpuMcMod.LOGGER.error(
                                "wgpu-mc could not bring the native renderer library up, so this client will run on " +
                                        "Minecraft's own renderer: {}",
                                throwable.toString(),
                        )
                        WgpuMcMod.LOGGER.debug("wgpu-mc native bootstrap failure", throwable)
                }

                // The window's display mode, applied once the game and its window both exist - the
                // same job the Forge build does from its client-tick listener. Client ticks rather
                // than frames, the same phase.
                ClientTickEvents.END_CLIENT_TICK.register {
                        DisplayMode.applyOnFirstFrame()
                }
        }
}
