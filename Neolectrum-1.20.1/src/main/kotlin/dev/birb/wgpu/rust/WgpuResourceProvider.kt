package dev.birb.wgpu.rust

import net.minecraft.client.Minecraft
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.packs.resources.ResourceManager
import java.io.IOException

/**
 * How the engine reads a file out of the game's resource packs.
 *
 * **It is a callback and not a path** because the engine is Rust: it has no idea what a resource pack
 * is, which ones are loaded, or that a mod's assets live inside a jar. Every shader and every texture it
 * loads comes back through here, by the resource id the pack system uses - `wgpu_mc:shaders/terrain.wgsl`
 * - and the answer is the bytes the game itself would read.
 *
 * **An empty array means "no such resource", and it is the only answer a failure gives.** The engine
 * treats a missing file as a legitimate outcome (a pack may not ship a shader) and a thrown exception as
 * a broken bridge: it describes and clears the exception, logs the Java stack, and returns nothing - so
 * a `FileNotFoundException` escaping this method would be reported against the wrong call. Every failure
 * here is an empty answer instead, which is what `None` means on the other side.
 */
object WgpuResourceProvider {
        /**
         * The manager a reload is currently running against, or null outside a reload.
         *
         * The engine reads from threads that are not the render thread - the bake pool asks for shaders - and
         * `Minecraft.getResourceManager()` is only meaningful once the game exists. During a reload the
         * manager being *built* is not the one the field points at yet, so the reload listener parks it here
         * and the fallback below covers every other moment.
         */
        @JvmField
        @Volatile
        var manager: ResourceManager? = null

        /**
         * The bytes of a resource, or an empty array.
         *
         * @param path a resource id, as text: `namespace:path`
         */
        @JvmStatic
        fun getResource(path: String): ByteArray {
                // 1.20.1 answers the parse with a nullable tryParse where 26.x throws-and-answers
                // parse(); a null here is the same "not a resource id" the catch answered.
                val id = ResourceLocation.tryParse(path) ?: return ByteArray(0)

                manager?.let { activeManager ->
                        val bytes = readResource(activeManager, id)
                        if (bytes.isNotEmpty()) {
                                return bytes
                        }
                }

                val minecraft = Minecraft.getInstance() ?: return ByteArray(0)
                return readResource(minecraft.resourceManager, id)
        }

        private fun readResource(manager: ResourceManager, id: ResourceLocation): ByteArray {
                return try {
                        val resource = manager.getResource(id)
                        if (resource.isEmpty) {
                                return ByteArray(0)
                        }

                        resource.get().open().use { stream ->
                                stream.readAllBytes()
                        }
                } catch (_: IOException) {
                        ByteArray(0)
                }
        }
}