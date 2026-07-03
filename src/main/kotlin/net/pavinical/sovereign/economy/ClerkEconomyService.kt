package net.pavinical.sovereign.economy

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.minecraft.registry.Registries
import net.minecraft.server.MinecraftServer
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.Identifier
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
    private const val PRODUCE_INTERVAL_TICKS = 2400L
    private const val NEED_INTERVAL_TICKS = 4800L
    private const val FARMER_DAILY_OUTPUT_CAP = 10
    private const val SELL_DISCOUNT_MIN_RATIO = 0.65
    private const val SELL_DISCOUNT_MAX_RATIO = 1.0
    private const val OUTPUT_KEY_PREFIX = "output|"
    private const val CLERIC_POTION_VARIANT_COUNT = 3

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

        if (village.lastEconomyDay < currentDay) {
            resetVillageEconomy(village)
            village.lastEconomyDay = currentDay
            village.lastEconomyTick = currentDay * TICKS_PER_DAY
            village.lastProductionTick = currentDay * TICKS_PER_DAY
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
        changed = applyTimedTradeProgress(world, village, snapshot) || changed
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
        val (produced, consumed) = collectSlotDemandAndSupply(snapshot)
        val (professionProduced, professionConsumed) = collectSlotProfessionDemandAndSupply(snapshot, village)
        var changed = false

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
        for (professionId in professionKeys) {
            val producedAmount = professionProduced[professionId] ?: 0
            val consumedAmount = professionConsumed[professionId] ?: 0
            val priorStock = village.sellStockRemainingByProfession[professionId] ?: 0
            val priorFulfilled = village.buyDemandFulfilledByProfession[professionId] ?: 0

            val desiredSellStock = if (producedAmount >= 0) {
                if (priorStock > producedAmount) {
                    producedAmount
                } else {
                    priorStock
                }
            } else {
                0
            }
            if (village.sellStockRemainingByProfession[professionId] != desiredSellStock) changed = true
            village.sellStockRemainingByProfession[professionId] = desiredSellStock

            val desiredDemandCapacity = kMax(consumedAmount, 0)
            val priorDemandTotal = village.buyDemandTotalByProfession[professionId] ?: 0
            val clampedDemandTotal = priorDemandTotal.coerceIn(0, desiredDemandCapacity)
            if (village.buyDemandTotalByProfession[professionId] != clampedDemandTotal) changed = true
            village.buyDemandTotalByProfession[professionId] = clampedDemandTotal

            if (priorFulfilled != 0) changed = true
            village.buyDemandFulfilledByProfession[professionId] = 0
            if (producedAmount > 0 && desiredSellStock != priorStock) changed = true
        }

        val staleProfessionIds = village.sellStockRemainingByProfession.keys.toSet() + village.buyDemandTotalByProfession.keys + village.buyDemandFulfilledByProfession.keys
        for (professionId in staleProfessionIds) {
            if (!professionKeys.contains(professionId)) {
                village.sellStockRemainingByProfession.remove(professionId)
                village.buyDemandTotalByProfession.remove(professionId)
                village.buyDemandFulfilledByProfession.remove(professionId)
            }
        }

        return changed
    }

    private fun applyTimedTradeProgress(
        world: ServerWorld,
        village: VillageData,
        snapshot: VillageEconomyState
    ): Boolean {
        val productionElapsedTicks = world.time - village.lastProductionTick
        val needElapsedTicks = world.time - village.lastNeedTick
        if (productionElapsedTicks <= 0L && needElapsedTicks <= 0L) return false

        val producedSteps = (productionElapsedTicks / PRODUCE_INTERVAL_TICKS).toInt()
        val neededSteps = (needElapsedTicks / NEED_INTERVAL_TICKS).toInt()
        if (producedSteps <= 0 && neededSteps <= 0) return false

        val (_, professionConsumed) = collectSlotProfessionDemandAndSupply(snapshot, village)
        val (professionProduced, _) = collectSlotProfessionDemandAndSupply(snapshot, village)
        var changed = false

        for ((professionId, dailyCapacity) in professionProduced) {
            if (dailyCapacity <= 0 || producedSteps <= 0) continue
            val priorStock = village.sellStockRemainingByProfession[professionId] ?: 0
            val updatedStock = (priorStock + producedSteps).coerceAtMost(dailyCapacity)
            if (updatedStock != priorStock) {
                village.sellStockRemainingByProfession[professionId] = updatedStock
                changed = true
            }
        }

        for ((professionId, dailyCapacity) in professionConsumed) {
            if (dailyCapacity <= 0 || neededSteps <= 0) continue
            val priorDemand = village.buyDemandTotalByProfession[professionId] ?: 0
            val updatedDemand = (priorDemand + neededSteps).coerceAtMost(dailyCapacity)
            if (updatedDemand != priorDemand) {
                village.buyDemandTotalByProfession[professionId] = updatedDemand
                changed = true
            }
            val fulfilled = village.buyDemandFulfilledByProfession[professionId] ?: 0
            if (fulfilled != 0) {
                village.buyDemandFulfilledByProfession[professionId] = 0
                changed = true
            }
        }

        if (producedSteps > 0) {
            village.lastProductionTick += producedSteps.toLong() * PRODUCE_INTERVAL_TICKS
        }
        if (neededSteps > 0) {
            village.lastNeedTick += neededSteps.toLong() * NEED_INTERVAL_TICKS
        }
        village.lastEconomyTick = kMax(village.lastProductionTick, village.lastNeedTick)
        return true
    }

    private fun collectSlotDemandAndSupply(snapshot: VillageEconomyState): Pair<MutableMap<ResourceType, Int>, MutableMap<ResourceType, Int>> {
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
                val cappedAmount = cappedOutputAmount(profession, delta.amount)
                produced[delta.resource] = (produced[delta.resource] ?: 0) + cappedAmount * count
            }

            professionProfile?.professionNeed?.let { delta ->
                consumed[delta.resource] = (consumed[delta.resource] ?: 0) + delta.amount * count
            }
        }

        return produced to consumed
    }

    private fun collectSlotProfessionDemandAndSupply(
        snapshot: VillageEconomyState,
        village: VillageData
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

            for ((stockKey, amount) in tieredProfessionOutputKeys(professionId, village)) {
                val cappedAmount = cappedOutputAmount(profession, amount)
                produced[stockKey] = (produced[stockKey] ?: 0) + cappedAmount * count
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

        village.sellStockRemainingByProfession.clear()
        village.buyDemandTotalByProfession.clear()
        village.buyDemandFulfilledByProfession.clear()
        village.wantDemandTotalByProfession.clear()
        village.wantDemandFulfilledByProfession.clear()
        village.wantTradeCountByProfession.clear()
    }

    private fun normalizeProfessionForTrade(profession: VillagerProfession): VillagerProfession {
        val professionId = Registries.VILLAGER_PROFESSION.getId(profession).toString()
        if (professionId != "minecraft:leatherworker") {
            return profession
        }

        return Registries.VILLAGER_PROFESSION.get(Identifier.of("minecraft", "butcher")) ?: profession
    }

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
            else -> null
        }
    }

    private fun cappedOutputAmount(profession: VillagerProfession, outputAmount: Int): Int {
        val professionId = Registries.VILLAGER_PROFESSION.getId(profession).path
        if (professionId != "farmer") return outputAmount
        return outputAmount.coerceAtMost(FARMER_DAILY_OUTPUT_CAP)
    }

    private fun tieredProfessionOutputKeys(professionId: String, village: VillageData): List<Pair<String, Int>> {
        val tier = village.tier
        val outputs = mutableListOf<Pair<String, Int>>()
        fun add(itemId: String, amount: Int) {
            outputs.add(outputKey(professionId, itemId) to amount)
        }

        when (professionId.substringAfterLast(":")) {
            "farmer" -> {
                add("minecraft:wheat", FARMER_DAILY_OUTPUT_CAP)
                if (tier.ordinal >= VillageTier.SETTLEMENT.ordinal) {
                    add("minecraft:carrot", 10)
                    add("minecraft:potato", 10)
                }
                if (tier.ordinal >= VillageTier.VILLAGE.ordinal) add("minecraft:paper", 10)
                if (tier.ordinal >= VillageTier.TOWN.ordinal) add("minecraft:melon_slice", 10)
                if (tier.ordinal >= VillageTier.CITY.ordinal) add("minecraft:golden_carrot", 10)
            }
            "shepherd" -> {
                add("minecraft:white_wool", 10)
                if (tier.ordinal >= VillageTier.SETTLEMENT.ordinal) add("minecraft:string", 10)
                if (tier.ordinal >= VillageTier.VILLAGE.ordinal) add("minecraft:leather", 10)
                if (tier.ordinal >= VillageTier.TOWN.ordinal) add("minecraft:honey_bottle", 10)
                if (tier.ordinal >= VillageTier.CITY.ordinal) add("minecraft:horse_spawn_egg", 3)
            }
            "mason" -> {
                add("minecraft:coal", 10)
                if (tier.ordinal >= VillageTier.SETTLEMENT.ordinal) add("minecraft:iron_ingot", 10)
                if (tier.ordinal >= VillageTier.VILLAGE.ordinal) add("minecraft:gold_ingot", 10)
                if (tier.ordinal >= VillageTier.TOWN.ordinal) add("minecraft:diamond", 10)
                if (tier.ordinal >= VillageTier.CITY.ordinal) add("minecraft:netherite_scrap", 1)
            }
            "fletcher" -> {
                add("minecraft:oak_log", 10)
                if (tier.ordinal >= VillageTier.SETTLEMENT.ordinal) add("minecraft:oak_planks", 10)
                if (tier.ordinal >= VillageTier.VILLAGE.ordinal) add("minecraft:stick", 10)
                if (tier.ordinal >= VillageTier.TOWN.ordinal) add("minecraft:apple", 10)
                if (tier.ordinal >= VillageTier.CITY.ordinal) add("minecraft:enchanted_golden_apple", 1)
            }
            "butcher" -> {
                add("minecraft:beef", 10)
                if (tier.ordinal >= VillageTier.SETTLEMENT.ordinal) add("minecraft:bone_meal", 10)
                if (tier.ordinal >= VillageTier.VILLAGE.ordinal) add("minecraft:blaze_powder", 10)
                if (tier.ordinal >= VillageTier.TOWN.ordinal) add("minecraft:gunpowder", 10)
                if (tier.ordinal >= VillageTier.CITY.ordinal) add("minecraft:elytra", 1)
            }
            "fisherman" -> {
                add("minecraft:cod", 10)
                add("minecraft:salmon", 10)
                if (tier.ordinal >= VillageTier.SETTLEMENT.ordinal) add("minecraft:flint", 10)
                if (tier.ordinal >= VillageTier.TOWN.ordinal) add("minecraft:pufferfish", 5)
                if (tier.ordinal >= VillageTier.CITY.ordinal) add("minecraft:sponge", 10)
            }
            "librarian" -> {
                if (tier.ordinal >= VillageTier.VILLAGE.ordinal) add("minecraft:book", 10)
                if (tier.ordinal >= VillageTier.TOWN.ordinal) {
                    val enchantment = deterministicVariantIds(village, "librarian:book", 1, librarianBookPool()).first()
                    add("minecraft:enchanted_book#$enchantment:${variantDay(village)}", 1)
                }
                if (tier.ordinal >= VillageTier.CITY.ordinal) {
                    add("minecraft:bookshelf", 1)
                    add("minecraft:enchanted_book#protection", 1)
                }
            }
            "cleric" -> {
                if (tier.ordinal >= VillageTier.VILLAGE.ordinal) {
                    val levelTwo = tier.ordinal >= VillageTier.TOWN.ordinal
                    val itemId = if (levelTwo) "minecraft:splash_potion" else "minecraft:potion"
                    val levelLabel = if (levelTwo) "level_2" else "level_1"
                    for (potion in deterministicVariantIds(village, "cleric:$levelLabel", CLERIC_POTION_VARIANT_COUNT, clericPotionPool(levelTwo))) {
                        add("$itemId#$levelLabel:$potion:${variantDay(village)}", 1)
                    }
                }
                if (tier.ordinal >= VillageTier.CITY.ordinal) add("minecraft:experience_bottle", 10)
            }
            "toolsmith" -> {
                if (tier.ordinal >= VillageTier.VILLAGE.ordinal) {
                    add("minecraft:iron_pickaxe", 1)
                    add("minecraft:iron_axe", 1)
                    add("minecraft:iron_shovel", 1)
                    add("minecraft:iron_hoe", 1)
                    add("minecraft:fishing_rod", 1)
                    add("minecraft:shears", 1)
                }
                if (tier.ordinal >= VillageTier.TOWN.ordinal) {
                    add("minecraft:diamond_pickaxe", 1)
                    add("minecraft:diamond_axe", 1)
                    add("minecraft:diamond_shovel", 1)
                    add("minecraft:diamond_hoe", 1)
                }
                if (tier.ordinal >= VillageTier.CITY.ordinal) add("minecraft:enchanted_book#mending", 1)
            }
            "weaponsmith" -> {
                if (tier.ordinal >= VillageTier.VILLAGE.ordinal) {
                    add("minecraft:iron_sword", 1)
                    add("minecraft:bow", 1)
                    add("minecraft:arrow", 16)
                }
                if (tier.ordinal >= VillageTier.CITY.ordinal) {
                    add("minecraft:diamond_sword", 1)
                    add("minecraft:crossbow", 1)
                    add("minecraft:enchanted_book#sharpness", 1)
                }
            }
            "armorer" -> {
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
                    val trim = deterministicVariantIds(village, "armorer:trim", 1, armorTrimPool()).first()
                    add("minecraft:${trim}_armor_trim_smithing_template#$trim:${variantDay(village)}", 1)
                }
            }
        }

        return outputs
    }

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

