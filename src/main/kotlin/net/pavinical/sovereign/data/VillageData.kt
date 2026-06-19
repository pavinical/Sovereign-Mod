package net.pavinical.sovereign.data

import net.minecraft.util.math.BlockPos
import java.util.UUID

/**
 * All the data associated with a single registered village.
 *
 * @param id          Unique ID for this village — never changes.
 * @param name        Player-chosen display name.
 * @param biome       The biome path at the Clerk Table (e.g. "plains", "desert").
 * @param clerkPos    Block position of the Clerk Table that founded this village.
 * @param tier        Current progression tier; starts at HAMLET.
 * @param resources   Running totals for each resource category.
 * @param villagerIds UUIDs of villagers bound to this village's economy.
 */
data class VillageData(
    val id: UUID,
    var name: String,
    val biome: String,
    val clerkPos: BlockPos,
    var tier: VillageTier = VillageTier.HAMLET,
    val resources: MutableMap<ResourceType, Int> = ResourceType.entries
        .associateWith { 0 }
        .toMutableMap(),
    val villagerIds: MutableList<UUID> = mutableListOf()
)
