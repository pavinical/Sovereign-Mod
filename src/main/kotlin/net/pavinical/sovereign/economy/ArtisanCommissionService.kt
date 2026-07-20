package net.pavinical.sovereign.economy

import net.minecraft.component.DataComponentTypes
import net.minecraft.component.type.AttributeModifierSlot
import net.minecraft.component.type.AttributeModifiersComponent
import net.minecraft.component.type.PotionContentsComponent
import net.minecraft.enchantment.Enchantment
import net.minecraft.enchantment.EnchantmentLevelEntry
import net.minecraft.enchantment.Enchantments
import net.minecraft.entity.attribute.EntityAttributeModifier
import net.minecraft.entity.attribute.EntityAttributes
import net.minecraft.item.EnchantedBookItem
import net.minecraft.item.Item
import net.minecraft.item.ItemStack
import net.minecraft.item.Items
import net.minecraft.item.trim.ArmorTrim
import net.minecraft.item.trim.ArmorTrimMaterials
import net.minecraft.item.trim.ArmorTrimPatterns
import net.minecraft.potion.Potion
import net.minecraft.potion.Potions
import net.minecraft.registry.RegistryKey
import net.minecraft.registry.RegistryKeys
import net.minecraft.registry.entry.RegistryEntry
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.text.Text
import net.minecraft.util.Identifier
import net.pavinical.sovereign.data.VillageCommissionData
import net.pavinical.sovereign.data.VillageData
import net.pavinical.sovereign.data.VillageTier
import java.util.UUID

object ArtisanCommissionService {
    private const val TICKS_PER_MINUTE = 1200L
    private const val MAX_COMMISSION_WAIT_TICKS = 10L * TICKS_PER_MINUTE
    private const val ABANDONED_COMMISSION_TICKS = 30L * 24000L
    private const val REPAIR_DURATION_TICKS = 2L * TICKS_PER_MINUTE
    private const val ENCHANT_DURATION_TICKS = 2L * TICKS_PER_MINUTE
    private const val POTION_DURATION_TICKS = 2L * TICKS_PER_MINUTE
    private const val REFORGE_DURATION_TICKS = 5L * TICKS_PER_MINUTE
    private const val TRIM_DURATION_TICKS = 5L * TICKS_PER_MINUTE
    private const val NEW_ITEM_DURATION_TICKS = 10L * TICKS_PER_MINUTE
    private const val ORDER_PRODUCTION_PREMIUM = 100
    private const val TRIMS_PER_DAY = 5
    private const val ARMORER_CUSTOM_PREFIX = "armorer_custom|"
    private const val SMITH_REFORGE_PREFIX = "smith_reforge|"
    private val ARTISAN_PROFESSIONS = setOf(
        "minecraft:armorer",
        "minecraft:toolsmith",
        "minecraft:weaponsmith",
        "minecraft:cleric",
        "minecraft:librarian"
    )

    fun offersFor(village: VillageData, occupiedProfessionIds: Set<String>, worldTime: Long): List<CommissionOffer> {
        if (village.tier.ordinal < VillageTier.VILLAGE.ordinal) return emptyList()
        val occupiedArtisans = occupiedProfessionIds.filter { it in ARTISAN_PROFESSIONS }.toSet()
        val dailyTrimPatterns = dailyTrimPatternKeys(worldTime)
        return DEFINITIONS
            .asSequence()
            .filter { it.professionId in occupiedArtisans }
            .filter { village.tier.ordinal >= it.unlockTier.ordinal }
            .filter { it.quality != "Trim" || trimTemplateKey(it.displayName) in dailyTrimPatterns }
            .map { it.toOffer() }
            .toList()
    }

    fun ordersFor(world: ServerWorld, village: VillageData, player: ServerPlayerEntity?): List<CommissionOrder> {
        val playerId = player?.uuid
        return village.commissions
            .asSequence()
            .filter { !it.claimed }
            .map { commission ->
                val ready = world.time >= commission.readyTick
                val abandonedInTicks = (commission.readyTick + ABANDONED_COMMISSION_TICKS - world.time).coerceAtLeast(0L)
                val abandoned = ready && abandonedInTicks <= 0L
                val ownerClaim = playerId != null && commission.playerId == playerId
                CommissionOrder(
                    id = commission.id,
                    item = ItemStack(itemForId(commission.itemId)),
                    displayName = commission.displayName,
                    professionId = commission.professionId,
                    professionName = commission.professionName,
                    emeraldCost = commission.emeraldCost,
                    readyTick = commission.readyTick,
                    remainingTicks = (commission.readyTick - world.time).coerceAtLeast(0L),
                    ready = ready,
                    claimable = ready && (ownerClaim || abandoned),
                    abandoned = abandoned,
                    abandonedInTicks = abandonedInTicks
                )
            }
            .toList()
    }

    fun order(
        world: ServerWorld,
        player: ServerPlayerEntity,
        village: VillageData,
        offerId: String,
        occupiedProfessionIds: Set<String>,
        inputStack: ItemStack? = null,
        requestedQuantity: Int = 1
    ): VillageTradeExecutionResult {
        if (offerId.startsWith(ARMORER_CUSTOM_PREFIX)) {
            return orderCustomArmorer(world, player, village, offerId, occupiedProfessionIds, inputStack)
        }
        if (offerId.startsWith(SMITH_REFORGE_PREFIX)) {
            return orderSmithReforge(world, player, village, offerId, occupiedProfessionIds, inputStack)
        }
        val offer = DEFINITIONS.firstOrNull { it.id == offerId }
            ?: return VillageTradeExecutionResult(false, Text.literal("The clerk cannot find that commission."))
        if (offer.professionId !in occupiedProfessionIds) {
            return VillageTradeExecutionResult(false, Text.literal("${village.name} has no ${offer.professionName} ready for that work."))
        }
        if (village.tier.ordinal < offer.unlockTier.ordinal) {
            return VillageTradeExecutionResult(false, Text.literal("${village.name} must grow before that commission is available."))
        }
        val activeCount = village.commissions.count { !it.claimed }
        if (activeCount >= activeCommissionCap(village.tier)) {
            return VillageTradeExecutionResult(false, Text.literal("${village.name}'s artisans are already fully booked."))
        }
        val playerActiveCount = village.commissions.count { !it.claimed && it.playerId == player.uuid }
        if (playerActiveCount >= playerCommissionCap(village.tier)) {
            return VillageTradeExecutionResult(false, Text.literal("The clerk will only hold ${playerCommissionCap(village.tier)} active commission(s) for you here."))
        }
        val submittedInput = prepareInputForOrder(offer, inputStack)
            ?: return VillageTradeExecutionResult(false, Text.literal(inputPromptFor(offer)))
        if (!submittedInput.isEmpty && !canVillageWorkItem(village.tier, offer.professionId, submittedInput.item)) {
            return VillageTradeExecutionResult(false, Text.literal("${village.name}'s ${offer.professionName} cannot work that material yet."))
        }
        val orderQuantity = commissionQuantityFor(offer, requestedQuantity)
        val orderCost = offer.emeraldCost.saturatingMultiply(orderQuantity)
        val orderDuration = capCommissionDuration(offer.durationTicks.saturatingMultiply(orderQuantity))

        val ledger = PlayerEmeraldLedger.get(world)
        if (!ledger.withdraw(player.uuid, orderCost)) {
            return VillageTradeExecutionResult(false, Text.literal("Your ledger needs $orderCost emeralds for that commission."))
        }
        if (offer.requiresInput()) {
            inputStack?.decrement(1)
        }
        village.treasuryEmeralds = village.treasuryEmeralds.coerceAtLeast(0).saturatingAdd(orderCost)
        val order = VillageCommissionData(
            id = UUID.randomUUID().toString(),
            playerId = player.uuid,
            offerId = offer.id,
            itemId = itemIdOf(if (submittedInput.isEmpty) offer.item else submittedInput.item),
            displayName = orderDisplayName(offer, submittedInput, orderQuantity),
            professionId = offer.professionId,
            professionName = offer.professionName,
            emeraldCost = orderCost,
            orderedTick = world.time,
            readyTick = world.time + orderDuration,
            quantity = orderQuantity,
            inputStack = submittedInput
        )
        village.commissions.add(order)
        world.persistentStateManager.getOrCreate(VillageRegistry.TYPE, VillageRegistry.KEY).markDirty()
        return VillageTradeExecutionResult(
            true,
            Text.literal("${offer.professionName} accepted the commission. Return in ${formatTime(orderDuration)}.")
        )
    }

    fun claim(
        world: ServerWorld,
        player: ServerPlayerEntity,
        village: VillageData,
        orderId: String
    ): VillageTradeExecutionResult {
        val order = village.commissions.firstOrNull { it.id == orderId && !it.claimed }
            ?: return VillageTradeExecutionResult(false, Text.literal("The clerk cannot find that commission order."))
        val remaining = order.readyTick - world.time
        if (remaining > 0L) {
            return VillageTradeExecutionResult(false, Text.literal("That commission will be ready in ${formatTime(remaining)}."))
        }
        val abandoned = world.time - order.readyTick >= ABANDONED_COMMISSION_TICKS
        if (order.playerId != player.uuid && !abandoned) {
            val reclaimIn = (order.readyTick + ABANDONED_COMMISSION_TICKS - world.time).coerceAtLeast(0L)
            return VillageTradeExecutionResult(false, Text.literal("That commission is still held for its patron. Check back in ${formatTime(reclaimIn)}."))
        }
        if (order.offerId == "armorer_custom") {
            val stack = order.inputStack.copyWithCount(1)
            if (!player.inventory.insertStack(stack)) {
                player.dropItem(stack, false)
            }
            order.claimed = true
            world.persistentStateManager.getOrCreate(VillageRegistry.TYPE, VillageRegistry.KEY).markDirty()
            return VillageTradeExecutionResult(true, Text.literal("Collected ${order.displayName}."))
        }
        if (order.offerId.startsWith(SMITH_REFORGE_PREFIX)) {
            val definitionId = order.offerId.removePrefix(SMITH_REFORGE_PREFIX)
            val definition = DEFINITIONS.firstOrNull { it.id == definitionId }
                ?: return VillageTradeExecutionResult(false, Text.literal("The artisan no longer knows how to finish that reforge."))
            val stack = definition.createStack(world, order.inputStack, order.quantity)
            if (!player.inventory.insertStack(stack)) {
                player.dropItem(stack, false)
            }
            order.claimed = true
            world.persistentStateManager.getOrCreate(VillageRegistry.TYPE, VillageRegistry.KEY).markDirty()
            return VillageTradeExecutionResult(true, Text.literal("Collected ${order.displayName}."))
        }
        val definition = DEFINITIONS.firstOrNull { it.id == order.offerId }
            ?: return VillageTradeExecutionResult(false, Text.literal("The artisan no longer knows how to finish that commission."))
        val stack = definition.createStack(world, order.inputStack, order.quantity)
        if (!player.inventory.insertStack(stack)) {
            player.dropItem(stack, false)
        }
        order.claimed = true
        world.persistentStateManager.getOrCreate(VillageRegistry.TYPE, VillageRegistry.KEY).markDirty()
        return VillageTradeExecutionResult(true, Text.literal("Collected ${order.displayName}."))
    }

    private fun activeCommissionCap(tier: VillageTier): Int = when (tier) {
        VillageTier.HAMLET,
        VillageTier.SETTLEMENT -> 0
        VillageTier.VILLAGE -> 1
        VillageTier.TOWN -> 2
        VillageTier.CITY -> 3
    }

    private fun playerCommissionCap(tier: VillageTier): Int = activeCommissionCap(tier)

    private fun orderSmithReforge(
        world: ServerWorld,
        player: ServerPlayerEntity,
        village: VillageData,
        payload: String,
        occupiedProfessionIds: Set<String>,
        inputStack: ItemStack?
    ): VillageTradeExecutionResult {
        val offerId = payload.removePrefix(SMITH_REFORGE_PREFIX)
        val offer = DEFINITIONS.firstOrNull {
            it.id == offerId &&
                it.inputMode == CommissionInputMode.NONE &&
                (
                    it.professionId == "minecraft:weaponsmith" ||
                        it.professionId == "minecraft:toolsmith" ||
                        it.professionId == "minecraft:armorer"
                    )
        } ?: return VillageTradeExecutionResult(false, Text.literal("Choose a smithing pattern first."))
        if (offer.professionId !in occupiedProfessionIds) {
            return VillageTradeExecutionResult(false, Text.literal("${village.name} has no ${offer.professionName} ready for that work."))
        }
        if (village.tier.ordinal < offer.unlockTier.ordinal) {
            return VillageTradeExecutionResult(false, Text.literal("${village.name} must grow before that reforge is available."))
        }
        val input = inputStack?.takeUnless { it.isEmpty }
            ?: return VillageTradeExecutionResult(false, Text.literal("Set a ${reforgeInputName(offer.professionId)} on the work order first."))
        val validInput = when (offer.professionId) {
            "minecraft:weaponsmith" -> isWeaponItem(input.item)
            "minecraft:toolsmith" -> isToolItem(input.item)
            "minecraft:armorer" -> isArmorPiece(input.item)
            else -> false
        }
        if (!validInput) {
            return VillageTradeExecutionResult(false, Text.literal("That artisan cannot reforge this item."))
        }
        if (!canVillageWorkItem(village.tier, offer.professionId, input.item)) {
            return VillageTradeExecutionResult(false, Text.literal("${village.name}'s ${offer.professionName} cannot work that material yet."))
        }
        val activeCount = village.commissions.count { !it.claimed }
        if (activeCount >= activeCommissionCap(village.tier)) {
            return VillageTradeExecutionResult(false, Text.literal("${village.name}'s artisans are already fully booked."))
        }
        val playerActiveCount = village.commissions.count { !it.claimed && it.playerId == player.uuid }
        if (playerActiveCount >= playerCommissionCap(village.tier)) {
            return VillageTradeExecutionResult(false, Text.literal("The clerk will only hold ${playerCommissionCap(village.tier)} active commission(s) for you here."))
        }

        val submittedInput = input.copyWithCount(1)
        val orderCost = reforgeCostFor(offer)
        val orderDuration = REFORGE_DURATION_TICKS
        val ledger = PlayerEmeraldLedger.get(world)
        if (!ledger.withdraw(player.uuid, orderCost)) {
            return VillageTradeExecutionResult(false, Text.literal("Your ledger needs $orderCost emeralds for that reforge."))
        }
        inputStack.decrement(1)
        village.treasuryEmeralds = village.treasuryEmeralds.coerceAtLeast(0).saturatingAdd(orderCost)
        val order = VillageCommissionData(
            id = UUID.randomUUID().toString(),
            playerId = player.uuid,
            offerId = "$SMITH_REFORGE_PREFIX${offer.id}",
            itemId = itemIdOf(submittedInput.item),
            displayName = "${offer.quality} ${baseItemName(submittedInput)}",
            professionId = offer.professionId,
            professionName = offer.professionName,
            emeraldCost = orderCost,
            orderedTick = world.time,
            readyTick = world.time + orderDuration,
            quantity = 1,
            inputStack = submittedInput
        )
        village.commissions.add(order)
        world.persistentStateManager.getOrCreate(VillageRegistry.TYPE, VillageRegistry.KEY).markDirty()
        return VillageTradeExecutionResult(
            true,
            Text.literal("${offer.professionName} accepted the reforge. Return in ${formatTime(orderDuration)}.")
        )
    }

    private fun orderCustomArmorer(
        world: ServerWorld,
        player: ServerPlayerEntity,
        village: VillageData,
        payload: String,
        occupiedProfessionIds: Set<String>,
        inputStack: ItemStack?
    ): VillageTradeExecutionResult {
        if ("minecraft:armorer" !in occupiedProfessionIds) {
            return VillageTradeExecutionResult(false, Text.literal("${village.name} has no Armorer ready for that work."))
        }
        val parts = payload.split("|")
        val armorOffer = DEFINITIONS.firstOrNull { it.id == parts.getOrNull(1) && it.professionId == "minecraft:armorer" && it.inputMode == CommissionInputMode.NONE }
            ?: return VillageTradeExecutionResult(false, Text.literal("Choose an armor piece first."))
        if (village.tier.ordinal < armorOffer.unlockTier.ordinal) {
            return VillageTradeExecutionResult(false, Text.literal("${village.name} must grow before that armor is available."))
        }
        val trimKey = parts.getOrNull(2).orEmpty()
        val materialKey = parts.getOrNull(3).orEmpty()
        val trimOffer = if (trimKey.isNotBlank() && materialKey.isNotBlank()) {
            if (trimKey !in dailyTrimPatternKeys(world.time)) {
                return VillageTradeExecutionResult(false, Text.literal("That trim pattern is not being offered today."))
            }
            DEFINITIONS.firstOrNull {
                it.professionId == "minecraft:armorer" &&
                    it.inputMode == CommissionInputMode.ARMOR &&
                    trimTemplateKey(it.displayName) == trimKey &&
                    trimMaterialKey(it.displayName) == materialKey
            } ?: return VillageTradeExecutionResult(false, Text.literal("That trim and material pairing is not available here."))
        } else {
            null
        }
        if (trimOffer != null && village.tier.ordinal < trimOffer.unlockTier.ordinal) {
            return VillageTradeExecutionResult(false, Text.literal("${village.name} must grow before that trim is available."))
        }
        val activeCount = village.commissions.count { !it.claimed }
        if (activeCount >= activeCommissionCap(village.tier)) {
            return VillageTradeExecutionResult(false, Text.literal("${village.name}'s artisans are already fully booked."))
        }
        val playerActiveCount = village.commissions.count { !it.claimed && it.playerId == player.uuid }
        if (playerActiveCount >= playerCommissionCap(village.tier)) {
            return VillageTradeExecutionResult(false, Text.literal("The clerk will only hold ${playerCommissionCap(village.tier)} active commission(s) for you here."))
        }

        val suppliedArmor = inputStack
            ?.takeUnless { it.isEmpty }
            ?.let { stack ->
                if (!isArmorPiece(stack.item)) {
                    return VillageTradeExecutionResult(false, Text.literal("Set a piece of armor on the work order first."))
                }
                if (!canVillageWorkItem(village.tier, "minecraft:armorer", stack.item)) {
                    return VillageTradeExecutionResult(false, Text.literal("${village.name}'s Armorer cannot work that material yet."))
                }
                stack.copyWithCount(1)
            }
        val baseCost = if (suppliedArmor == null) armorOffer.emeraldCost else 0
        val totalCost = baseCost.saturatingAdd(trimOffer?.emeraldCost ?: 0)
        val totalDuration = if (trimOffer != null) TRIM_DURATION_TICKS else NEW_ITEM_DURATION_TICKS
        val ledger = PlayerEmeraldLedger.get(world)
        if (!ledger.withdraw(player.uuid, totalCost)) {
            return VillageTradeExecutionResult(false, Text.literal("Your ledger needs $totalCost emeralds for that commission."))
        }
        if (suppliedArmor != null) {
            inputStack.decrement(1)
        }

        val stack = suppliedArmor ?: ItemStack(armorOffer.item)
        val display = when {
            trimOffer != null -> "${trimOffer.displayName} ${baseItemName(stack)}"
            suppliedArmor != null -> "Reworked ${baseItemName(stack)}"
            else -> armorOffer.displayName
        }
        stack.set(DataComponentTypes.CUSTOM_NAME, Text.literal(display))
        if (trimOffer != null) {
            applyTrimFromDisplayName(world, stack, trimOffer.displayName)
        }

        village.treasuryEmeralds = village.treasuryEmeralds.coerceAtLeast(0).saturatingAdd(totalCost)
        village.commissions.add(
            VillageCommissionData(
                id = UUID.randomUUID().toString(),
                playerId = player.uuid,
                offerId = "armorer_custom",
                itemId = itemIdOf(stack.item),
                displayName = display,
                professionId = "minecraft:armorer",
                professionName = "Armorer",
                emeraldCost = totalCost,
                orderedTick = world.time,
                readyTick = world.time + totalDuration,
                inputStack = stack
            )
        )
        world.persistentStateManager.getOrCreate(VillageRegistry.TYPE, VillageRegistry.KEY).markDirty()
        return VillageTradeExecutionResult(true, Text.literal("Armorer accepted the commission. Return in ${formatTime(totalDuration)}."))
    }

    private fun CommissionDefinition.toOffer(): CommissionOffer = CommissionOffer(
        id = id,
        item = ItemStack(item),
        displayName = displayName,
        professionId = professionId,
        professionName = professionName,
        quality = quality,
        description = description,
        emeraldCost = emeraldCost,
        durationTicks = capCommissionDuration(durationTicks),
        requiresInput = requiresInput()
    )

    private fun formatTime(ticks: Long): String {
        val minutes = (ticks / TICKS_PER_MINUTE).coerceAtLeast(1L)
        return if (minutes == 1L) "1 minute" else "$minutes minutes"
    }

    private fun capCommissionDuration(ticks: Long): Long = ticks.coerceAtMost(MAX_COMMISSION_WAIT_TICKS)

    private fun Int.saturatingAdd(amount: Int): Int {
        val result = toLong() + amount.toLong()
        return result.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    private fun Int.saturatingMultiply(amount: Int): Int {
        val result = toLong() * amount.coerceAtLeast(1).toLong()
        return result.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    private fun Long.saturatingMultiply(amount: Int): Long {
        val result = this * amount.coerceAtLeast(1).toLong()
        return result.coerceAtMost(Long.MAX_VALUE)
    }

    private data class CommissionDefinition(
        val id: String,
        val professionId: String,
        val professionName: String,
        val item: Item,
        val displayName: String,
        val quality: String,
        val description: String,
        val emeraldCost: Int,
        val durationTicks: Long,
        val unlockTier: VillageTier,
        val inputMode: CommissionInputMode = CommissionInputMode.NONE,
        val stackFactory: (ServerWorld, CommissionDefinition, ItemStack, Int) -> ItemStack
    ) {
        fun createStack(world: ServerWorld, inputStack: ItemStack = ItemStack.EMPTY, quantity: Int = 1): ItemStack = stackFactory(world, this, inputStack, quantity)
        fun requiresInput(): Boolean = inputMode != CommissionInputMode.NONE
    }

    private enum class CommissionInputMode {
        NONE,
        ARMOR,
        LIBRARIAN,
        TOOL_REPAIR,
        WEAPON_REPAIR
    }

    private data class TrimPatternDefinition(
        val key: String,
        val label: String,
        val patternKey: RegistryKey<net.minecraft.item.trim.ArmorTrimPattern>
    )

    private data class TrimMaterialDefinition(
        val key: String,
        val label: String,
        val materialKey: RegistryKey<net.minecraft.item.trim.ArmorTrimMaterial>,
        val cost: Int
    )

    private data class EnchantDefinition(
        val id: String,
        val label: String,
        val key: RegistryKey<Enchantment>,
        val villageLevel: Int,
        val townLevel: Int,
        val cityLevel: Int,
        val villageCost: Int,
        val townCost: Int,
        val cityCost: Int
    )

    private val DEFINITIONS: List<CommissionDefinition> by lazy { listOf(
        repair("weaponsmith_repair", "minecraft:weaponsmith", "Weaponsmith", "Weapon Repair", "Repair", "Repair supplied weapon", 75, 6, VillageTier.VILLAGE, CommissionInputMode.WEAPON_REPAIR),
        repair("toolsmith_repair", "minecraft:toolsmith", "Toolsmith", "Tool Repair", "Repair", "Repair supplied tool", 65, 6, VillageTier.VILLAGE, CommissionInputMode.TOOL_REPAIR),
        repair("armorer_repair", "minecraft:armorer", "Armorer", "Armor Repair", "Repair", "Repair supplied armor", 75, 6, VillageTier.VILLAGE, CommissionInputMode.ARMOR),
        smith("weaponsmith_work_iron_sword", "minecraft:weaponsmith", "Weaponsmith", Items.IRON_SWORD, "Work Iron Sword", "Work", "+1 attack damage", 300, 20, VillageTier.VILLAGE, damage = 1.0),
        smith("weaponsmith_work_iron_axe", "minecraft:weaponsmith", "Weaponsmith", Items.IRON_AXE, "Work Iron Axe", "Work", "+1 attack damage", 325, 20, VillageTier.VILLAGE, damage = 1.0),
        smith("weaponsmith_work_diamond_sword", "minecraft:weaponsmith", "Weaponsmith", Items.DIAMOND_SWORD, "Work Diamond Sword", "Work", "+2 attack damage", 450, 35, VillageTier.TOWN, damage = 2.0),
        smith("weaponsmith_work_diamond_axe", "minecraft:weaponsmith", "Weaponsmith", Items.DIAMOND_AXE, "Work Diamond Axe", "Work", "+2 attack damage", 475, 35, VillageTier.TOWN, damage = 2.0),
        smith("weaponsmith_masterwork_diamond_sword", "minecraft:weaponsmith", "Weaponsmith", Items.DIAMOND_SWORD, "Masterwork Diamond Sword", "Masterwork", "+4 attack damage", 500, 60, VillageTier.CITY, damage = 4.0),
        smith("weaponsmith_masterwork_diamond_axe", "minecraft:weaponsmith", "Weaponsmith", Items.DIAMOND_AXE, "Masterwork Diamond Axe", "Masterwork", "+4 attack damage", 500, 60, VillageTier.CITY, damage = 4.0),
        smith("toolsmith_work_iron_pickaxe", "minecraft:toolsmith", "Toolsmith", Items.IRON_PICKAXE, "Work Iron Pickaxe", "Work", "+2 mining efficiency", 325, 20, VillageTier.VILLAGE, mining = 2.0),
        smith("toolsmith_work_iron_axe", "minecraft:toolsmith", "Toolsmith", Items.IRON_AXE, "Work Iron Axe", "Work", "+2 mining efficiency", 325, 20, VillageTier.VILLAGE, mining = 2.0),
        smith("toolsmith_work_iron_shovel", "minecraft:toolsmith", "Toolsmith", Items.IRON_SHOVEL, "Work Iron Shovel", "Work", "+2 mining efficiency", 275, 15, VillageTier.VILLAGE, mining = 2.0),
        smith("toolsmith_work_iron_hoe", "minecraft:toolsmith", "Toolsmith", Items.IRON_HOE, "Work Iron Hoe", "Work", "+2 mining efficiency", 275, 15, VillageTier.VILLAGE, mining = 2.0),
        smith("toolsmith_work_diamond_pickaxe", "minecraft:toolsmith", "Toolsmith", Items.DIAMOND_PICKAXE, "Work Diamond Pickaxe", "Work", "+4 mining efficiency", 475, 35, VillageTier.TOWN, mining = 4.0),
        smith("toolsmith_work_diamond_axe", "minecraft:toolsmith", "Toolsmith", Items.DIAMOND_AXE, "Work Diamond Axe", "Work", "+4 mining efficiency", 475, 35, VillageTier.TOWN, mining = 4.0),
        smith("toolsmith_work_diamond_shovel", "minecraft:toolsmith", "Toolsmith", Items.DIAMOND_SHOVEL, "Work Diamond Shovel", "Work", "+4 mining efficiency", 400, 30, VillageTier.TOWN, mining = 4.0),
        smith("toolsmith_work_diamond_hoe", "minecraft:toolsmith", "Toolsmith", Items.DIAMOND_HOE, "Work Diamond Hoe", "Work", "+4 mining efficiency", 400, 30, VillageTier.TOWN, mining = 4.0),
        smith("toolsmith_masterwork_diamond_pickaxe", "minecraft:toolsmith", "Toolsmith", Items.DIAMOND_PICKAXE, "Masterwork Diamond Pickaxe", "Masterwork", "+7 mining efficiency", 500, 60, VillageTier.CITY, mining = 7.0),
        smith("toolsmith_masterwork_diamond_axe", "minecraft:toolsmith", "Toolsmith", Items.DIAMOND_AXE, "Masterwork Diamond Axe", "Masterwork", "+7 mining efficiency", 500, 60, VillageTier.CITY, mining = 7.0),
        smith("toolsmith_masterwork_diamond_shovel", "minecraft:toolsmith", "Toolsmith", Items.DIAMOND_SHOVEL, "Masterwork Diamond Shovel", "Masterwork", "+7 mining efficiency", 425, 55, VillageTier.CITY, mining = 7.0),
        smith("toolsmith_masterwork_diamond_hoe", "minecraft:toolsmith", "Toolsmith", Items.DIAMOND_HOE, "Masterwork Diamond Hoe", "Masterwork", "+7 mining efficiency", 425, 55, VillageTier.CITY, mining = 7.0),
        simple("armorer_work_iron_helmet", "minecraft:armorer", "Armorer", Items.IRON_HELMET, "Work Iron Helmet", "Work", "Commissioned armor piece", 275, 20, VillageTier.VILLAGE),
        simple("armorer_work_iron_chestplate", "minecraft:armorer", "Armorer", Items.IRON_CHESTPLATE, "Work Iron Chestplate", "Work", "Commissioned armor piece", 350, 25, VillageTier.VILLAGE),
        simple("armorer_work_iron_leggings", "minecraft:armorer", "Armorer", Items.IRON_LEGGINGS, "Work Iron Leggings", "Work", "Commissioned armor piece", 325, 25, VillageTier.VILLAGE),
        simple("armorer_work_iron_boots", "minecraft:armorer", "Armorer", Items.IRON_BOOTS, "Work Iron Boots", "Work", "Commissioned armor piece", 250, 20, VillageTier.VILLAGE),
        simple("armorer_work_diamond_helmet", "minecraft:armorer", "Armorer", Items.DIAMOND_HELMET, "Work Diamond Helmet", "Work", "Commissioned armor piece", 425, 35, VillageTier.TOWN),
        simple("armorer_work_diamond_chestplate", "minecraft:armorer", "Armorer", Items.DIAMOND_CHESTPLATE, "Work Diamond Chestplate", "Work", "Commissioned armor piece", 500, 40, VillageTier.TOWN),
        simple("armorer_work_diamond_leggings", "minecraft:armorer", "Armorer", Items.DIAMOND_LEGGINGS, "Work Diamond Leggings", "Work", "Commissioned armor piece", 475, 40, VillageTier.TOWN),
        simple("armorer_work_diamond_boots", "minecraft:armorer", "Armorer", Items.DIAMOND_BOOTS, "Work Diamond Boots", "Work", "Commissioned armor piece", 400, 35, VillageTier.TOWN),
        simple("armorer_masterwork_diamond_helmet", "minecraft:armorer", "Armorer", Items.DIAMOND_HELMET, "Masterwork Diamond Helmet", "Masterwork", "Commissioned armor piece", 500, 60, VillageTier.CITY),
        simple("armorer_masterwork_diamond_chestplate", "minecraft:armorer", "Armorer", Items.DIAMOND_CHESTPLATE, "Masterwork Diamond Chestplate", "Masterwork", "Commissioned armor piece", 500, 65, VillageTier.CITY),
        simple("armorer_masterwork_diamond_leggings", "minecraft:armorer", "Armorer", Items.DIAMOND_LEGGINGS, "Masterwork Diamond Leggings", "Masterwork", "Commissioned armor piece", 500, 65, VillageTier.CITY),
        simple("armorer_masterwork_diamond_boots", "minecraft:armorer", "Armorer", Items.DIAMOND_BOOTS, "Masterwork Diamond Boots", "Masterwork", "Commissioned armor piece", 450, 60, VillageTier.CITY),
        potion("cleric_healing_order", "Healing Potion Batch", "healing", Items.POTION, 6, 300, 15, VillageTier.VILLAGE),
        potion("cleric_strong_healing_order", "Healing II Potion Batch", "strong_healing", Items.POTION, 6, 500, 20, VillageTier.VILLAGE),
        potion("cleric_swiftness_order", "Swiftness Potion Batch", "swiftness", Items.POTION, 6, 550, 20, VillageTier.VILLAGE),
        potion("cleric_regeneration_order", "Regeneration Potion Batch", "regeneration", Items.POTION, 6, 850, 30, VillageTier.TOWN),
        potion("cleric_strength_order", "Strength II Potion Batch", "strong_strength", Items.POTION, 6, 900, 35, VillageTier.TOWN),
        potion("cleric_fire_resistance_order", "Fire Resistance Batch", "fire_resistance", Items.POTION, 6, 1100, 40, VillageTier.TOWN),
        potion("cleric_water_breathing_order", "Water Breathing Batch", "water_breathing", Items.POTION, 6, 1300, 45, VillageTier.TOWN),
        potion("cleric_strong_swiftness_order", "Swiftness II Potion Batch", "strong_swiftness", Items.POTION, 6, 1500, 55, VillageTier.CITY),
    ) + trimDefinitions() + bookDefinitions() }

    private fun trimDefinitions(): List<CommissionDefinition> =
        ALL_TRIM_PATTERNS.flatMap { pattern ->
            ALL_TRIM_MATERIALS.map { material ->
                trim(
                    id = "armorer_${pattern.key}_${material.key}_trim",
                    displayName = "${pattern.label} ${material.label} Trim",
                    patternKey = pattern.patternKey,
                    materialKey = material.materialKey,
                    cost = 200 + material.cost,
                    minutes = 5,
                    tier = VillageTier.VILLAGE
                )
            }
        }

    private fun bookDefinitions(): List<CommissionDefinition> =
        ALL_ENCHANTS.flatMap { enchant ->
            listOfNotNull(
                book("${enchant.id}_village", "${enchant.label} ${enchant.villageLevel} Enchant", enchant.key, enchant.villageLevel, markedDownEnchantCost(enchant.villageCost), 25, VillageTier.VILLAGE),
                book("${enchant.id}_town", "${enchant.label} ${enchant.townLevel} Enchant", enchant.key, enchant.townLevel, markedDownEnchantCost(enchant.townCost), 45, VillageTier.TOWN)
                    .takeIf { enchant.townLevel > enchant.villageLevel },
                book("${enchant.id}_city", "${enchant.label} ${enchant.cityLevel} Enchant", enchant.key, enchant.cityLevel, markedDownEnchantCost(enchant.cityCost), 60, VillageTier.CITY)
                    .takeIf { enchant.cityLevel > enchant.townLevel }
            )
        }

    private fun markedDownEnchantCost(cost: Int): Int {
        val reduced = (cost / 4).coerceAtLeast(75)
        return ((reduced + 12) / 25) * 25
    }

    private fun dailyTrimPatternKeys(worldTime: Long): Set<String> {
        val day = (worldTime / 24000L).coerceAtLeast(0L).toInt()
        return (0 until TRIMS_PER_DAY)
            .map { ALL_TRIM_PATTERNS[(day + it) % ALL_TRIM_PATTERNS.size].key }
            .toSet()
    }

    private fun smith(
        id: String,
        professionId: String,
        professionName: String,
        item: Item,
        displayName: String,
        quality: String,
        description: String,
        cost: Int,
        minutes: Long,
        tier: VillageTier,
        damage: Double = 0.0,
        mining: Double = 0.0,
        toughness: Double = 0.0
    ): CommissionDefinition = CommissionDefinition(id, professionId, professionName, item, displayName, quality, description, cost, NEW_ITEM_DURATION_TICKS, tier) { _, definition, input, _ ->
        val stack = if (input.isEmpty) ItemStack(definition.item) else input.copyWithCount(1)
        stack.set(
            DataComponentTypes.CUSTOM_NAME,
            Text.literal(if (input.isEmpty) definition.displayName else "${definition.quality} ${baseItemName(input)}")
        )
        val modifiers = stack.get(DataComponentTypes.ATTRIBUTE_MODIFIERS) ?: AttributeModifiersComponent.DEFAULT
        var next = modifiers
        if (damage > 0.0) {
            next = next.with(EntityAttributes.GENERIC_ATTACK_DAMAGE, modifier(id, damage), AttributeModifierSlot.MAINHAND)
        }
        if (mining > 0.0) {
            next = next.with(EntityAttributes.PLAYER_MINING_EFFICIENCY, modifier(id, mining), AttributeModifierSlot.MAINHAND)
        }
        if (toughness > 0.0) {
            next = next.with(EntityAttributes.GENERIC_ARMOR_TOUGHNESS, modifier(id, toughness), AttributeModifierSlot.CHEST)
        }
        stack.set(DataComponentTypes.ATTRIBUTE_MODIFIERS, next)
        stack
    }

    private fun armorwork(
        id: String,
        displayName: String,
        quality: String,
        description: String,
        cost: Int,
        minutes: Long,
        tier: VillageTier,
        toughness: Double
    ): CommissionDefinition = CommissionDefinition(
        id,
        "minecraft:armorer",
        "Armorer",
        Items.DIAMOND_CHESTPLATE,
        displayName,
        quality,
        description,
        cost,
        minutes * TICKS_PER_MINUTE,
        tier,
        CommissionInputMode.ARMOR
    ) { _, definition, input, _ ->
        val stack = input.copyWithCount(1)
        stack.set(DataComponentTypes.CUSTOM_NAME, Text.literal("${definition.quality} ${baseItemName(input)}"))
        val modifiers = stack.get(DataComponentTypes.ATTRIBUTE_MODIFIERS) ?: AttributeModifiersComponent.DEFAULT
        stack.set(
            DataComponentTypes.ATTRIBUTE_MODIFIERS,
            modifiers.with(EntityAttributes.GENERIC_ARMOR_TOUGHNESS, modifier(id, toughness), armorSlotFor(input.item))
        )
        stack
    }

    private fun trim(
        id: String,
        displayName: String,
        patternKey: RegistryKey<net.minecraft.item.trim.ArmorTrimPattern>,
        materialKey: RegistryKey<net.minecraft.item.trim.ArmorTrimMaterial>,
        cost: Int,
        minutes: Long,
        tier: VillageTier
    ): CommissionDefinition = CommissionDefinition(id, "minecraft:armorer", "Armorer", Items.DIAMOND_CHESTPLATE, displayName, "Trim", "Custom trim selection", cost, TRIM_DURATION_TICKS, tier, CommissionInputMode.ARMOR) { world, definition, input, _ ->
        val stack = input.copyWithCount(1)
        stack.set(DataComponentTypes.CUSTOM_NAME, Text.literal("${definition.displayName} ${baseItemName(input)}"))
        val pattern = world.registryManager.getWrapperOrThrow(RegistryKeys.TRIM_PATTERN).getOrThrow(patternKey)
        val material = world.registryManager.getWrapperOrThrow(RegistryKeys.TRIM_MATERIAL).getOrThrow(materialKey)
        stack.set(DataComponentTypes.TRIM, ArmorTrim(material, pattern))
        stack
    }

    private fun potion(
        id: String,
        displayName: String,
        potionId: String,
        item: Item,
        count: Int,
        cost: Int,
        minutes: Long,
        tier: VillageTier
    ): CommissionDefinition = CommissionDefinition(id, "minecraft:cleric", "Cleric", item, displayName, displayName.removeSuffix(" Batch"), "Potions to order", cost, POTION_DURATION_TICKS, tier) { _, definition, _, quantity ->
        val stack = PotionContentsComponent.createStack(definition.item, potionEntry(potionId))
        stack.count = quantity.coerceAtLeast(1)
        stack.set(DataComponentTypes.CUSTOM_NAME, Text.literal(if (quantity > 1) "${definition.displayName} x$quantity" else definition.displayName))
        stack
    }

    private fun book(
        id: String,
        displayName: String,
        enchantmentKey: RegistryKey<Enchantment>,
        level: Int,
        cost: Int,
        minutes: Long,
        tier: VillageTier
    ): CommissionDefinition = CommissionDefinition(id, "minecraft:librarian", "Librarian", Items.ENCHANTED_BOOK, displayName, "Enchanting", "Apply to a book or enchantable item", cost, ENCHANT_DURATION_TICKS, tier, CommissionInputMode.LIBRARIAN) { world, definition, input, _ ->
        val enchantment = world.registryManager.getWrapperOrThrow(RegistryKeys.ENCHANTMENT).getOrThrow(enchantmentKey)
        val stack = if (input.isOf(Items.BOOK)) {
            EnchantedBookItem.forEnchantment(EnchantmentLevelEntry(enchantment, level))
        } else {
            input.copyWithCount(1).also { it.addEnchantment(enchantment, level) }
        }
        stack.set(DataComponentTypes.CUSTOM_NAME, Text.literal(orderDisplayName(definition, input, 1)))
        stack
    }

    private fun simple(
        id: String,
        professionId: String,
        professionName: String,
        item: Item,
        displayName: String,
        quality: String,
        description: String,
        cost: Int,
        minutes: Long,
        tier: VillageTier
    ): CommissionDefinition = CommissionDefinition(id, professionId, professionName, item, displayName, quality, description, cost, NEW_ITEM_DURATION_TICKS, tier) { _, definition, input, _ ->
        val stack = if (input.isEmpty) ItemStack(definition.item) else input.copyWithCount(1)
        stack.set(
            DataComponentTypes.CUSTOM_NAME,
            Text.literal(if (input.isEmpty) definition.displayName else "${definition.quality} ${baseItemName(input)}")
        )
        stack
    }

    private fun repair(
        id: String,
        professionId: String,
        professionName: String,
        displayName: String,
        quality: String,
        description: String,
        cost: Int,
        minutes: Long,
        tier: VillageTier,
        inputMode: CommissionInputMode
    ): CommissionDefinition = CommissionDefinition(id, professionId, professionName, Items.ANVIL, displayName, quality, description, cost, REPAIR_DURATION_TICKS, tier, inputMode) { _, definition, input, _ ->
        val stack = input.copyWithCount(1)
        stack.damage = 0
        stack.set(DataComponentTypes.CUSTOM_NAME, Text.literal("${definition.displayName}: ${baseItemName(input)}"))
        stack
    }

    private fun prepareInputForOrder(definition: CommissionDefinition, inputStack: ItemStack?): ItemStack? {
        if (!definition.requiresInput()) return ItemStack.EMPTY
        val stack = inputStack?.takeUnless { it.isEmpty } ?: return null
        return when (definition.inputMode) {
            CommissionInputMode.ARMOR -> stack.takeIf { isArmorPiece(it.item) }?.copyWithCount(1)
            CommissionInputMode.LIBRARIAN -> stack.takeIf { isLibrarianInput(it) }?.copyWithCount(1)
            CommissionInputMode.TOOL_REPAIR -> stack.takeIf { isToolItem(it.item) && it.isDamageable }?.copyWithCount(1)
            CommissionInputMode.WEAPON_REPAIR -> stack.takeIf { isWeaponItem(it.item) && it.isDamageable }?.copyWithCount(1)
            CommissionInputMode.NONE -> ItemStack.EMPTY
        }
    }

    private fun inputPromptFor(definition: CommissionDefinition): String = when (definition.inputMode) {
        CommissionInputMode.ARMOR -> "Set a piece of armor on the work order first."
        CommissionInputMode.LIBRARIAN -> "Set a book or enchantable item on the lectern first."
        CommissionInputMode.TOOL_REPAIR -> "Set a damaged tool on the work order first."
        CommissionInputMode.WEAPON_REPAIR -> "Set a damaged weapon on the work order first."
        CommissionInputMode.NONE -> "That commission is not ready for an input item."
    }

    private fun orderDisplayName(definition: CommissionDefinition, inputStack: ItemStack, quantity: Int): String {
        if (definition.professionId == "minecraft:cleric" && quantity > 1) return "${definition.displayName} x$quantity"
        if (inputStack.isEmpty) return definition.displayName
        return when (definition.inputMode) {
            CommissionInputMode.ARMOR -> "${definition.displayName}: ${baseItemName(inputStack)}"
            CommissionInputMode.LIBRARIAN -> "${definition.displayName}: ${baseItemName(inputStack)}"
            CommissionInputMode.TOOL_REPAIR,
            CommissionInputMode.WEAPON_REPAIR -> "${definition.displayName}: ${baseItemName(inputStack)}"
            CommissionInputMode.NONE -> definition.displayName
        }
    }

    private fun commissionQuantityFor(definition: CommissionDefinition, requestedQuantity: Int): Int =
        if (definition.professionId == "minecraft:cleric") requestedQuantity.coerceIn(1, 5) else 1

    private fun baseItemName(stack: ItemStack): String = stack.name.string

    private fun isLibrarianInput(stack: ItemStack): Boolean =
        stack.isOf(Items.BOOK) || stack.isOf(Items.ENCHANTED_BOOK) || stack.isEnchantable

    private fun isArmorPiece(item: Item): Boolean = item in ARMOR_ITEMS

    private fun isToolItem(item: Item): Boolean = item in TOOL_ITEMS

    private fun isWeaponItem(item: Item): Boolean = item in WEAPON_ITEMS

    private fun reforgeInputName(professionId: String): String = when (professionId) {
        "minecraft:weaponsmith" -> "weapon"
        "minecraft:toolsmith" -> "tool"
        "minecraft:armorer" -> "piece of armor"
        else -> "item"
    }

    private fun armorSlotFor(item: Item): AttributeModifierSlot = when (item) {
        Items.LEATHER_HELMET, Items.CHAINMAIL_HELMET, Items.IRON_HELMET, Items.GOLDEN_HELMET, Items.DIAMOND_HELMET, Items.NETHERITE_HELMET -> AttributeModifierSlot.HEAD
        Items.LEATHER_LEGGINGS, Items.CHAINMAIL_LEGGINGS, Items.IRON_LEGGINGS, Items.GOLDEN_LEGGINGS, Items.DIAMOND_LEGGINGS, Items.NETHERITE_LEGGINGS -> AttributeModifierSlot.LEGS
        Items.LEATHER_BOOTS, Items.CHAINMAIL_BOOTS, Items.IRON_BOOTS, Items.GOLDEN_BOOTS, Items.DIAMOND_BOOTS, Items.NETHERITE_BOOTS -> AttributeModifierSlot.FEET
        else -> AttributeModifierSlot.CHEST
    }

    private fun modifier(id: String, value: Double): EntityAttributeModifier =
        EntityAttributeModifier(Identifier.of("sovereign", "commission_$id"), value, EntityAttributeModifier.Operation.ADD_VALUE)

    private fun applyTrimFromDisplayName(world: ServerWorld, stack: ItemStack, displayName: String) {
        val pattern = world.registryManager.getWrapperOrThrow(RegistryKeys.TRIM_PATTERN).getOrThrow(patternKeyFor(trimTemplateKey(displayName)))
        val material = world.registryManager.getWrapperOrThrow(RegistryKeys.TRIM_MATERIAL).getOrThrow(materialKeyFor(trimMaterialKey(displayName)))
        stack.set(DataComponentTypes.TRIM, ArmorTrim(material, pattern))
    }

    private fun trimTemplateKey(displayName: String): String {
        val name = displayName.lowercase()
        return when {
            "sentry" in name -> "sentry"
            "dune" in name -> "dune"
            "coast" in name -> "coast"
            "wild" in name -> "wild"
            "ward" in name -> "ward"
            "eye" in name -> "eye"
            "vex" in name -> "vex"
            "tide" in name -> "tide"
            "snout" in name -> "snout"
            "rib" in name -> "rib"
            "spire" in name -> "spire"
            "wayfinder" in name -> "wayfinder"
            "shaper" in name -> "shaper"
            "silence" in name -> "silence"
            "raiser" in name -> "raiser"
            "host" in name -> "host"
            "flow" in name -> "flow"
            "bolt" in name -> "bolt"
            else -> "coast"
        }
    }

    private fun trimMaterialKey(displayName: String): String {
        val name = displayName.lowercase()
        return when {
            "quartz" in name -> "quartz"
            "iron" in name -> "iron"
            "redstone" in name -> "redstone"
            "copper" in name -> "copper"
            "gold" in name -> "gold"
            "emerald" in name -> "emerald"
            "diamond" in name -> "diamond"
            "lapis" in name -> "lapis"
            else -> "gold"
        }
    }

    private fun patternKeyFor(key: String): RegistryKey<net.minecraft.item.trim.ArmorTrimPattern> = when (key) {
        "sentry" -> ArmorTrimPatterns.SENTRY
        "dune" -> ArmorTrimPatterns.DUNE
        "coast" -> ArmorTrimPatterns.COAST
        "wild" -> ArmorTrimPatterns.WILD
        "ward" -> ArmorTrimPatterns.WARD
        "eye" -> ArmorTrimPatterns.EYE
        "vex" -> ArmorTrimPatterns.VEX
        "tide" -> ArmorTrimPatterns.TIDE
        "snout" -> ArmorTrimPatterns.SNOUT
        "rib" -> ArmorTrimPatterns.RIB
        "spire" -> ArmorTrimPatterns.SPIRE
        "wayfinder" -> ArmorTrimPatterns.WAYFINDER
        "shaper" -> ArmorTrimPatterns.SHAPER
        "silence" -> ArmorTrimPatterns.SILENCE
        "raiser" -> ArmorTrimPatterns.RAISER
        "host" -> ArmorTrimPatterns.HOST
        "flow" -> ArmorTrimPatterns.FLOW
        "bolt" -> ArmorTrimPatterns.BOLT
        else -> ArmorTrimPatterns.COAST
    }

    private fun materialKeyFor(key: String): RegistryKey<net.minecraft.item.trim.ArmorTrimMaterial> = when (key) {
        "quartz" -> ArmorTrimMaterials.QUARTZ
        "iron" -> ArmorTrimMaterials.IRON
        "redstone" -> ArmorTrimMaterials.REDSTONE
        "copper" -> ArmorTrimMaterials.COPPER
        "gold" -> ArmorTrimMaterials.GOLD
        "emerald" -> ArmorTrimMaterials.EMERALD
        "diamond" -> ArmorTrimMaterials.DIAMOND
        "lapis" -> ArmorTrimMaterials.LAPIS
        else -> ArmorTrimMaterials.GOLD
    }

    private fun potionEntry(potionId: String): RegistryEntry<Potion> = when (potionId) {
        "healing" -> Potions.HEALING
        "strong_healing" -> Potions.STRONG_HEALING
        "swiftness" -> Potions.SWIFTNESS
        "strong_swiftness" -> Potions.STRONG_SWIFTNESS
        "regeneration" -> Potions.REGENERATION
        "strong_strength" -> Potions.STRONG_STRENGTH
        "fire_resistance" -> Potions.FIRE_RESISTANCE
        "water_breathing" -> Potions.WATER_BREATHING
        else -> Potions.HEALING
    }

    private fun reforgeCostFor(offer: CommissionDefinition): Int =
        (offer.emeraldCost - ORDER_PRODUCTION_PREMIUM).coerceIn(200, 400)

    private fun canVillageWorkItem(tier: VillageTier, professionId: String, item: Item): Boolean {
        val requiredTier = when (professionId) {
            "minecraft:armorer" -> armorWorkTier(item)
            "minecraft:toolsmith" -> toolWorkTier(item)
            "minecraft:weaponsmith" -> weaponWorkTier(item)
            else -> VillageTier.VILLAGE
        } ?: return false
        return tier.ordinal >= requiredTier.ordinal
    }

    private fun armorWorkTier(item: Item): VillageTier? = when (item) {
        Items.LEATHER_HELMET, Items.LEATHER_CHESTPLATE, Items.LEATHER_LEGGINGS, Items.LEATHER_BOOTS,
        Items.CHAINMAIL_HELMET, Items.CHAINMAIL_CHESTPLATE, Items.CHAINMAIL_LEGGINGS, Items.CHAINMAIL_BOOTS,
        Items.IRON_HELMET, Items.IRON_CHESTPLATE, Items.IRON_LEGGINGS, Items.IRON_BOOTS,
        Items.GOLDEN_HELMET, Items.GOLDEN_CHESTPLATE, Items.GOLDEN_LEGGINGS, Items.GOLDEN_BOOTS -> VillageTier.VILLAGE
        Items.DIAMOND_HELMET, Items.DIAMOND_CHESTPLATE, Items.DIAMOND_LEGGINGS, Items.DIAMOND_BOOTS -> VillageTier.TOWN
        Items.NETHERITE_HELMET, Items.NETHERITE_CHESTPLATE, Items.NETHERITE_LEGGINGS, Items.NETHERITE_BOOTS -> VillageTier.CITY
        else -> null
    }

    private fun toolWorkTier(item: Item): VillageTier? = when (item) {
        Items.WOODEN_PICKAXE, Items.WOODEN_AXE, Items.WOODEN_SHOVEL, Items.WOODEN_HOE,
        Items.STONE_PICKAXE, Items.STONE_AXE, Items.STONE_SHOVEL, Items.STONE_HOE,
        Items.IRON_PICKAXE, Items.IRON_AXE, Items.IRON_SHOVEL, Items.IRON_HOE,
        Items.GOLDEN_PICKAXE, Items.GOLDEN_AXE, Items.GOLDEN_SHOVEL, Items.GOLDEN_HOE -> VillageTier.VILLAGE
        Items.DIAMOND_PICKAXE, Items.DIAMOND_AXE, Items.DIAMOND_SHOVEL, Items.DIAMOND_HOE -> VillageTier.TOWN
        Items.NETHERITE_PICKAXE, Items.NETHERITE_AXE, Items.NETHERITE_SHOVEL, Items.NETHERITE_HOE -> VillageTier.CITY
        else -> null
    }

    private fun weaponWorkTier(item: Item): VillageTier? = when (item) {
        Items.WOODEN_SWORD, Items.STONE_SWORD, Items.IRON_SWORD, Items.GOLDEN_SWORD,
        Items.WOODEN_AXE, Items.STONE_AXE, Items.IRON_AXE, Items.GOLDEN_AXE,
        Items.BOW, Items.CROSSBOW, Items.TRIDENT -> VillageTier.VILLAGE
        Items.DIAMOND_SWORD, Items.DIAMOND_AXE -> VillageTier.TOWN
        Items.NETHERITE_SWORD, Items.NETHERITE_AXE -> VillageTier.CITY
        else -> null
    }

    private val ALL_TRIM_PATTERNS = listOf(
        TrimPatternDefinition("sentry", "Sentry", ArmorTrimPatterns.SENTRY),
        TrimPatternDefinition("dune", "Dune", ArmorTrimPatterns.DUNE),
        TrimPatternDefinition("coast", "Coast", ArmorTrimPatterns.COAST),
        TrimPatternDefinition("wild", "Wild", ArmorTrimPatterns.WILD),
        TrimPatternDefinition("ward", "Ward", ArmorTrimPatterns.WARD),
        TrimPatternDefinition("eye", "Eye", ArmorTrimPatterns.EYE),
        TrimPatternDefinition("vex", "Vex", ArmorTrimPatterns.VEX),
        TrimPatternDefinition("tide", "Tide", ArmorTrimPatterns.TIDE),
        TrimPatternDefinition("snout", "Snout", ArmorTrimPatterns.SNOUT),
        TrimPatternDefinition("rib", "Rib", ArmorTrimPatterns.RIB),
        TrimPatternDefinition("spire", "Spire", ArmorTrimPatterns.SPIRE),
        TrimPatternDefinition("wayfinder", "Wayfinder", ArmorTrimPatterns.WAYFINDER),
        TrimPatternDefinition("shaper", "Shaper", ArmorTrimPatterns.SHAPER),
        TrimPatternDefinition("silence", "Silence", ArmorTrimPatterns.SILENCE),
        TrimPatternDefinition("raiser", "Raiser", ArmorTrimPatterns.RAISER),
        TrimPatternDefinition("host", "Host", ArmorTrimPatterns.HOST),
        TrimPatternDefinition("flow", "Flow", ArmorTrimPatterns.FLOW),
        TrimPatternDefinition("bolt", "Bolt", ArmorTrimPatterns.BOLT)
    )

    private val ALL_TRIM_MATERIALS = listOf(
        TrimMaterialDefinition("quartz", "Quartz", ArmorTrimMaterials.QUARTZ, 80),
        TrimMaterialDefinition("iron", "Iron", ArmorTrimMaterials.IRON, 70),
        TrimMaterialDefinition("redstone", "Redstone", ArmorTrimMaterials.REDSTONE, 70),
        TrimMaterialDefinition("copper", "Copper", ArmorTrimMaterials.COPPER, 60),
        TrimMaterialDefinition("gold", "Gold", ArmorTrimMaterials.GOLD, 90),
        TrimMaterialDefinition("emerald", "Emerald", ArmorTrimMaterials.EMERALD, 110),
        TrimMaterialDefinition("diamond", "Diamond", ArmorTrimMaterials.DIAMOND, 160),
        TrimMaterialDefinition("lapis", "Lapis", ArmorTrimMaterials.LAPIS, 80)
    )

    private val ALL_ENCHANTS = listOf(
        EnchantDefinition("librarian_protection", "Protection", Enchantments.PROTECTION, 1, 3, 4, 350, 800, 1200),
        EnchantDefinition("librarian_fire_protection", "Fire Protection", Enchantments.FIRE_PROTECTION, 1, 3, 4, 300, 650, 950),
        EnchantDefinition("librarian_feather_falling", "Feather Falling", Enchantments.FEATHER_FALLING, 1, 3, 4, 300, 700, 1000),
        EnchantDefinition("librarian_blast_protection", "Blast Protection", Enchantments.BLAST_PROTECTION, 1, 3, 4, 300, 650, 950),
        EnchantDefinition("librarian_projectile_protection", "Projectile Protection", Enchantments.PROJECTILE_PROTECTION, 1, 3, 4, 300, 650, 950),
        EnchantDefinition("librarian_respiration", "Respiration", Enchantments.RESPIRATION, 1, 2, 3, 350, 700, 1000),
        EnchantDefinition("librarian_aqua_affinity", "Aqua Affinity", Enchantments.AQUA_AFFINITY, 1, 1, 1, 450, 450, 450),
        EnchantDefinition("librarian_thorns", "Thorns", Enchantments.THORNS, 1, 2, 3, 500, 950, 1400),
        EnchantDefinition("librarian_depth_strider", "Depth Strider", Enchantments.DEPTH_STRIDER, 1, 2, 3, 350, 750, 1100),
        EnchantDefinition("librarian_frost_walker", "Frost Walker", Enchantments.FROST_WALKER, 1, 1, 2, 450, 450, 950),
        EnchantDefinition("librarian_binding_curse", "Curse of Binding", Enchantments.BINDING_CURSE, 1, 1, 1, 250, 250, 250),
        EnchantDefinition("librarian_soul_speed", "Soul Speed", Enchantments.SOUL_SPEED, 1, 2, 3, 450, 850, 1250),
        EnchantDefinition("librarian_swift_sneak", "Swift Sneak", Enchantments.SWIFT_SNEAK, 1, 2, 3, 500, 950, 1450),
        EnchantDefinition("librarian_sharpness", "Sharpness", Enchantments.SHARPNESS, 1, 3, 5, 350, 800, 1400),
        EnchantDefinition("librarian_smite", "Smite", Enchantments.SMITE, 1, 3, 5, 300, 650, 1000),
        EnchantDefinition("librarian_bane_of_arthropods", "Bane of Arthropods", Enchantments.BANE_OF_ARTHROPODS, 1, 3, 5, 250, 550, 850),
        EnchantDefinition("librarian_knockback", "Knockback", Enchantments.KNOCKBACK, 1, 1, 2, 350, 350, 750),
        EnchantDefinition("librarian_fire_aspect", "Fire Aspect", Enchantments.FIRE_ASPECT, 1, 1, 2, 450, 450, 900),
        EnchantDefinition("librarian_looting", "Looting", Enchantments.LOOTING, 1, 2, 3, 500, 950, 1500),
        EnchantDefinition("librarian_sweeping_edge", "Sweeping Edge", Enchantments.SWEEPING_EDGE, 1, 2, 3, 350, 700, 1000),
        EnchantDefinition("librarian_efficiency", "Efficiency", Enchantments.EFFICIENCY, 1, 3, 5, 300, 750, 1250),
        EnchantDefinition("librarian_silk_touch", "Silk Touch", Enchantments.SILK_TOUCH, 1, 1, 1, 900, 900, 900),
        EnchantDefinition("librarian_unbreaking", "Unbreaking", Enchantments.UNBREAKING, 1, 2, 3, 400, 800, 1200),
        EnchantDefinition("librarian_fortune", "Fortune", Enchantments.FORTUNE, 1, 2, 3, 550, 1100, 1700),
        EnchantDefinition("librarian_power", "Power", Enchantments.POWER, 1, 3, 5, 300, 700, 1100),
        EnchantDefinition("librarian_punch", "Punch", Enchantments.PUNCH, 1, 1, 2, 350, 350, 750),
        EnchantDefinition("librarian_flame", "Flame", Enchantments.FLAME, 1, 1, 1, 650, 650, 650),
        EnchantDefinition("librarian_infinity", "Infinity", Enchantments.INFINITY, 1, 1, 1, 900, 900, 900),
        EnchantDefinition("librarian_luck_of_the_sea", "Luck of the Sea", Enchantments.LUCK_OF_THE_SEA, 1, 2, 3, 350, 700, 1000),
        EnchantDefinition("librarian_lure", "Lure", Enchantments.LURE, 1, 2, 3, 300, 650, 950),
        EnchantDefinition("librarian_loyalty", "Loyalty", Enchantments.LOYALTY, 1, 2, 3, 350, 700, 1000),
        EnchantDefinition("librarian_impaling", "Impaling", Enchantments.IMPALING, 1, 3, 5, 300, 700, 1100),
        EnchantDefinition("librarian_riptide", "Riptide", Enchantments.RIPTIDE, 1, 2, 3, 500, 950, 1400),
        EnchantDefinition("librarian_channeling", "Channeling", Enchantments.CHANNELING, 1, 1, 1, 800, 800, 800),
        EnchantDefinition("librarian_multishot", "Multishot", Enchantments.MULTISHOT, 1, 1, 1, 750, 750, 750),
        EnchantDefinition("librarian_quick_charge", "Quick Charge", Enchantments.QUICK_CHARGE, 1, 2, 3, 350, 700, 1050),
        EnchantDefinition("librarian_piercing", "Piercing", Enchantments.PIERCING, 1, 3, 4, 300, 650, 950),
        EnchantDefinition("librarian_density", "Density", Enchantments.DENSITY, 1, 3, 5, 350, 800, 1250),
        EnchantDefinition("librarian_breach", "Breach", Enchantments.BREACH, 1, 2, 4, 450, 900, 1400),
        EnchantDefinition("librarian_wind_burst", "Wind Burst", Enchantments.WIND_BURST, 1, 2, 3, 650, 1200, 1800),
        EnchantDefinition("librarian_mending", "Mending", Enchantments.MENDING, 1, 1, 1, 2500, 2500, 2500),
        EnchantDefinition("librarian_vanishing_curse", "Curse of Vanishing", Enchantments.VANISHING_CURSE, 1, 1, 1, 250, 250, 250)
    )

    private fun itemForId(itemId: String): Item {
        val id = Identifier.tryParse(itemId) ?: return Items.AIR
        return net.minecraft.registry.Registries.ITEM.get(id)
    }

    private fun itemIdOf(item: Item): String = net.minecraft.registry.Registries.ITEM.getId(item).toString()

    private val ARMOR_ITEMS = setOf(
        Items.LEATHER_HELMET,
        Items.LEATHER_CHESTPLATE,
        Items.LEATHER_LEGGINGS,
        Items.LEATHER_BOOTS,
        Items.CHAINMAIL_HELMET,
        Items.CHAINMAIL_CHESTPLATE,
        Items.CHAINMAIL_LEGGINGS,
        Items.CHAINMAIL_BOOTS,
        Items.IRON_HELMET,
        Items.IRON_CHESTPLATE,
        Items.IRON_LEGGINGS,
        Items.IRON_BOOTS,
        Items.GOLDEN_HELMET,
        Items.GOLDEN_CHESTPLATE,
        Items.GOLDEN_LEGGINGS,
        Items.GOLDEN_BOOTS,
        Items.DIAMOND_HELMET,
        Items.DIAMOND_CHESTPLATE,
        Items.DIAMOND_LEGGINGS,
        Items.DIAMOND_BOOTS,
        Items.NETHERITE_HELMET,
        Items.NETHERITE_CHESTPLATE,
        Items.NETHERITE_LEGGINGS,
        Items.NETHERITE_BOOTS
    )

    private val TOOL_ITEMS = setOf(
        Items.WOODEN_PICKAXE,
        Items.WOODEN_AXE,
        Items.WOODEN_SHOVEL,
        Items.WOODEN_HOE,
        Items.STONE_PICKAXE,
        Items.STONE_AXE,
        Items.STONE_SHOVEL,
        Items.STONE_HOE,
        Items.IRON_PICKAXE,
        Items.IRON_AXE,
        Items.IRON_SHOVEL,
        Items.IRON_HOE,
        Items.GOLDEN_PICKAXE,
        Items.GOLDEN_AXE,
        Items.GOLDEN_SHOVEL,
        Items.GOLDEN_HOE,
        Items.DIAMOND_PICKAXE,
        Items.DIAMOND_AXE,
        Items.DIAMOND_SHOVEL,
        Items.DIAMOND_HOE,
        Items.NETHERITE_PICKAXE,
        Items.NETHERITE_AXE,
        Items.NETHERITE_SHOVEL,
        Items.NETHERITE_HOE
    )

    private val WEAPON_ITEMS = setOf(
        Items.WOODEN_SWORD,
        Items.STONE_SWORD,
        Items.IRON_SWORD,
        Items.GOLDEN_SWORD,
        Items.DIAMOND_SWORD,
        Items.NETHERITE_SWORD,
        Items.WOODEN_AXE,
        Items.STONE_AXE,
        Items.IRON_AXE,
        Items.GOLDEN_AXE,
        Items.DIAMOND_AXE,
        Items.NETHERITE_AXE,
        Items.BOW,
        Items.CROSSBOW,
        Items.TRIDENT
    )
}
