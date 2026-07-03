package net.pavinical.sovereign.economy

import net.minecraft.item.Item
import net.minecraft.item.ItemStack
import net.minecraft.item.Items
import net.minecraft.item.EnchantedBookItem
import net.minecraft.enchantment.Enchantment
import net.minecraft.enchantment.EnchantmentLevelEntry
import net.minecraft.enchantment.Enchantments
import net.minecraft.component.DataComponentTypes
import net.minecraft.component.type.PotionContentsComponent
import net.minecraft.potion.Potion
import net.minecraft.potion.Potions
import net.minecraft.registry.entry.RegistryEntry
import net.minecraft.registry.Registries
import net.minecraft.registry.RegistryKey
import net.minecraft.registry.RegistryKeys
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.text.Text
import net.minecraft.util.Identifier
import net.minecraft.util.math.BlockPos
import net.pavinical.sovereign.block.ClerkTableBlockEntity
import net.pavinical.sovereign.block.VillageEconomyState
import net.pavinical.sovereign.data.VillageTier
import net.pavinical.sovereign.block.professionDisplayName
import net.pavinical.sovereign.data.VillageData
import java.util.Locale

private const val FARMER_DAILY_OUTPUT_CAP = 10
private const val TICKS_PER_DAY = 24000L
private const val PRODUCE_INTERVAL_TICKS = 2400L
private const val NEED_INTERVAL_TICKS = 4800L
private const val LEATHERWORKER_ID = "minecraft:leatherworker"
private const val BUTCHER_ID = "minecraft:butcher"
private const val WANT_KEY_PREFIX = "want|"
private const val OUTPUT_KEY_PREFIX = "output|"
private const val CLERIC_POTION_VARIANT_COUNT = 3
private const val NORMAL_PRODUCT_PRICE_PERCENT = 100
private const val ARTISAN_VILLAGE_PRODUCT_PRICE_PERCENT = 105
private const val ARTISAN_TOWN_PRODUCT_PRICE_PERCENT = 107
private const val ARTISAN_CITY_PRODUCT_PRICE_PERCENT = 110
private const val WANT_DIMINISHING_RETURN_BUCKET_SIZE = 8

data class VillageTradeExecutionResult(
    val accepted: Boolean,
    val message: Text,
    val snapshot: VillageMarketSnapshot? = null
)

object VillageEconomyService {
    fun openSnapshot(world: ServerWorld, clerkPos: BlockPos): VillageMarketSnapshot? {
        val village = getVillage(world, clerkPos) ?: return null
        val blockEntity = getClerkTable(world, village, clerkPos) ?: return null
        val censusSnapshot = blockEntity.getSnapshot(village)
        ClerkEconomyService.refreshVillageFromSnapshot(world, village, censusSnapshot)
        return buildSnapshot(world, village.clerkPos, censusSnapshot)
    }

    fun emptySnapshotForRegisteredVillage(world: ServerWorld, clerkPos: BlockPos): VillageMarketSnapshot? {
        val village = getVillage(world, clerkPos) ?: return null
        return VillageMarketSnapshot(
            sellOffers = emptyList(),
            buyOffers = emptyList(),
            playerEmeralds = 0,
            refreshTicks = nextTradeProgressTicksRemaining(world, village),
            produceRefreshTicks = nextProduceProgressTicksRemaining(world, village),
            needRefreshTicks = nextNeedProgressTicksRemaining(world, village),
            tierName = village.tier.displayName(),
            villageExperience = village.experience,
            currentTierMinExperience = tierMinExperience(village.tier),
            nextTierExperience = nextTierExperience(village.tier)
        )
    }

    fun restockVillageToFull(world: ServerWorld, village: VillageData): Text {
        val censusSnapshot = ClerkCensusService.forceSyncVillage(world, village)
            ?: return Text.literal("Could not restock '${village.name}': clerk table is missing.")
        val professionCounts = mutableMapOf<String, Int>()

        for (slot in censusSnapshot.hamletSlots) {
            if (!slot.occupied) continue
            val professionId = Registries.VILLAGER_PROFESSION.getId(slot.requiredProfession).toString()
            professionCounts[professionId] = (professionCounts[professionId] ?: 0) + 1
        }

        if (professionCounts.isEmpty()) {
            return Text.literal(
                "Could not restock '${village.name}': no filled worker slots were found. " +
                    "Scan villagers near the clerk table or check /villageinfo ${village.name}."
            )
        }

        var stockedItems = 0
        var demandedItems = 0

        for ((professionId, count) in professionCounts) {
            val profile = professionTradeProfile(professionId) ?: continue
            for (output in profile.outputsForTier(village.tier, village)) {
                val stockKey = outputKey(professionId, output)
                val sellCapacity = count * output.amount
                village.sellStockRemainingByProfession[stockKey] = sellCapacity
                stockedItems += sellCapacity
            }

            val needCapacity = profile.need?.let { count * it.amount } ?: 0
            if (needCapacity > 0) {
                village.buyDemandTotalByProfession[professionId] = needCapacity
                village.buyDemandFulfilledByProfession[professionId] = 0
                demandedItems += needCapacity
            }

            for (want in profile.wantsForTier(village.tier)) {
                val key = wantKey(professionId, want.item)
                val wantCapacity = count * want.amount
                village.wantDemandTotalByProfession[key] = wantCapacity
                village.wantDemandFulfilledByProfession[key] = 0
                demandedItems += wantCapacity
            }
        }

        ensureDailyPriceModifiers(world, village)
        world.persistentStateManager.getOrCreate(VillageRegistry.TYPE, VillageRegistry.KEY).markDirty()

        return Text.literal(
            "Restocked '${village.name}' to full capacity: $stockedItems item(s) for sale, $demandedItems item(s) needed."
        )
    }

    fun executeTrade(
        world: ServerWorld,
        player: ServerPlayerEntity,
        clerkPos: BlockPos,
        direction: Int,
        professionId: String,
        requestedQuantity: Int
    ): VillageTradeExecutionResult {
        val village = getVillage(world, clerkPos)
            ?: return VillageTradeExecutionResult(false, Text.literal("No village data found for this clerk."))
        val blockEntity = getClerkTable(world, village, clerkPos)
            ?: return VillageTradeExecutionResult(false, Text.literal("Clerk block is missing."))

        val censusSnapshot = blockEntity.getSnapshot(village)
        ClerkEconomyService.refreshVillageFromSnapshot(world, village, censusSnapshot)
        ensureDailyPriceModifiers(world, village)

        val result = when (direction) {
            0 -> processBuyFromVillage(world, player, village, professionId, requestedQuantity, censusSnapshot)
            1 -> processSellToVillage(world, player, village, professionId, requestedQuantity, censusSnapshot)
            else -> VillageTradeExecutionResult(false, Text.literal("Invalid trade action."))
        }

        val latestSnapshot = blockEntity.getSnapshot(village)
        ClerkEconomyService.refreshVillageFromSnapshot(world, village, latestSnapshot)
        val latest = buildSnapshot(world, village.clerkPos, latestSnapshot)
        return result.copy(snapshot = latest, accepted = result.accepted)
    }

    private fun processBuyFromVillage(
        world: ServerWorld,
        player: ServerPlayerEntity,
        village: VillageData,
        professionId: String,
        requestedQuantity: Int,
        censusSnapshot: VillageEconomyState
    ): VillageTradeExecutionResult {
        val tradeProfessionId = professionIdFromOutputKey(professionId) ?: professionId
        val profile = professionTradeProfile(tradeProfessionId)
            ?: return VillageTradeExecutionResult(false, Text.literal("Unknown profession trade."))

        val output = outputItemIdFromOutputKey(professionId)
            ?.let { itemId -> profile.outputsForTier(village.tier, village).firstOrNull { it.keyItemId() == itemId } }
            ?: profile.outputsForTier(village.tier, village).firstOrNull()
            ?: return VillageTradeExecutionResult(false, Text.literal("This profession has no produce to sell."))
        val stockKey = if (professionId.startsWith(OUTPUT_KEY_PREFIX)) professionId else outputKey(tradeProfessionId, output)
        val remaining = village.sellStockRemainingByProfession[stockKey]?.coerceAtLeast(0) ?: 0
        if (remaining <= 0) {
            return VillageTradeExecutionResult(false, Text.literal("This trade is currently unavailable."))
        }

        val quantity = if (requestedQuantity == Int.MAX_VALUE) {
            remaining
        } else {
            requestedQuantity.coerceAtLeast(1).coerceAtMost(remaining)
        }
        val stockCapacity = professionCountFor(censusSnapshot, tradeProfessionId) * output.amount
        val unitPrice = getProductPrice(village, output, remaining, stockCapacity).currentPrice
        val emeraldsNeeded = unitPrice * quantity

        if (player.inventory.count(Items.EMERALD) < emeraldsNeeded) {
            return VillageTradeExecutionResult(false, Text.literal("You need $emeraldsNeeded emerald(s) for this trade."))
        }

        if (!hasInventorySpace(player, output.item, quantity)) {
            return VillageTradeExecutionResult(
                false,
                Text.literal("You need inventory space for ${quantity}x ${output.itemName}.")
            )
        }

        val removed = player.inventory.remove({ stack -> stack.isOf(Items.EMERALD) }, emeraldsNeeded, player.inventory)
        if (removed < emeraldsNeeded) {
            player.inventory.insertStack(ItemStack(Items.EMERALD, removed))
            return VillageTradeExecutionResult(false, Text.literal("Could not spend emeralds safely."))
        }

        val rewardStack = output.stack(world, quantity)
        if (!player.inventory.insertStack(rewardStack)) {
            player.inventory.insertStack(ItemStack(Items.EMERALD, emeraldsNeeded))
            return VillageTradeExecutionResult(false, Text.literal("Could not add items to inventory."))
        }

        village.sellStockRemainingByProfession[stockKey] = remaining - quantity
        world.persistentStateManager.getOrCreate(VillageRegistry.TYPE, VillageRegistry.KEY).markDirty()

        return VillageTradeExecutionResult(
            true,
            Text.literal("You bought ${quantity}x ${output.itemName} for $emeraldsNeeded emerald(s).")
        )
    }

    private fun processSellToVillage(
        world: ServerWorld,
        player: ServerPlayerEntity,
        village: VillageData,
        professionId: String,
        requestedQuantity: Int,
        censusSnapshot: VillageEconomyState
    ): VillageTradeExecutionResult {
        if (professionId.startsWith(WANT_KEY_PREFIX)) {
            return processSellWantToVillage(world, player, village, professionId, requestedQuantity, censusSnapshot)
        }
        val profile = professionTradeProfile(professionId)
            ?: return VillageTradeExecutionResult(false, Text.literal("Unknown profession trade."))

        val need = profile.need ?: return VillageTradeExecutionResult(false, Text.literal("This profession has no demand."))
        val demandTotal = village.buyDemandTotalByProfession[professionId] ?: 0
        val remainingNeed = demandTotal.coerceAtLeast(0)
        if (remainingNeed <= 0) {
            return VillageTradeExecutionResult(false, Text.literal("Demand is already fulfilled for this profession today."))
        }

        val quantity = if (requestedQuantity == Int.MAX_VALUE) {
            remainingNeed
        } else {
            requestedQuantity.coerceAtLeast(1).coerceAtMost(remainingNeed)
        }
        val demandCapacity = professionCountFor(censusSnapshot, professionId) * need.amount
        val unitPrice = getNeedPrice(village, need, remainingNeed, demandCapacity).currentPrice
        val emeraldReward = unitPrice * quantity

        if (player.inventory.count(need.item) < quantity) {
            return VillageTradeExecutionResult(
                false,
                Text.literal("You need ${quantity}x ${need.itemName} to complete this trade.")
            )
        }

        if (!hasInventorySpace(player, Items.EMERALD, emeraldReward)) {
            return VillageTradeExecutionResult(false, Text.literal("Not enough room for emerald payment."))
        }

        val removed = player.inventory.remove({ stack -> stack.isOf(need.item) }, quantity, player.inventory)
        if (removed < quantity) {
            player.inventory.offerOrDrop(ItemStack(need.item, removed))
            return VillageTradeExecutionResult(false, Text.literal("Could not remove requested items safely."))
        }

        val payment = ItemStack(Items.EMERALD, emeraldReward)
        if (!player.inventory.insertStack(payment)) {
            player.inventory.insertStack(ItemStack(need.item, quantity))
            return VillageTradeExecutionResult(false, Text.literal("Not enough room for emerald payment."))
        }

        village.buyDemandTotalByProfession[professionId] = (demandTotal - quantity).coerceAtLeast(0)
        village.buyDemandFulfilledByProfession[professionId] = 0
        val grantsHamletExperience = village.tier == VillageTier.HAMLET
        val oldTier = village.tier
        if (grantsHamletExperience) {
            village.experience += quantity
            updateVillageTier(village)
        }
        world.persistentStateManager.getOrCreate(VillageRegistry.TYPE, VillageRegistry.KEY).markDirty()

        val experienceText = if (grantsHamletExperience) " and +$quantity village XP" else ""
        val tierText = if (village.tier != oldTier) " ${village.name} is now a ${village.tier.name.lowercase(Locale.ROOT)}!" else ""
        return VillageTradeExecutionResult(
            true,
            Text.literal("You sold ${quantity}x ${need.itemName} for $emeraldReward emerald(s)$experienceText.$tierText")
        )
    }

    private fun processSellWantToVillage(
        world: ServerWorld,
        player: ServerPlayerEntity,
        village: VillageData,
        rawWantKey: String,
        requestedQuantity: Int,
        censusSnapshot: VillageEconomyState
    ): VillageTradeExecutionResult {
        val professionId = professionIdFromWantKey(rawWantKey)
            ?: return VillageTradeExecutionResult(false, Text.literal("Unknown village want."))
        val profile = professionTradeProfile(professionId)
            ?: return VillageTradeExecutionResult(false, Text.literal("Unknown profession want."))
        val want = profile.wantsForTier(village.tier).firstOrNull { wantKey(professionId, it.item) == rawWantKey }
            ?: return VillageTradeExecutionResult(false, Text.literal("This want is not unlocked yet."))

        val availableItems = player.inventory.count(want.item)
        val quantity = if (requestedQuantity == Int.MAX_VALUE) {
            availableItems
        } else {
            requestedQuantity.coerceAtLeast(1)
        }
        if (quantity <= 0 || availableItems < quantity) {
            return VillageTradeExecutionResult(false, Text.literal("You need ${quantity.coerceAtLeast(1)}x ${want.itemName} to complete this want."))
        }

        val demandCapacity = professionCountFor(censusSnapshot, professionId) * want.amount
        val baseUnitPrice = getNeedPrice(village, want, demandCapacity, demandCapacity).currentPrice
        val alreadyTraded = village.wantTradeCountByProfession[rawWantKey] ?: 0
        val emeraldReward = wantTradeReward(baseUnitPrice, alreadyTraded, quantity)
        if (!hasInventorySpace(player, Items.EMERALD, emeraldReward)) {
            return VillageTradeExecutionResult(false, Text.literal("Not enough room for emerald payment."))
        }

        val removed = player.inventory.remove({ stack -> stack.isOf(want.item) }, quantity, player.inventory)
        if (removed < quantity) {
            player.inventory.offerOrDrop(ItemStack(want.item, removed))
            return VillageTradeExecutionResult(false, Text.literal("Could not remove requested items safely."))
        }

        if (!player.inventory.insertStack(ItemStack(Items.EMERALD, emeraldReward))) {
            player.inventory.insertStack(ItemStack(want.item, quantity))
            return VillageTradeExecutionResult(false, Text.literal("Not enough room for emerald payment."))
        }

        village.wantTradeCountByProfession[rawWantKey] = alreadyTraded + quantity
        village.experience += quantity
        val oldTier = village.tier
        updateVillageTier(village)
        world.persistentStateManager.getOrCreate(VillageRegistry.TYPE, VillageRegistry.KEY).markDirty()

        val tierText = if (village.tier != oldTier) " ${village.name} is now a ${village.tier.name.lowercase(Locale.ROOT)}!" else ""
        return VillageTradeExecutionResult(
            true,
            Text.literal("You fulfilled ${quantity}x ${want.itemName} for $emeraldReward emerald(s) and +$quantity village XP.$tierText")
        )
    }

    private fun buildSnapshot(
        world: ServerWorld,
        clerkPos: BlockPos,
        censusSnapshot: VillageEconomyState
    ): VillageMarketSnapshot {
        val village = getVillage(world, clerkPos) ?: return VillageMarketSnapshot(
            emptyList(),
            emptyList(),
            0,
            world.time,
            world.time,
            world.time,
            "Hamlet",
            0,
            0,
            300
        )
        ensureDailyPriceModifiers(world, village)

        val professionCounts = mutableMapOf<String, Int>()
        val occupiedProfessionCounts = mutableMapOf<String, Int>()
        for (slot in censusSnapshot.hamletSlots) {
            if (!slot.occupied) continue
            val professionId = slot.requiredProfession.let { Registries.VILLAGER_PROFESSION.getId(it).toString() }
            professionCounts[professionId] = (professionCounts[professionId] ?: 0) + 1
            occupiedProfessionCounts[professionId] = (occupiedProfessionCounts[professionId] ?: 0) + 1
        }

        var changedStock = false
        val sellOffers = buildList {
            for ((professionId, count) in professionCounts) {
                val profile = professionTradeProfile(professionId) ?: continue
                val profession = professionById(professionId) ?: continue
                val occupiedCount = occupiedProfessionCounts[professionId] ?: 0
                for (output in profile.outputsForTier(village.tier, village)) {
                    val stockKey = outputKey(professionId, output)
                    val maxDaily = count * output.amount
                    if (maxDaily <= 0) continue
                    val remaining = village.sellStockRemainingByProfession[stockKey] ?: 0
                    if (!village.sellStockRemainingByProfession.containsKey(stockKey)) {
                        village.sellStockRemainingByProfession[stockKey] = 0
                        changedStock = true
                    }
                    val price = getProductPrice(village, output, remaining, maxDaily)
                    add(
                        SellOffer(
                            item = output.stack(world),
                            displayName = output.displayLabel(),
                            emeraldCost = price.currentPrice,
                            producedToday = maxDaily,
                            remainingToday = remaining,
                            maxDailyProduction = maxDaily,
                            baseValue = price.baseValue,
                            minPrice = price.minPrice,
                            maxPrice = price.maxPrice,
                            stockPercent = price.availabilityPercent,
                            stockModifier = price.availabilityModifier,
                            stockLabel = price.availabilityLabel,
                            dailyModifier = price.dailyModifier,
                            producerCount = occupiedCount.takeIf { it > 0 } ?: count,
                            producerProfessionId = stockKey,
                            producerProfession = professionDisplayName(profession)
                        )
                    )
                }
            }
        }.sortedBy { it.producerProfessionId }

        var changedDemand = false
        val buyOffers = buildList {
            for ((professionId, count) in professionCounts) {
                val profile = professionTradeProfile(professionId) ?: continue
                val need = profile.need ?: continue
                val profession = professionById(professionId) ?: continue
                val occupiedCount = occupiedProfessionCounts[professionId] ?: 0
                val demand = village.buyDemandTotalByProfession[professionId] ?: 0
                if (!village.buyDemandTotalByProfession.containsKey(professionId)) {
                    village.buyDemandTotalByProfession[professionId] = demand
                    changedDemand = true
                }
                val maxDailyNeed = count * need.amount
                val remainingNeed = demand.coerceIn(0, maxDailyNeed)
                val price = getNeedPrice(village, need, remainingNeed, maxDailyNeed)
                add(
                        BuyOffer(
                            item = ItemStack(need.item, 1),
                            displayName = need.displayLabel(),
                            emeraldReward = price.currentPrice,
                        neededToday = maxDailyNeed,
                        remainingNeed = remainingNeed,
                        maxDailyNeed = maxDailyNeed,
                        baseValue = price.baseValue,
                        minPrice = price.minPrice,
                        maxPrice = price.maxPrice,
                        demandPercent = price.availabilityPercent,
                        demandModifier = price.availabilityModifier,
                        demandLabel = price.availabilityLabel,
                        dailyModifier = price.dailyModifier,
                        grantsExperience = false,
                        experienceReward = if (village.tier == VillageTier.HAMLET) 1 else 0,
                        requesterCount = occupiedCount.takeIf { it > 0 } ?: count,
                        requesterProfessionId = professionId,
                        requesterProfession = professionDisplayName(profession)
                    )
                )

                for (want in profile.wantsForTier(village.tier)) {
                    val wantKey = wantKey(professionId, want.item)
                    val wantCapacity = count * want.amount
                    if (!village.wantDemandTotalByProfession.containsKey(wantKey)) {
                        village.wantDemandTotalByProfession[wantKey] = wantCapacity
                        changedDemand = true
                    }
                    val wantBasePrice = getNeedPrice(village, want, wantCapacity, wantCapacity)
                    val wantPrice = wantPriceWithDiminishingReturns(
                        wantBasePrice.currentPrice,
                        village.wantTradeCountByProfession[wantKey] ?: 0
                    )
                    add(
                        BuyOffer(
                            item = ItemStack(want.item, 1),
                            displayName = want.displayLabel(),
                            emeraldReward = wantPrice,
                            neededToday = Int.MAX_VALUE,
                            remainingNeed = Int.MAX_VALUE,
                            maxDailyNeed = wantCapacity,
                            baseValue = wantBasePrice.baseValue,
                            minPrice = wantBasePrice.minPrice,
                            maxPrice = wantBasePrice.maxPrice,
                            demandPercent = wantBasePrice.availabilityPercent,
                            demandModifier = wantBasePrice.availabilityModifier,
                            demandLabel = wantBasePrice.availabilityLabel,
                            dailyModifier = wantBasePrice.dailyModifier,
                            grantsExperience = true,
                            experienceReward = 1,
                            requesterCount = occupiedCount.takeIf { it > 0 } ?: count,
                            requesterProfessionId = wantKey,
                            requesterProfession = professionDisplayName(profession)
                        )
                    )
                }
            }
        }.sortedWith(compareBy<BuyOffer> { !it.grantsExperience }.thenBy { it.requesterProfessionId })

        if (changedStock || changedDemand) {
            world.persistentStateManager.getOrCreate(VillageRegistry.TYPE, VillageRegistry.KEY).markDirty()
        }

        val playerEmeralds = 0
        return VillageMarketSnapshot(
            sellOffers = sellOffers,
            buyOffers = buyOffers,
            playerEmeralds = playerEmeralds,
            refreshTicks = nextTradeProgressTicksRemaining(world, village),
            produceRefreshTicks = nextProduceProgressTicksRemaining(world, village),
            needRefreshTicks = nextNeedProgressTicksRemaining(world, village),
            tierName = village.tier.displayName(),
            villageExperience = village.experience,
            currentTierMinExperience = tierMinExperience(village.tier),
            nextTierExperience = nextTierExperience(village.tier)
        )
    }

    private fun nextTradeProgressTicksRemaining(world: ServerWorld, village: VillageData?): Long {
        val dayRemaining = TICKS_PER_DAY - (world.time % TICKS_PER_DAY)
        return minOf(
            dayRemaining,
            nextProduceProgressTicksRemaining(world, village),
            nextNeedProgressTicksRemaining(world, village)
        ).coerceAtLeast(1L)
    }

    private fun nextProduceProgressTicksRemaining(world: ServerWorld, village: VillageData?): Long {
        val lastProductionTick = village?.lastProductionTick?.takeIf { it >= 0L }
            ?: village?.lastEconomyTick?.takeIf { it >= 0L }
            ?: world.time
        val productionElapsedTicks = (world.time - lastProductionTick).coerceAtLeast(0L)
        return (PRODUCE_INTERVAL_TICKS - (productionElapsedTicks % PRODUCE_INTERVAL_TICKS)).coerceAtLeast(1L)
    }

    private fun nextNeedProgressTicksRemaining(world: ServerWorld, village: VillageData?): Long {
        val lastNeedTick = village?.lastNeedTick?.takeIf { it >= 0L }
            ?: village?.lastEconomyTick?.takeIf { it >= 0L }
            ?: world.time
        val needElapsedTicks = (world.time - lastNeedTick).coerceAtLeast(0L)
        return (NEED_INTERVAL_TICKS - (needElapsedTicks % NEED_INTERVAL_TICKS)).coerceAtLeast(1L)
    }

    private fun hasInventorySpace(player: ServerPlayerEntity, item: Item, amount: Int): Boolean {
        var remaining = amount
        val inv = player.inventory
        for (slot in 0 until inv.size()) {
            val stack = inv.getStack(slot)
            if (stack.isEmpty) {
                remaining -= ItemStack(item).maxCount
                if (remaining <= 0) return true
                continue
            }
            if (stack.isOf(item) && stack.count < stack.maxCount) {
                remaining -= (stack.maxCount - stack.count)
                if (remaining <= 0) return true
            }
        }
        return remaining <= 0
    }

    private fun ensureDailyPriceModifiers(world: ServerWorld, village: VillageData) {
        val currentDay = world.time / TICKS_PER_DAY
        val itemIds = economyItemIds()
        var changed = false

        if (village.priceModifierDay != currentDay) {
            village.dailyPriceModifiers.clear()
            village.priceModifierDay = currentDay
            changed = true
        }

        for (itemId in itemIds) {
            if (!village.dailyPriceModifiers.containsKey(itemId)) {
                village.dailyPriceModifiers[itemId] = deterministicDailyModifier(village, itemId, currentDay)
                changed = true
            }
        }

        if (changed) {
            world.persistentStateManager.getOrCreate(VillageRegistry.TYPE, VillageRegistry.KEY).markDirty()
        }
    }

    private fun deterministicDailyModifier(village: VillageData, itemId: String, day: Long): Int {
        val seed = "${village.id}:$day:$itemId".hashCode()
        return Math.floorMod(seed, 3) - 1
    }

    private fun getProductPrice(
        village: VillageData,
        item: Commodity,
        stockRemaining: Int,
        stockCapacity: Int
    ): PriceBreakdown {
        val percent = percentOf(stockRemaining, stockCapacity)
        val modifier = when {
            percent >= 80 -> ModifierBreakdown("Full Stock", -1)
            percent >= 50 -> ModifierBreakdown("Stable Stock", 0)
            percent >= 20 -> ModifierBreakdown("Low Stock", 1)
            else -> ModifierBreakdown("Critical Stock", 2)
        }
        return priceBreakdown(village, item, percent, modifier)
    }

    private fun getNeedPrice(
        village: VillageData,
        item: Commodity,
        demandRemaining: Int,
        demandCapacity: Int
    ): PriceBreakdown {
        val percent = percentOf(demandRemaining, demandCapacity)
        val modifier = when {
            percent < 20 -> ModifierBreakdown("Low Demand", 0)
            percent < 50 -> ModifierBreakdown("Steady Demand", 0)
            percent < 80 -> ModifierBreakdown("High Demand", 1)
            else -> ModifierBreakdown("Critical Demand", 2)
        }
        return priceBreakdown(village, item, percent, modifier)
    }

    private fun wantPriceWithDiminishingReturns(basePrice: Int, alreadyTraded: Int): Int {
        val reduction = alreadyTraded.coerceAtLeast(0) / WANT_DIMINISHING_RETURN_BUCKET_SIZE
        return (basePrice - reduction).coerceAtLeast(1)
    }

    private fun wantTradeReward(basePrice: Int, alreadyTraded: Int, quantity: Int): Int {
        var reward = 0
        repeat(quantity.coerceAtLeast(0)) { offset ->
            reward += wantPriceWithDiminishingReturns(basePrice, alreadyTraded + offset)
        }
        return reward
    }

    private fun priceBreakdown(
        village: VillageData,
        item: Commodity,
        percent: Int,
        modifier: ModifierBreakdown
    ): PriceBreakdown {
        val dailyModifier = village.dailyPriceModifiers[itemIdOf(item.item)] ?: 0
        val basePrice = (item.baseValue + modifier.value + dailyModifier)
            .coerceIn(item.minPrice, item.maxPrice)
        val currentPrice = applyProductPricePercent(basePrice, item.productPricePercent)
        return PriceBreakdown(
            currentPrice = currentPrice,
            baseValue = item.baseValue,
            minPrice = item.minPrice,
            maxPrice = item.maxPrice,
            availabilityPercent = percent,
            availabilityModifier = modifier.value,
            availabilityLabel = modifier.label,
            dailyModifier = dailyModifier
        )
    }

    private fun percentOf(value: Int, capacity: Int): Int {
        if (capacity <= 0) return 0
        return ((value.coerceAtLeast(0) * 100) / capacity).coerceIn(0, 100)
    }

    private fun applyProductPricePercent(price: Int, percent: Int): Int {
        if (percent <= NORMAL_PRODUCT_PRICE_PERCENT) return price
        return ((price * percent) + 99) / 100
    }

    private fun professionCountFor(censusSnapshot: VillageEconomyState, professionId: String): Int {
        return censusSnapshot.hamletSlots.count { slot ->
            slot.occupied && Registries.VILLAGER_PROFESSION.getId(
                normalizeProfessionForTradeSimulation(slot.requiredProfession)
            ).toString() == professionId
        }
    }

    private fun economyItemIds(): Set<String> {
        return listOf(
            "minecraft:farmer",
            "minecraft:shepherd",
            "minecraft:mason",
            "minecraft:fletcher",
            "minecraft:butcher",
            "minecraft:fisherman",
            "minecraft:toolsmith",
            "minecraft:weaponsmith",
            "minecraft:armorer",
            "minecraft:cleric",
            "minecraft:librarian"
        ).mapNotNull { professionTradeProfileByProfession(it) }
            .flatMap { it.allCommodities().map { commodity -> commodity.item } }
            .map { itemIdOf(it) }
            .toSet()
    }

    private fun wantKey(professionId: String, item: Item): String = "$WANT_KEY_PREFIX$professionId|${itemIdOf(item)}"
    private fun outputKey(professionId: String, item: Item): String = "$OUTPUT_KEY_PREFIX$professionId|${itemIdOf(item)}"
    private fun outputKey(professionId: String, commodity: Commodity): String = "$OUTPUT_KEY_PREFIX$professionId|${commodity.keyItemId()}"

    private fun professionIdFromWantKey(wantKey: String): String? {
        if (!wantKey.startsWith(WANT_KEY_PREFIX)) return null
        return wantKey.removePrefix(WANT_KEY_PREFIX).substringBefore("|").takeIf { it.isNotBlank() }
    }

    private fun professionIdFromOutputKey(outputKey: String): String? {
        if (!outputKey.startsWith(OUTPUT_KEY_PREFIX)) return null
        return outputKey.removePrefix(OUTPUT_KEY_PREFIX).substringBefore("|").takeIf { it.isNotBlank() }
    }

    private fun outputItemIdFromOutputKey(outputKey: String): String? {
        if (!outputKey.startsWith(OUTPUT_KEY_PREFIX)) return null
        return outputKey.removePrefix(OUTPUT_KEY_PREFIX).substringAfter("|").takeIf { it.isNotBlank() }
    }

    private fun updateVillageTier(village: VillageData) {
        village.tier = when {
            village.experience >= 15000 -> VillageTier.CITY
            village.experience >= 5000 -> VillageTier.TOWN
            village.experience >= 1600 -> VillageTier.VILLAGE
            village.experience >= 300 -> VillageTier.SETTLEMENT
            else -> VillageTier.HAMLET
        }
    }

    private fun tierMinExperience(tier: VillageTier): Int {
        return when (tier) {
            VillageTier.HAMLET -> 0
            VillageTier.SETTLEMENT -> 300
            VillageTier.VILLAGE -> 1600
            VillageTier.TOWN -> 5000
            VillageTier.CITY -> 15000
        }
    }

    private fun nextTierExperience(tier: VillageTier): Int {
        return when (tier) {
            VillageTier.HAMLET -> 300
            VillageTier.SETTLEMENT -> 1600
            VillageTier.VILLAGE -> 5000
            VillageTier.TOWN -> 15000
            VillageTier.CITY -> 15000
        }
    }

    private fun VillageTier.displayName(): String {
        return name.lowercase(Locale.ROOT).replaceFirstChar { it.uppercase() }
    }

    private fun itemIdOf(item: Item): String = Registries.ITEM.getId(item).toString()

    private fun normalizeProfessionForTradeSimulation(profession: net.minecraft.village.VillagerProfession): net.minecraft.village.VillagerProfession {
        val professionId = Registries.VILLAGER_PROFESSION.getId(profession).toString()
        if (professionId != LEATHERWORKER_ID) return profession
        return professionById(BUTCHER_ID) ?: profession
    }

    private fun professionById(professionId: String): net.minecraft.village.VillagerProfession? {
        val id = Identifier.tryParse(professionId) ?: return null
        return Registries.VILLAGER_PROFESSION.get(id)
    }

    private fun professionTradeProfile(professionId: String): ProfessionTradeProfile? {
        val profession = professionById(professionId) ?: return null
        return professionTradeProfile(normalizeProfessionForTradeSimulation(profession))
    }

    private fun professionTradeProfile(profession: net.minecraft.village.VillagerProfession): ProfessionTradeProfile? {
        val professionId = Registries.VILLAGER_PROFESSION.getId(profession).toString()
        return when (professionId.substringAfterLast(":")) {
            "farmer" -> professionTradeProfileByProfession("minecraft:farmer")
            "shepherd" -> professionTradeProfileByProfession("minecraft:shepherd")
            "mason" -> professionTradeProfileByProfession("minecraft:mason")
            "fletcher" -> professionTradeProfileByProfession("minecraft:fletcher")
            "butcher" -> professionTradeProfileByProfession("minecraft:butcher")
            "fisherman" -> professionTradeProfileByProfession("minecraft:fisherman")
            "toolsmith" -> professionTradeProfileByProfession("minecraft:toolsmith")
            "weaponsmith" -> professionTradeProfileByProfession("minecraft:weaponsmith")
            "armorer" -> professionTradeProfileByProfession("minecraft:armorer")
            "cleric" -> professionTradeProfileByProfession("minecraft:cleric")
            "librarian" -> professionTradeProfileByProfession("minecraft:librarian")
            else -> null
        }
    }

    private fun professionTradeProfileByProfession(professionId: String): ProfessionTradeProfile? {
        return when (professionId.substringAfterLast(":").lowercase(Locale.ROOT)) {
            "farmer" -> ProfessionTradeProfile(
                hamletOutputs = listOf(Commodity(Items.WHEAT, FARMER_DAILY_OUTPUT_CAP, baseValue = 2, minPrice = 1, maxPrice = 3)),
                need = Commodity(Items.COAL, 5, baseValue = 3, minPrice = 2, maxPrice = 5),
                settlementOutputs = listOf(
                    Commodity(Items.CARROT, 10, baseValue = 2, minPrice = 1, maxPrice = 4),
                    Commodity(Items.POTATO, 10, baseValue = 2, minPrice = 1, maxPrice = 4)
                ),
                villageOutputs = listOf(Commodity(Items.PAPER, 10, baseValue = 3, minPrice = 2, maxPrice = 5)),
                townOutputs = listOf(Commodity(Items.MELON_SLICE, 10, baseValue = 3, minPrice = 2, maxPrice = 5)),
                cityOutputs = listOf(Commodity(Items.GOLDEN_CARROT, 10, baseValue = 6, minPrice = 4, maxPrice = 9)),
                settlementWant = Commodity(Items.BONE_MEAL, 1, baseValue = 2, minPrice = 1, maxPrice = 4),
                villageWant = Commodity(Items.BOOK, 1, baseValue = 4, minPrice = 2, maxPrice = 6),
                townWant = Commodity(Items.DIAMOND_HOE, 1, baseValue = 8, minPrice = 5, maxPrice = 12)
            )
            "shepherd" -> ProfessionTradeProfile(
                hamletOutputs = listOf(Commodity(Items.WHITE_WOOL, 10, baseValue = 3, minPrice = 2, maxPrice = 5)),
                need = Commodity(Items.OAK_LOG, 5, baseValue = 3, minPrice = 2, maxPrice = 5),
                settlementOutputs = listOf(Commodity(Items.STRING, 10, baseValue = 2, minPrice = 1, maxPrice = 4)),
                villageOutputs = listOf(Commodity(Items.LEATHER, 10, baseValue = 5, minPrice = 3, maxPrice = 6)),
                townOutputs = listOf(Commodity(Items.HONEY_BOTTLE, 10, baseValue = 4, minPrice = 2, maxPrice = 7)),
                cityOutputs = listOf(Commodity(Items.HORSE_SPAWN_EGG, 3, baseValue = 10, minPrice = 6, maxPrice = 16)),
                settlementWant = Commodity(Items.OAK_PLANKS, 1, baseValue = 2, minPrice = 1, maxPrice = 4),
                villageWant = Commodity(Items.SHEARS, 1, baseValue = 4, minPrice = 2, maxPrice = 6),
                townWant = Commodity(Items.DIAMOND_CHESTPLATE, 1, baseValue = 12, minPrice = 8, maxPrice = 18)
            )
            "mason" -> ProfessionTradeProfile(
                hamletOutputs = listOf(Commodity(Items.COAL, 10, baseValue = 3, minPrice = 2, maxPrice = 5)),
                need = Commodity(Items.WHEAT, 5, baseValue = 2, minPrice = 1, maxPrice = 3),
                settlementOutputs = listOf(Commodity(Items.IRON_INGOT, 10, baseValue = 5, minPrice = 3, maxPrice = 7)),
                villageOutputs = listOf(Commodity(Items.GOLD_INGOT, 10, baseValue = 6, minPrice = 4, maxPrice = 9)),
                townOutputs = listOf(Commodity(Items.DIAMOND, 10, baseValue = 8, minPrice = 5, maxPrice = 12)),
                cityOutputs = listOf(Commodity(Items.NETHERITE_SCRAP, 1, baseValue = 12, minPrice = 8, maxPrice = 18)),
                settlementWant = Commodity(Items.FLINT, 1, baseValue = 2, minPrice = 1, maxPrice = 4),
                villageWant = Commodity(Items.IRON_HELMET, 1, baseValue = 6, minPrice = 3, maxPrice = 9),
                townWant = Commodity(Items.DIAMOND_PICKAXE, 1, baseValue = 10, minPrice = 6, maxPrice = 14)
            )
            "fletcher" -> ProfessionTradeProfile(
                hamletOutputs = listOf(Commodity(Items.OAK_LOG, 10, baseValue = 3, minPrice = 2, maxPrice = 5)),
                need = Commodity(Items.WHEAT, 5, baseValue = 2, minPrice = 1, maxPrice = 3),
                settlementOutputs = listOf(Commodity(Items.OAK_PLANKS, 10, baseValue = 2, minPrice = 1, maxPrice = 4)),
                villageOutputs = listOf(Commodity(Items.STICK, 10, baseValue = 1, minPrice = 1, maxPrice = 3)),
                townOutputs = listOf(Commodity(Items.APPLE, 10, baseValue = 3, minPrice = 2, maxPrice = 5)),
                cityOutputs = listOf(Commodity(Items.ENCHANTED_GOLDEN_APPLE, 1, baseValue = 16, minPrice = 10, maxPrice = 24)),
                settlementWant = Commodity(Items.IRON_INGOT, 1, baseValue = 5, minPrice = 3, maxPrice = 7),
                villageWant = Commodity(Items.IRON_AXE, 1, baseValue = 6, minPrice = 3, maxPrice = 9),
                townWant = Commodity(Items.DIAMOND_AXE, 1, baseValue = 10, minPrice = 6, maxPrice = 14)
            )
            "butcher" -> ProfessionTradeProfile(
                hamletOutputs = listOf(Commodity(Items.BEEF, 10, baseValue = 4, minPrice = 2, maxPrice = 6)),
                need = Commodity(Items.COAL, 5, baseValue = 3, minPrice = 2, maxPrice = 5),
                settlementOutputs = listOf(Commodity(Items.BONE_MEAL, 10, baseValue = 2, minPrice = 1, maxPrice = 4)),
                villageOutputs = listOf(Commodity(Items.BLAZE_POWDER, 10, baseValue = 6, minPrice = 3, maxPrice = 9)),
                townOutputs = listOf(Commodity(Items.GUNPOWDER, 10, baseValue = 5, minPrice = 3, maxPrice = 8)),
                cityOutputs = listOf(Commodity(Items.ELYTRA, 1, baseValue = 24, minPrice = 16, maxPrice = 32)),
                settlementWant = Commodity(Items.CARROT, 1, baseValue = 2, minPrice = 1, maxPrice = 4),
                villageWant = Commodity(Items.IRON_SWORD, 1, baseValue = 6, minPrice = 3, maxPrice = 9),
                townWant = Commodity(Items.DIAMOND_SWORD, 1, baseValue = 10, minPrice = 6, maxPrice = 14)
            )
            "fisherman" -> ProfessionTradeProfile(
                hamletOutputs = listOf(
                    Commodity(Items.COD, 10, baseValue = 3, minPrice = 2, maxPrice = 5),
                    Commodity(Items.SALMON, 10, baseValue = 3, minPrice = 2, maxPrice = 5)
                ),
                need = Commodity(Items.LEATHER, 5, baseValue = 5, minPrice = 3, maxPrice = 6),
                settlementOutputs = listOf(Commodity(Items.FLINT, 10, baseValue = 2, minPrice = 1, maxPrice = 4)),
                townOutputs = listOf(Commodity(Items.PUFFERFISH, 5, baseValue = 5, minPrice = 3, maxPrice = 8)),
                cityOutputs = listOf(Commodity(Items.SPONGE, 10, baseValue = 8, minPrice = 5, maxPrice = 12)),
                settlementWant = Commodity(Items.STRING, 1, baseValue = 2, minPrice = 1, maxPrice = 4),
                villageWant = Commodity(Items.FISHING_ROD, 1, baseValue = 4, minPrice = 2, maxPrice = 6),
                townWant = Commodity(Items.POTION, 1, baseValue = 8, minPrice = 5, maxPrice = 12)
            )
            "toolsmith" -> ProfessionTradeProfile(
                need = Commodity(Items.OAK_LOG, 5, baseValue = 3, minPrice = 2, maxPrice = 5),
                villageOutputs = listOf(
                    Commodity(Items.IRON_PICKAXE, 1, baseValue = 6, minPrice = 3, maxPrice = 9, productPricePercent = ARTISAN_VILLAGE_PRODUCT_PRICE_PERCENT),
                    Commodity(Items.IRON_AXE, 1, baseValue = 6, minPrice = 3, maxPrice = 9, productPricePercent = ARTISAN_VILLAGE_PRODUCT_PRICE_PERCENT),
                    Commodity(Items.IRON_SHOVEL, 1, baseValue = 5, minPrice = 3, maxPrice = 8, productPricePercent = ARTISAN_VILLAGE_PRODUCT_PRICE_PERCENT),
                    Commodity(Items.IRON_HOE, 1, baseValue = 5, minPrice = 3, maxPrice = 8, productPricePercent = ARTISAN_VILLAGE_PRODUCT_PRICE_PERCENT),
                    Commodity(Items.FISHING_ROD, 1, baseValue = 4, minPrice = 2, maxPrice = 6, productPricePercent = ARTISAN_VILLAGE_PRODUCT_PRICE_PERCENT),
                    Commodity(Items.SHEARS, 1, baseValue = 4, minPrice = 2, maxPrice = 6, productPricePercent = ARTISAN_VILLAGE_PRODUCT_PRICE_PERCENT)
                ),
                townOutputs = listOf(
                    Commodity(Items.DIAMOND_PICKAXE, 1, baseValue = 10, minPrice = 6, maxPrice = 14, productPricePercent = ARTISAN_TOWN_PRODUCT_PRICE_PERCENT),
                    Commodity(Items.DIAMOND_AXE, 1, baseValue = 10, minPrice = 6, maxPrice = 14, productPricePercent = ARTISAN_TOWN_PRODUCT_PRICE_PERCENT),
                    Commodity(Items.DIAMOND_SHOVEL, 1, baseValue = 9, minPrice = 5, maxPrice = 13, productPricePercent = ARTISAN_TOWN_PRODUCT_PRICE_PERCENT),
                    Commodity(Items.DIAMOND_HOE, 1, baseValue = 9, minPrice = 5, maxPrice = 13, productPricePercent = ARTISAN_TOWN_PRODUCT_PRICE_PERCENT)
                ),
                cityOutputs = listOf(enchantedBookCommodity("mending", baseValue = 12, minPrice = 8, maxPrice = 18, productPricePercent = ARTISAN_CITY_PRODUCT_PRICE_PERCENT)),
                villageWant = Commodity(Items.STICK, 1, baseValue = 1, minPrice = 1, maxPrice = 3),
                townWant = Commodity(Items.DIAMOND, 1, baseValue = 8, minPrice = 5, maxPrice = 12)
            )
            "weaponsmith" -> ProfessionTradeProfile(
                need = Commodity(Items.OAK_LOG, 5, baseValue = 3, minPrice = 2, maxPrice = 5),
                villageOutputs = listOf(
                    Commodity(Items.IRON_SWORD, 1, baseValue = 6, minPrice = 3, maxPrice = 9, productPricePercent = ARTISAN_VILLAGE_PRODUCT_PRICE_PERCENT),
                    Commodity(Items.BOW, 1, baseValue = 5, minPrice = 3, maxPrice = 8, productPricePercent = ARTISAN_VILLAGE_PRODUCT_PRICE_PERCENT),
                    Commodity(Items.ARROW, 16, baseValue = 3, minPrice = 2, maxPrice = 5, productPricePercent = ARTISAN_VILLAGE_PRODUCT_PRICE_PERCENT)
                ),
                cityOutputs = listOf(
                    Commodity(Items.DIAMOND_SWORD, 1, baseValue = 10, minPrice = 6, maxPrice = 14, productPricePercent = ARTISAN_CITY_PRODUCT_PRICE_PERCENT),
                    Commodity(Items.CROSSBOW, 1, baseValue = 8, minPrice = 5, maxPrice = 12, productPricePercent = ARTISAN_CITY_PRODUCT_PRICE_PERCENT),
                    enchantedBookCommodity("sharpness", baseValue = 12, minPrice = 8, maxPrice = 18, productPricePercent = ARTISAN_CITY_PRODUCT_PRICE_PERCENT)
                ),
                villageWant = Commodity(Items.STICK, 1, baseValue = 1, minPrice = 1, maxPrice = 3),
                townWant = Commodity(Items.DIAMOND, 1, baseValue = 8, minPrice = 5, maxPrice = 12)
            )
            "armorer" -> ProfessionTradeProfile(
                need = Commodity(Items.WOODEN_PICKAXE, 5, baseValue = 4, minPrice = 2, maxPrice = 6),
                villageOutputs = listOf(
                    Commodity(Items.IRON_HELMET, 1, baseValue = 6, minPrice = 3, maxPrice = 9, productPricePercent = ARTISAN_VILLAGE_PRODUCT_PRICE_PERCENT),
                    Commodity(Items.IRON_CHESTPLATE, 1, baseValue = 8, minPrice = 5, maxPrice = 12, productPricePercent = ARTISAN_VILLAGE_PRODUCT_PRICE_PERCENT),
                    Commodity(Items.IRON_LEGGINGS, 1, baseValue = 7, minPrice = 4, maxPrice = 11, productPricePercent = ARTISAN_VILLAGE_PRODUCT_PRICE_PERCENT),
                    Commodity(Items.IRON_BOOTS, 1, baseValue = 5, minPrice = 3, maxPrice = 8, productPricePercent = ARTISAN_VILLAGE_PRODUCT_PRICE_PERCENT)
                ),
                townOutputs = listOf(
                    Commodity(Items.DIAMOND_HELMET, 1, baseValue = 10, minPrice = 6, maxPrice = 14, productPricePercent = ARTISAN_TOWN_PRODUCT_PRICE_PERCENT),
                    Commodity(Items.DIAMOND_CHESTPLATE, 1, baseValue = 12, minPrice = 8, maxPrice = 18, productPricePercent = ARTISAN_TOWN_PRODUCT_PRICE_PERCENT),
                    Commodity(Items.DIAMOND_LEGGINGS, 1, baseValue = 11, minPrice = 7, maxPrice = 16, productPricePercent = ARTISAN_TOWN_PRODUCT_PRICE_PERCENT),
                    Commodity(Items.DIAMOND_BOOTS, 1, baseValue = 9, minPrice = 5, maxPrice = 13, productPricePercent = ARTISAN_TOWN_PRODUCT_PRICE_PERCENT)
                ),
                specialOutputs = ::armorerOutputsForTier,
                villageWant = Commodity(Items.LEATHER, 1, baseValue = 5, minPrice = 3, maxPrice = 6),
                townWant = Commodity(Items.DIAMOND, 1, baseValue = 8, minPrice = 5, maxPrice = 12)
            )
            "cleric" -> ProfessionTradeProfile(
                need = Commodity(Items.WHEAT, 5, baseValue = 2, minPrice = 1, maxPrice = 3),
                specialOutputs = ::clericOutputsForTier,
                cityOutputs = listOf(Commodity(Items.EXPERIENCE_BOTTLE, 10, baseValue = 8, minPrice = 5, maxPrice = 12, productPricePercent = ARTISAN_CITY_PRODUCT_PRICE_PERCENT)),
                villageWant = Commodity(Items.BLAZE_POWDER, 1, baseValue = 6, minPrice = 3, maxPrice = 9),
                townWant = Commodity(Items.PUFFERFISH, 1, baseValue = 5, minPrice = 3, maxPrice = 8)
            )
            "librarian" -> ProfessionTradeProfile(
                need = Commodity(Items.WHEAT, 5, baseValue = 2, minPrice = 1, maxPrice = 3),
                villageOutputs = listOf(Commodity(Items.BOOK, 10, baseValue = 4, minPrice = 2, maxPrice = 6, productPricePercent = ARTISAN_VILLAGE_PRODUCT_PRICE_PERCENT)),
                specialOutputs = ::librarianOutputsForTier,
                cityOutputs = listOf(
                    Commodity(Items.BOOKSHELF, 1, baseValue = 16, minPrice = 10, maxPrice = 24, productPricePercent = ARTISAN_CITY_PRODUCT_PRICE_PERCENT),
                    enchantedBookCommodity("protection", baseValue = 14, minPrice = 9, maxPrice = 20, productPricePercent = ARTISAN_CITY_PRODUCT_PRICE_PERCENT)
                ),
                villageWant = Commodity(Items.PAPER, 1, baseValue = 2, minPrice = 1, maxPrice = 4),
                townWant = Commodity(Items.APPLE, 1, baseValue = 3, minPrice = 2, maxPrice = 5)
            )
            else -> null
        }
    }

    private data class Commodity(
        val item: Item,
        val amount: Int,
        val baseValue: Int,
        val minPrice: Int,
        val maxPrice: Int,
        val variantId: String? = null,
        val displayName: String? = null,
        val enchantmentId: String? = null,
        val potionId: String? = null,
        val productPricePercent: Int = NORMAL_PRODUCT_PRICE_PERCENT
    ) {
        val itemName: String = item.toString()

        fun keyItemId(): String = variantId ?: itemIdOf(item)

        fun stack(world: ServerWorld, count: Int = 1): ItemStack {
            val stack = if (item == Items.ENCHANTED_BOOK && enchantmentId != null) {
                enchantedBookStack(world, enchantmentId, count)
            } else if ((item == Items.POTION || item == Items.SPLASH_POTION) && potionId != null) {
                val potionStack = PotionContentsComponent.createStack(item, potionEntry(potionId))
                potionStack.count = count
                potionStack
            } else {
                ItemStack(item, count)
            }
            displayName?.let { name ->
                stack.set(DataComponentTypes.CUSTOM_NAME, Text.literal(name))
            }
            return stack
        }

        fun displayLabel(): String = displayName ?: item.name.string
    }

    private data class ModifierBreakdown(
        val label: String,
        val value: Int
    )

    private data class PriceBreakdown(
        val currentPrice: Int,
        val baseValue: Int,
        val minPrice: Int,
        val maxPrice: Int,
        val availabilityPercent: Int,
        val availabilityModifier: Int,
        val availabilityLabel: String,
        val dailyModifier: Int
    )

    private data class ProfessionTradeProfile(
        val need: Commodity?,
        val hamletOutputs: List<Commodity> = emptyList(),
        val settlementOutputs: List<Commodity> = emptyList(),
        val villageOutputs: List<Commodity> = emptyList(),
        val townOutputs: List<Commodity> = emptyList(),
        val cityOutputs: List<Commodity> = emptyList(),
        val specialOutputs: ((VillageTier, VillageData) -> List<Commodity>)? = null,
        val settlementWant: Commodity? = null,
        val villageWant: Commodity? = null,
        val townWant: Commodity? = null,
        val cityWant: Commodity? = null
    ) {
        fun outputsForTier(tier: VillageTier, village: VillageData): List<Commodity> {
            val outputs = mutableListOf<Commodity>()
            outputs.addAll(hamletOutputs)
            if (tier.ordinal >= VillageTier.SETTLEMENT.ordinal) outputs.addAll(settlementOutputs)
            if (tier.ordinal >= VillageTier.VILLAGE.ordinal) outputs.addAll(villageOutputs)
            if (tier.ordinal >= VillageTier.TOWN.ordinal) outputs.addAll(townOutputs)
            if (tier.ordinal >= VillageTier.CITY.ordinal) outputs.addAll(cityOutputs)
            specialOutputs?.let { outputs.addAll(it(tier, village)) }
            return outputs
        }

        fun allCommodities(): List<Commodity> {
            return hamletOutputs + settlementOutputs + villageOutputs + townOutputs + cityOutputs +
                listOfNotNull(need, settlementWant, villageWant, townWant, cityWant)
        }

        fun wantsForTier(tier: VillageTier): List<Commodity> {
            val wants = mutableListOf<Commodity>()
            if (tier.ordinal >= VillageTier.SETTLEMENT.ordinal) settlementWant?.let(wants::add)
            if (tier.ordinal >= VillageTier.VILLAGE.ordinal) villageWant?.let(wants::add)
            if (tier.ordinal >= VillageTier.TOWN.ordinal) townWant?.let(wants::add)
            if (tier.ordinal >= VillageTier.CITY.ordinal) cityWant?.let(wants::add)
            return wants
        }
    }

    private fun enchantedBookCommodity(
        enchantmentId: String,
        baseValue: Int,
        minPrice: Int,
        maxPrice: Int,
        amount: Int = 1,
        productPricePercent: Int = NORMAL_PRODUCT_PRICE_PERCENT
    ): Commodity {
        return Commodity(
            item = Items.ENCHANTED_BOOK,
            amount = amount,
            baseValue = baseValue,
            minPrice = minPrice,
            maxPrice = maxPrice,
            variantId = "${itemIdOf(Items.ENCHANTED_BOOK)}#$enchantmentId",
            displayName = "${enchantmentDisplayNameWithLevel(enchantmentId)} Book",
            enchantmentId = enchantmentId,
            productPricePercent = productPricePercent
        )
    }

    private fun clericOutputsForTier(tier: VillageTier, village: VillageData): List<Commodity> {
        if (tier.ordinal < VillageTier.VILLAGE.ordinal) return emptyList()

        val isLevelTwo = tier.ordinal >= VillageTier.TOWN.ordinal
        val potionLevel = if (isLevelTwo) "level_2" else "level_1"
        val item = if (isLevelTwo) Items.SPLASH_POTION else Items.POTION
        val baseValue = if (isLevelTwo) 10 else 8
        val minPrice = if (isLevelTwo) 6 else 5
        val maxPrice = if (isLevelTwo) 14 else 12
        val productPricePercent = if (isLevelTwo) {
            ARTISAN_TOWN_PRODUCT_PRICE_PERCENT
        } else {
            ARTISAN_VILLAGE_PRODUCT_PRICE_PERCENT
        }
        val day = variantDay(village)

        return deterministicVariantIds(village, "cleric:$potionLevel", CLERIC_POTION_VARIANT_COUNT, clericPotionPool(isLevelTwo))
            .map { potion ->
                Commodity(
                    item = item,
                    amount = 1,
                    baseValue = baseValue,
                    minPrice = minPrice,
                    maxPrice = maxPrice,
                    variantId = "${itemIdOf(item)}#$potionLevel:$potion:$day",
                    displayName = potionDisplayName(potion, isLevelTwo),
                    potionId = if (isLevelTwo) "strong_$potion" else potion,
                    productPricePercent = productPricePercent
                )
            }
    }

    private fun librarianOutputsForTier(tier: VillageTier, village: VillageData): List<Commodity> {
        if (tier.ordinal < VillageTier.TOWN.ordinal) return emptyList()

        val day = variantDay(village)
        val enchantment = deterministicVariantIds(village, "librarian:book", 1, librarianBookPool()).first()
        return listOf(
            Commodity(
                item = Items.ENCHANTED_BOOK,
                amount = 1,
                baseValue = 12,
                minPrice = 8,
                maxPrice = 18,
                variantId = "${itemIdOf(Items.ENCHANTED_BOOK)}#$enchantment:$day",
                displayName = "${enchantmentDisplayNameWithLevel(enchantment)} Book",
                enchantmentId = enchantment,
                productPricePercent = ARTISAN_TOWN_PRODUCT_PRICE_PERCENT
            )
        )
    }

    private fun armorerOutputsForTier(tier: VillageTier, village: VillageData): List<Commodity> {
        if (tier.ordinal < VillageTier.CITY.ordinal) return emptyList()

        val day = variantDay(village)
        val trim = deterministicVariantIds(village, "armorer:trim", 1, armorTrimPool()).first()
        val trimItem = armorTrimItem(trim)
        return listOf(
            Commodity(
                item = trimItem,
                amount = 1,
                baseValue = 16,
                minPrice = 10,
                maxPrice = 24,
                variantId = "${itemIdOf(trimItem)}#$trim:$day",
                displayName = "${enchantmentDisplayName(trim)} Armor Trim",
                productPricePercent = ARTISAN_CITY_PRODUCT_PRICE_PERCENT
            )
        )
    }

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

    private fun armorTrimItem(trim: String): Item {
        return when (trim) {
            "coast" -> Items.COAST_ARMOR_TRIM_SMITHING_TEMPLATE
            "dune" -> Items.DUNE_ARMOR_TRIM_SMITHING_TEMPLATE
            "eye" -> Items.EYE_ARMOR_TRIM_SMITHING_TEMPLATE
            "host" -> Items.HOST_ARMOR_TRIM_SMITHING_TEMPLATE
            "raiser" -> Items.RAISER_ARMOR_TRIM_SMITHING_TEMPLATE
            "rib" -> Items.RIB_ARMOR_TRIM_SMITHING_TEMPLATE
            "sentry" -> Items.SENTRY_ARMOR_TRIM_SMITHING_TEMPLATE
            "shaper" -> Items.SHAPER_ARMOR_TRIM_SMITHING_TEMPLATE
            "silence" -> Items.SILENCE_ARMOR_TRIM_SMITHING_TEMPLATE
            "snout" -> Items.SNOUT_ARMOR_TRIM_SMITHING_TEMPLATE
            "spire" -> Items.SPIRE_ARMOR_TRIM_SMITHING_TEMPLATE
            "tide" -> Items.TIDE_ARMOR_TRIM_SMITHING_TEMPLATE
            "vex" -> Items.VEX_ARMOR_TRIM_SMITHING_TEMPLATE
            "ward" -> Items.WARD_ARMOR_TRIM_SMITHING_TEMPLATE
            "wayfinder" -> Items.WAYFINDER_ARMOR_TRIM_SMITHING_TEMPLATE
            "wild" -> Items.WILD_ARMOR_TRIM_SMITHING_TEMPLATE
            else -> Items.COAST_ARMOR_TRIM_SMITHING_TEMPLATE
        }
    }

    private fun potionDisplayName(potion: String, levelTwo: Boolean): String {
        val baseName = potion.split("_").joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }
        return if (levelTwo) "$baseName II Potion" else "$baseName Potion"
    }

    private fun potionEntry(potionId: String): RegistryEntry<Potion> {
        return when (potionId) {
            "healing" -> Potions.HEALING
            "strong_healing" -> Potions.STRONG_HEALING
            "strength" -> Potions.STRENGTH
            "strong_strength" -> Potions.STRONG_STRENGTH
            "swiftness" -> Potions.SWIFTNESS
            "strong_swiftness" -> Potions.STRONG_SWIFTNESS
            "leaping" -> Potions.LEAPING
            "strong_leaping" -> Potions.STRONG_LEAPING
            "poison" -> Potions.POISON
            "strong_poison" -> Potions.STRONG_POISON
            "regeneration" -> Potions.REGENERATION
            "strong_regeneration" -> Potions.STRONG_REGENERATION
            "fire_resistance" -> Potions.FIRE_RESISTANCE
            "water_breathing" -> Potions.WATER_BREATHING
            else -> Potions.HEALING
        }
    }

    private fun enchantmentDisplayName(enchantment: String): String {
        return enchantment.split("_").joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }
    }

    private fun enchantmentDisplayNameWithLevel(enchantment: String): String {
        val level = enchantmentLevel(enchantment)
        val levelText = when (level) {
            1 -> if (enchantment == "mending" || enchantment == "silk_touch") "" else " I"
            2 -> " II"
            3 -> " III"
            4 -> " IV"
            5 -> " V"
            else -> " $level"
        }
        return "${enchantmentDisplayName(enchantment)}$levelText"
    }

    private fun enchantedBookStack(world: ServerWorld, enchantmentId: String, count: Int): ItemStack {
        val enchantmentKey = enchantmentKey(enchantmentId)
        val enchantment = world.registryManager
            .getWrapperOrThrow(RegistryKeys.ENCHANTMENT)
            .getOrThrow(enchantmentKey)
        val stack = EnchantedBookItem.forEnchantment(EnchantmentLevelEntry(enchantment, enchantmentLevel(enchantmentId)))
        stack.count = count
        return stack
    }

    private fun enchantmentKey(enchantmentId: String): RegistryKey<Enchantment> {
        return when (enchantmentId) {
            "protection" -> Enchantments.PROTECTION
            "sharpness" -> Enchantments.SHARPNESS
            "efficiency" -> Enchantments.EFFICIENCY
            "unbreaking" -> Enchantments.UNBREAKING
            "fortune" -> Enchantments.FORTUNE
            "power" -> Enchantments.POWER
            "mending" -> Enchantments.MENDING
            "silk_touch" -> Enchantments.SILK_TOUCH
            else -> Enchantments.UNBREAKING
        }
    }

    private fun enchantmentLevel(enchantmentId: String): Int {
        return when (enchantmentId) {
            "protection" -> 4
            "sharpness" -> 5
            "efficiency" -> 3
            "unbreaking" -> 3
            "fortune" -> 2
            "power" -> 3
            "mending" -> 1
            "silk_touch" -> 1
            else -> 1
        }
    }

    private fun variantDay(village: VillageData): Long = maxOf(village.priceModifierDay, village.lastEconomyDay).coerceAtLeast(0L)

    private fun getVillage(world: ServerWorld, clerkPos: BlockPos): VillageData? {
        val registry = world.persistentStateManager.getOrCreate(VillageRegistry.TYPE, VillageRegistry.KEY)
        val clerkTable = world.getBlockEntity(clerkPos) as? ClerkTableBlockEntity
        return clerkTable?.villageId?.let { registry.getVillageById(it) }
            ?: registry.getVillageAtPos(clerkPos)
    }

    private fun getClerkTable(world: ServerWorld, village: VillageData, fallbackPos: BlockPos): ClerkTableBlockEntity? {
        return world.getBlockEntity(village.clerkPos) as? ClerkTableBlockEntity
            ?: world.getBlockEntity(fallbackPos) as? ClerkTableBlockEntity
    }
}

