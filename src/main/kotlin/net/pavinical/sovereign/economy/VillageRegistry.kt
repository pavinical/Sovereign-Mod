package net.pavinical.sovereign.economy

import com.mojang.serialization.Codec
import com.mojang.serialization.Dynamic
import net.minecraft.nbt.NbtCompound
import net.minecraft.nbt.NbtList
import net.minecraft.nbt.NbtOps
import net.minecraft.nbt.NbtString
import net.minecraft.util.math.BlockPos
import net.minecraft.world.PersistentState
import net.minecraft.world.PersistentStateType
import net.pavinical.sovereign.data.ResourceType
import net.pavinical.sovereign.data.VillageData
import net.pavinical.sovereign.data.VillageTier
import java.util.UUID

/**
 * Stores all village data using Minecraft's PersistentState system,
 * which saves to a .dat file in the world folder and survives restarts.
 *
 * Access it via:
     *   server.overworld.persistentStateManager.getOrCreate(VillageRegistry.TYPE)
 *
 * Always use the overworld — PersistentState is dimension-specific and the
 * overworld is always loaded, which keeps our data accessible from anywhere.
 */
class VillageRegistry : PersistentState() {

    // All registered villages, keyed by their UUID
    val villages: MutableMap<UUID, VillageData> = mutableMapOf()

    fun addVillage(village: VillageData) {
        villages[village.id] = village
        markDirty() // tells Minecraft this state needs to be saved
    }

    /** Returns the village whose Clerk Table sits at [pos], or null if none. */
    fun getVillageAtPos(pos: BlockPos): VillageData? =
        villages.values.firstOrNull { it.clerkPos == pos }

    fun getVillageWithinRadius(pos: BlockPos, radius: Int): VillageData? {
        val radiusSquared = radius * radius
        return villages.values.firstOrNull { village ->
            val dx = village.clerkPos.x - pos.x
            val dz = village.clerkPos.z - pos.z
            dx * dx + dz * dz <= radiusSquared
        }
    }

    // -------------------------------------------------------------------------
    // Serialization — write to NBT (the save format Minecraft uses for .dat files)
    // -------------------------------------------------------------------------
    private fun writeNbt(nbt: NbtCompound) {
        val villageList = NbtList()

        for (village in villages.values) {
            val tag = NbtCompound()
            tag.putString("id", village.id.toString())
            tag.putString("name", village.name)
            tag.putString("biome", village.biome)
            tag.putString("tier", village.tier.name)
            tag.putInt("clerkX", village.clerkPos.x)
            tag.putInt("clerkY", village.clerkPos.y)
            tag.putInt("clerkZ", village.clerkPos.z)

            val resourcesTag = NbtCompound()
            for ((type, amount) in village.resources) {
                resourcesTag.putInt(type.name, amount)
            }
            tag.put("resources", resourcesTag)

            val villagersTag = NbtList()
            for (villagerId in village.villagerIds) {
                villagersTag.add(NbtString.of(villagerId.toString()))
            }
            tag.put("villagers", villagersTag)

            villageList.add(tag)
        }

        nbt.put("villages", villageList)
    }

    private fun toNbt(): NbtCompound {
        val nbt = NbtCompound()
        writeNbt(nbt)
        return nbt
    }

    companion object {
        // -------------------------------------------------------------------------
        // Deserialization — read back from NBT on world load
        // -------------------------------------------------------------------------
        private fun createFromNbt(nbt: NbtCompound): VillageRegistry {
            val registry = VillageRegistry()
            // Type ID 10 = NbtCompound, 8 = NbtString
            val villageList = nbt.getList("villages").orElse(NbtList())

            for (i in 0 until villageList.size) {
                val tag = villageList[i] as NbtCompound
                val id = UUID.fromString(tag.getString("id").orElse(""))
                val name = tag.getString("name").orElse("")
                val biome = tag.getString("biome").orElse("")
                val tier = VillageTier.valueOf(tag.getString("tier").orElse("HAMLET"))
                val clerkPos = BlockPos(
                    tag.getInt("clerkX").orElse(0),
                    tag.getInt("clerkY").orElse(0),
                    tag.getInt("clerkZ").orElse(0)
                )

                val resources = ResourceType.entries.associateWith { 0 }.toMutableMap()
                val resourcesTag = tag.getCompound("resources").orElse(NbtCompound())
                for (type in ResourceType.entries) {
                    resources[type] = resourcesTag.getInt(type.name).orElse(0)
                }

                val villagerIds = mutableListOf<UUID>()
                val villagersTag = tag.getList("villagers").orElse(NbtList())
                for (j in 0 until villagersTag.size) {
                    villagerIds.add(UUID.fromString((villagersTag[j] as NbtString).asString().orElse("")))
                }

                registry.villages[id] = VillageData(id, name, biome, clerkPos, tier, resources, villagerIds)
            }

            return registry
        }

        /** The Type descriptor Minecraft needs to get-or-create our state. */
        val TYPE = PersistentStateType(
            KEY,
            ::VillageRegistry,
            Codec.PASSTHROUGH.xmap(
                { dynamic ->
                    val value = dynamic.convert(NbtOps.INSTANCE).value
                    if (value is NbtCompound) createFromNbt(value) else VillageRegistry()
                },
                { registry -> Dynamic(NbtOps.INSTANCE, registry.toNbt()) }
            ),
            null
        )

        /** The filename used under world/data/ (becomes sovereign_villages.dat). */
        const val KEY = "sovereign_villages"
    }
}
