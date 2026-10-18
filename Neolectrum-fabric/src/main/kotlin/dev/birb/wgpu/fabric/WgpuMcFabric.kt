package dev.birb.wgpu.fabric

import dev.birb.wgpu.BlockCache
import dev.birb.wgpu.DebugCamera
import dev.birb.wgpu.DebugReload
import dev.birb.wgpu.WgpuMcMod
import dev.birb.wgpu.render.ShaderReloadListener
import dev.birb.wgpu.rust.WgpuNative
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.resource.ResourceManagerHelper
import net.fabricmc.fabric.api.resource.IdentifiableResourceReloadListener
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.resources.Identifier
import net.minecraft.server.packs.PackType

/**
 * The Fabric answer to the two `@Mod` classes the NeoForge build carries.
 *
 * The sequence is the one `WgpuMcMod`'s constructor and `WgpuMcModClient`'s client setup run,
 * ordered the same way for the same reasons: the run directory goes to the native **before** the
 * renderer exists (the renderer is built while the window is created, and the config it reads
 * then names the backend - a run directory handed over after that is a setting that silently does
 * nothing), and the panic hook plus the settings readback come as soon as the loader is on.
 *
 * The per-tick heartbeat and the shader reload listener register here exactly as
 * `WgpuMcModClient` registers them on NeoForge - the objects are loader-neutral, the hooks are
 * not, and this file is the Fabric pair of that registration.
 */
object WgpuMcFabric : ClientModInitializer {
        override fun onInitializeClient() {
                WgpuMcMod.LOGGER.info("Initializing wgpu-mc Fabric module")

                try {
                        WgpuNative.getClassLoader()
                        WgpuNative.sendRunDirectory(
                                FabricLoader.getInstance().gameDir.toAbsolutePath().normalize().toString()
                        )
                        WgpuNative.setPanicHook()

                        // The same readback the NeoForge setup logs, for the same reason: the
                        // renderer read the config before `setPanicHook` installed `env_logger`,
                        // and this line is what makes "my setting did not survive the restart"
                        // answerable from the log.
                        WgpuMcMod.LOGGER.info(
                                "wgpu-mc renderer settings as loaded: {}",
                                WgpuNative.getSettings(),
                        )

                        WgpuMcMod.LOGGER.info("wgpu-mc native bridge initialized")
                } catch (throwable: Throwable) {
                        WgpuMcMod.LOGGER.error("Failed to initialize wgpu-mc native bridge", throwable)
                }

                // The per-tick heartbeat: the cache the bake reads its block colours from, the
                // camera the frame comparisons pin, and the debug reload marker. Client ticks
                // rather than frames, the same phase NeoForge's `ClientTickEvent.Post` gives them.
                ClientTickEvents.END_CLIENT_TICK.register {
                        BlockCache.onClientTick()
                        DebugCamera.onClientTick()
                        DebugReload.onClientTick()
                }

                // The shader reload listener, under the same id the NeoForge event names it with.
                // Fabric's resource-loader wants an identifiable listener, and the game's own
                // listener interface the mod implements is the one it delegates through.
                ResourceManagerHelper.get(PackType.CLIENT_RESOURCES)
                        .registerReloadListener(FabricShaderReloadListener)
        }
}

/** The mod's shader reload listener, named the way Fabric's resource-loader registers it. */
object FabricShaderReloadListener : IdentifiableResourceReloadListener by ShaderReloadListener {
        override val fabricId: Identifier
                get() = Identifier.fromNamespaceAndPath(WgpuMcMod.MOD_ID, "shaders")
}
