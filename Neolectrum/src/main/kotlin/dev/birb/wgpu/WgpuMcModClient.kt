package dev.birb.wgpu

import dev.birb.wgpu.render.ShaderReloadListener
import dev.birb.wgpu.rust.WgpuNative
import net.minecraft.resources.Identifier
import net.neoforged.api.distmarker.Dist
import net.neoforged.bus.api.IEventBus
import net.neoforged.fml.common.Mod
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent
import net.neoforged.fml.loading.FMLPaths
import net.neoforged.neoforge.client.event.AddClientReloadListenersEvent
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.common.NeoForge
import java.util.function.Consumer

@Mod(value = WgpuMcMod.MOD_ID, dist = [Dist.CLIENT])
class WgpuMcModClient(modEventBus: IEventBus) {
        init {
                modEventBus.addListener(::onClientSetup)
                modEventBus.addListener(::onRegisterClientReloadListeners)

                // **The per-tick work, registered explicitly.** These three objects used to carry
                // their own `@EventBusSubscriber`, which tied their files to the NeoForge imports the
                // annotation needs - and the files hold the renderer's per-tick heartbeat, the cache
                // the bake reads its block colours from and the camera the frame comparisons pin.
                // The logic is loader-neutral and the registration is not, so the annotations moved
                // here: named `Consumer` classes rather than lambdas, because the bus reads the event
                // type out of the consumer's generic signature, and a named class writes that
                // signature where reflection always finds it.
                NeoForge.EVENT_BUS.addListener(ClientTickPostConsumer())
        }

        private fun onClientSetup(event: FMLClientSetupEvent) {
                event.enqueueWork {
                        try {
                                WgpuNative.getClassLoader()
                                WgpuNative.sendRunDirectory(FMLPaths.GAMEDIR.get().toAbsolutePath().normalize().toString())
                                WgpuNative.setPanicHook()

                                // The renderer reads its config during mod construction, which is before
                                // `setPanicHook` installs `env_logger` - so everything it says while loading it is
                                // dropped, and that is both the settings themselves and the warning that a
                                // malformed file was replaced by the defaults. Reading the same document back here
                                // is what makes "my setting did not survive the restart" answerable from the log
                                // instead of from the config file by hand. They live in
                                // `config/wgpu-mc-renderer.json`, next to the game directory.
                                WgpuMcMod.LOGGER.info(
                                        "wgpu-mc renderer settings as loaded: {}",
                                        WgpuNative.getSettings(),
                                )

                                WgpuMcMod.LOGGER.info("wgpu-mc native bridge initialized")
                        } catch (throwable: Throwable) {
                                WgpuMcMod.LOGGER.error("Failed to initialize wgpu-mc native bridge", throwable)
                        }
                }
        }

        private fun onRegisterClientReloadListeners(event: AddClientReloadListenersEvent) {
                // NeoForge 26.1 replaced RegisterClientReloadListenersEvent's
                // registerReloadListener(listener) with SortedReloadListenerEvent#addListener(id, listener).
                event.addListener(Identifier.fromNamespaceAndPath(WgpuMcMod.MOD_ID, "shaders"), ShaderReloadListener)
        }

        /** The per-tick heartbeat, one [Consumer] the bus can read an event type out of. */
        private class ClientTickPostConsumer : Consumer<ClientTickEvent.Post> {
                override fun accept(event: ClientTickEvent.Post) {
                        BlockCache.onClientTick()
                        DebugCamera.onClientTick()
                        DebugReload.onClientTick()
                }
        }
}
