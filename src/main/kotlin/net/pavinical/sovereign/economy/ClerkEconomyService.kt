package net.pavinical.sovereign.economy

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.minecraft.registry.Registries
import net.minecraft.server.MinecraftServer
import net.minecraft.server.world.ServerWorld
import net.minecraft.village.VillagerProfession
import net.pavinical.sovereign.block.ClerkTableBlockEntity
import net.pavinical.sovereign.block.VillageEconomyState
import net.pavinical.sovereign.data.ResourceType
import net.pavinical.sovereign.data.VillageData
import net.pavinical.sovereign.data.VillageTier
import kotlin.math.roundToInt
import kotlin.math.max as kMax

object ClerkEconomyService {
    private const val TICKS_PER_DAY = 24000L
    private const val FARMER_DAILY_OUTPUT_CAP = 10
    private const val PEASANT_PRODUCTION_INTERVAL_TICKS = 6000L
    private const val PEASANT_TIMED_OUTPUT_BASE = 15
    private const val ARTISAN_TIMED_OUTPUT_BASE = 1
    private const val PROFESSION_SUPPLY_EFFECTIVE_COUNT_CAP = 6
    private const val PROFESSION_SUPPLY_QUANTITY_BONUS_PERCENT_PER_EXTRA = 10
    private const val PROFESSION_SUPPLY_QUANTITY_BONUS_PERCENT_MAX = 50
    private const val PROFESSION_DIVERSITY_OUTPUT_BONUS_PERCENT_PER_EXTRA = 5
    private const val PROFESSION_DIVERSITY_OUTPUT_BONUS_PERCENT_MAX = 20
    private const val SELL_DISCOUNT_MIN_RATIO = 0.65
    private const val SELL_DISCOUNT_MAX_RATIO = 1.0
    private const val OUTPUT_KEY_PREFIX = "output|"
    private const val CLERIC_POTION_VARIANT_COUNT = 3
    private val ARTISAN_PROFESSION_PATHS = setOf("librarian", "cleric", "toolsmith", "weaponsmith", "armorer", "cartographer")

    data class CommodityDelta(val resource: ResourceType, val amount: Int)

    private data class EconomyProfile(
        val professionNeed: CommodityDelta?,
        val professionOutput: CommodityDelta?
    )

    private data class TradePriceProfile(
        val tableToPlayerMin: Int,
        val tableToPlayerMax: Int,
        val playerToTableMin: Int,
        val playerToTableMax: Int
    )

    fun register() {
        ServerTickEvents.END_SERVER_TICK.register(::tick)
    }

    fun refreshVillageFromSnapshot(world: ServerWorld, village: VillageData, snapshot: VillageEconomyState): Boolean {
        val currentDay = world.time / TICKS_PER_DAY
        var changed = false

        val newEconomyDay = village.lastEconomyDay < currentDay
        if (newEconomyDay) {
            resetVillageEconomy(village)
            village.lastEconomyDay = currentDay
            village.lastEconomyTick = currentDay * TICKS_PER_DAY
            village.lastNeedTick = currentDay * TICKS_PER_DAY
            changed = true
        }

        if (village.lastEconomyTick < 0L) {
            village.lastEconomyTick = currentDay * TICKS_PER_DAY
            changed = true
        }
        if (village.lastProductionTick < 0L) {
            village.lastProductionTick = village.lastEconomyTick
            changed = true
        }
        if (village.lastNeedTick < 0L) {
            village.lastNeedTick = village.lastEconomyTick
            changed = true
        }

        changed = refreshActiveVillageSnapshot(world, village, snapshot) || changed
        changed = applyTimedPeasantProduction(world, village, snapshot) || changed
        return changed
    }

    private fun tick(server: MinecraftServer) {
        val world = server.overworld
        val registry = world.persistentStateManager.getOrCreate(VillageRegistry.TYPE, VillageRegistry.KEY)
        if (registry.villages.isEmpty()) return

        var changed = false

        for (village in registry.villages.values) {
            val clerk = world.getBlockEntity(village.clerkPos) as? ClerkTableBlockEntity
            if (clerk == null) continue

            val snapshot = clerk.getSnapshot(village)
            if (refreshVillageFromSnapshot(world, village, snapshot)) {
                changed = true
            }
        }

        if (changed) {
            registry.markDirty()
        }
    }

    private fun refreshActiveVillageSnapshot(
        world: ServerWorld,
        village: VillageData,
        snapshot: VillageEconomyState
    ): Boolean {
        val (produced, consumed) = collectSlotDemandAndSupply(snapshot, village)
        val (professionProduced, professionConsumed) = collectSlotProfessionDemandAndSupply(snapshot, village)
        var changed = false
        val specializationProfessionId = specializationProfessionId(snapshot)
        if (village.specializationProfessionId != specializationProfessionId) {
            village.specializationProfessionId = specializationProfessionId
            changed = true
        }

        for ((resource, amount) in produced) {
            if (village.producedResources[resource] != amount) changed = true
            village.producedResources[resource] = amount
        }
        for ((resource, amount) in consumed) {
            if (village.consumedResources[resource] != amount) changed = true
            village.consumedResources[resource] = amount
        }

        for (resource in ResourceType.entries) {
            val resourceProduced = village.producedResources[resource] ?: 0
            val resourceConsumed = village.consumedResources[resource] ?: 0
            val balance = resourceProduced - resourceConsumed
            val priorSurplus = village.surplusResources[resource] ?: 0
            val priorStock = village.sellStockRemaining[resource] ?: 0

            if (balance > 0) {
                if (village.surplusResources[resource] != balance) changed = true
                village.surplusResources[resource] = balance
                if (village.deficitResources[resource] != 0) changed = true
                village.deficitResources[resource] = 0
                val soldToday = kMax(priorSurplus - priorStock, 0)
                val refreshedStock = kMax(balance - soldToday, 0).coerceAtMost(balance)
                if (village.sellStockRemaining[resource] != refreshedStock) changed = true
                village.sellStockRemaining[resource] = refreshedStock
                if (village.buyDemandTotal[resource] != 0) changed = true
                village.buyDemandTotal[resource] = 0
                if (village.buyDemandFulfilled[resource] != 0) changed = true
                village.buyDemandFulfilled[resource] = 0
                if (village.buyPrices[resource] != 0) changed = true
                village.buyPrices[resource] = 0
                val sellPrice = village.sellPrices[resource] ?: 0
                val updatedSellPrice = calculateSellPrice(world.time, resource, balance, kMax(balance, 1))
                if (sellPrice != updatedSellPrice) changed = true
                village.sellPrices[resource] = updatedSellPrice
            } else if (balance < 0) {
                if (village.surplusResources[resource] != 0) changed = true
                village.surplusResources[resource] = 0
                val dayDemand = kMax(-balance, 0)
                if (village.deficitResources[resource] != dayDemand) changed = true
                village.deficitResources[resource] = dayDemand
                if (village.sellStockRemaining[resource] != 0) changed = true
                village.sellStockRemaining[resource] = 0
                if (village.buyDemandTotal[resource] != dayDemand) {
                    changed = true
                    village.buyDemandTotal[resource] = dayDemand
                }

                val fulfilled = village.buyDemandFulfilled[resource] ?: 0
                val clampedFulfilled = fulfilled.coerceIn(0, dayDemand)
                if (village.buyDemandFulfilled[resource] != clampedFulfilled) changed = true
                village.buyDemandFulfilled[resource] = fulfilled.coerceIn(0, dayDemand)
                if (village.sellPrices[resource] != 0) changed = true
                village.sellPrices[resource] = 0
                val buyPrice = village.buyPrices[resource] ?: 0
                val updatedBuyPrice = calculateBuyPrice(world.time, resource, dayDemand)
                if (buyPrice != updatedBuyPrice) changed = true
                village.buyPrices[resource] = updatedBuyPrice
            } else {
                if (village.surplusResources[resource] != 0) changed = true
                village.surplusResources[resource] = 0
                if (village.deficitResources[resource] != 0) changed = true
                village.deficitResources[resource] = 0
                if (village.sellStockRemaining[resource] != 0) changed = true
                village.sellStockRemaining[resource] = 0
                if (village.buyDemandTotal[resource] != 0) {
                    changed = true
                    village.buyDemandTotal[resource] = 0
                }

                val fulfilled = village.buyDemandFulfilled[resource] ?: 0
                val clampedFulfilled = fulfilled.coerceIn(0, 0)
                if (village.buyDemandFulfilled[resource] != clampedFulfilled) changed = true
                village.buyDemandFulfilled[resource] = clampedFulfilled
                if (village.sellPrices[resource] != 0) changed = true
                village.sellPrices[resource] = 0
                if (village.buyPrices[resource] != 0) changed = true
                village.buyPrices[resource] = 0
            }
        }

        val professionKeys = (professionProduced.keys + professionConsumed.keys).toSet()
        val initialStock = mutableMapOf<String, Int>()
        for (professionId in professionKeys) {
            val producedAmount = professionProduced[professionId] ?: 0
            if (producedAmount > 0 && !village.sellStockRemainingByProfession.containsKey(professionId)) {
                initialStock[professionId] = producedAmount
            }
        }
        if (initialStock.isNotEmpty()) {
            changed = addProductionToStorage(village, initialStock) || changed
        }

        val staleProfessionIds = village.sellStockRemainingByProfession.keys.toSet() + village.buyDemandTotalByProfession.keys + village.buyDemandFulfilledByProfession.keys
        for (professionId in staleProfessionIds) {
            if (!professionKeys.contains(professionId)) {
                village.sellStockRemainingByProfession.remove(professionId)
                village.buyDemandTotalByProfession.remove(professionId)
                village.buyDemandFulfilledByProfession.remove(professionId)
                village.artisanRefillTickByProfession.remove(professionId)
                village.artisanRefillCountByProfession.remove(professionId)
            }
        }

        return changed
    }

    private fun applyTimedPeasantProduction(
        world: ServerWorld,
        village: VillageData,
        snapshot: VillageEconomyState
    ): Boolean {
        val elapsedTicks = world.time - village.lastProductionTick
        val productionSteps = (elapsedTicks / PEASANT_PRODUCTION_INTERVAL_TICKS).toInt()
        if (productionSteps <= 0) return false

        val (professionProduced, _) = collectSlotProfessionDemandAndSupply(
            snapshot = snapshot,
            village = village,
            includePeasantOutput = true,
            includeArtisanOutput = true,
            productionBatches = productionSteps
        )
        val changed = addProductionToStorage(village, professionProduced)
        village.lastProductionTick += productionSteps.toLong() * PEASANT_PRODUCTION_INTERVAL_TICKS
        village.lastEconomyTick = kMax(village.lastEconomyTick, village.lastProductionTick)
        return true
    }

    fun noteArtisanStockSold(village: VillageData, stockKey: String, worldTime: Long) {
        // Artisans now use the shared timed production path.
    }

    private fun addProductionToStorage(
        village: VillageData,
        professionProduced: Map<String, Int>
    ): Boolean {
        var remainingStorage = villageStorageCapacity(village.tier) - currentStoredSellItems(village)
        if (remainingStorage <= 0) return false

        var changed = false

        for ((professionId, dailyCapacity) in professionProduced.toSortedMap()) {
            if (dailyCapacity <= 0 || remainingStorage <= 0) continue
            val priorStock = village.sellStockRemainingByProfession[professionId] ?: 0
            val produced = dailyCapacity.coerceAtMost(remainingStorage)
            village.sellStockRemainingByProfession[professionId] = priorStock + produced
            remainingStorage -= produced
            changed = true
        }

        return changed
    }

    private fun collectSlotDemandAndSupply(
        snapshot: VillageEconomyState,
        village: VillageData
    ): Pair<MutableMap<ResourceType, Int>, MutableMap<ResourceType, Int>> {
        val produced = ResourceType.entries.associateWith { 0 }.toMutableMap()
        val consumed = ResourceType.entries.associateWith { 0 }.toMutableMap()

        val occupiedSlotProfessionCounts = mutableMapOf<VillagerProfession, Int>()
        snapshot.hamletSlots.filter { it.occupied }.forEach { slot ->
            val normalized = normalizeProfessionForTrade(slot.requiredProfession)
            occupiedSlotProfessionCounts[normalized] = (occupiedSlotProfessionCounts[normalized] ?: 0) + 1
        }

        for ((profession, count) in occupiedSlotProfessionCounts) {
            if (count <= 0) continue

            val professionProfile = professionEconomyProfile(normalizeProfessionForTrade(profession))
            professionProfile?.professionOutput?.let { delta ->
                val professionId = Registries.VILLAGER_PROFESSION.getId(profession).toString()
                produced[delta.resource] = (produced[delta.resource] ?: 0) + outputTotal(
                    professionId = professionId,
                    professionCount = count,
                    productionBatches = 1,
                    diversityProfessionCount = occupiedSlotProfessionCounts.size
                )
            }

            professionProfile?.professionNeed?.let { delta ->
                consumed[delta.resource] = (consumed[delta.resource] ?: 0) + delta.amount * count
            }
        }

        return produced to consumed
    }

    private fun collectSlotProfessionDemandAndSupply(
        snapshot: VillageEconomyState,
        village: VillageData,
        includePeasantOutput: Boolean = true,
        includeArtisanOutput: Boolean = true,
        productionBatches: Int = 1
    ): Pair<MutableMap<String, Int>, MutableMap<String, Int>> {
        val produced = mutableMapOf<String, Int>()
        val consumed = mutableMapOf<String, Int>()

        val occupiedSlotProfessionCounts = mutableMapOf<VillagerProfession, Int>()
        snapshot.hamletSlots.filter { it.occupied }.forEach { slot ->
            val normalized = normalizeProfessionForTrade(slot.requiredProfession)
            occupiedSlotProfessionCounts[normalized] = (occupiedSlotProfessionCounts[normalized] ?: 0) + 1
        }

        for ((profession, count) in occupiedSlotProfessionCounts) {
            if (count <= 0) continue

            val professionProfile = professionEconomyProfile(profession)
            val professionId = Registries.VILLAGER_PROFESSION.getId(profession).toString()

            val outputKeys = tieredProfessionOutputKeys(professionId, village).map { it.first }
            val isArtisan = isArtisanProfession(professionId)
            val includeOutput = (isArtisan && includeArtisanOutput) || (!isArtisan && includePeasantOutput)
            if (includeOutput) {
                if (isArtisan) {
                    for (stockKey in outputKeys.toSet()) {
                        produced[stockKey] = (produced[stockKey] ?: 0) + (count * productionBatches.coerceAtLeast(1))
                    }
                } else {
                    val outputAmounts = distributeOutput(
                        professionId = professionId,
                        outputKeys = outputKeys,
                        village = village,
                        professionCount = count,
                        productionBatches = productionBatches,
                        diversityProfessionCount = occupiedSlotProfessionCounts.size
                    )
                    for ((stockKey, amount) in outputAmounts) {
                        produced[stockKey] = (produced[stockKey] ?: 0) + amount
                    }
                }
            }

            professionProfile?.professionNeed?.let { delta ->
                consumed[professionId] = (consumed[professionId] ?: 0) + delta.amount * count
            }
        }

        return produced to consumed
    }

    private fun resetVillageEconomy(village: VillageData) {
        for (resource in ResourceType.entries) {
            village.producedResources[resource] = 0
            village.consumedResources[resource] = 0
            village.surplusResources[resource] = 0
            village.deficitResources[resource] = 0
            village.sellStockRemaining[resource] = 0
            village.buyDemandTotal[resource] = 0
            village.buyDemandFulfilled[resource] = 0
        }

        village.buyDemandTotalByProfession.clear()
        village.buyDemandFulfilledByProfession.clear()
        village.wantDemandTotalByProfession.clear()
        village.wantDemandFulfilledByProfession.clear()
        village.wantTradeCountByProfession.clear()
        village.artisanRefillTickByProfession.clear()
        village.artisanRefillCountByProfession.clear()
    }

    private fun currentStoredSellItems(village: VillageData): Int {
        return village.sellStockRemainingByProfession.values.sumOf { it.coerceAtLeast(0) }
    }

    private fun villageStorageCapacity(tier: VillageTier): Int {
        return when (tier) {
            VillageTier.HAMLET -> 4096
            VillageTier.SETTLEMENT -> 8192
            VillageTier.VILLAGE -> 16384
            VillageTier.TOWN -> 32768
            VillageTier.CITY -> 65536
        }
    }

    private fun normalizeProfessionForTrade(profession: VillagerProfession): VillagerProfession = profession

    private fun calculateBuyPrice(worldTime: Long, resource: ResourceType, amount: Int): Int {
        val profile = tradePriceProfile(resource)
        return deterministicPrice(worldTime, resource, amount, profile.playerToTableMin, profile.playerToTableMax)
    }

    private fun calculateSellPrice(
        worldTime: Long,
        resource: ResourceType,
        stockAvailable: Int,
        stockCapacity: Int
    ): Int {
        val profile = tradePriceProfile(resource)
        val basePrice = deterministicPrice(worldTime, resource, stockAvailable, profile.tableToPlayerMin, profile.tableToPlayerMax)
        return adjustSellPriceByStock(resource, basePrice, stockAvailable, stockCapacity)
    }

    private fun adjustSellPriceByStock(resource: ResourceType, basePrice: Int, stockAvailable: Int, stockCapacity: Int): Int {
        val capacity = kMax(stockCapacity, 1)
        val stockRatio = kotlin.math.min(stockAvailable.toDouble() / capacity.toDouble(), 1.0)
        val profile = tradePriceProfile(resource)
        val effectiveRatio = SELL_DISCOUNT_MIN_RATIO + ((1.0 - stockRatio) * (SELL_DISCOUNT_MAX_RATIO - SELL_DISCOUNT_MIN_RATIO))
        return (basePrice * effectiveRatio).coerceAtLeast(1.0).roundToInt()
    }

    private fun deterministicPrice(worldTime: Long, resource: ResourceType, amount: Int, minimum: Int, maximum: Int): Int {
        if (maximum <= minimum) return minimum
        val seed = worldTime + (resource.ordinal.toLong() * 17L) + (amount.toLong() * 29L)
        val range = maximum - minimum + 1
        return minimum + (seed % range).toInt()
    }

    private fun professionEconomyProfile(profession: VillagerProfession): EconomyProfile? {
        val professionId = Registries.VILLAGER_PROFESSION.getId(profession).path
        return when (professionId) {
            "farmer" -> EconomyProfile(
                professionNeed = CommodityDelta(ResourceType.MINERAL, 5),
                professionOutput = CommodityDelta(ResourceType.FOOD, 10)
            )
            "shepherd" -> EconomyProfile(
                professionNeed = CommodityDelta(ResourceType.LUMBER, 5),
                professionOutput = CommodityDelta(ResourceType.FUR, 10)
            )
            "fletcher" -> EconomyProfile(
                professionNeed = CommodityDelta(ResourceType.FOOD, 5),
                professionOutput = CommodityDelta(ResourceType.LUMBER, 10)
            )
            "mason" -> EconomyProfile(
                professionNeed = CommodityDelta(ResourceType.FOOD, 5),
                professionOutput = CommodityDelta(ResourceType.MINERAL, 10)
            )
            "fisherman" -> EconomyProfile(
                professionNeed = CommodityDelta(ResourceType.FUR, 5),
                professionOutput = CommodityDelta(ResourceType.FOOD, 10)
            )
            "butcher" -> EconomyProfile(
                professionNeed = CommodityDelta(ResourceType.MINERAL, 5),
                professionOutput = CommodityDelta(ResourceType.FOOD, 10)
            )
            "leatherworker" -> EconomyProfile(
                professionNeed = CommodityDelta(ResourceType.FUR, 5),
                professionOutput = CommodityDelta(ResourceType.FUR, 10)
            )
            "librarian" -> EconomyProfile(
                professionNeed = CommodityDelta(ResourceType.FOOD, 5),
                professionOutput = null
            )
            "cleric" -> EconomyProfile(
                professionNeed = CommodityDelta(ResourceType.FOOD, 5),
                professionOutput = null
            )
            "toolsmith" -> EconomyProfile(
                professionNeed = CommodityDelta(ResourceType.MINERAL, 5),
                professionOutput = null
            )
            "weaponsmith" -> EconomyProfile(
                professionNeed = CommodityDelta(ResourceType.LUMBER, 5),
                professionOutput = null
            )
            "armorer" -> EconomyProfile(
                professionNeed = CommodityDelta(ResourceType.FUR, 5),
                professionOutput = null
            )
            "cartographer" -> EconomyProfile(
                professionNeed = CommodityDelta(ResourceType.LUMBER, 5),
                professionOutput = null
            )
            else -> null
        }
    }

    private fun distributeOutput(
        professionId: String,
        outputKeys: List<String>,
        village: VillageData,
        professionCount: Int,
        productionBatches: Int,
        diversityProfessionCount: Int
    ): List<Pair<String, Int>> {
        if (outputKeys.isEmpty() || professionCount <= 0) return emptyList()

        val totalOutput = outputTotal(professionId, professionCount, productionBatches, diversityProfessionCount)
        val baseAmount = totalOutput / outputKeys.size
        val remainder = totalOutput % outputKeys.size
        val startIndex = Math.floorMod("${village.id}:${village.lastEconomyDay}:$professionId:output-start".hashCode(), outputKeys.size)

        return outputKeys.mapIndexed { index, stockKey ->
            val rotatedIndex = Math.floorMod(index - startIndex, outputKeys.size)
            val amount = baseAmount + if (rotatedIndex < remainder) 1 else 0
            stockKey to amount
        }.filter { it.second > 0 }
    }

    private fun outputTotal(
        professionId: String,
        professionCount: Int,
        productionBatches: Int,
        diversityProfessionCount: Int
    ): Int {
        val amountPerBatch = if (isArtisanProfession(professionId)) {
            ARTISAN_TIMED_OUTPUT_BASE
        } else {
            PEASANT_TIMED_OUTPUT_BASE
        }
        val effectiveProfessionCount = professionCount.coerceIn(1, PROFESSION_SUPPLY_EFFECTIVE_COUNT_CAP)
        val quantityBonusPercent = ((effectiveProfessionCount - 1) * PROFESSION_SUPPLY_QUANTITY_BONUS_PERCENT_PER_EXTRA)
            .coerceAtMost(PROFESSION_SUPPLY_QUANTITY_BONUS_PERCENT_MAX)
        val diversityBonusPercent = ((diversityProfessionCount.coerceAtLeast(1) - 1) * PROFESSION_DIVERSITY_OUTPUT_BONUS_PERCENT_PER_EXTRA)
            .coerceAtMost(PROFESSION_DIVERSITY_OUTPUT_BONUS_PERCENT_MAX)
        val adjustedAmountPerBatch = ((amountPerBatch * (100 + quantityBonusPercent + diversityBonusPercent)) + 99) / 100
        return (adjustedAmountPerBatch.coerceAtLeast(1) * effectiveProfessionCount * productionBatches.coerceAtLeast(1)).coerceAtLeast(0)
    }

    private fun isArtisanProfession(professionId: String): Boolean =
        professionId.substringAfterLast(":") in ARTISAN_PROFESSION_PATHS

    private fun specializationProfessionId(snapshot: VillageEconomyState): String {
        return snapshot.hamletSlots
            .asSequence()
            .filter { it.occupied }
            .map { slot -> Registries.VILLAGER_PROFESSION.getId(normalizeProfessionForTrade(slot.requiredProfession)).toString() }
            .filter { professionId -> !isArtisanProfession(professionId) && professionId != "minecraft:nitwit" }
            .groupingBy { it }
            .eachCount()
            .toList()
            .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })
            .firstOrNull()
            ?.first
            ?: ""
    }

    private fun dailyProductionModifier(village: VillageData, professionId: String): Int {
        val seed = "${village.id}:${village.lastEconomyDay}:$professionId:production".hashCode()
        return Math.floorMod(seed, 3) - 1
    }

    private fun tieredProfessionOutputKeys(professionId: String, village: VillageData): List<Pair<String, Int>> {
        val tier = village.tier
        val outputs = mutableListOf<Pair<String, Int>>()
        fun add(itemId: String, amount: Int) {
            outputs.add(outputKey(professionId, itemId) to amount)
        }

        when (professionId.substringAfterLast(":")) {
            "farmer" -> {
                add("minecraft:wheat", 10)
                add("minecraft:bread", 6)
                add("minecraft:carrot", 10)
                add("minecraft:potato", 10)
                add("minecraft:beetroot", 10)
                if (tier.ordinal >= VillageTier.SETTLEMENT.ordinal) {
                    add("minecraft:pumpkin", 8)
                    add("minecraft:melon", 8)
                }
                if (tier.ordinal >= VillageTier.VILLAGE.ordinal) {
                    add("minecraft:cookie", 12)
                    add("minecraft:pumpkin_pie", 4)
                }
                if (tier.ordinal >= VillageTier.TOWN.ordinal) add("minecraft:golden_carrot", 6)
                if (tier.ordinal >= VillageTier.CITY.ordinal) {
                    add("minecraft:suspicious_stew", 3)
                    add("minecraft:golden_carrot", 16)
                }
            }
            "shepherd" -> {
                add("minecraft:white_wool", 10)
                add("minecraft:gray_wool", 10)
                add("minecraft:black_wool", 10)
                if (tier.ordinal >= VillageTier.SETTLEMENT.ordinal) {
                    add("minecraft:red_wool", 10)
                    add("minecraft:blue_wool", 10)
                    add("minecraft:string", 10)
                }
                if (tier.ordinal >= VillageTier.VILLAGE.ordinal) {
                    add("minecraft:white_carpet", 12)
                    add("minecraft:white_bed", 2)
                }
                if (tier.ordinal >= VillageTier.TOWN.ordinal) add("minecraft:white_banner", 4)
                if (tier.ordinal >= VillageTier.CITY.ordinal) {
                    add("minecraft:white_banner", 8)
                    add("minecraft:black_banner", 8)
                }
            }
            "mason" -> {
                add("minecraft:coal", 10)
                add("minecraft:cobblestone", 16)
                add("minecraft:stone", 16)
                add("minecraft:gravel", 16)
                if (tier.ordinal >= VillageTier.SETTLEMENT.ordinal) {
                    add("minecraft:raw_iron", 8)
                    add("minecraft:raw_copper", 8)
                }
                if (tier.ordinal >= VillageTier.VILLAGE.ordinal) {
                    add("minecraft:tuff", 16)
                }
                if (tier.ordinal >= VillageTier.TOWN.ordinal) {
                    add("minecraft:raw_gold", 8)
                }
                if (tier.ordinal >= VillageTier.CITY.ordinal) {
                    add("minecraft:raw_iron", 32)
                    add("minecraft:raw_copper", 32)
                }
            }
            "fletcher" -> {
                add("minecraft:oak_log", 10)
                add("minecraft:spruce_log", 10)
                if (tier.ordinal >= VillageTier.SETTLEMENT.ordinal) {
                    add("minecraft:birch_log", 10)
                    add("minecraft:jungle_log", 10)
                    add("minecraft:acacia_log", 10)
                }
                if (tier.ordinal >= VillageTier.VILLAGE.ordinal) {
                    add("minecraft:mangrove_log", 10)
                    add("minecraft:cherry_log", 10)
                }
                if (tier.ordinal >= VillageTier.TOWN.ordinal) {
                    add("minecraft:bamboo_block", 12)
                    add("minecraft:oak_planks", 16)
                }
                if (tier.ordinal >= VillageTier.CITY.ordinal) {
                    add("minecraft:oak_log", 32)
                    add("minecraft:spruce_log", 32)
                    add("minecraft:birch_log", 32)
                }
            }
            "butcher" -> {
                add("minecraft:beef", 10)
                add("minecraft:porkchop", 10)
                add("minecraft:chicken", 10)
                if (tier.ordinal >= VillageTier.SETTLEMENT.ordinal) {
                    add("minecraft:cooked_beef", 8)
                    add("minecraft:cooked_porkchop", 8)
                }
                if (tier.ordinal >= VillageTier.VILLAGE.ordinal) {
                    add("minecraft:cooked_chicken", 8)
                    add("minecraft:rabbit", 6)
                }
                if (tier.ordinal >= VillageTier.TOWN.ordinal) add("minecraft:rabbit_stew", 3)
                if (tier.ordinal >= VillageTier.CITY.ordinal) {
                    add("minecraft:cooked_beef", 24)
                    add("minecraft:cooked_porkchop", 24)
                    add("minecraft:cooked_chicken", 24)
                }
            }
            "leatherworker" -> {
                add("minecraft:leather", 10)
                add("minecraft:leather_helmet", 1)
                add("minecraft:leather_chestplate", 1)
                add("minecraft:leather_leggings", 1)
                add("minecraft:leather_boots", 1)
                if (tier.ordinal >= VillageTier.SETTLEMENT.ordinal) add("minecraft:saddle", 1)
                if (tier.ordinal >= VillageTier.VILLAGE.ordinal) add("minecraft:item_frame", 4)
                if (tier.ordinal >= VillageTier.TOWN.ordinal) add("minecraft:bundle", 1)
                if (tier.ordinal >= VillageTier.CITY.ordinal) {
                    add("minecraft:leather", 32)
                    add("minecraft:bundle", 4)
                }
            }
            "fisherman" -> {
                add("minecraft:cod", 10)
                add("minecraft:salmon", 10)
                if (tier.ordinal >= VillageTier.SETTLEMENT.ordinal) {
                    add("minecraft:cooked_cod", 8)
                    add("minecraft:cooked_salmon", 8)
                }
                if (tier.ordinal >= VillageTier.VILLAGE.ordinal) {
                    add("minecraft:tropical_fish", 6)
                    add("minecraft:pufferfish", 5)
                }
                if (tier.ordinal >= VillageTier.TOWN.ordinal) {
                    add("minecraft:oak_boat", 2)
                    add("minecraft:barrel", 4)
                }
                if (tier.ordinal >= VillageTier.CITY.ordinal) {
                    add("minecraft:cod", 32)
                    add("minecraft:salmon", 32)
                }
            }
            "librarian" -> {
                add("minecraft:paper", 12)
                add("minecraft:ink_sac", 4)
                if (tier.ordinal >= VillageTier.SETTLEMENT.ordinal) {
                    add("minecraft:book", 10)
                    add("minecraft:writable_book", 2)
                }
                if (tier.ordinal >= VillageTier.VILLAGE.ordinal) {
                    add("minecraft:bookshelf", 2)
                }
                if (tier.ordinal >= VillageTier.TOWN.ordinal) add("minecraft:lantern", 4)
                if (tier.ordinal >= VillageTier.TOWN.ordinal) {
                    val enchantment = deterministicVariantIds(village, "librarian:book", 1, librarianBookPool()).first()
                    add("minecraft:enchanted_book#$enchantment:${variantDay(village)}", 1)
                }
                if (tier.ordinal >= VillageTier.CITY.ordinal) {
                    add("minecraft:enchanted_book#mending", 1)
                    add("minecraft:enchanted_book#unbreaking", 1)
                    add("minecraft:enchanted_book#efficiency", 1)
                    add("minecraft:enchanted_book#fortune", 1)
                    add("minecraft:enchanted_book#silk_touch", 1)
                    add("minecraft:enchanted_book#protection", 1)
                    add("minecraft:enchanted_book#sharpness", 1)
                    add("minecraft:enchanted_book#power", 1)
                    add("minecraft:enchanted_book#infinity", 1)
                    add("minecraft:enchanted_book#respiration", 1)
                    add("minecraft:enchanted_book#aqua_affinity", 1)
                }
            }
            "cleric" -> {
                add("minecraft:candle", 4)
                add("minecraft:glass_bottle", 8)
                if (tier.ordinal >= VillageTier.SETTLEMENT.ordinal) {
                    add("minecraft:lapis_lazuli", 8)
                    add("minecraft:amethyst_shard", 4)
                }
                if (tier.ordinal >= VillageTier.VILLAGE.ordinal) {
                    add("minecraft:redstone", 8)
                    add("minecraft:glowstone_dust", 8)
                }
                if (tier.ordinal >= VillageTier.TOWN.ordinal) {
                    add("minecraft:brewing_stand", 1)
                    add("minecraft:glass_bottle", 12)
                }
                if (tier.ordinal >= VillageTier.VILLAGE.ordinal) {
                    val levelTwo = tier.ordinal >= VillageTier.TOWN.ordinal
                    val itemId = if (levelTwo) "minecraft:splash_potion" else "minecraft:potion"
                    val levelLabel = if (levelTwo) "level_2" else "level_1"
                    for (potion in deterministicVariantIds(village, "cleric:$levelLabel", CLERIC_POTION_VARIANT_COUNT, clericPotionPool(levelTwo))) {
                        add("$itemId#$levelLabel:$potion:${variantDay(village)}", 1)
                    }
                }
                if (tier.ordinal >= VillageTier.CITY.ordinal) {
                    add("minecraft:experience_bottle", 8)
                    add("minecraft:ender_pearl", 2)
                    add("minecraft:redstone", 32)
                }
            }
            "toolsmith" -> {
                add("minecraft:stone_pickaxe", 1)
                add("minecraft:stone_axe", 1)
                add("minecraft:stone_shovel", 1)
                if (tier.ordinal >= VillageTier.SETTLEMENT.ordinal) {
                    add("minecraft:shears", 1)
                    add("minecraft:flint_and_steel", 1)
                    add("minecraft:bucket", 1)
                }
                if (tier.ordinal >= VillageTier.VILLAGE.ordinal) {
                    add("minecraft:iron_pickaxe", 1)
                    add("minecraft:iron_axe", 1)
                    add("minecraft:iron_shovel", 1)
                    add("minecraft:iron_hoe", 1)
                    add("minecraft:fishing_rod", 1)
                }
                if (tier.ordinal >= VillageTier.TOWN.ordinal) {
                    add("minecraft:diamond_pickaxe", 1)
                    add("minecraft:diamond_axe", 1)
                }
                if (tier.ordinal >= VillageTier.CITY.ordinal) add("minecraft:enchanted_book#mending", 1)
            }
            "weaponsmith" -> {
                add("minecraft:arrow", 12)
                add("minecraft:stone_sword", 1)
                if (tier.ordinal >= VillageTier.SETTLEMENT.ordinal) {
                    add("minecraft:shield", 1)
                    add("minecraft:bow", 1)
                }
                if (tier.ordinal >= VillageTier.VILLAGE.ordinal) {
                    add("minecraft:iron_sword", 1)
                    add("minecraft:iron_axe", 1)
                }
                if (tier.ordinal >= VillageTier.TOWN.ordinal) {
                    add("minecraft:diamond_sword", 1)
                    add("minecraft:crossbow", 1)
                }
                if (tier.ordinal >= VillageTier.CITY.ordinal) {
                    add("minecraft:enchanted_book#sharpness", 1)
                    add("minecraft:enchanted_book#power", 1)
                    add("minecraft:enchanted_book#infinity", 1)
                }
            }
            "armorer" -> {
                add("minecraft:leather_helmet", 1)
                add("minecraft:leather_boots", 1)
                add("minecraft:shield", 1)
                if (tier.ordinal >= VillageTier.SETTLEMENT.ordinal) {
                    add("minecraft:chainmail_helmet", 1)
                    add("minecraft:chainmail_chestplate", 1)
                    add("minecraft:chainmail_leggings", 1)
                    add("minecraft:chainmail_boots", 1)
                }
                if (tier.ordinal >= VillageTier.VILLAGE.ordinal) {
                    add("minecraft:iron_helmet", 1)
                    add("minecraft:iron_chestplate", 1)
                    add("minecraft:iron_leggings", 1)
                    add("minecraft:iron_boots", 1)
                }
                if (tier.ordinal >= VillageTier.TOWN.ordinal) {
                    add("minecraft:diamond_helmet", 1)
                    add("minecraft:diamond_chestplate", 1)
                    add("minecraft:diamond_leggings", 1)
                    add("minecraft:diamond_boots", 1)
                }
                if (tier.ordinal >= VillageTier.CITY.ordinal) {
                    add("minecraft:enchanted_book#protection", 1)
                    add("minecraft:enchanted_book#unbreaking", 1)
                    add("minecraft:enchanted_book#feather_falling", 1)
                    add("minecraft:enchanted_book#respiration", 1)
                    add("minecraft:enchanted_book#aqua_affinity", 1)
                    val trim = deterministicVariantIds(village, "armorer:trim", 1, armorTrimPool()).first()
                    add("minecraft:${trim}_armor_trim_smithing_template#$trim:${variantDay(village)}", 1)
                }
            }
            "cartographer" -> {
                add("minecraft:map", 4)
                add("minecraft:paper", 12)
                if (tier.ordinal >= VillageTier.SETTLEMENT.ordinal) {
                    add("minecraft:compass", 1)
                    add("minecraft:item_frame", 2)
                }
                if (tier.ordinal >= VillageTier.VILLAGE.ordinal) {
                    add("minecraft:filled_map", 1)
                }
                if (tier.ordinal >= VillageTier.TOWN.ordinal) {
                    add("minecraft:flower_banner_pattern", 1)
                }
                if (tier.ordinal >= VillageTier.CITY.ordinal) {
                    add("minecraft:filled_map#ocean_explorer", 1)
                    add("minecraft:filled_map#woodland_explorer", 1)
                    add("minecraft:filled_map#trial_explorer", 1)
                }
            }
        }

        val filtered = outputs.filter { (stockKey, _) -> shouldIncludeArchetypeOutput(village, professionId, stockKey) }
        if (filtered.isNotEmpty() || outputs.isEmpty()) return filtered

        return outputs.sortedBy { (stockKey, _) -> "${village.id}:$professionId:$stockKey".hashCode() }
            .take(MIN_ARCHETYPE_SELL_OUTPUTS)
    }

    private fun shouldIncludeArchetypeOutput(village: VillageData, professionId: String, stockKey: String): Boolean {
        val archetypes = PROFESSION_OUTPUT_ARCHETYPES[professionId.substringAfterLast(":")] ?: return true
        val itemKey = stockKey.substringAfter("|").substringAfter("|").substringBefore("#")
        if (village.tier.ordinal >= VillageTier.CITY.ordinal && itemKey in CITY_OUTPUT_KEYS_BY_PROFESSION[professionId.substringAfterLast(":")].orEmpty()) {
            return true
        }
        val allArchetypeKeys = archetypes.flatten().toSet()
        if (itemKey !in allArchetypeKeys) return true
        val index = Math.floorMod("${village.id}:$professionId:trade-archetype".hashCode(), archetypes.size)
        return itemKey in archetypes[index]
    }

    private const val MIN_ARCHETYPE_SELL_OUTPUTS = 1

    private val PROFESSION_OUTPUT_ARCHETYPES: Map<String, List<Set<String>>> = mapOf(
        "farmer" to listOf(
            setOf("minecraft:wheat", "minecraft:bread", "minecraft:cookie"),
            setOf("minecraft:carrot", "minecraft:potato", "minecraft:golden_carrot"),
            setOf("minecraft:beetroot", "minecraft:pumpkin", "minecraft:melon", "minecraft:pumpkin_pie", "minecraft:suspicious_stew")
        ),
        "shepherd" to listOf(
            setOf("minecraft:white_wool", "minecraft:white_carpet", "minecraft:white_bed", "minecraft:white_banner"),
            setOf("minecraft:gray_wool", "minecraft:black_wool", "minecraft:black_banner"),
            setOf("minecraft:red_wool", "minecraft:blue_wool", "minecraft:string")
        ),
        "mason" to listOf(
            setOf("minecraft:cobblestone", "minecraft:stone", "minecraft:tuff"),
            setOf("minecraft:gravel", "minecraft:coal", "minecraft:raw_copper"),
            setOf("minecraft:raw_iron", "minecraft:raw_gold")
        ),
        "fletcher" to listOf(
            setOf("minecraft:oak_log", "minecraft:spruce_log", "minecraft:oak_planks"),
            setOf("minecraft:birch_log", "minecraft:jungle_log", "minecraft:acacia_log"),
            setOf("minecraft:mangrove_log", "minecraft:cherry_log", "minecraft:bamboo_block")
        ),
        "butcher" to listOf(
            setOf("minecraft:beef", "minecraft:cooked_beef", "minecraft:porkchop", "minecraft:cooked_porkchop"),
            setOf("minecraft:chicken", "minecraft:cooked_chicken", "minecraft:rabbit", "minecraft:rabbit_stew"),
            setOf("minecraft:beef", "minecraft:chicken", "minecraft:cooked_chicken")
        ),
        "leatherworker" to listOf(
            setOf("minecraft:leather", "minecraft:saddle", "minecraft:bundle"),
            setOf("minecraft:leather_helmet", "minecraft:leather_chestplate", "minecraft:leather_leggings", "minecraft:leather_boots"),
            setOf("minecraft:item_frame", "minecraft:bundle", "minecraft:leather")
        ),
        "fisherman" to listOf(
            setOf("minecraft:cod", "minecraft:cooked_cod", "minecraft:barrel"),
            setOf("minecraft:salmon", "minecraft:cooked_salmon", "minecraft:oak_boat"),
            setOf("minecraft:tropical_fish", "minecraft:pufferfish", "minecraft:barrel")
        ),
        "toolsmith" to listOf(
            setOf("minecraft:stone_pickaxe", "minecraft:stone_shovel", "minecraft:iron_pickaxe", "minecraft:iron_shovel", "minecraft:diamond_pickaxe"),
            setOf("minecraft:stone_axe", "minecraft:iron_axe", "minecraft:iron_hoe", "minecraft:diamond_axe", "minecraft:flint_and_steel"),
            setOf("minecraft:shears", "minecraft:bucket", "minecraft:fishing_rod", "minecraft:iron_hoe")
        ),
        "weaponsmith" to listOf(
            setOf("minecraft:stone_sword", "minecraft:iron_sword", "minecraft:diamond_sword"),
            setOf("minecraft:arrow", "minecraft:bow", "minecraft:crossbow"),
            setOf("minecraft:shield", "minecraft:iron_axe", "minecraft:crossbow")
        ),
        "armorer" to listOf(
            setOf("minecraft:leather_helmet", "minecraft:chainmail_helmet", "minecraft:chainmail_chestplate", "minecraft:iron_helmet", "minecraft:iron_chestplate", "minecraft:diamond_helmet", "minecraft:diamond_chestplate"),
            setOf("minecraft:leather_boots", "minecraft:chainmail_leggings", "minecraft:chainmail_boots", "minecraft:iron_leggings", "minecraft:iron_boots", "minecraft:diamond_leggings", "minecraft:diamond_boots"),
            setOf("minecraft:shield", "minecraft:chainmail_chestplate", "minecraft:chainmail_leggings", "minecraft:iron_chestplate", "minecraft:iron_leggings", "minecraft:diamond_chestplate", "minecraft:diamond_leggings")
        ),
        "cleric" to listOf(
            setOf("minecraft:candle", "minecraft:glass_bottle", "minecraft:brewing_stand"),
            setOf("minecraft:lapis_lazuli", "minecraft:glowstone_dust", "minecraft:glass_bottle"),
            setOf("minecraft:amethyst_shard", "minecraft:redstone", "minecraft:experience_bottle", "minecraft:ender_pearl")
        ),
        "librarian" to listOf(
            setOf("minecraft:paper", "minecraft:book", "minecraft:bookshelf"),
            setOf("minecraft:ink_sac", "minecraft:writable_book", "minecraft:lantern"),
            setOf("minecraft:paper", "minecraft:writable_book", "minecraft:book")
        ),
        "cartographer" to listOf(
            setOf("minecraft:paper", "minecraft:map", "minecraft:filled_map"),
            setOf("minecraft:compass", "minecraft:item_frame"),
            setOf("minecraft:map", "minecraft:flower_banner_pattern", "minecraft:filled_map")
        )
    )

    private val CITY_OUTPUT_KEYS_BY_PROFESSION: Map<String, Set<String>> = mapOf(
        "farmer" to setOf("minecraft:suspicious_stew", "minecraft:golden_carrot"),
        "shepherd" to setOf("minecraft:white_banner", "minecraft:black_banner"),
        "mason" to setOf("minecraft:raw_iron", "minecraft:raw_copper"),
        "fletcher" to setOf("minecraft:oak_log", "minecraft:spruce_log", "minecraft:birch_log"),
        "butcher" to setOf("minecraft:cooked_beef", "minecraft:cooked_porkchop", "minecraft:cooked_chicken"),
        "leatherworker" to setOf("minecraft:leather", "minecraft:bundle"),
        "fisherman" to setOf("minecraft:cod", "minecraft:salmon"),
        "toolsmith" to setOf("minecraft:enchanted_book"),
        "weaponsmith" to setOf("minecraft:enchanted_book"),
        "armorer" to setOf("minecraft:enchanted_book"),
        "cleric" to setOf("minecraft:experience_bottle", "minecraft:ender_pearl", "minecraft:redstone"),
        "librarian" to setOf("minecraft:enchanted_book"),
        "cartographer" to setOf("minecraft:filled_map")
    )

    private fun outputKey(professionId: String, itemId: String): String = "$OUTPUT_KEY_PREFIX$professionId|$itemId"

    private fun deterministicVariantIds(village: VillageData, salt: String, count: Int, pool: List<String>): List<String> {
        val day = variantDay(village)
        val available = pool.toMutableList()
        val selected = mutableListOf<String>()
        repeat(count.coerceAtMost(available.size)) { index ->
            val pick = Math.floorMod("${village.id}:$day:$salt:$index".hashCode(), available.size)
            selected.add(available.removeAt(pick))
        }
        return selected
    }

    private fun clericPotionPool(levelTwo: Boolean): List<String> {
        return if (levelTwo) {
            listOf("healing", "strength", "swiftness", "leaping", "poison", "regeneration")
        } else {
            listOf("healing", "strength", "swiftness", "leaping", "poison", "regeneration", "fire_resistance", "water_breathing")
        }
    }

    private fun librarianBookPool(): List<String> {
        return listOf("protection", "sharpness", "efficiency", "unbreaking", "fortune", "power", "mending", "silk_touch")
    }

    private fun armorTrimPool(): List<String> {
        return listOf("coast", "dune", "eye", "host", "raiser", "rib", "sentry", "shaper", "silence", "snout", "spire", "tide", "vex", "ward", "wayfinder", "wild")
    }

    private fun variantDay(village: VillageData): Long = kMax(village.priceModifierDay, village.lastEconomyDay).coerceAtLeast(0L)

    private fun tradePriceProfile(resource: ResourceType): TradePriceProfile {
        return when (resource) {
            ResourceType.FOOD -> TradePriceProfile(
                tableToPlayerMin = 1,
                tableToPlayerMax = 3,
                playerToTableMin = 4,
                playerToTableMax = 9
            )
            ResourceType.LUMBER -> TradePriceProfile(
                tableToPlayerMin = 2,
                tableToPlayerMax = 5,
                playerToTableMin = 2,
                playerToTableMax = 6
            )
            ResourceType.MINERAL -> TradePriceProfile(
                tableToPlayerMin = 2,
                tableToPlayerMax = 6,
                playerToTableMin = 3,
                playerToTableMax = 8
            )
            ResourceType.FUR -> TradePriceProfile(
                tableToPlayerMin = 2,
                tableToPlayerMax = 6,
                playerToTableMin = 3,
                playerToTableMax = 7
            )
            ResourceType.LUXURY -> TradePriceProfile(
                tableToPlayerMin = 4,
                tableToPlayerMax = 10,
                playerToTableMin = 6,
                playerToTableMax = 14
            )
        }
    }
}

