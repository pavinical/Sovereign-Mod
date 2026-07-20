package net.pavinical.sovereign.world

import net.minecraft.nbt.NbtCompound
import net.minecraft.nbt.NbtElement
import net.minecraft.nbt.NbtList
import net.minecraft.registry.RegistryWrapper
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.BlockPos
import net.minecraft.world.PersistentState

class GeneratedClerkHallRegistry : PersistentState() {
    private val halls = mutableListOf<BlockPos>()

    fun hasHallNear(pos: BlockPos, radius: Int): Boolean {
        val radiusSquared = radius * radius
        return halls.any {
            val dx = it.x - pos.x
            val dz = it.z - pos.z
            dx * dx + dz * dz <= radiusSquared
        }
    }

    fun markHallPlaced(pos: BlockPos) {
        if (hasHallNear(pos, 1)) return
        halls += pos
        markDirty()
    }

    override fun writeNbt(nbt: NbtCompound, registries: RegistryWrapper.WrapperLookup): NbtCompound {
        val list = NbtList()
        halls.forEach { pos ->
            val hall = NbtCompound()
            hall.putInt("x", pos.x)
            hall.putInt("y", pos.y)
            hall.putInt("z", pos.z)
            list.add(hall)
        }
        nbt.put("halls", list)
        return nbt
    }

    companion object {
        private const val KEY = "sovereign_generated_clerk_halls"

        private val TYPE = Type(
            ::GeneratedClerkHallRegistry,
            { nbt, _ -> fromNbt(nbt) },
            null
        )

        fun get(world: ServerWorld): GeneratedClerkHallRegistry {
            return world.persistentStateManager.getOrCreate(TYPE, KEY)
        }

        private fun fromNbt(nbt: NbtCompound): GeneratedClerkHallRegistry {
            val registry = GeneratedClerkHallRegistry()
            val list = nbt.getList("halls", NbtElement.COMPOUND_TYPE.toInt())
            for (index in 0 until list.size) {
                val hall = list.getCompound(index)
                registry.halls += BlockPos(hall.getInt("x"), hall.getInt("y"), hall.getInt("z"))
            }
            return registry
        }
    }
}
