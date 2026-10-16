package dev.birb.wgpu.entity

import net.minecraft.client.model.geom.ModelLayerLocation
import net.minecraft.world.entity.EntityType

object EntityState {
        @JvmField
        var builderType: EntityType<*>? = null

        @JvmField
        val layers: HashMap<EntityType<*>, EntityModelInfo> = HashMap()

        @JvmField
        var registeringRoot: Boolean = false

        @JvmField
        val matrixIndices: HashMap<String, HashMap<String, Int>> = HashMap()

        class EntityModelInfo {
                @JvmField
                var root: ModelLayerLocation? = null

                @JvmField
                val features: MutableList<ModelLayerLocation> = ArrayList()
        }
}
