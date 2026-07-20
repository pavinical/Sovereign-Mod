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
import net.pavinical.sovereign.event.ProfessionBlockProtection
import net.pavinical.sovereign.registry.ModItems

private const val FARMER_DAILY_OUTPUT_CAP = 10
private const val TICKS_PER_DAY = 24000L
private const val LEATHERWORKER_ID = "minecraft:leatherworker"
private const val BUTCHER_ID = "minecraft:butcher"
private const val WANT_KEY_PREFIX = "want|"
private const val WANT_GROUP_KEY_PREFIX = "want_group|"
private const val OUTPUT_KEY_PREFIX = "output|"
private const val CLERIC_POTION_VARIANT_COUNT = 3
private const val NORMAL_PRODUCT_PRICE_PERCENT = 100
private const val ARTISAN_VILLAGE_PRODUCT_PRICE_PERCENT = 125
private const val ARTISAN_TOWN_PRODUCT_PRICE_PERCENT = 160
private const val ARTISAN_CITY_PRODUCT_PRICE_PERCENT = 220
private const val PEASANT_PRODUCTION_INTERVAL_TICKS = 6000L
private const val WANT_DIMINISHING_RETURN_BUCKET_SIZE = 16
private const val WANT_DAILY_FLOOR_TRADE_LIMIT = 64
private const val WANT_DAILY_LIMIT_DIVISOR = 1
private const val PEASANT_ADVANCED_WANT_DAILY_LIMIT = 64
private const val BOOK_WANT_DAILY_LIMIT = 64
private const val NON_STACKABLE_WANT_TURN_IN_LIMIT = 4
private const val NON_STACKABLE_WANT_XP_REWARD = 5
private const val PROFESSION_PRICE_EFFECTIVE_COUNT_CAP = 6
private const val PROFESSION_SUPPLY_PRICE_DISCOUNT_PERCENT_PER_EXTRA = 5
private const val PROFESSION_SUPPLY_PRICE_DISCOUNT_PERCENT_MAX = 25
private const val PROFESSION_DEMAND_PRICE_BONUS_PERCENT_PER_EXTRA = 8
private const val PROFESSION_DEMAND_PRICE_BONUS_PERCENT_MAX = 40
private const val STACKABLE_WANT_BUY_PRICE_PERCENT = 40
private const val TREASURY_REPLENISH_INTERVAL_TICKS = 12000L

private val MAIN_TRADE_TOOLSMITH_OUTPUTS: Set<Item> = setOf(
    Items.STONE_PICKAXE,
    Items.STONE_AXE,
    Items.STONE_SHOVEL,
    Items.SHEARS,
    Items.FLINT_AND_STEEL,
    Items.BUCKET,
    Items.IRON_PICKAXE,
    Items.IRON_AXE,
    Items.IRON_SHOVEL,
    Items.IRON_HOE,
    Items.FISHING_ROD
)
private val MAIN_TRADE_WEAPONSMITH_OUTPUTS: Set<Item> = setOf(
    Items.ARROW
)
private val MAIN_TRADE_ARMORER_OUTPUTS: Set<Item> = emptySet()
private val MAIN_TRADE_CLERIC_OUTPUTS: Set<Item> = setOf(
    Items.CANDLE,
    Items.GLASS_BOTTLE,
    Items.LAPIS_LAZULI,
    Items.AMETHYST_SHARD,
    Items.REDSTONE,
    Items.GLOWSTONE_DUST,
    Items.ENDER_PEARL
)
private val MAIN_TRADE_LIBRARIAN_OUTPUTS: Set<Item> = setOf(
    Items.PAPER,
    Items.INK_SAC,
    Items.BOOK,
    Items.WRITABLE_BOOK,
    Items.BOOKSHELF,
    Items.LANTERN
)
private val MAIN_TRADE_CARTOGRAPHER_OUTPUTS: Set<Item> = setOf(
    Items.PAPER,
    Items.MAP,
    Items.COMPASS,
    Items.ITEM_FRAME
)

data class VillageTradeExecutionResult(
    val accepted: Boolean,
    val message: Text,
    val snapshot: VillageMarketSnapshot? = null
)

object VillageEconomyService {
    private data class ProfessionMarketCounts(
        val totalByProfession: Map<String, Int>,
        val occupiedByProfession: Map<String, Int>
    )

    private data class WantOfferComponent(
        val professionName: String,
        val wantKey: String,
        val want: Commodity,
        val price: PriceBreakdown,
        val baseUnitPrice: Int,
        val currentUnitPrice: Int,
        val exchange: TradeExchange,
        val alreadyTraded: Int,
        val dailyLimit: Int,
        val remainingDaily: Int,
        val requesterCount: Int
    )

    private data class WantOfferCollection(
        val components: List<WantOfferComponent>,
        val changedDemand: Boolean
    )

    fun openSnapshot(world: ServerWorld, clerkPos: BlockPos, player: ServerPlayerEntity? = null): VillageMarketSnapshot? {
        val village = getVillage(world, clerkPos) ?: return null
        val blockEntity = getClerkTable(world, village, clerkPos) ?: return null
        val censusSnapshot = blockEntity.getSnapshot(village)
        ClerkEconomyService.refreshVillageFromSnapshot(world, village, censusSnapshot)
        ensureVillageTreasury(world, village)
        return buildSnapshot(world, village.clerkPos, censusSnapshot, player)
    }

    fun emptySnapshotForRegisteredVillage(world: ServerWorld, clerkPos: BlockPos, player: ServerPlayerEntity? = null): VillageMarketSnapshot? {
        val village = getVillage(world, clerkPos) ?: return null
        ensureVillageTreasury(world, village)
        return VillageMarketSnapshot(
            sellOffers = emptyList(),
            buyOffers = emptyList(),
            jobBlockOffers = JobBlockMarket.clerkOffers(),
            commissionOffers = emptyList(),
            commissionOrders = ArtisanCommissionService.ordersFor(world, village, player),
            buildingPlots = VillagePlotService.buildingPlotsFor(world, village),
            playerEmeralds = playerLedgerBalance(world, player),
            villageWealth = village.treasuryEmeralds.coerceAtLeast(0),
            refreshTicks = nextTradeProgressTicksRemaining(world, village),
            produceRefreshTicks = nextProduceProgressTicksRemaining(world, village),
            needRefreshTicks = nextNeedProgressTicksRemaining(world, village),
            tierName = village.tier.displayName(),
            villageExperience = village.experience,
            currentTierMinExperience = tierMinExperience(village.tier),
            nextTierExperience = nextTierExperience(village.tier),
            info = buildVillageInfo(village, null)
        )
    }

    fun restockVillageToFull(world: ServerWorld, village: VillageData): Text {
        val censusSnapshot = ClerkCensusService.forceSyncVillage(world, village)
            ?: return Text.literal("Could not restock '${village.name}': clerk table is missing.")
        val professionCounts = mutableMapOf<String, Int>()

        for (slot in censusSnapshot.hamletSlots) {
            if (!slot.occupied) continue
            val profession = normalizeProfessionForTradeSimulation(slot.requiredProfession)
            val professionId = Registries.VILLAGER_PROFESSION.getId(profession).toString()
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
            for (output in mainTradeOutputsFor(village, professionId, profile)) {
                val stockKey = outputKey(professionId, output)
                val sellCapacity = count * output.amount
                village.sellStockRemainingByProfession[stockKey] = sellCapacity
                stockedItems += sellCapacity
            }

            for (want in profile.requestsForTier(village.tier, village, professionId)) {
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

    fun forceRestockTimers(world: ServerWorld, village: VillageData): Text {
        val clerk = getClerkTable(world, village, village.clerkPos)
            ?: return Text.literal("Could not fast-restock '${village.name}': clerk table is missing.")

        val censusSnapshot = ClerkCensusService.forceSyncVillage(world, village)
            ?: return Text.literal("Could not fast-restock '${village.name}': clerk table is missing.")

        val currentDay = world.time / TICKS_PER_DAY
        village.lastEconomyDay = currentDay - 1L
        village.priceModifierDay = -1L
        village.dailyPriceModifiers.clear()
        val changed = ClerkEconomyService.refreshVillageFromSnapshot(world, village, censusSnapshot)

        if (changed) {
            world.persistentStateManager.getOrCreate(VillageRegistry.TYPE, VillageRegistry.KEY).markDirty()
        }
        val lastProduction = clerk.getSnapshot(village)
        val nextTradeTicks = nextTradeProgressTicksRemaining(world, village)
        val nextProduceTicks = nextProduceProgressTicksRemaining(world, village)
        val nextNeedTicks = nextNeedProgressTicksRemaining(world, village)
        return if (changed) {
            Text.literal(
                "Fast-restocked '${village.name}' (timer advance applied): " +
                    "next trade in ${nextTradeTicks} tick(s), next produce in ${nextProduceTicks} tick(s), next wants in ${nextNeedTicks} tick(s)."
            )
        } else {
            Text.literal(
                "Fast-restocked '${village.name}', no stock changes were pending."
            )
        }
    }

    fun advanceRestockTimers(world: ServerWorld, village: VillageData, ticksToAdvance: Long): Text {
        val clerk = getClerkTable(world, village, village.clerkPos)
            ?: return Text.literal("Could not advance '${village.name}': clerk table is missing.")
        val snapshot = ClerkCensusService.forceSyncVillage(world, village)
            ?: return Text.literal("Could not advance '${village.name}': clerk table is missing.")

        if (ticksToAdvance <= 0L) {
            return Text.literal("Please provide a positive tick value to advance the timers.")
        }

        val currentTime = world.time
        if (village.lastEconomyTick < 0L) {
            village.lastEconomyTick = currentTime
        }
        if (village.lastProductionTick < 0L) {
            village.lastProductionTick = currentTime
        }
        if (village.lastNeedTick < 0L) {
            village.lastNeedTick = currentTime
        }

        val advance = ticksToAdvance.coerceAtLeast(1L)
        village.lastProductionTick -= advance
        village.lastNeedTick -= advance
        village.lastEconomyTick -= advance
        val artisanRefillTicks = village.artisanRefillTickByProfession.toMap()
        village.artisanRefillTickByProfession.clear()
        artisanRefillTicks.forEach { (stockKey, tick) ->
            village.artisanRefillTickByProfession[stockKey] = tick - advance
        }
        val changed = ClerkEconomyService.refreshVillageFromSnapshot(world, village, snapshot)

        if (changed) {
            world.persistentStateManager.getOrCreate(VillageRegistry.TYPE, VillageRegistry.KEY).markDirty()
        }

        val nextTradeTicks = nextTradeProgressTicksRemaining(world, village)
        val nextProduceTicks = nextProduceProgressTicksRemaining(world, village)
        val nextNeedTicks = nextNeedProgressTicksRemaining(world, village)
        return Text.literal(
            "Advanced '${village.name}' restock timers by $advance tick(s): " +
                "next trade in $nextTradeTicks tick(s), next produce in $nextProduceTicks tick(s), next wants in $nextNeedTicks tick(s)."
        )
    }

    fun executeTrade(
        world: ServerWorld,
        player: ServerPlayerEntity,
        clerkPos: BlockPos,
        direction: Int,
        professionId: String,
        requestedQuantity: Int,
        commissionInputStack: ItemStack? = null
    ): VillageTradeExecutionResult {
        val village = getVillage(world, clerkPos)
            ?: return VillageTradeExecutionResult(false, Text.literal("No village data found for this clerk."))
        val blockEntity = getClerkTable(world, village, clerkPos)
            ?: return VillageTradeExecutionResult(false, Text.literal("Clerk block is missing."))

        val censusSnapshot = blockEntity.getSnapshot(village)
        ClerkEconomyService.refreshVillageFromSnapshot(world, village, censusSnapshot)
        ensureDailyPriceModifiers(world, village)
        ensureVillageTreasury(world, village)

        val result = when (direction) {
            VillageTradeNetwork.TRADE_DIRECTION_BUY -> processBuyFromVillage(world, player, village, professionId, requestedQuantity, censusSnapshot)
            VillageTradeNetwork.TRADE_DIRECTION_SELL -> processSellToVillage(world, player, village, professionId, requestedQuantity, censusSnapshot)
            VillageTradeNetwork.TRADE_DIRECTION_JOB_BLOCK -> processBuyAuthorizedJobBlock(world, player, village, professionId)
            VillageTradeNetwork.TRADE_DIRECTION_RELOCATION_TOOL -> processBuyJobRelocationTool(world, player, village)
            VillageTradeNetwork.TRADE_DIRECTION_COMMISSION_ORDER -> {
                val occupiedProfessionIds = professionMarketCounts(censusSnapshot).occupiedByProfession.keys
                ArtisanCommissionService.order(world, player, village, professionId, occupiedProfessionIds, commissionInputStack, requestedQuantity)
            }
            VillageTradeNetwork.TRADE_DIRECTION_COMMISSION_CLAIM -> ArtisanCommissionService.claim(world, player, village, professionId)
            VillageTradeNetwork.TRADE_DIRECTION_BUILD_STRUCTURE -> processBuildStructure(world, player, village, professionId, requestedQuantity)
            VillageTradeNetwork.TRADE_DIRECTION_UPGRADE_HOUSE -> processUpgradeHouse(world, player, village, requestedQuantity)
            VillageTradeNetwork.TRADE_DIRECTION_RELOCATE_PLOT -> processRelocatePlot(world, player, village, requestedQuantity)
            else -> VillageTradeExecutionResult(false, Text.literal("Invalid trade action."))
        }

        val latestSnapshot = blockEntity.getSnapshot(village)
        ClerkEconomyService.refreshVillageFromSnapshot(world, village, latestSnapshot)
        val latest = buildSnapshot(world, village.clerkPos, latestSnapshot, player)
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
        val normalizedTradeProfessionId = professionIdNormalizedForTrade(tradeProfessionId)
        val profile = professionTradeProfile(normalizedTradeProfessionId)
            ?: return VillageTradeExecutionResult(false, Text.literal("Unknown profession trade."))

        val output = outputItemIdFromOutputKey(professionId)
            ?.let { itemId -> mainTradeOutputsFor(village, normalizedTradeProfessionId, profile).firstOrNull { it.keyItemId() == itemId } }
            ?: mainTradeOutputsFor(village, normalizedTradeProfessionId, profile).firstOrNull()
            ?: return VillageTradeExecutionResult(false, Text.literal("This profession has no produce to sell."))
        val stockKey = resolveSellStockKey(village, normalizedTradeProfessionId, output, professionId)
        val remaining = village.sellStockRemainingByProfession[stockKey]?.coerceAtLeast(0) ?: 0
        val professionCount = professionCountFor(censusSnapshot, normalizedTradeProfessionId)
        val stockCapacity = professionCount * output.amount
        val unitPrice = getProductPrice(village, output, remaining, stockCapacity, professionCount).currentPrice
        val exchange = tradeExchange(output, unitPrice)
        val availableTradeUnits = remaining / exchange.itemCount
        if (availableTradeUnits <= 0) {
            return VillageTradeExecutionResult(false, Text.literal("This trade is currently unavailable."))
        }

        val quantity = if (requestedQuantity == Int.MAX_VALUE) {
            availableTradeUnits
        } else {
            requestedQuantity.coerceAtLeast(1).coerceAtMost(availableTradeUnits)
        }
        val itemsBought = quantity * exchange.itemCount
        val emeraldsNeeded = exchange.emeralds * quantity

        val ledgerBalance = playerLedgerBalance(world, player)
        val physicalEmeralds = player.inventory.count(Items.EMERALD)
        if (ledgerBalance + physicalEmeralds < emeraldsNeeded) {
            return VillageTradeExecutionResult(false, Text.literal("You need $emeraldsNeeded emerald(s) for this trade."))
        }

        if (!hasInventorySpace(player, output.item, itemsBought)) {
            return VillageTradeExecutionResult(
                false,
                Text.literal("You need inventory space for ${itemsBought}x ${output.itemName}.")
            )
        }

        if (!spendPlayerEmeralds(world, player, emeraldsNeeded)) {
            return VillageTradeExecutionResult(false, Text.literal("Could not spend emeralds safely."))
        }

        val rewardStack = output.stack(world, itemsBought)
        if (!player.inventory.insertStack(rewardStack)) {
            depositPlayerEmeralds(world, player, emeraldsNeeded)
            return VillageTradeExecutionResult(false, Text.literal("Could not add items to inventory."))
        }

        val newRemaining = remaining - itemsBought
        village.sellStockRemainingByProfession[stockKey] = newRemaining
        addToVillageTreasury(village, emeraldsNeeded)

        val stockCapacityForCooldown = professionCountFor(censusSnapshot, normalizedTradeProfessionId) * output.amount
        if (isArtisanProfession(normalizedTradeProfessionId) && newRemaining < stockCapacityForCooldown) {
            ClerkEconomyService.noteArtisanStockSold(village, stockKey, world.time)
        }

        world.persistentStateManager.getOrCreate(VillageRegistry.TYPE, VillageRegistry.KEY).markDirty()

        return VillageTradeExecutionResult(
            true,
            Text.literal("You bought ${itemsBought}x ${output.itemName} for $emeraldsNeeded emerald(s).")
        )
    }

    private fun processBuildStructure(
        world: ServerWorld,
        player: ServerPlayerEntity,
        village: VillageData,
        structureType: String,
        plotIndex: Int
    ): VillageTradeExecutionResult {
        val normalizedType = structureType.lowercase(Locale.ROOT)
        if (plotIndex <= 0) {
            val marker = PlotPlacementTool.createStack(village, normalizedType)
            if (!player.inventory.insertStack(marker)) {
                return VillageTradeExecutionResult(false, Text.literal("You need room in your pack for a plot marker."))
            }
            return VillageTradeExecutionResult(true, Text.literal("Mark plot before building."))
        }
        val message = VillagePlotService.buyBuild(world, village, player, plotIndex, normalizedType)
        return VillageTradeExecutionResult(!message.style.color?.name.equals("red", ignoreCase = true), message)
    }

    private fun processRelocatePlot(
        world: ServerWorld,
        player: ServerPlayerEntity,
        village: VillageData,
        plotIndex: Int
    ): VillageTradeExecutionResult {
        val plot = village.plots.getOrNull(plotIndex - 1)
            ?: return VillageTradeExecutionResult(false, Text.literal("Choose a plot to move."))
        if (plot.isPending()) {
            return VillageTradeExecutionResult(false, Text.literal("Construction is underway."))
        }
        val emeraldsNeeded = VillagePlotService.relocationCost(plot)
        val ledgerBalance = playerLedgerBalance(world, player)
        val physicalEmeralds = player.inventory.count(Items.EMERALD)
        if (ledgerBalance + physicalEmeralds < emeraldsNeeded) {
            return VillageTradeExecutionResult(false, Text.literal("The builders ask $emeraldsNeeded emerald(s) to move that structure."))
        }
        if (!hasInventorySpace(player, ModItems.STAMP_TOOL, 1)) {
            return VillageTradeExecutionResult(false, Text.literal("You need room in your pack for a plot marker."))
        }
        if (!spendPlayerEmeralds(world, player, emeraldsNeeded)) {
            return VillageTradeExecutionResult(false, Text.literal("The clerk could not settle the emeralds."))
        }
        val markerType = when {
            plot.builtType == "generated" || plot.allowedType == "generated" || plot.structureId.startsWith("minecraft:village/") -> "generated"
            plot.allowedType == "castle" || plot.sizeX >= 25 -> "castle"
            else -> "house"
        }
        val marker = PlotPlacementTool.createStack(village, markerType, plotIndex)
        if (!player.inventory.insertStack(marker)) {
            depositPlayerEmeralds(world, player, emeraldsNeeded)
            return VillageTradeExecutionResult(false, Text.literal("You need room in your pack for a plot marker."))
        }
        if (emeraldsNeeded > 0) {
            addToVillageTreasury(village, emeraldsNeeded)
            world.persistentStateManager.getOrCreate(VillageRegistry.TYPE, VillageRegistry.KEY).markDirty()
        }
        val costText = if (emeraldsNeeded > 0) " Paid $emeraldsNeeded emeralds." else ""
        return VillageTradeExecutionResult(true, Text.literal("Use the marker to choose this plot's new site.$costText"))
    }

    private fun processUpgradeHouse(
        world: ServerWorld,
        player: ServerPlayerEntity,
        village: VillageData,
        plotIndex: Int
    ): VillageTradeExecutionResult {
        if (plotIndex <= 0) {
            return VillageTradeExecutionResult(false, Text.literal("Choose a house first."))
        }
        val message = VillagePlotService.upgradeHouse(world, village, player, plotIndex)
        return VillageTradeExecutionResult(!message.style.color?.name.equals("red", ignoreCase = true), message)
    }

    private fun processBuyAuthorizedJobBlock(
        world: ServerWorld,
        player: ServerPlayerEntity,
        village: VillageData,
        professionId: String
    ): VillageTradeExecutionResult {
        val offer = JobBlockMarket.definitionForProfession(professionId)
            ?: return VillageTradeExecutionResult(false, Text.literal("The clerk does not recognize that work permit."))
        val emeraldsNeeded = offer.emeraldCost
        val ledgerBalance = playerLedgerBalance(world, player)
        val physicalEmeralds = player.inventory.count(Items.EMERALD)
        if (ledgerBalance + physicalEmeralds < emeraldsNeeded) {
            return VillageTradeExecutionResult(false, Text.literal("The clerk asks $emeraldsNeeded emerald(s) for that work permit."))
        }
        if (!hasInventorySpace(player, offer.item, 1)) {
            return VillageTradeExecutionResult(false, Text.literal("You need room in your pack for ${ItemStack(offer.item).name.string}."))
        }
        if (!spendPlayerEmeralds(world, player, emeraldsNeeded)) {
            return VillageTradeExecutionResult(false, Text.literal("The clerk could not settle the emeralds."))
        }

        val stack = ProfessionBlockProtection.authorizeJobBlockStack(ItemStack(offer.item), offer.professionId)
        if (!player.inventory.insertStack(stack)) {
            depositPlayerEmeralds(world, player, emeraldsNeeded)
            return VillageTradeExecutionResult(false, Text.literal("The clerk could not hand over the stamped station."))
        }

        addToVillageTreasury(village, emeraldsNeeded)
        world.persistentStateManager.getOrCreate(VillageRegistry.TYPE, VillageRegistry.KEY).markDirty()
        return VillageTradeExecutionResult(
            true,
            Text.literal("The clerk stamped a ${offer.professionName} station for $emeraldsNeeded emerald(s).")
        )
    }

    private fun processBuyJobRelocationTool(
        world: ServerWorld,
        player: ServerPlayerEntity,
        village: VillageData
    ): VillageTradeExecutionResult {
        val emeraldsNeeded = JobBlockRelocationTool.EMERALD_COST
        val ledgerBalance = playerLedgerBalance(world, player)
        val physicalEmeralds = player.inventory.count(Items.EMERALD)
        if (ledgerBalance + physicalEmeralds < emeraldsNeeded) {
            return VillageTradeExecutionResult(false, Text.literal("The clerk asks $emeraldsNeeded emerald(s) for a relocation seal."))
        }
        if (!hasInventorySpace(player, ModItems.STAMP_TOOL, 1)) {
            return VillageTradeExecutionResult(false, Text.literal("You need room in your pack for the relocation seal."))
        }
        if (!spendPlayerEmeralds(world, player, emeraldsNeeded)) {
            return VillageTradeExecutionResult(false, Text.literal("The clerk could not settle the emeralds."))
        }

        val stack = JobBlockRelocationTool.createStack(village)
        if (!player.inventory.insertStack(stack)) {
            depositPlayerEmeralds(world, player, emeraldsNeeded)
            return VillageTradeExecutionResult(false, Text.literal("The clerk could not hand over the relocation seal."))
        }

        addToVillageTreasury(village, emeraldsNeeded)
        world.persistentStateManager.getOrCreate(VillageRegistry.TYPE, VillageRegistry.KEY).markDirty()
        return VillageTradeExecutionResult(
            true,
            Text.literal("The clerk issued ${village.name}'s relocation seal for $emeraldsNeeded emerald(s).")
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
        if (professionId.startsWith(WANT_GROUP_KEY_PREFIX)) {
            return processSellGroupedWantToVillage(world, player, village, professionId, requestedQuantity, censusSnapshot)
        }
        if (professionId.startsWith(WANT_KEY_PREFIX)) {
            return processSellWantToVillage(world, player, village, professionId, requestedQuantity, censusSnapshot)
        }
        val profile = professionTradeProfile(professionId)
            ?: return VillageTradeExecutionResult(false, Text.literal("Unknown profession trade."))

        val request = profile.requestsForTier(village.tier, village, professionId).firstOrNull()
            ?: return VillageTradeExecutionResult(false, Text.literal("This profession has no request."))
        return processSellRequestToVillage(world, player, village, professionId, wantKey(professionId, request.item), request, requestedQuantity, censusSnapshot)
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
        val want = profile.requestsForTier(village.tier, village, professionId).firstOrNull { wantKey(professionId, it.item) == rawWantKey }
            ?: return VillageTradeExecutionResult(false, Text.literal("This want is not unlocked yet."))
        if (villageHasLocalSellStock(village, itemIdOf(want.item))) {
            return VillageTradeExecutionResult(false, Text.literal("This village already supplies that item locally."))
        }

        return processSellRequestToVillage(world, player, village, professionId, rawWantKey, want, requestedQuantity, censusSnapshot)
    }

    private fun processSellGroupedWantToVillage(
        world: ServerWorld,
        player: ServerPlayerEntity,
        village: VillageData,
        groupedWantKey: String,
        requestedQuantity: Int,
        censusSnapshot: VillageEconomyState
    ): VillageTradeExecutionResult {
        val itemId = itemIdFromWantGroupKey(groupedWantKey)
            ?: return VillageTradeExecutionResult(false, Text.literal("Unknown village want."))
        if (villageHasLocalSellStock(village, itemId)) {
            return VillageTradeExecutionResult(false, Text.literal("This village already supplies that item locally."))
        }
        val marketCounts = professionMarketCounts(censusSnapshot)
        val collection = collectWantOfferComponents(village, marketCounts.totalByProfession, marketCounts.occupiedByProfession)
        val candidates = collection.components
            .filter { itemIdOf(it.want.item) == itemId && it.remainingDaily > 0 }
            .sortedWith(compareByDescending<WantOfferComponent> { it.exchange.valuePerItemScaled() }.thenBy { it.professionName })
        if (candidates.isEmpty()) {
            return VillageTradeExecutionResult(false, Text.literal("This request has cooled down until the next daily restock."))
        }

        val item = candidates.first().want.item
        var availableItems = player.inventory.count(item)
        val totalRemaining = candidates.sumOf { it.remainingDaily / it.exchange.itemCount }
        val quantity = if (requestedQuantity == Int.MAX_VALUE) {
            candidates.sumOf { candidate ->
                val candidateUnits = minOf(candidate.remainingDaily / candidate.exchange.itemCount, availableItems / candidate.exchange.itemCount)
                availableItems -= candidateUnits * candidate.exchange.itemCount
                candidateUnits
            }
        } else {
            requestedQuantity.coerceAtLeast(1).coerceAtMost(totalRemaining)
        }
        if (quantity <= 0) {
            return VillageTradeExecutionResult(false, Text.literal("You need ${candidates.first().exchange.itemCount}x ${candidates.first().want.itemName} to complete this request."))
        }

        var remainingToAssign = quantity
        var remainingAvailableItems = player.inventory.count(item)
        val assignments = mutableListOf<Pair<WantOfferComponent, Int>>()
        for (candidate in candidates) {
            if (remainingToAssign <= 0) break
            val assigned = minOf(
                remainingToAssign,
                candidate.remainingDaily / candidate.exchange.itemCount,
                remainingAvailableItems / candidate.exchange.itemCount
            )
            if (assigned > 0) {
                assignments.add(candidate to assigned)
                remainingToAssign -= assigned
                remainingAvailableItems -= assigned * candidate.exchange.itemCount
            }
        }
        val itemsSold = assignments.sumOf { (candidate, units) -> candidate.exchange.itemCount * units }
        if (itemsSold <= 0) {
            return VillageTradeExecutionResult(false, Text.literal("You need ${candidates.first().exchange.itemCount}x ${candidates.first().want.itemName} to complete this request."))
        }
        val emeraldReward = assignments.sumOf { (candidate, amount) ->
            tradeRewardFor(candidate.exchange, candidate.baseUnitPrice, candidate.alreadyTraded, amount)
        }
        if (!villageCanPay(village, emeraldReward)) {
            return VillageTradeExecutionResult(false, Text.literal("${village.name} cannot afford that purchase yet. Treasury replenishes every half day."))
        }
        val removed = player.inventory.remove({ stack -> stack.isOf(item) }, itemsSold, player.inventory)
        if (removed < itemsSold) {
            player.inventory.offerOrDrop(ItemStack(item, removed))
            return VillageTradeExecutionResult(false, Text.literal("Could not remove requested items safely."))
        }

        depositPlayerEmeralds(world, player, emeraldReward)
        payFromVillageTreasury(village, emeraldReward)

        var requestedExperience = 0
        for ((candidate, amount) in assignments) {
            val itemAmount = amount * candidate.exchange.itemCount
            village.wantTradeCountByProfession[candidate.wantKey] = candidate.alreadyTraded + itemAmount
            requestedExperience += itemAmount * candidate.want.experienceReward()
        }
        val oldTier = village.tier
        val experienceGained = VillageProgression.addTradeExperience(village, requestedExperience)
        VillageLevelUpCelebration.play(world, village, oldTier)
        VillagePlotService.ensurePlots(world, village)
        world.persistentStateManager.getOrCreate(VillageRegistry.TYPE, VillageRegistry.KEY).markDirty()

        val tierText = if (village.tier != oldTier) " ${village.name} is now a ${village.tier.name.lowercase(Locale.ROOT)}!" else ""
        val xpText = if (experienceGained > 0) "+$experienceGained village XP" else "trade XP is capped until buildings improve"
        return VillageTradeExecutionResult(true, Text.literal("You fulfilled ${itemsSold}x ${candidates.first().want.itemName} for $emeraldReward emerald(s) and $xpText.$tierText"))
    }

    private fun processSellRequestToVillage(
        world: ServerWorld,
        player: ServerPlayerEntity,
        village: VillageData,
        professionId: String,
        requestKey: String,
        request: Commodity,
        requestedQuantity: Int,
        censusSnapshot: VillageEconomyState
    ): VillageTradeExecutionResult {
        val availableItems = player.inventory.count(request.item)
        val demandCapacity = (professionCountFor(censusSnapshot, professionId) * request.amount).coerceAtLeast(1)
        val baseUnitPrice = applyVillageBuyerMarkdown(
            request = request,
            price = applyProfessionDemandPrice(
                price = getNeedPrice(village, request, demandCapacity, demandCapacity).currentPrice,
                professionCount = professionCountFor(censusSnapshot, professionId)
            )
        )
        val exchange = tradeExchange(request, wantPriceWithDiminishingReturns(baseUnitPrice, village.wantTradeCountByProfession[requestKey] ?: 0))
        val alreadyTraded = village.wantTradeCountByProfession[requestKey] ?: 0
        val remainingDaily = wantDailyTradeLimit(baseUnitPrice, request) - alreadyTraded
        val availableTradeUnits = remainingDaily / exchange.itemCount
        if (availableTradeUnits <= 0) {
            return VillageTradeExecutionResult(false, Text.literal("This request has cooled down until the next daily restock."))
        }

        val quantity = if (requestedQuantity == Int.MAX_VALUE) {
            (availableItems / exchange.itemCount).coerceAtMost(availableTradeUnits)
        } else {
            requestedQuantity.coerceAtLeast(1).coerceAtMost(availableTradeUnits)
        }
        val itemsSold = quantity * exchange.itemCount
        if (quantity <= 0 || availableItems < itemsSold) {
            return VillageTradeExecutionResult(false, Text.literal("You need ${exchange.itemCount}x ${request.itemName} to complete this request."))
        }

        val emeraldReward = tradeRewardFor(exchange, baseUnitPrice, alreadyTraded, quantity)
        if (!villageCanPay(village, emeraldReward)) {
            return VillageTradeExecutionResult(false, Text.literal("${village.name} cannot afford that purchase yet. Treasury replenishes every half day."))
        }
        val removed = player.inventory.remove({ stack -> stack.isOf(request.item) }, itemsSold, player.inventory)
        if (removed < itemsSold) {
            player.inventory.offerOrDrop(ItemStack(request.item, removed))
            return VillageTradeExecutionResult(false, Text.literal("Could not remove requested items safely."))
        }

        depositPlayerEmeralds(world, player, emeraldReward)
        payFromVillageTreasury(village, emeraldReward)

        village.wantTradeCountByProfession[requestKey] = alreadyTraded + itemsSold
        val oldTier = village.tier
        val requestedExperience = itemsSold * request.experienceReward()
        val experienceGained = VillageProgression.addTradeExperience(village, requestedExperience)
        VillageLevelUpCelebration.play(world, village, oldTier)
        VillagePlotService.ensurePlots(world, village)
        world.persistentStateManager.getOrCreate(VillageRegistry.TYPE, VillageRegistry.KEY).markDirty()

        val tierText = if (village.tier != oldTier) " ${village.name} is now a ${village.tier.name.lowercase(Locale.ROOT)}!" else ""
        val xpText = if (experienceGained > 0) "+$experienceGained village XP" else "trade XP is capped until buildings improve"
        return VillageTradeExecutionResult(true, Text.literal("You fulfilled ${itemsSold}x ${request.itemName} for $emeraldReward emerald(s) and $xpText.$tierText"))
    }

    private fun buildSnapshot(
        world: ServerWorld,
        clerkPos: BlockPos,
        censusSnapshot: VillageEconomyState,
        player: ServerPlayerEntity? = null
    ): VillageMarketSnapshot {
        val village = getVillage(world, clerkPos) ?: return VillageMarketSnapshot(
            emptyList(),
            emptyList(),
            JobBlockMarket.clerkOffers(),
            emptyList(),
            emptyList(),
            emptyList(),
            0,
            0,
            world.time,
            world.time,
            world.time,
            "Hamlet",
            0,
            0,
            300,
            VillageInfoData.EMPTY
        )
        ensureDailyPriceModifiers(world, village)
        ensureVillageTreasury(world, village)
        VillagePlotService.ensurePlots(world, village)

        val marketCounts = professionMarketCounts(censusSnapshot)
        val professionCounts = marketCounts.totalByProfession
        val occupiedProfessionCounts = marketCounts.occupiedByProfession

        var changedStock = false
        val sellOffers = buildList {
            for ((professionId, count) in professionCounts) {
                val profile = professionTradeProfile(professionId) ?: continue
                val profession = professionById(professionId) ?: continue
                val occupiedCount = occupiedProfessionCounts[professionId] ?: 0
                for (output in mainTradeOutputsFor(village, professionId, profile)) {
                    val stockKey = outputKey(professionId, output)
                    val maxDaily = count * output.amount
                    if (maxDaily <= 0) continue
                    val remaining = getSellStockForProfession(village, professionId, output)
                    if (!village.sellStockRemainingByProfession.containsKey(stockKey)) {
                        village.sellStockRemainingByProfession[stockKey] = 0
                        changedStock = true
                    }
                    val stockCapacity = maxOf(maxDaily, remaining)
                    val price = getProductPrice(village, output, remaining, stockCapacity, count)
                    val exchange = tradeExchange(output, price.currentPrice)
                    add(
                        SellOffer(
                            item = output.stack(world),
                            displayName = output.displayLabel(),
                            emeraldCost = exchange.emeralds,
                            tradeItemCount = exchange.itemCount,
                            producedToday = maxDaily,
                            remainingToday = remaining,
                            maxDailyProduction = stockCapacity,
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
        }.sortedWith(
            compareByDescending<SellOffer> { it.remainingToday }
                .thenByDescending { it.stockPercent }
                .thenBy { it.displayName }
                .thenBy { it.producerProfessionId }
        )

        val wantOfferCollection = collectWantOfferComponents(village, professionCounts, occupiedProfessionCounts)
        val changedDemand = wantOfferCollection.changedDemand
        val buyOffers = buildList {
            for ((_, components) in wantOfferCollection.components.groupBy { itemIdOf(it.want.item) }) {
                if (villageHasLocalSellStock(village, itemIdOf(components.first().want.item))) continue
                val sortedComponents = components.sortedWith(
                    compareByDescending<WantOfferComponent> { it.exchange.valuePerItemScaled() }.thenBy { it.professionName }
                )
                val representative = sortedComponents.first()
                val professionNames = sortedComponents.map { it.professionName }.distinct().joinToString(", ")
                add(
                    BuyOffer(
                        item = ItemStack(representative.want.item, 1),
                        displayName = representative.want.displayLabel(),
                        emeraldReward = representative.exchange.emeralds,
                        tradeItemCount = representative.exchange.itemCount,
                        neededToday = sortedComponents.sumOf { it.dailyLimit },
                        remainingNeed = sortedComponents.sumOf { it.remainingDaily },
                        maxDailyNeed = sortedComponents.sumOf { it.dailyLimit },
                        baseValue = representative.price.baseValue,
                        minPrice = sortedComponents.minOf { it.price.minPrice },
                        maxPrice = sortedComponents.maxOf { it.price.maxPrice },
                        demandPercent = sortedComponents.maxOf { it.price.availabilityPercent },
                        demandModifier = sortedComponents.maxOf { it.price.availabilityModifier },
                        demandLabel = "Combined Demand",
                        dailyModifier = representative.price.dailyModifier,
                        grantsExperience = true,
                        experienceReward = sortedComponents.maxOf { it.want.experienceReward() },
                        requesterCount = sortedComponents.sumOf { it.requesterCount },
                        requesterProfessionId = wantGroupKey(representative.want.item),
                        requesterProfession = professionNames
                    )
                )
            }
        }.sortedWith(
            compareByDescending<BuyOffer> { it.remainingNeed }
                .thenByDescending { it.demandPercent }
                .thenBy { it.displayName }
                .thenBy { it.requesterProfessionId }
        )

        if (changedStock || changedDemand) {
            world.persistentStateManager.getOrCreate(VillageRegistry.TYPE, VillageRegistry.KEY).markDirty()
        }

        val playerEmeralds = playerLedgerBalance(world, player)
        return VillageMarketSnapshot(
            sellOffers = sellOffers,
            buyOffers = buyOffers,
            jobBlockOffers = JobBlockMarket.clerkOffers(),
            commissionOffers = ArtisanCommissionService.offersFor(village, occupiedProfessionCounts.keys, world.time),
            commissionOrders = ArtisanCommissionService.ordersFor(world, village, player),
            buildingPlots = VillagePlotService.buildingPlotsFor(world, village),
            playerEmeralds = playerEmeralds,
            villageWealth = village.treasuryEmeralds.coerceAtLeast(0),
            refreshTicks = nextTradeProgressTicksRemaining(world, village),
            produceRefreshTicks = nextProduceProgressTicksRemaining(world, village),
            needRefreshTicks = nextNeedProgressTicksRemaining(world, village),
            tierName = village.tier.displayName(),
            villageExperience = village.experience,
            currentTierMinExperience = tierMinExperience(village.tier),
            nextTierExperience = nextTierExperience(village.tier),
            info = buildVillageInfo(village, censusSnapshot)
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
        val lastProductionTick = village?.lastProductionTick?.takeIf { it >= 0L } ?: return 1L
        val elapsed = (world.time - lastProductionTick).coerceAtLeast(0L)
        return (PEASANT_PRODUCTION_INTERVAL_TICKS - (elapsed % PEASANT_PRODUCTION_INTERVAL_TICKS)).coerceAtLeast(1L)
    }

    private fun nextNeedProgressTicksRemaining(world: ServerWorld, village: VillageData?): Long {
        val lastNeedTick = village?.lastNeedTick?.takeIf { it >= 0L } ?: return 1L
        val elapsed = (world.time - lastNeedTick).coerceAtLeast(0L)
        val elapsedThisCycle = elapsed % TICKS_PER_DAY
        return (TICKS_PER_DAY - elapsedThisCycle).coerceAtLeast(1L)
    }

    private fun playerLedgerBalance(world: ServerWorld, player: ServerPlayerEntity?): Int {
        return player?.let { PlayerEmeraldLedger.get(world).balance(it.uuid) } ?: 0
    }

    private fun villageCanPay(village: VillageData, emeraldReward: Int): Boolean {
        return emeraldReward <= 0 || village.treasuryEmeralds.coerceAtLeast(0) >= emeraldReward
    }

    private fun payFromVillageTreasury(village: VillageData, emeraldReward: Int) {
        if (emeraldReward > 0) {
            village.treasuryEmeralds = (village.treasuryEmeralds - emeraldReward).coerceAtLeast(0)
        }
    }

    private fun addToVillageTreasury(village: VillageData, emeraldsPaid: Int) {
        if (emeraldsPaid > 0) {
            village.treasuryEmeralds = (village.treasuryEmeralds + emeraldsPaid).coerceAtMost(villageTreasuryMax(village.tier))
        }
    }

    private fun ensureVillageTreasury(world: ServerWorld, village: VillageData) {
        val maxTreasury = villageTreasuryMax(village.tier)
        var changed = false

        if (village.treasuryEmeralds < 0) {
            village.treasuryEmeralds = maxTreasury
            village.lastTreasuryReplenishTick = world.time
            changed = true
        }
        if (village.treasuryEmeralds > maxTreasury) {
            village.treasuryEmeralds = maxTreasury
            changed = true
        }
        if (village.lastTreasuryReplenishTick < 0L) {
            village.lastTreasuryReplenishTick = world.time
            changed = true
        }

        val lastTick = village.lastTreasuryReplenishTick
        val elapsed = (world.time - lastTick).coerceAtLeast(0L)
        val cycles = elapsed / TREASURY_REPLENISH_INTERVAL_TICKS
        if (cycles > 0) {
            val replenishAmount = (villageTreasuryReplenishAmount(village.tier).toLong() * cycles)
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt()
            village.treasuryEmeralds = (village.treasuryEmeralds + replenishAmount).coerceAtMost(maxTreasury)
            village.lastTreasuryReplenishTick = lastTick + cycles * TREASURY_REPLENISH_INTERVAL_TICKS
            changed = true
        }

        if (changed) {
            world.persistentStateManager.getOrCreate(VillageRegistry.TYPE, VillageRegistry.KEY).markDirty()
        }
    }

    private fun villageTreasuryMax(tier: VillageTier): Int {
        return when (tier) {
            VillageTier.HAMLET -> 128
            VillageTier.SETTLEMENT -> 320
            VillageTier.VILLAGE -> 640
            VillageTier.TOWN -> 1280
            VillageTier.CITY -> 2560
        }
    }

    private fun villageTreasuryReplenishAmount(tier: VillageTier): Int {
        return when (tier) {
            VillageTier.HAMLET -> 32
            VillageTier.SETTLEMENT -> 80
            VillageTier.VILLAGE -> 160
            VillageTier.TOWN -> 320
            VillageTier.CITY -> 640
        }
    }

    private fun depositPlayerEmeralds(world: ServerWorld, player: ServerPlayerEntity, amount: Int) {
        PlayerEmeraldLedger.get(world).deposit(player.uuid, amount)
    }

    private fun spendPlayerEmeralds(world: ServerWorld, player: ServerPlayerEntity, amount: Int): Boolean {
        if (amount <= 0) return true
        val ledger = PlayerEmeraldLedger.get(world)
        val ledgerSpent = minOf(ledger.balance(player.uuid), amount)
        val physicalNeeded = amount - ledgerSpent
        if (player.inventory.count(Items.EMERALD) < physicalNeeded) return false

        if (physicalNeeded > 0) {
            val removed = player.inventory.remove({ stack -> stack.isOf(Items.EMERALD) }, physicalNeeded, player.inventory)
            if (removed < physicalNeeded) {
                if (removed > 0) player.inventory.insertStack(ItemStack(Items.EMERALD, removed))
                return false
            }
        }

        if (ledgerSpent > 0 && !ledger.withdraw(player.uuid, ledgerSpent)) {
            if (physicalNeeded > 0) player.inventory.insertStack(ItemStack(Items.EMERALD, physicalNeeded))
            return false
        }

        return true
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

    private fun professionMarketCounts(censusSnapshot: VillageEconomyState): ProfessionMarketCounts {
        val professionCounts = mutableMapOf<String, Int>()
        val occupiedProfessionCounts = mutableMapOf<String, Int>()
        for (slot in censusSnapshot.hamletSlots) {
            if (!slot.occupied) continue
            val normalized = normalizeProfessionForTradeSimulation(slot.requiredProfession)
            val professionId = Registries.VILLAGER_PROFESSION.getId(normalized).toString()
            professionCounts[professionId] = (professionCounts[professionId] ?: 0) + 1
            occupiedProfessionCounts[professionId] = (occupiedProfessionCounts[professionId] ?: 0) + 1
        }
        return ProfessionMarketCounts(professionCounts, occupiedProfessionCounts)
    }

    private fun buildVillageInfo(village: VillageData, censusSnapshot: VillageEconomyState?): VillageInfoData {
        val slotLines = censusSnapshot?.hamletSlots
            ?.groupingBy { slot -> professionDisplayName(slot.requiredProfession) to slot.occupied }
            ?.eachCount()
            ?.toList()
            ?.sortedWith(compareBy<Pair<Pair<String, Boolean>, Int>> { it.first.first }.thenBy { if (it.first.second) 0 else 1 })
            ?.map { (slotState, count) ->
                val status = if (slotState.second) "filled" else "open"
                "$count ${slotState.first} $status"
            }
            ?: emptyList()

        val professionLines = censusSnapshot
            ?.sortedProfessionCounts()
            ?.map { (profession, count) -> "$count ${professionDisplayName(profession)}" }
            ?: emptyList()

        val occupiedSlots = censusSnapshot?.hamletSlots?.count { it.occupied } ?: 0
        val totalSlots = censusSnapshot?.hamletSlots?.size ?: 0

        return VillageInfoData(
            tierName = village.tier.displayName(),
            population = censusSnapshot?.population ?: 0,
            activeVillagers = censusSnapshot?.activeCount ?: 0,
            missingVillagers = censusSnapshot?.temporarilyMissingCount ?: 0,
            deceasedVillagers = censusSnapshot?.deceasedCount ?: 0,
            occupiedSlots = occupiedSlots,
            totalSlots = totalSlots,
            storageUsed = village.sellStockRemainingByProfession.values.sumOf { it.coerceAtLeast(0) },
            storageCapacity = villageStorageCapacity(village.tier),
            professionLines = professionLines,
            slotLines = slotLines
        )
    }

    private fun collectWantOfferComponents(
        village: VillageData,
        professionCounts: Map<String, Int>,
        occupiedProfessionCounts: Map<String, Int>
    ): WantOfferCollection {
        var changedDemand = false
        val components = buildList {
            for ((professionId, count) in professionCounts) {
                val profile = professionTradeProfile(professionId) ?: continue
                val profession = professionById(professionId) ?: continue
                val occupiedCount = occupiedProfessionCounts[professionId] ?: 0
                for (want in profile.requestsForTier(village.tier, village, professionId)) {
                    val wantKey = wantKey(professionId, want.item)
                    val wantCapacity = count * want.amount
                    if (!village.wantDemandTotalByProfession.containsKey(wantKey)) {
                        village.wantDemandTotalByProfession[wantKey] = wantCapacity
                        changedDemand = true
                    }
                    val wantBasePrice = getNeedPrice(village, want, wantCapacity, wantCapacity)
                    val currentWantPrice = applyVillageBuyerMarkdown(
                        request = want,
                        price = applyProfessionDemandPrice(
                            price = wantBasePrice.currentPrice,
                            professionCount = count
                        )
                    )
                    val alreadyTraded = village.wantTradeCountByProfession[wantKey] ?: 0
                    val dailyLimit = wantDailyTradeLimit(currentWantPrice, want)
                    val remainingDaily = (dailyLimit - alreadyTraded).coerceAtLeast(0)
                    add(
                        WantOfferComponent(
                            professionName = professionDisplayName(profession),
                            wantKey = wantKey,
                            want = want,
                            price = wantBasePrice,
                            baseUnitPrice = currentWantPrice,
                            currentUnitPrice = wantPriceWithDiminishingReturns(currentWantPrice, alreadyTraded),
                            exchange = tradeExchange(want, wantPriceWithDiminishingReturns(currentWantPrice, alreadyTraded)),
                            alreadyTraded = alreadyTraded,
                            dailyLimit = dailyLimit,
                            remainingDaily = remainingDaily,
                            requesterCount = occupiedCount.takeIf { it > 0 } ?: count
                        )
                    )
                }
            }
        }
        return WantOfferCollection(components, changedDemand)
    }

    private fun getProductPrice(
        village: VillageData,
        item: Commodity,
        stockRemaining: Int,
        stockCapacity: Int,
        professionCount: Int
    ): PriceBreakdown {
        val percent = percentOf(stockRemaining, stockCapacity)
        val modifier = when {
            percent >= 80 -> ModifierBreakdown("Full Stock", -1)
            percent >= 50 -> ModifierBreakdown("Stable Stock", 0)
            percent >= 20 -> ModifierBreakdown("Low Stock", 1)
            else -> ModifierBreakdown("Critical Stock", 2)
        }
        val price = priceBreakdown(village, item, percent, modifier)
        return price.copy(currentPrice = applyProfessionSupplyDiscount(price.currentPrice, professionCount))
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

    private fun wantDailyTradeLimit(basePrice: Int, request: Commodity): Int {
        request.dailyTurnInLimit?.let { return it.coerceAtLeast(1) }
        if (request.isNonStackable()) return NON_STACKABLE_WANT_TURN_IN_LIMIT
        val rawLimit = ((basePrice - 1).coerceAtLeast(0) * WANT_DIMINISHING_RETURN_BUCKET_SIZE) + WANT_DAILY_FLOOR_TRADE_LIMIT
        return ((rawLimit + WANT_DAILY_LIMIT_DIVISOR - 1) / WANT_DAILY_LIMIT_DIVISOR).coerceAtLeast(1)
    }

    private fun wantTradeReward(basePrice: Int, alreadyTraded: Int, quantity: Int): Int {
        var reward = 0
        repeat(quantity.coerceAtLeast(0)) { offset ->
            reward += wantPriceWithDiminishingReturns(basePrice, alreadyTraded + offset)
        }
        return reward
    }

    private fun tradeExchange(item: Commodity, currentPrice: Int): TradeExchange {
        if (item.isNonStackable() || currentPrice >= 4) {
            return TradeExchange(emeralds = currentPrice.coerceAtLeast(1), itemCount = 1)
        }
        val itemCount = when {
            currentPrice <= 1 -> 8
            currentPrice == 2 -> 4
            else -> 2
        }.coerceAtMost(ItemStack(item.item).maxCount)
        return TradeExchange(emeralds = 1, itemCount = itemCount.coerceAtLeast(1))
    }

    private fun tradeRewardFor(exchange: TradeExchange, baseUnitPrice: Int, alreadyTraded: Int, tradeUnits: Int): Int {
        if (exchange.itemCount > 1) return stackableWantTradeReward(baseUnitPrice, alreadyTraded, tradeUnits * exchange.itemCount)
        return wantTradeReward(baseUnitPrice, alreadyTraded, tradeUnits)
    }

    private fun stackableWantTradeReward(baseUnitPrice: Int, alreadyTraded: Int, itemsSold: Int): Int {
        var reward = 0
        var remainingItems = itemsSold.coerceAtLeast(0)
        var tradedItems = alreadyTraded.coerceAtLeast(0)

        while (remainingItems > 0) {
            val currentPrice = wantPriceWithDiminishingReturns(baseUnitPrice, tradedItems)
            val itemCount = when {
                currentPrice <= 1 -> 8
                currentPrice == 2 -> 4
                else -> 2
            }
            if (remainingItems < itemCount) break
            reward += 1
            remainingItems -= itemCount
            tradedItems += itemCount
        }

        return reward
    }

    private fun applyVillageBuyerMarkdown(request: Commodity, price: Int): Int {
        if (request.isNonStackable()) return price.coerceAtLeast(1)
        return ((price.coerceAtLeast(1) * STACKABLE_WANT_BUY_PRICE_PERCENT) + 99) / 100
    }

    private fun applyProfessionSupplyDiscount(price: Int, professionCount: Int): Int {
        val discountPercent = ((effectiveProfessionPriceCount(professionCount) - 1) * PROFESSION_SUPPLY_PRICE_DISCOUNT_PERCENT_PER_EXTRA)
            .coerceAtMost(PROFESSION_SUPPLY_PRICE_DISCOUNT_PERCENT_MAX)
        return ((price * (100 - discountPercent)) + 99) / 100
    }

    private fun applyProfessionDemandPrice(price: Int, professionCount: Int): Int {
        val bonusPercent = ((effectiveProfessionPriceCount(professionCount) - 1) * PROFESSION_DEMAND_PRICE_BONUS_PERCENT_PER_EXTRA)
            .coerceAtMost(PROFESSION_DEMAND_PRICE_BONUS_PERCENT_MAX)
        return ((price * (100 + bonusPercent)) + 99) / 100
    }

    private fun effectiveProfessionPriceCount(professionCount: Int): Int {
        return professionCount.coerceIn(1, PROFESSION_PRICE_EFFECTIVE_COUNT_CAP)
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

    private fun isArtisanProfession(professionId: String): Boolean {
        return professionId.substringAfterLast(":") in setOf(
            "librarian",
            "cleric",
            "toolsmith",
            "weaponsmith",
            "armorer",
            "cartographer"
        )
    }

    private fun mainTradeOutputsFor(
        village: VillageData,
        professionId: String,
        profile: ProfessionTradeProfile
    ): List<Commodity> {
        val outputs = profile.outputsForTier(village.tier, village, professionId)
        if (!isArtisanProfession(professionId)) return outputs
        return outputs.filter { isMainTradeArtisanOutput(professionId, it) }
    }

    private fun isMainTradeArtisanOutput(professionId: String, commodity: Commodity): Boolean {
        if (commodity.enchantmentId != null || commodity.potionId != null) return false
        if (commodity.variantId != null) return false
        val item = commodity.item
        return when (professionId.substringAfterLast(":")) {
            "toolsmith" -> item in MAIN_TRADE_TOOLSMITH_OUTPUTS
            "weaponsmith" -> item in MAIN_TRADE_WEAPONSMITH_OUTPUTS
            "armorer" -> item in MAIN_TRADE_ARMORER_OUTPUTS
            "cleric" -> item in MAIN_TRADE_CLERIC_OUTPUTS
            "librarian" -> item in MAIN_TRADE_LIBRARIAN_OUTPUTS
            "cartographer" -> item in MAIN_TRADE_CARTOGRAPHER_OUTPUTS
            else -> true
        }
    }

    private fun professionCountFor(censusSnapshot: VillageEconomyState, professionId: String): Int {
        return censusSnapshot.hamletSlots.count { slot ->
            slot.occupied && Registries.VILLAGER_PROFESSION.getId(
                normalizeProfessionForTradeSimulation(slot.requiredProfession)
            ).toString() == professionId
        }
    }

    private fun professionIdNormalizedForTrade(professionId: String): String {
        val profession = professionById(professionId) ?: return professionId
        return Registries.VILLAGER_PROFESSION.getId(normalizeProfessionForTradeSimulation(profession)).toString()
    }

    private fun legacyProfessionAliases(professionId: String): Set<String> {
        return when (professionId) {
            else -> setOf(professionId)
        }
    }

    private fun getSellStockForProfession(
        village: VillageData,
        professionId: String,
        output: Commodity
    ): Int {
        val normalizedKey = outputKey(professionId, output)
        village.sellStockRemainingByProfession[normalizedKey]?.let { return it }

        legacyProfessionAliases(professionId).drop(1).forEach { legacyProfessionId ->
            val legacyKey = outputKey(legacyProfessionId, output)
            val legacyStock = village.sellStockRemainingByProfession[legacyKey]
            if (legacyStock != null) {
                village.sellStockRemainingByProfession[normalizedKey] = legacyStock
                village.sellStockRemainingByProfession.remove(legacyKey)
                val legacyTick = village.artisanRefillTickByProfession[legacyKey]
                if (legacyTick != null) {
                    village.artisanRefillTickByProfession.remove(legacyKey)
                    village.artisanRefillTickByProfession[normalizedKey] = legacyTick
                }
                return legacyStock
            }
        }

        return 0
    }

    private fun resolveSellStockKey(
        village: VillageData,
        professionId: String,
        output: Commodity,
        originalProfessionId: String
    ): String {
        val normalizedKey = outputKey(professionId, output)
        if (village.sellStockRemainingByProfession.containsKey(normalizedKey)) return normalizedKey

        val legacyKey = outputKey(originalProfessionId, output)
        val legacyStock = village.sellStockRemainingByProfession[legacyKey]
        if (professionId != originalProfessionId && legacyStock != null) {
            village.sellStockRemainingByProfession[normalizedKey] = legacyStock
            village.sellStockRemainingByProfession.remove(legacyKey)
            val legacyTick = village.artisanRefillTickByProfession[legacyKey]
            if (legacyTick != null) {
                village.artisanRefillTickByProfession.remove(legacyKey)
                village.artisanRefillTickByProfession[normalizedKey] = legacyTick
            }
        }
        return normalizedKey
    }

    private fun economyItemIds(): Set<String> {
        return listOf(
            "minecraft:farmer",
            "minecraft:shepherd",
            "minecraft:mason",
            "minecraft:fletcher",
            "minecraft:butcher",
            "minecraft:leatherworker",
            "minecraft:fisherman",
            "minecraft:toolsmith",
            "minecraft:weaponsmith",
            "minecraft:armorer",
            "minecraft:cleric",
            "minecraft:librarian",
            "minecraft:cartographer"
        ).mapNotNull { professionTradeProfileByProfession(it) }
            .flatMap { it.allCommodities().map { commodity -> commodity.item } }
            .map { itemIdOf(it) }
            .toSet()
    }

    private fun wantKey(professionId: String, item: Item): String = "$WANT_KEY_PREFIX$professionId|${itemIdOf(item)}"
    private fun wantGroupKey(item: Item): String = "$WANT_GROUP_KEY_PREFIX${itemIdOf(item)}"
    private fun outputKey(professionId: String, item: Item): String = "$OUTPUT_KEY_PREFIX$professionId|${itemIdOf(item)}"
    private fun outputKey(professionId: String, commodity: Commodity): String = "$OUTPUT_KEY_PREFIX$professionId|${commodity.keyItemId()}"

    private fun villageHasLocalSellStock(village: VillageData, itemId: String): Boolean {
        return village.sellStockRemainingByProfession.any { (stockKey, stock) ->
            stock > 0 && outputItemIdFromOutputKey(stockKey) == itemId
        }
    }

    private fun itemIdFromWantGroupKey(wantGroupKey: String): String? {
        if (!wantGroupKey.startsWith(WANT_GROUP_KEY_PREFIX)) return null
        return wantGroupKey.removePrefix(WANT_GROUP_KEY_PREFIX).takeIf { it.isNotBlank() }
    }

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
        VillageProgression.updateTier(village)
    }

    private fun tierMinExperience(tier: VillageTier): Int {
        return VillageProgression.tierMinExperience(tier)
    }

    private fun nextTierExperience(tier: VillageTier): Int {
        return VillageProgression.nextTierExperience(tier)
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

    private fun VillageTier.displayName(): String {
        return name.lowercase(Locale.ROOT).replaceFirstChar { it.uppercase() }
    }

    private fun itemIdOf(item: Item): String = Registries.ITEM.getId(item).toString()
    private fun itemById(itemId: String): Item = Registries.ITEM.get(Identifier.of("minecraft", itemId))

    private fun normalizeProfessionForTradeSimulation(profession: net.minecraft.village.VillagerProfession): net.minecraft.village.VillagerProfession {
        return profession
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
            "leatherworker" -> professionTradeProfileByProfession("minecraft:leatherworker")
            "fisherman" -> professionTradeProfileByProfession("minecraft:fisherman")
            "toolsmith" -> professionTradeProfileByProfession("minecraft:toolsmith")
            "weaponsmith" -> professionTradeProfileByProfession("minecraft:weaponsmith")
            "armorer" -> professionTradeProfileByProfession("minecraft:armorer")
            "cleric" -> professionTradeProfileByProfession("minecraft:cleric")
            "librarian" -> professionTradeProfileByProfession("minecraft:librarian")
            "cartographer" -> professionTradeProfileByProfession("minecraft:cartographer")
            else -> null
        }
    }

    private fun professionTradeProfileByProfession(professionId: String): ProfessionTradeProfile? {
        return when (professionId.substringAfterLast(":").lowercase(Locale.ROOT)) {
            "farmer" -> ProfessionTradeProfile(
                hamletOutputs = listOf(
                    Commodity(Items.WHEAT, 10, baseValue = 2, minPrice = 1, maxPrice = 3),
                    Commodity(Items.BREAD, 6, baseValue = 3, minPrice = 2, maxPrice = 5),
                    Commodity(Items.CARROT, 10, baseValue = 2, minPrice = 1, maxPrice = 4),
                    Commodity(Items.POTATO, 10, baseValue = 2, minPrice = 1, maxPrice = 4),
                    Commodity(Items.BEETROOT, 10, baseValue = 2, minPrice = 1, maxPrice = 4)
                ),
                need = Commodity(Items.IRON_HOE, 1, baseValue = 5, minPrice = 3, maxPrice = 8),
                extraNeeds = listOf(
                    Commodity(Items.BONE_MEAL, 1, baseValue = 2, minPrice = 1, maxPrice = 4),
                    Commodity(Items.COAL, 1, baseValue = 3, minPrice = 2, maxPrice = 5),
                    Commodity(Items.WATER_BUCKET, 1, baseValue = 6, minPrice = 4, maxPrice = 10)
                ),
                settlementOutputs = listOf(
                    Commodity(Items.PUMPKIN, 8, baseValue = 3, minPrice = 2, maxPrice = 5),
                    Commodity(itemById("melon"), 8, baseValue = 3, minPrice = 2, maxPrice = 5)
                ),
                villageOutputs = listOf(
                    Commodity(Items.COOKIE, 12, baseValue = 3, minPrice = 2, maxPrice = 5),
                    Commodity(Items.PUMPKIN_PIE, 4, baseValue = 5, minPrice = 3, maxPrice = 8)
                ),
                townOutputs = listOf(Commodity(Items.GOLDEN_CARROT, 6, baseValue = 8, minPrice = 5, maxPrice = 12)),
                cityOutputs = listOf(
                    Commodity(Items.SUSPICIOUS_STEW, 3, baseValue = 8, minPrice = 5, maxPrice = 12),
                    Commodity(Items.GOLDEN_CARROT, 16, baseValue = 8, minPrice = 5, maxPrice = 12)
                )
            )
            "shepherd" -> ProfessionTradeProfile(
                hamletOutputs = listOf(
                    Commodity(Items.WHITE_WOOL, 10, baseValue = 3, minPrice = 2, maxPrice = 5),
                    Commodity(Items.GRAY_WOOL, 10, baseValue = 3, minPrice = 2, maxPrice = 5),
                    Commodity(Items.BLACK_WOOL, 10, baseValue = 3, minPrice = 2, maxPrice = 5)
                ),
                need = Commodity(Items.WHEAT, 1, baseValue = 2, minPrice = 1, maxPrice = 3),
                extraNeeds = listOf(
                    Commodity(Items.SHEARS, 1, baseValue = 4, minPrice = 2, maxPrice = 6),
                    Commodity(Items.IRON_INGOT, 1, baseValue = 5, minPrice = 3, maxPrice = 7)
                ),
                settlementOutputs = listOf(
                    Commodity(Items.RED_WOOL, 10, baseValue = 3, minPrice = 2, maxPrice = 5),
                    Commodity(Items.BLUE_WOOL, 10, baseValue = 3, minPrice = 2, maxPrice = 5),
                    Commodity(Items.STRING, 10, baseValue = 2, minPrice = 1, maxPrice = 4)
                ),
                villageOutputs = listOf(
                    Commodity(Items.WHITE_CARPET, 12, baseValue = 2, minPrice = 1, maxPrice = 4),
                    Commodity(Items.WHITE_BED, 2, baseValue = 7, minPrice = 4, maxPrice = 11)
                ),
                townOutputs = listOf(Commodity(Items.WHITE_BANNER, 4, baseValue = 6, minPrice = 3, maxPrice = 10)),
                cityOutputs = listOf(
                    Commodity(Items.WHITE_BANNER, 8, baseValue = 6, minPrice = 3, maxPrice = 10),
                    Commodity(Items.BLACK_BANNER, 8, baseValue = 6, minPrice = 3, maxPrice = 10)
                )
            )
            "mason" -> ProfessionTradeProfile(
                hamletOutputs = listOf(
                    Commodity(Items.COAL, 10, baseValue = 3, minPrice = 2, maxPrice = 5),
                    Commodity(Items.COBBLESTONE, 16, baseValue = 1, minPrice = 1, maxPrice = 2),
                    Commodity(Items.STONE, 16, baseValue = 2, minPrice = 1, maxPrice = 3),
                    Commodity(Items.GRAVEL, 16, baseValue = 1, minPrice = 1, maxPrice = 2)
                ),
                need = Commodity(Items.BREAD, 1, baseValue = 3, minPrice = 2, maxPrice = 5),
                extraNeeds = listOf(
                    Commodity(Items.TORCH, 1, baseValue = 2, minPrice = 1, maxPrice = 4),
                    Commodity(Items.IRON_PICKAXE, 1, baseValue = 6, minPrice = 3, maxPrice = 9),
                    Commodity(Items.OAK_LOG, 1, baseValue = 3, minPrice = 2, maxPrice = 5)
                ),
                settlementOutputs = listOf(
                    Commodity(Items.RAW_IRON, 8, baseValue = 5, minPrice = 3, maxPrice = 8),
                    Commodity(Items.RAW_COPPER, 8, baseValue = 4, minPrice = 2, maxPrice = 7)
                ),
                villageOutputs = listOf(Commodity(Items.TUFF, 16, baseValue = 2, minPrice = 1, maxPrice = 3)),
                townOutputs = listOf(Commodity(Items.RAW_GOLD, 8, baseValue = 6, minPrice = 4, maxPrice = 9)),
                cityOutputs = listOf(
                    Commodity(Items.RAW_IRON, 32, baseValue = 5, minPrice = 3, maxPrice = 7),
                    Commodity(Items.RAW_COPPER, 32, baseValue = 4, minPrice = 2, maxPrice = 7)
                )
            )
            "fletcher" -> ProfessionTradeProfile(
                hamletOutputs = listOf(
                    Commodity(Items.OAK_LOG, 10, baseValue = 3, minPrice = 2, maxPrice = 5),
                    Commodity(Items.SPRUCE_LOG, 10, baseValue = 3, minPrice = 2, maxPrice = 5)
                ),
                need = Commodity(Items.IRON_AXE, 1, baseValue = 6, minPrice = 3, maxPrice = 9),
                extraNeeds = listOf(
                    Commodity(Items.BREAD, 1, baseValue = 3, minPrice = 2, maxPrice = 5),
                    Commodity(Items.COAL, 1, baseValue = 3, minPrice = 2, maxPrice = 5)
                ),
                settlementOutputs = listOf(
                    Commodity(Items.BIRCH_LOG, 10, baseValue = 3, minPrice = 2, maxPrice = 5),
                    Commodity(Items.JUNGLE_LOG, 10, baseValue = 3, minPrice = 2, maxPrice = 5),
                    Commodity(Items.ACACIA_LOG, 10, baseValue = 3, minPrice = 2, maxPrice = 5)
                ),
                villageOutputs = listOf(
                    Commodity(Items.MANGROVE_LOG, 10, baseValue = 3, minPrice = 2, maxPrice = 5),
                    Commodity(Items.CHERRY_LOG, 10, baseValue = 3, minPrice = 2, maxPrice = 5)
                ),
                townOutputs = listOf(
                    Commodity(Items.BAMBOO_BLOCK, 12, baseValue = 3, minPrice = 2, maxPrice = 5),
                    Commodity(Items.OAK_PLANKS, 16, baseValue = 2, minPrice = 1, maxPrice = 4)
                ),
                cityOutputs = listOf(
                    Commodity(Items.OAK_LOG, 32, baseValue = 3, minPrice = 2, maxPrice = 5),
                    Commodity(Items.SPRUCE_LOG, 32, baseValue = 3, minPrice = 2, maxPrice = 5),
                    Commodity(Items.BIRCH_LOG, 32, baseValue = 3, minPrice = 2, maxPrice = 5)
                )
            )
            "butcher" -> ProfessionTradeProfile(
                hamletOutputs = listOf(
                    Commodity(Items.BEEF, 10, baseValue = 4, minPrice = 2, maxPrice = 6),
                    Commodity(Items.PORKCHOP, 10, baseValue = 4, minPrice = 2, maxPrice = 6),
                    Commodity(Items.CHICKEN, 10, baseValue = 3, minPrice = 2, maxPrice = 5)
                ),
                need = Commodity(Items.COAL, 1, baseValue = 3, minPrice = 2, maxPrice = 5),
                extraNeeds = listOf(
                    Commodity(Items.WHEAT, 1, baseValue = 2, minPrice = 1, maxPrice = 3),
                    Commodity(Items.CARROT, 1, baseValue = 2, minPrice = 1, maxPrice = 4),
                    Commodity(Items.BUCKET, 1, baseValue = 5, minPrice = 3, maxPrice = 8)
                ),
                settlementOutputs = listOf(
                    Commodity(Items.COOKED_BEEF, 8, baseValue = 5, minPrice = 3, maxPrice = 8),
                    Commodity(Items.COOKED_PORKCHOP, 8, baseValue = 5, minPrice = 3, maxPrice = 8)
                ),
                villageOutputs = listOf(
                    Commodity(Items.COOKED_CHICKEN, 8, baseValue = 4, minPrice = 2, maxPrice = 7),
                    Commodity(Items.RABBIT, 6, baseValue = 4, minPrice = 2, maxPrice = 7)
                ),
                townOutputs = listOf(Commodity(Items.RABBIT_STEW, 3, baseValue = 7, minPrice = 4, maxPrice = 11)),
                cityOutputs = listOf(
                    Commodity(Items.COOKED_BEEF, 24, baseValue = 5, minPrice = 3, maxPrice = 8),
                    Commodity(Items.COOKED_PORKCHOP, 24, baseValue = 5, minPrice = 3, maxPrice = 8),
                    Commodity(Items.COOKED_CHICKEN, 24, baseValue = 4, minPrice = 2, maxPrice = 7)
                )
            )
            "leatherworker" -> ProfessionTradeProfile(
                hamletOutputs = listOf(
                    Commodity(Items.LEATHER, 10, baseValue = 5, minPrice = 3, maxPrice = 7),
                    Commodity(Items.LEATHER_HELMET, 1, baseValue = 5, minPrice = 3, maxPrice = 8),
                    Commodity(Items.LEATHER_CHESTPLATE, 1, baseValue = 7, minPrice = 4, maxPrice = 10),
                    Commodity(Items.LEATHER_LEGGINGS, 1, baseValue = 6, minPrice = 3, maxPrice = 9),
                    Commodity(Items.LEATHER_BOOTS, 1, baseValue = 4, minPrice = 2, maxPrice = 7)
                ),
                need = Commodity(Items.LEATHER, 1, baseValue = 5, minPrice = 3, maxPrice = 7),
                extraNeeds = listOf(
                    Commodity(Items.STRING, 1, baseValue = 2, minPrice = 1, maxPrice = 4),
                    Commodity(Items.IRON_INGOT, 1, baseValue = 5, minPrice = 3, maxPrice = 7)
                ),
                settlementOutputs = listOf(Commodity(Items.SADDLE, 1, baseValue = 12, minPrice = 8, maxPrice = 18)),
                villageOutputs = listOf(Commodity(Items.ITEM_FRAME, 4, baseValue = 4, minPrice = 2, maxPrice = 7)),
                townOutputs = listOf(Commodity(itemById("bundle"), 1, baseValue = 10, minPrice = 6, maxPrice = 15)),
                cityOutputs = listOf(
                    Commodity(Items.LEATHER, 32, baseValue = 5, minPrice = 3, maxPrice = 7),
                    Commodity(itemById("bundle"), 4, baseValue = 10, minPrice = 6, maxPrice = 15)
                )
            )
            "fisherman" -> ProfessionTradeProfile(
                hamletOutputs = listOf(
                    Commodity(Items.COD, 10, baseValue = 3, minPrice = 2, maxPrice = 5),
                    Commodity(Items.SALMON, 10, baseValue = 3, minPrice = 2, maxPrice = 5)
                ),
                need = Commodity(Items.STRING, 1, baseValue = 2, minPrice = 1, maxPrice = 4),
                extraNeeds = listOf(
                    Commodity(Items.OAK_LOG, 1, baseValue = 3, minPrice = 2, maxPrice = 5),
                    Commodity(Items.COAL, 1, baseValue = 3, minPrice = 2, maxPrice = 5)
                ),
                settlementOutputs = listOf(
                    Commodity(Items.COOKED_COD, 8, baseValue = 4, minPrice = 2, maxPrice = 6),
                    Commodity(Items.COOKED_SALMON, 8, baseValue = 4, minPrice = 2, maxPrice = 6)
                ),
                villageOutputs = listOf(
                    Commodity(Items.TROPICAL_FISH, 6, baseValue = 4, minPrice = 2, maxPrice = 7),
                    Commodity(Items.PUFFERFISH, 5, baseValue = 5, minPrice = 3, maxPrice = 8)
                ),
                townOutputs = listOf(
                    Commodity(Items.OAK_BOAT, 2, baseValue = 5, minPrice = 3, maxPrice = 8),
                    Commodity(Items.BARREL, 4, baseValue = 4, minPrice = 2, maxPrice = 7)
                ),
                cityOutputs = listOf(
                    Commodity(Items.COD, 32, baseValue = 3, minPrice = 2, maxPrice = 5),
                    Commodity(Items.SALMON, 32, baseValue = 3, minPrice = 2, maxPrice = 5)
                )
            )
            "toolsmith" -> ProfessionTradeProfile(
                need = Commodity(Items.COBBLESTONE, 1, baseValue = 1, minPrice = 1, maxPrice = 2),
                extraNeeds = listOf(
                    Commodity(Items.COAL, 1, baseValue = 3, minPrice = 2, maxPrice = 5),
                    Commodity(Items.OAK_LOG, 1, baseValue = 3, minPrice = 2, maxPrice = 5)
                ),
                hamletOutputs = listOf(
                    Commodity(Items.STONE_PICKAXE, 1, baseValue = 3, minPrice = 2, maxPrice = 5, productPricePercent = ARTISAN_VILLAGE_PRODUCT_PRICE_PERCENT),
                    Commodity(Items.STONE_AXE, 1, baseValue = 3, minPrice = 2, maxPrice = 5, productPricePercent = ARTISAN_VILLAGE_PRODUCT_PRICE_PERCENT),
                    Commodity(Items.STONE_SHOVEL, 1, baseValue = 2, minPrice = 1, maxPrice = 4, productPricePercent = ARTISAN_VILLAGE_PRODUCT_PRICE_PERCENT)
                ),
                settlementOutputs = listOf(
                    Commodity(Items.SHEARS, 1, baseValue = 4, minPrice = 2, maxPrice = 6, productPricePercent = ARTISAN_VILLAGE_PRODUCT_PRICE_PERCENT),
                    Commodity(Items.FLINT_AND_STEEL, 1, baseValue = 5, minPrice = 3, maxPrice = 8, productPricePercent = ARTISAN_VILLAGE_PRODUCT_PRICE_PERCENT),
                    Commodity(Items.BUCKET, 1, baseValue = 5, minPrice = 3, maxPrice = 8, productPricePercent = ARTISAN_VILLAGE_PRODUCT_PRICE_PERCENT)
                ),
                villageOutputs = listOf(
                    Commodity(Items.IRON_PICKAXE, 1, baseValue = 6, minPrice = 3, maxPrice = 9, productPricePercent = ARTISAN_VILLAGE_PRODUCT_PRICE_PERCENT),
                    Commodity(Items.IRON_AXE, 1, baseValue = 6, minPrice = 3, maxPrice = 9, productPricePercent = ARTISAN_VILLAGE_PRODUCT_PRICE_PERCENT),
                    Commodity(Items.IRON_SHOVEL, 1, baseValue = 5, minPrice = 3, maxPrice = 8, productPricePercent = ARTISAN_VILLAGE_PRODUCT_PRICE_PERCENT),
                    Commodity(Items.IRON_HOE, 1, baseValue = 5, minPrice = 3, maxPrice = 8, productPricePercent = ARTISAN_VILLAGE_PRODUCT_PRICE_PERCENT),
                    Commodity(Items.FISHING_ROD, 1, baseValue = 4, minPrice = 2, maxPrice = 6, productPricePercent = ARTISAN_VILLAGE_PRODUCT_PRICE_PERCENT)
                ),
                townOutputs = listOf(
                    Commodity(Items.DIAMOND_PICKAXE, 1, baseValue = 18, minPrice = 12, maxPrice = 28, productPricePercent = ARTISAN_TOWN_PRODUCT_PRICE_PERCENT),
                    Commodity(Items.DIAMOND_AXE, 1, baseValue = 18, minPrice = 12, maxPrice = 28, productPricePercent = ARTISAN_TOWN_PRODUCT_PRICE_PERCENT)
                ),
                cityOutputs = listOf(enchantedBookCommodity("mending", baseValue = 12, minPrice = 8, maxPrice = 18, productPricePercent = ARTISAN_CITY_PRODUCT_PRICE_PERCENT)),
                villageWant = Commodity(Items.IRON_INGOT, 1, baseValue = 5, minPrice = 3, maxPrice = 7),
                extraVillageWants = listOf(
                    Commodity(Items.COAL, 1, baseValue = 3, minPrice = 2, maxPrice = 5),
                    Commodity(Items.OAK_LOG, 1, baseValue = 3, minPrice = 2, maxPrice = 5)
                )
            )
            "weaponsmith" -> ProfessionTradeProfile(
                need = Commodity(Items.FLINT, 1, baseValue = 2, minPrice = 1, maxPrice = 4),
                extraNeeds = listOf(
                    Commodity(Items.COAL, 1, baseValue = 3, minPrice = 2, maxPrice = 5),
                    Commodity(Items.STRING, 1, baseValue = 2, minPrice = 1, maxPrice = 4)
                ),
                hamletOutputs = listOf(
                    Commodity(Items.ARROW, 12, baseValue = 3, minPrice = 2, maxPrice = 5, productPricePercent = ARTISAN_VILLAGE_PRODUCT_PRICE_PERCENT),
                    Commodity(Items.STONE_SWORD, 1, baseValue = 4, minPrice = 2, maxPrice = 6, productPricePercent = ARTISAN_VILLAGE_PRODUCT_PRICE_PERCENT)
                ),
                settlementOutputs = listOf(
                    Commodity(Items.SHIELD, 1, baseValue = 6, minPrice = 3, maxPrice = 9, productPricePercent = ARTISAN_VILLAGE_PRODUCT_PRICE_PERCENT),
                    Commodity(Items.BOW, 1, baseValue = 8, minPrice = 5, maxPrice = 12, productPricePercent = ARTISAN_VILLAGE_PRODUCT_PRICE_PERCENT)
                ),
                villageOutputs = listOf(
                    Commodity(Items.IRON_SWORD, 1, baseValue = 6, minPrice = 3, maxPrice = 9, productPricePercent = ARTISAN_VILLAGE_PRODUCT_PRICE_PERCENT),
                    Commodity(Items.IRON_AXE, 1, baseValue = 6, minPrice = 3, maxPrice = 9, productPricePercent = ARTISAN_VILLAGE_PRODUCT_PRICE_PERCENT)
                ),
                townOutputs = listOf(
                    Commodity(Items.DIAMOND_SWORD, 1, baseValue = 18, minPrice = 12, maxPrice = 28, productPricePercent = ARTISAN_TOWN_PRODUCT_PRICE_PERCENT),
                    Commodity(Items.CROSSBOW, 1, baseValue = 10, minPrice = 6, maxPrice = 16, productPricePercent = ARTISAN_TOWN_PRODUCT_PRICE_PERCENT)
                ),
                cityOutputs = listOf(
                    enchantedBookCommodity("sharpness", baseValue = 22, minPrice = 14, maxPrice = 34, productPricePercent = ARTISAN_CITY_PRODUCT_PRICE_PERCENT),
                    enchantedBookCommodity("power", baseValue = 18, minPrice = 12, maxPrice = 28, productPricePercent = ARTISAN_CITY_PRODUCT_PRICE_PERCENT),
                    enchantedBookCommodity("infinity", baseValue = 20, minPrice = 14, maxPrice = 30, productPricePercent = ARTISAN_CITY_PRODUCT_PRICE_PERCENT)
                ),
                villageWant = Commodity(Items.IRON_INGOT, 1, baseValue = 5, minPrice = 3, maxPrice = 7),
                extraVillageWants = listOf(
                    Commodity(Items.COAL, 1, baseValue = 3, minPrice = 2, maxPrice = 5),
                    Commodity(Items.OAK_LOG, 1, baseValue = 3, minPrice = 2, maxPrice = 5),
                    Commodity(Items.STRING, 1, baseValue = 2, minPrice = 1, maxPrice = 4)
                )
            )
            "armorer" -> ProfessionTradeProfile(
                need = Commodity(Items.LEATHER, 1, baseValue = 5, minPrice = 3, maxPrice = 7),
                extraNeeds = listOf(
                    Commodity(Items.COAL, 1, baseValue = 3, minPrice = 2, maxPrice = 5),
                    Commodity(Items.IRON_INGOT, 1, baseValue = 5, minPrice = 3, maxPrice = 7)
                ),
                hamletOutputs = listOf(
                    Commodity(Items.LEATHER_HELMET, 1, baseValue = 5, minPrice = 3, maxPrice = 8, productPricePercent = ARTISAN_VILLAGE_PRODUCT_PRICE_PERCENT),
                    Commodity(Items.LEATHER_BOOTS, 1, baseValue = 4, minPrice = 2, maxPrice = 7, productPricePercent = ARTISAN_VILLAGE_PRODUCT_PRICE_PERCENT),
                    Commodity(Items.SHIELD, 1, baseValue = 6, minPrice = 3, maxPrice = 9, productPricePercent = ARTISAN_VILLAGE_PRODUCT_PRICE_PERCENT)
                ),
                settlementOutputs = listOf(
                    Commodity(Items.CHAINMAIL_HELMET, 1, baseValue = 7, minPrice = 4, maxPrice = 10, productPricePercent = ARTISAN_VILLAGE_PRODUCT_PRICE_PERCENT),
                    Commodity(Items.CHAINMAIL_CHESTPLATE, 1, baseValue = 10, minPrice = 6, maxPrice = 15, productPricePercent = ARTISAN_VILLAGE_PRODUCT_PRICE_PERCENT),
                    Commodity(Items.CHAINMAIL_LEGGINGS, 1, baseValue = 9, minPrice = 5, maxPrice = 14, productPricePercent = ARTISAN_VILLAGE_PRODUCT_PRICE_PERCENT),
                    Commodity(Items.CHAINMAIL_BOOTS, 1, baseValue = 6, minPrice = 3, maxPrice = 9, productPricePercent = ARTISAN_VILLAGE_PRODUCT_PRICE_PERCENT)
                ),
                villageOutputs = listOf(
                    Commodity(Items.IRON_HELMET, 1, baseValue = 6, minPrice = 3, maxPrice = 9, productPricePercent = ARTISAN_VILLAGE_PRODUCT_PRICE_PERCENT),
                    Commodity(Items.IRON_CHESTPLATE, 1, baseValue = 8, minPrice = 5, maxPrice = 12, productPricePercent = ARTISAN_VILLAGE_PRODUCT_PRICE_PERCENT),
                    Commodity(Items.IRON_LEGGINGS, 1, baseValue = 7, minPrice = 4, maxPrice = 11, productPricePercent = ARTISAN_VILLAGE_PRODUCT_PRICE_PERCENT),
                    Commodity(Items.IRON_BOOTS, 1, baseValue = 5, minPrice = 3, maxPrice = 8, productPricePercent = ARTISAN_VILLAGE_PRODUCT_PRICE_PERCENT)
                ),
                townOutputs = listOf(
                    Commodity(Items.DIAMOND_HELMET, 1, baseValue = 18, minPrice = 12, maxPrice = 28, productPricePercent = ARTISAN_TOWN_PRODUCT_PRICE_PERCENT),
                    Commodity(Items.DIAMOND_CHESTPLATE, 1, baseValue = 26, minPrice = 18, maxPrice = 38, productPricePercent = ARTISAN_TOWN_PRODUCT_PRICE_PERCENT),
                    Commodity(Items.DIAMOND_LEGGINGS, 1, baseValue = 24, minPrice = 16, maxPrice = 36, productPricePercent = ARTISAN_TOWN_PRODUCT_PRICE_PERCENT),
                    Commodity(Items.DIAMOND_BOOTS, 1, baseValue = 16, minPrice = 10, maxPrice = 24, productPricePercent = ARTISAN_TOWN_PRODUCT_PRICE_PERCENT)
                ),
                cityOutputs = listOf(
                    enchantedBookCommodity("protection", baseValue = 24, minPrice = 16, maxPrice = 36, productPricePercent = ARTISAN_CITY_PRODUCT_PRICE_PERCENT),
                    enchantedBookCommodity("unbreaking", baseValue = 20, minPrice = 14, maxPrice = 30, productPricePercent = ARTISAN_CITY_PRODUCT_PRICE_PERCENT),
                    enchantedBookCommodity("feather_falling", baseValue = 18, minPrice = 12, maxPrice = 28, productPricePercent = ARTISAN_CITY_PRODUCT_PRICE_PERCENT),
                    enchantedBookCommodity("respiration", baseValue = 18, minPrice = 12, maxPrice = 28, productPricePercent = ARTISAN_CITY_PRODUCT_PRICE_PERCENT),
                    enchantedBookCommodity("aqua_affinity", baseValue = 16, minPrice = 10, maxPrice = 24, productPricePercent = ARTISAN_CITY_PRODUCT_PRICE_PERCENT)
                ),
                specialOutputs = ::armorerOutputsForTier,
                villageWant = Commodity(Items.IRON_INGOT, 1, baseValue = 5, minPrice = 3, maxPrice = 7),
                extraVillageWants = listOf(
                    Commodity(Items.COAL, 1, baseValue = 3, minPrice = 2, maxPrice = 5),
                    Commodity(Items.LEATHER, 1, baseValue = 5, minPrice = 3, maxPrice = 7)
                )
            )
            "cleric" -> ProfessionTradeProfile(
                need = Commodity(Items.ROTTEN_FLESH, 1, baseValue = 2, minPrice = 1, maxPrice = 4),
                extraNeeds = listOf(
                    Commodity(Items.GLASS_BOTTLE, 1, baseValue = 2, minPrice = 1, maxPrice = 4),
                    Commodity(Items.REDSTONE, 1, baseValue = 4, minPrice = 2, maxPrice = 7)
                ),
                specialOutputs = ::clericOutputsForTier,
                hamletOutputs = listOf(
                    Commodity(Items.CANDLE, 4, baseValue = 3, minPrice = 2, maxPrice = 5, productPricePercent = ARTISAN_VILLAGE_PRODUCT_PRICE_PERCENT),
                    Commodity(Items.GLASS_BOTTLE, 8, baseValue = 2, minPrice = 1, maxPrice = 4, productPricePercent = ARTISAN_VILLAGE_PRODUCT_PRICE_PERCENT)
                ),
                settlementOutputs = listOf(
                    Commodity(Items.LAPIS_LAZULI, 8, baseValue = 4, minPrice = 2, maxPrice = 7, productPricePercent = ARTISAN_VILLAGE_PRODUCT_PRICE_PERCENT),
                    Commodity(Items.AMETHYST_SHARD, 4, baseValue = 5, minPrice = 3, maxPrice = 8, productPricePercent = ARTISAN_VILLAGE_PRODUCT_PRICE_PERCENT)
                ),
                villageOutputs = listOf(
                    Commodity(Items.REDSTONE, 8, baseValue = 4, minPrice = 2, maxPrice = 7, productPricePercent = ARTISAN_VILLAGE_PRODUCT_PRICE_PERCENT),
                    Commodity(Items.GLOWSTONE_DUST, 8, baseValue = 5, minPrice = 3, maxPrice = 8, productPricePercent = ARTISAN_VILLAGE_PRODUCT_PRICE_PERCENT)
                ),
                townOutputs = listOf(
                    Commodity(Items.BREWING_STAND, 1, baseValue = 12, minPrice = 8, maxPrice = 18, productPricePercent = ARTISAN_TOWN_PRODUCT_PRICE_PERCENT),
                    Commodity(Items.GLASS_BOTTLE, 12, baseValue = 2, minPrice = 1, maxPrice = 4, productPricePercent = ARTISAN_TOWN_PRODUCT_PRICE_PERCENT)
                ),
                cityOutputs = listOf(
                    Commodity(Items.EXPERIENCE_BOTTLE, 8, baseValue = 12, minPrice = 8, maxPrice = 18, productPricePercent = ARTISAN_CITY_PRODUCT_PRICE_PERCENT),
                    Commodity(Items.ENDER_PEARL, 2, baseValue = 14, minPrice = 10, maxPrice = 22, productPricePercent = ARTISAN_CITY_PRODUCT_PRICE_PERCENT),
                    Commodity(Items.REDSTONE, 32, baseValue = 4, minPrice = 2, maxPrice = 7, productPricePercent = ARTISAN_CITY_PRODUCT_PRICE_PERCENT)
                ),
                villageWant = Commodity(Items.GLASS_BOTTLE, 1, baseValue = 2, minPrice = 1, maxPrice = 4),
                extraVillageWants = listOf(
                    Commodity(Items.REDSTONE, 1, baseValue = 4, minPrice = 2, maxPrice = 7),
                    Commodity(Items.COAL, 1, baseValue = 3, minPrice = 2, maxPrice = 5),
                    Commodity(Items.GOLD_INGOT, 1, baseValue = 6, minPrice = 4, maxPrice = 9)
                )
            )
            "librarian" -> ProfessionTradeProfile(
                need = Commodity(Items.PAPER, 1, baseValue = 2, minPrice = 1, maxPrice = 4),
                extraNeeds = listOf(
                    Commodity(Items.FEATHER, 1, baseValue = 2, minPrice = 1, maxPrice = 4),
                    Commodity(Items.LEATHER, 1, baseValue = 5, minPrice = 3, maxPrice = 7)
                ),
                hamletOutputs = listOf(
                    Commodity(Items.PAPER, 12, baseValue = 2, minPrice = 1, maxPrice = 4, productPricePercent = ARTISAN_VILLAGE_PRODUCT_PRICE_PERCENT),
                    Commodity(Items.INK_SAC, 4, baseValue = 3, minPrice = 2, maxPrice = 5, productPricePercent = ARTISAN_VILLAGE_PRODUCT_PRICE_PERCENT)
                ),
                settlementOutputs = listOf(
                    Commodity(Items.BOOK, 10, baseValue = 4, minPrice = 2, maxPrice = 6, productPricePercent = ARTISAN_VILLAGE_PRODUCT_PRICE_PERCENT),
                    Commodity(Items.WRITABLE_BOOK, 2, baseValue = 6, minPrice = 3, maxPrice = 9, productPricePercent = ARTISAN_VILLAGE_PRODUCT_PRICE_PERCENT)
                ),
                villageOutputs = listOf(
                    Commodity(Items.BOOKSHELF, 2, baseValue = 8, minPrice = 5, maxPrice = 12, productPricePercent = ARTISAN_VILLAGE_PRODUCT_PRICE_PERCENT)
                ),
                townOutputs = listOf(Commodity(Items.LANTERN, 4, baseValue = 5, minPrice = 3, maxPrice = 8, productPricePercent = ARTISAN_TOWN_PRODUCT_PRICE_PERCENT)),
                specialOutputs = ::librarianOutputsForTier,
                cityOutputs = listOf(
                    enchantedBookCommodity("mending", baseValue = 28, minPrice = 18, maxPrice = 42, productPricePercent = ARTISAN_CITY_PRODUCT_PRICE_PERCENT),
                    enchantedBookCommodity("unbreaking", baseValue = 22, minPrice = 14, maxPrice = 34, productPricePercent = ARTISAN_CITY_PRODUCT_PRICE_PERCENT),
                    enchantedBookCommodity("efficiency", baseValue = 24, minPrice = 16, maxPrice = 36, productPricePercent = ARTISAN_CITY_PRODUCT_PRICE_PERCENT),
                    enchantedBookCommodity("fortune", baseValue = 24, minPrice = 16, maxPrice = 36, productPricePercent = ARTISAN_CITY_PRODUCT_PRICE_PERCENT),
                    enchantedBookCommodity("silk_touch", baseValue = 24, minPrice = 16, maxPrice = 36, productPricePercent = ARTISAN_CITY_PRODUCT_PRICE_PERCENT),
                    enchantedBookCommodity("protection", baseValue = 24, minPrice = 16, maxPrice = 36, productPricePercent = ARTISAN_CITY_PRODUCT_PRICE_PERCENT),
                    enchantedBookCommodity("sharpness", baseValue = 24, minPrice = 16, maxPrice = 36, productPricePercent = ARTISAN_CITY_PRODUCT_PRICE_PERCENT),
                    enchantedBookCommodity("power", baseValue = 20, minPrice = 14, maxPrice = 32, productPricePercent = ARTISAN_CITY_PRODUCT_PRICE_PERCENT),
                    enchantedBookCommodity("infinity", baseValue = 22, minPrice = 14, maxPrice = 34, productPricePercent = ARTISAN_CITY_PRODUCT_PRICE_PERCENT),
                    enchantedBookCommodity("respiration", baseValue = 18, minPrice = 12, maxPrice = 28, productPricePercent = ARTISAN_CITY_PRODUCT_PRICE_PERCENT),
                    enchantedBookCommodity("aqua_affinity", baseValue = 16, minPrice = 10, maxPrice = 24, productPricePercent = ARTISAN_CITY_PRODUCT_PRICE_PERCENT)
                ),
                villageWant = Commodity(Items.PAPER, 1, baseValue = 2, minPrice = 1, maxPrice = 4),
                extraVillageWants = listOf(
                    Commodity(Items.BOOK, 1, baseValue = 4, minPrice = 2, maxPrice = 6),
                    Commodity(Items.LEATHER, 1, baseValue = 5, minPrice = 3, maxPrice = 7),
                    Commodity(Items.EMERALD, 1, baseValue = 8, minPrice = 5, maxPrice = 12)
                )
            )
            "cartographer" -> ProfessionTradeProfile(
                need = Commodity(Items.PAPER, 1, baseValue = 2, minPrice = 1, maxPrice = 4),
                extraNeeds = listOf(
                    Commodity(Items.INK_SAC, 1, baseValue = 3, minPrice = 2, maxPrice = 5),
                    Commodity(Items.REDSTONE, 1, baseValue = 4, minPrice = 2, maxPrice = 7)
                ),
                hamletOutputs = listOf(
                    Commodity(Items.MAP, 4, baseValue = 4, minPrice = 2, maxPrice = 7, productPricePercent = ARTISAN_VILLAGE_PRODUCT_PRICE_PERCENT),
                    Commodity(Items.PAPER, 12, baseValue = 2, minPrice = 1, maxPrice = 4, productPricePercent = ARTISAN_VILLAGE_PRODUCT_PRICE_PERCENT)
                ),
                settlementOutputs = listOf(
                    Commodity(Items.COMPASS, 1, baseValue = 8, minPrice = 5, maxPrice = 12, productPricePercent = ARTISAN_VILLAGE_PRODUCT_PRICE_PERCENT),
                    Commodity(Items.ITEM_FRAME, 2, baseValue = 4, minPrice = 2, maxPrice = 7, productPricePercent = ARTISAN_VILLAGE_PRODUCT_PRICE_PERCENT)
                ),
                villageOutputs = listOf(
                    Commodity(Items.FILLED_MAP, 1, baseValue = 8, minPrice = 5, maxPrice = 12, productPricePercent = ARTISAN_VILLAGE_PRODUCT_PRICE_PERCENT)
                ),
                townOutputs = listOf(
                    Commodity(Items.FLOWER_BANNER_PATTERN, 1, baseValue = 10, minPrice = 6, maxPrice = 15, productPricePercent = ARTISAN_TOWN_PRODUCT_PRICE_PERCENT)
                ),
                cityOutputs = listOf(
                    Commodity(Items.FILLED_MAP, 1, baseValue = 18, minPrice = 12, maxPrice = 28, variantId = "minecraft:filled_map#ocean_explorer", displayName = "Ocean Explorer Map", productPricePercent = ARTISAN_CITY_PRODUCT_PRICE_PERCENT),
                    Commodity(Items.FILLED_MAP, 1, baseValue = 18, minPrice = 12, maxPrice = 28, variantId = "minecraft:filled_map#woodland_explorer", displayName = "Woodland Explorer Map", productPricePercent = ARTISAN_CITY_PRODUCT_PRICE_PERCENT),
                    Commodity(Items.FILLED_MAP, 1, baseValue = 20, minPrice = 14, maxPrice = 32, variantId = "minecraft:filled_map#trial_explorer", displayName = "Trial Explorer Map", productPricePercent = ARTISAN_CITY_PRODUCT_PRICE_PERCENT)
                ),
                villageWant = Commodity(Items.PAPER, 1, baseValue = 2, minPrice = 1, maxPrice = 4),
                extraVillageWants = listOf(
                    Commodity(Items.COMPASS, 1, baseValue = 8, minPrice = 5, maxPrice = 12),
                    Commodity(Items.IRON_INGOT, 1, baseValue = 5, minPrice = 3, maxPrice = 7),
                    Commodity(Items.REDSTONE, 1, baseValue = 4, minPrice = 2, maxPrice = 7)
                )
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
        val productPricePercent: Int = NORMAL_PRODUCT_PRICE_PERCENT,
        val dailyTurnInLimit: Int? = null
    ) {
        val itemName: String = item.toString()

        fun keyItemId(): String = variantId ?: itemIdOf(item)

        fun isNonStackable(): Boolean = ItemStack(item).maxCount <= 1

        fun experienceReward(): Int = if (isNonStackable()) NON_STACKABLE_WANT_XP_REWARD else 1

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

    private data class TradeExchange(
        val emeralds: Int,
        val itemCount: Int
    ) {
        fun valuePerItemScaled(): Int = (emeralds * 1000) / itemCount.coerceAtLeast(1)
    }

    private data class TradeArchetype(
        val outputKeys: Set<String>,
        val wantKeys: Set<String>
    )

    private const val MIN_ARCHETYPE_SELL_OFFERS = 1

    private val PROFESSION_TRADE_ARCHETYPES: Map<String, List<TradeArchetype>> = mapOf(
        "farmer" to listOf(
            TradeArchetype(setOf("minecraft:wheat", "minecraft:bread", "minecraft:cookie"), setOf("minecraft:iron_hoe", "minecraft:bone_meal")),
            TradeArchetype(setOf("minecraft:carrot", "minecraft:potato", "minecraft:golden_carrot"), setOf("minecraft:water_bucket", "minecraft:bone_meal")),
            TradeArchetype(setOf("minecraft:beetroot", "minecraft:pumpkin", "minecraft:melon", "minecraft:pumpkin_pie", "minecraft:suspicious_stew"), setOf("minecraft:coal", "minecraft:bone_meal"))
        ),
        "shepherd" to listOf(
            TradeArchetype(setOf("minecraft:white_wool", "minecraft:white_carpet", "minecraft:white_bed", "minecraft:white_banner"), setOf("minecraft:wheat", "minecraft:shears")),
            TradeArchetype(setOf("minecraft:gray_wool", "minecraft:black_wool", "minecraft:black_banner"), setOf("minecraft:iron_ingot", "minecraft:shears")),
            TradeArchetype(setOf("minecraft:red_wool", "minecraft:blue_wool", "minecraft:string"), setOf("minecraft:wheat", "minecraft:shears"))
        ),
        "mason" to listOf(
            TradeArchetype(setOf("minecraft:cobblestone", "minecraft:stone", "minecraft:tuff"), setOf("minecraft:bread", "minecraft:oak_log")),
            TradeArchetype(setOf("minecraft:gravel", "minecraft:coal", "minecraft:raw_copper"), setOf("minecraft:torch", "minecraft:bread")),
            TradeArchetype(setOf("minecraft:raw_iron", "minecraft:raw_gold"), setOf("minecraft:iron_pickaxe", "minecraft:torch"))
        ),
        "fletcher" to listOf(
            TradeArchetype(setOf("minecraft:oak_log", "minecraft:spruce_log", "minecraft:oak_planks"), setOf("minecraft:iron_axe", "minecraft:bread")),
            TradeArchetype(setOf("minecraft:birch_log", "minecraft:jungle_log", "minecraft:acacia_log"), setOf("minecraft:iron_axe", "minecraft:coal")),
            TradeArchetype(setOf("minecraft:mangrove_log", "minecraft:cherry_log", "minecraft:bamboo_block"), setOf("minecraft:iron_axe", "minecraft:bread"))
        ),
        "butcher" to listOf(
            TradeArchetype(setOf("minecraft:beef", "minecraft:cooked_beef", "minecraft:porkchop", "minecraft:cooked_porkchop"), setOf("minecraft:coal", "minecraft:wheat")),
            TradeArchetype(setOf("minecraft:chicken", "minecraft:cooked_chicken", "minecraft:rabbit", "minecraft:rabbit_stew"), setOf("minecraft:coal", "minecraft:carrot")),
            TradeArchetype(setOf("minecraft:beef", "minecraft:chicken", "minecraft:cooked_chicken"), setOf("minecraft:bucket", "minecraft:wheat"))
        ),
        "leatherworker" to listOf(
            TradeArchetype(setOf("minecraft:leather", "minecraft:saddle", "minecraft:bundle"), setOf("minecraft:leather", "minecraft:string")),
            TradeArchetype(setOf("minecraft:leather_helmet", "minecraft:leather_chestplate", "minecraft:leather_leggings", "minecraft:leather_boots"), setOf("minecraft:leather", "minecraft:iron_ingot")),
            TradeArchetype(setOf("minecraft:item_frame", "minecraft:bundle", "minecraft:leather"), setOf("minecraft:string", "minecraft:iron_ingot"))
        ),
        "fisherman" to listOf(
            TradeArchetype(setOf("minecraft:cod", "minecraft:cooked_cod", "minecraft:barrel"), setOf("minecraft:string", "minecraft:oak_log")),
            TradeArchetype(setOf("minecraft:salmon", "minecraft:cooked_salmon", "minecraft:oak_boat"), setOf("minecraft:string", "minecraft:coal")),
            TradeArchetype(setOf("minecraft:tropical_fish", "minecraft:pufferfish", "minecraft:barrel"), setOf("minecraft:oak_log", "minecraft:coal"))
        ),
        "toolsmith" to listOf(
            TradeArchetype(setOf("minecraft:stone_pickaxe", "minecraft:stone_shovel", "minecraft:iron_pickaxe", "minecraft:iron_shovel", "minecraft:diamond_pickaxe"), setOf("minecraft:cobblestone", "minecraft:coal", "minecraft:iron_ingot")),
            TradeArchetype(setOf("minecraft:stone_axe", "minecraft:iron_axe", "minecraft:iron_hoe", "minecraft:diamond_axe", "minecraft:flint_and_steel"), setOf("minecraft:cobblestone", "minecraft:oak_log", "minecraft:iron_ingot")),
            TradeArchetype(setOf("minecraft:shears", "minecraft:bucket", "minecraft:fishing_rod", "minecraft:iron_hoe"), setOf("minecraft:coal", "minecraft:oak_log", "minecraft:iron_ingot"))
        ),
        "weaponsmith" to listOf(
            TradeArchetype(setOf("minecraft:stone_sword", "minecraft:iron_sword", "minecraft:diamond_sword"), setOf("minecraft:flint", "minecraft:coal", "minecraft:iron_ingot")),
            TradeArchetype(setOf("minecraft:arrow", "minecraft:bow", "minecraft:crossbow"), setOf("minecraft:flint", "minecraft:string", "minecraft:oak_log")),
            TradeArchetype(setOf("minecraft:shield", "minecraft:iron_axe", "minecraft:crossbow"), setOf("minecraft:coal", "minecraft:string", "minecraft:iron_ingot"))
        ),
        "armorer" to listOf(
            TradeArchetype(setOf("minecraft:leather_helmet", "minecraft:chainmail_helmet", "minecraft:chainmail_chestplate", "minecraft:iron_helmet", "minecraft:iron_chestplate", "minecraft:diamond_helmet", "minecraft:diamond_chestplate"), setOf("minecraft:leather", "minecraft:coal", "minecraft:iron_ingot")),
            TradeArchetype(setOf("minecraft:leather_boots", "minecraft:chainmail_leggings", "minecraft:chainmail_boots", "minecraft:iron_leggings", "minecraft:iron_boots", "minecraft:diamond_leggings", "minecraft:diamond_boots"), setOf("minecraft:leather", "minecraft:iron_ingot")),
            TradeArchetype(setOf("minecraft:shield", "minecraft:chainmail_chestplate", "minecraft:chainmail_leggings", "minecraft:iron_chestplate", "minecraft:iron_leggings", "minecraft:diamond_chestplate", "minecraft:diamond_leggings"), setOf("minecraft:coal", "minecraft:leather", "minecraft:iron_ingot"))
        ),
        "cleric" to listOf(
            TradeArchetype(setOf("minecraft:candle", "minecraft:glass_bottle", "minecraft:brewing_stand"), setOf("minecraft:rotten_flesh", "minecraft:glass_bottle", "minecraft:redstone")),
            TradeArchetype(setOf("minecraft:lapis_lazuli", "minecraft:glowstone_dust", "minecraft:glass_bottle"), setOf("minecraft:rotten_flesh", "minecraft:coal", "minecraft:gold_ingot")),
            TradeArchetype(setOf("minecraft:amethyst_shard", "minecraft:redstone", "minecraft:experience_bottle", "minecraft:ender_pearl"), setOf("minecraft:glass_bottle", "minecraft:redstone", "minecraft:gold_ingot"))
        ),
        "librarian" to listOf(
            TradeArchetype(setOf("minecraft:paper", "minecraft:book", "minecraft:bookshelf"), setOf("minecraft:paper", "minecraft:book")),
            TradeArchetype(setOf("minecraft:ink_sac", "minecraft:writable_book", "minecraft:lantern"), setOf("minecraft:paper", "minecraft:leather", "minecraft:feather")),
            TradeArchetype(setOf("minecraft:paper", "minecraft:writable_book", "minecraft:book"), setOf("minecraft:book", "minecraft:emerald", "minecraft:feather"))
        ),
        "cartographer" to listOf(
            TradeArchetype(setOf("minecraft:paper", "minecraft:map", "minecraft:filled_map"), setOf("minecraft:paper", "minecraft:compass")),
            TradeArchetype(setOf("minecraft:compass", "minecraft:item_frame"), setOf("minecraft:paper", "minecraft:iron_ingot", "minecraft:ink_sac")),
            TradeArchetype(setOf("minecraft:map", "minecraft:flower_banner_pattern", "minecraft:filled_map"), setOf("minecraft:compass", "minecraft:redstone", "minecraft:ink_sac"))
        )
    )

    private fun archetypeForVillage(village: VillageData, professionId: String): TradeArchetype? {
        val archetypes = PROFESSION_TRADE_ARCHETYPES[professionId.substringAfterLast(":")] ?: return null
        val index = Math.floorMod("${village.id}:$professionId:trade-archetype".hashCode(), archetypes.size)
        return archetypes[index]
    }

    private fun shouldIncludeArchetypeCommodity(
        village: VillageData,
        professionId: String,
        commodity: Commodity,
        selector: (TradeArchetype) -> Set<String>
    ): Boolean {
        val archetypes = PROFESSION_TRADE_ARCHETYPES[professionId.substringAfterLast(":")] ?: return true
        val key = commodity.keyItemId().substringBefore("#")
        val allArchetypeKeys = archetypes.flatMap { selector(it) }.toSet()
        if (key !in allArchetypeKeys) return true
        return key in selector(archetypeForVillage(village, professionId) ?: return true)
    }

    private data class ProfessionTradeProfile(
        val need: Commodity?,
        val extraNeeds: List<Commodity> = emptyList(),
        val hamletOutputs: List<Commodity> = emptyList(),
        val settlementOutputs: List<Commodity> = emptyList(),
        val villageOutputs: List<Commodity> = emptyList(),
        val townOutputs: List<Commodity> = emptyList(),
        val cityOutputs: List<Commodity> = emptyList(),
        val specialOutputs: ((VillageTier, VillageData) -> List<Commodity>)? = null,
        val settlementWant: Commodity? = null,
        val villageWant: Commodity? = null,
        val extraVillageWants: List<Commodity> = emptyList(),
        val townWant: Commodity? = null,
        val cityWant: Commodity? = null
    ) {
        fun outputsForTier(tier: VillageTier, village: VillageData, professionId: String): List<Commodity> {
            val outputs = mutableListOf<Commodity>()
            outputs.addAll(hamletOutputs)
            if (tier.ordinal >= VillageTier.SETTLEMENT.ordinal) outputs.addAll(settlementOutputs)
            if (tier.ordinal >= VillageTier.VILLAGE.ordinal) outputs.addAll(villageOutputs)
            if (tier.ordinal >= VillageTier.TOWN.ordinal) outputs.addAll(townOutputs)
            if (tier.ordinal >= VillageTier.CITY.ordinal) outputs.addAll(cityOutputs)
            specialOutputs?.let { outputs.addAll(it(tier, village)) }
            val tierOutputs = outputs.reversed().distinctBy { it.keyItemId() }.asReversed()
            if (professionId.isBlank()) return tierOutputs

            val filtered = tierOutputs.filter {
                shouldIncludeArchetypeCommodity(village, professionId, it, TradeArchetype::outputKeys)
            }
            if (filtered.size >= MIN_ARCHETYPE_SELL_OFFERS || tierOutputs.isEmpty()) return filtered

            val fallback = tierOutputs.sortedBy { "${village.id}:$professionId:${it.keyItemId()}".hashCode() }
                .take(MIN_ARCHETYPE_SELL_OFFERS)
            return (filtered + fallback).distinctBy { it.keyItemId() }
        }

        fun allCommodities(): List<Commodity> {
            return hamletOutputs + settlementOutputs + villageOutputs + townOutputs + cityOutputs +
                listOfNotNull(need, settlementWant, villageWant, townWant, cityWant) + extraNeeds + extraVillageWants +
                listOf(bookVillageWant())
        }

        fun requestsForTier(tier: VillageTier, village: VillageData, professionId: String): List<Commodity> {
            val requests = listOfNotNull(need) + extraNeeds + wantsForTier(tier)
            return requests.filter {
                shouldIncludeArchetypeCommodity(village, professionId, it, TradeArchetype::wantKeys)
            }
        }

        fun wantsForTier(tier: VillageTier): List<Commodity> {
            val wants = mutableListOf<Commodity>()
            if (tier.ordinal >= VillageTier.SETTLEMENT.ordinal) settlementWant?.let(wants::add)
            if (tier.ordinal >= VillageTier.VILLAGE.ordinal) {
                wants.add(bookVillageWant())
                villageWant?.let(wants::add)
                wants.addAll(extraVillageWants)
            }
            if (tier.ordinal >= VillageTier.TOWN.ordinal) townWant?.let(wants::add)
            if (tier.ordinal >= VillageTier.CITY.ordinal) cityWant?.let(wants::add)
            return wants
        }
    }

    private fun bookVillageWant(): Commodity {
        return Commodity(Items.BOOK, 1, baseValue = 4, minPrice = 2, maxPrice = 6, dailyTurnInLimit = BOOK_WANT_DAILY_LIMIT)
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
            "infinity" -> Enchantments.INFINITY
            "respiration" -> Enchantments.RESPIRATION
            "aqua_affinity" -> Enchantments.AQUA_AFFINITY
            "feather_falling" -> Enchantments.FEATHER_FALLING
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
            "infinity" -> 1
            "respiration" -> 3
            "aqua_affinity" -> 1
            "feather_falling" -> 4
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

