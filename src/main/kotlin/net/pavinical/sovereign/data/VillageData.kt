package net.pavinical.sovereign.data

import net.minecraft.util.math.BlockPos
import java.util.UUID

/**
 * All the data associated with a single registered village.
 */
data class VillageData(
    val id: UUID,
    var name: String,
    val biome: String,
    val clerkPos: BlockPos,
    var centerPos: BlockPos = clerkPos,
    val founderId: UUID? = null,
    var specializationProfessionId: String = "",
    var tier: VillageTier = VillageTier.HAMLET,
    var experience: Int = 0,
    var tradeExperience: Int = 0,
    val resources: MutableMap<ResourceType, Int> = ResourceType.entries
        .associateWith { 0 }
        .toMutableMap(),
    var lastEconomyDay: Long = -1L,
    var lastEconomyTick: Long = -1L,
    var lastProductionTick: Long = -1L,
    var lastNeedTick: Long = -1L,
    var priceModifierDay: Long = -1L,
    val dailyPriceModifiers: MutableMap<String, Int> = mutableMapOf(),
    val producedResources: MutableMap<ResourceType, Int> = ResourceType.entries.associateWith { 0 }.toMutableMap(),
    val consumedResources: MutableMap<ResourceType, Int> = ResourceType.entries.associateWith { 0 }.toMutableMap(),
    val surplusResources: MutableMap<ResourceType, Int> = ResourceType.entries.associateWith { 0 }.toMutableMap(),
    val deficitResources: MutableMap<ResourceType, Int> = ResourceType.entries.associateWith { 0 }.toMutableMap(),
    val sellStockRemaining: MutableMap<ResourceType, Int> = ResourceType.entries.associateWith { 0 }.toMutableMap(),
    val sellStockRemainingByProfession: MutableMap<String, Int> = mutableMapOf(),
    val artisanRefillTickByProfession: MutableMap<String, Long> = mutableMapOf(),
    val artisanRefillCountByProfession: MutableMap<String, Int> = mutableMapOf(),
    val buyDemandTotal: MutableMap<ResourceType, Int> = ResourceType.entries.associateWith { 0 }.toMutableMap(),
    val buyDemandFulfilled: MutableMap<ResourceType, Int> = ResourceType.entries.associateWith { 0 }.toMutableMap(),
    val buyDemandTotalByProfession: MutableMap<String, Int> = mutableMapOf(),
    val buyDemandFulfilledByProfession: MutableMap<String, Int> = mutableMapOf(),
    val wantDemandTotalByProfession: MutableMap<String, Int> = mutableMapOf(),
    val wantDemandFulfilledByProfession: MutableMap<String, Int> = mutableMapOf(),
    val wantTradeCountByProfession: MutableMap<String, Int> = mutableMapOf(),
    val commissions: MutableList<VillageCommissionData> = mutableListOf(),
    val plots: MutableList<VillagePlotData> = mutableListOf(),
    var treasuryEmeralds: Int = -1,
    var lastTreasuryReplenishTick: Long = -1L,
    val buyPrices: MutableMap<ResourceType, Int> = ResourceType.entries.associateWith { 0 }.toMutableMap(),
    val sellPrices: MutableMap<ResourceType, Int> = ResourceType.entries.associateWith { 0 }.toMutableMap()
)

