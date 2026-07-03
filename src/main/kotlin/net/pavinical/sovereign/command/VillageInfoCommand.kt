package net.pavinical.sovereign.command

import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.minecraft.server.command.CommandManager.argument
import net.minecraft.server.command.CommandManager.literal
import net.minecraft.server.command.ServerCommandSource
import net.minecraft.server.world.ServerWorld
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.pavinical.sovereign.block.ClerkTableBlockEntity
import net.pavinical.sovereign.block.VillagerState
import net.pavinical.sovereign.block.professionDisplayName
import net.pavinical.sovereign.block.HamletSlot
import net.pavinical.sovereign.data.VillageData
import net.pavinical.sovereign.data.VillageTier
import net.pavinical.sovereign.economy.VillageEconomyService
import net.pavinical.sovereign.economy.VillageRegistry

private const val ARG_NAME = "name"
private const val ARG_NEW_NAME = "newname"
private const val ARG_AMOUNT = "amount"
private const val ARG_LEVEL = "level"
private const val MAX_VILLAGE_NAME_LENGTH = 16

object VillageInfoCommand {
    fun register() {
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            dispatcher.register(
                literal("villageinfo")
                    .requires { true }
                    .executes { context ->
                        executeNearestInfo(context.source)
                    }
                    .then(
                        literal("dump")
                            .then(
                                argument(ARG_NAME, StringArgumentType.greedyString())
                                    .executes { context ->
                                        execute(context.source, StringArgumentType.getString(context, ARG_NAME), true)
                                    }
                            )
                    )
                    .then(
                        argument(ARG_NAME, StringArgumentType.greedyString())
                            .executes { context ->
                                execute(context.source, StringArgumentType.getString(context, ARG_NAME), false)
                            }
                    )
            )
            dispatcher.register(
                literal("villagerestock")
                    .executes { context ->
                        executeRestockNearest(context.source)
                    }
                    .then(
                        argument(ARG_NAME, StringArgumentType.greedyString())
                            .executes { context ->
                                executeRestockNamed(context.source, StringArgumentType.getString(context, ARG_NAME))
                            }
                    )
            )
            dispatcher.register(
                literal("villagerename")
                    .then(
                        argument(ARG_NAME, StringArgumentType.string())
                            .then(
                                argument(ARG_NEW_NAME, StringArgumentType.greedyString())
                                    .executes { context ->
                                        executeRename(
                                            context.source,
                                            StringArgumentType.getString(context, ARG_NAME),
                                            StringArgumentType.getString(context, ARG_NEW_NAME)
                                        )
                                    }
                            )
                    )
            )
            dispatcher.register(
                literal("villageaddxp")
                    .then(
                        argument(ARG_AMOUNT, IntegerArgumentType.integer())
                            .executes { context ->
                                executeAddExperience(
                                    context.source,
                                    IntegerArgumentType.getInteger(context, ARG_AMOUNT)
                                )
                            }
                    )
            )
            dispatcher.register(
                literal("villagesetlevel")
                    .then(
                        argument(ARG_LEVEL, StringArgumentType.word())
                            .executes { context ->
                                executeSetLevel(
                                    context.source,
                                    StringArgumentType.getString(context, ARG_LEVEL)
                                )
                            }
                    )
            )
        }
    }

    private fun execute(source: ServerCommandSource, rawName: String, dump: Boolean): Int {
        val registry = source.server.overworld.persistentStateManager.getOrCreate(VillageRegistry.TYPE, VillageRegistry.KEY)
        val village = registry.getVillageByName(rawName)
            ?: run {
                source.sendFeedback({ Text.literal("No village named '$rawName' found.").formatted(Formatting.RED) }, false)
                return Command.SINGLE_SUCCESS
            }

        source.sendFeedback(
            {
                if (dump) {
                    Text.literal(
                        formatVillageInfoDump(source.server.overworld, village)
                    )
                } else {
                    buildVillageInfoText(source.server.overworld, village)
                }
            },
            false
        )

        return Command.SINGLE_SUCCESS
    }

    private fun executeNearestInfo(source: ServerCommandSource): Int {
        val player = source.player
            ?: run {
                source.sendFeedback({ Text.literal("Run /villageinfo <name> from the server console.").formatted(Formatting.RED) }, false)
                return Command.SINGLE_SUCCESS
            }
        val registry = source.server.overworld.persistentStateManager.getOrCreate(VillageRegistry.TYPE, VillageRegistry.KEY)
        val village = registry.getVillageWithinRadius(player.blockPos, 128)
            ?: run {
                source.sendFeedback({ Text.literal("No registered village found within 128 blocks.").formatted(Formatting.RED) }, false)
                return Command.SINGLE_SUCCESS
            }

        source.sendFeedback({ buildVillageInfoText(source.server.overworld, village) }, false)
        return Command.SINGLE_SUCCESS
    }

    private fun executeRename(source: ServerCommandSource, rawName: String, rawNewName: String): Int {
        val player = source.player
            ?: run {
                source.sendFeedback({ Text.literal("Only players can rename villages.") }, false)
                return Command.SINGLE_SUCCESS
            }
        val registry = source.server.overworld.persistentStateManager.getOrCreate(VillageRegistry.TYPE, VillageRegistry.KEY)
        val village = registry.getVillageByName(rawName)
            ?: run {
                source.sendFeedback({ Text.literal("No village named '$rawName' found.") }, false)
                return Command.SINGLE_SUCCESS
            }

        if (village.founderId != player.uuid) {
            source.sendFeedback({ Text.literal("This village does not belong to you").formatted(Formatting.RED) }, false)
            return Command.SINGLE_SUCCESS
        }

        val newName = rawNewName.trim().take(MAX_VILLAGE_NAME_LENGTH)
        if (newName.isBlank()) {
            source.sendFeedback({ Text.literal("Village name cannot be blank.").formatted(Formatting.RED) }, false)
            return Command.SINGLE_SUCCESS
        }
        if (newName.any { it.isDigit() }) {
            source.sendFeedback({ Text.literal("Village names cannot contain numbers.").formatted(Formatting.RED) }, false)
            return Command.SINGLE_SUCCESS
        }

        val duplicate = registry.getVillageByName(newName)
        if (duplicate != null && duplicate.id != village.id) {
            source.sendFeedback({ Text.literal("A village with that name already exists.").formatted(Formatting.RED) }, false)
            return Command.SINGLE_SUCCESS
        }

        val oldName = village.name
        village.name = newName
        registry.markDirty()
        source.sendFeedback({ Text.literal("Village '$oldName' renamed to '$newName'.") }, false)
        return Command.SINGLE_SUCCESS
    }

    private fun executeAddExperience(source: ServerCommandSource, amount: Int): Int {
        val world = source.server.overworld
        val village = nearestVillageForCommand(source) ?: return Command.SINGLE_SUCCESS
        village.experience = (village.experience + amount).coerceAtLeast(0)
        updateVillageTier(village)
        world.persistentStateManager.getOrCreate(VillageRegistry.TYPE, VillageRegistry.KEY).markDirty()
        source.sendFeedback(
            { Text.literal("${village.name} now has ${village.experience} XP and is a ${village.tier.displayName()}.") },
            false
        )
        return Command.SINGLE_SUCCESS
    }

    private fun executeSetLevel(source: ServerCommandSource, rawLevel: String): Int {
        val world = source.server.overworld
        val village = nearestVillageForCommand(source) ?: return Command.SINGLE_SUCCESS
        val tier = parseVillageTier(rawLevel)
            ?: run {
                source.sendFeedback({ Text.literal("Unknown village level '$rawLevel'. Use hamlet, settlement, village, town, or city.").formatted(Formatting.RED) }, false)
                return Command.SINGLE_SUCCESS
            }

        village.tier = tier
        village.experience = tierMinExperience(tier)
        world.persistentStateManager.getOrCreate(VillageRegistry.TYPE, VillageRegistry.KEY).markDirty()
        source.sendFeedback(
            { Text.literal("${village.name} set to ${tier.displayName()} with ${village.experience} XP.") },
            false
        )
        return Command.SINGLE_SUCCESS
    }

    private fun nearestVillageForCommand(source: ServerCommandSource): VillageData? {
        val player = source.player
            ?: run {
                source.sendFeedback({ Text.literal("Run this command as a player near a village.") }, false)
                return null
            }
        val registry = source.server.overworld.persistentStateManager.getOrCreate(VillageRegistry.TYPE, VillageRegistry.KEY)
        return registry.getVillageWithinRadius(player.blockPos, 128)
            ?: run {
                source.sendFeedback({ Text.literal("No registered village found within 128 blocks.") }, false)
                null
            }
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

    private fun parseVillageTier(rawLevel: String): VillageTier? {
        return when (rawLevel.trim().lowercase()) {
            "hamlet" -> VillageTier.HAMLET
            "settlement" -> VillageTier.SETTLEMENT
            "village" -> VillageTier.VILLAGE
            "town" -> VillageTier.TOWN
            "city" -> VillageTier.CITY
            else -> null
        }
    }

    private fun VillageTier.displayName(): String {
        return name.lowercase().replaceFirstChar { it.uppercase() }
    }

    private fun executeRestockNamed(source: ServerCommandSource, rawName: String): Int {
        val world = source.server.overworld
        val registry = world.persistentStateManager.getOrCreate(VillageRegistry.TYPE, VillageRegistry.KEY)
        val village = registry.getVillageByName(rawName)
            ?: run {
                source.sendFeedback({ Text.literal("No village named '$rawName' found.") }, false)
                return Command.SINGLE_SUCCESS
            }

        source.sendFeedback({ VillageEconomyService.restockVillageToFull(world, village) }, false)
        return Command.SINGLE_SUCCESS
    }

    private fun executeRestockNearest(source: ServerCommandSource): Int {
        val world = source.server.overworld
        val player = source.player
            ?: run {
                source.sendFeedback({ Text.literal("Run /villagerestock <name> from the server console.") }, false)
                return Command.SINGLE_SUCCESS
            }
        val registry = world.persistentStateManager.getOrCreate(VillageRegistry.TYPE, VillageRegistry.KEY)
        val village = registry.getVillageWithinRadius(player.blockPos, 128)
            ?: run {
                source.sendFeedback({ Text.literal("No registered village found within 128 blocks. Use /villagerestock <name>.") }, false)
                return Command.SINGLE_SUCCESS
            }

        source.sendFeedback({ VillageEconomyService.restockVillageToFull(world, village) }, false)
        return Command.SINGLE_SUCCESS
    }

    private fun formatVillageInfoDump(world: ServerWorld, village: VillageData): String {
        val blockEntity = world.getBlockEntity(village.clerkPos) as? ClerkTableBlockEntity
        val snapshot = blockEntity?.getSnapshot(village) ?: return buildString {
            appendLine("VillageInfoDump {")
            appendLine("  name=${village.name}")
            appendLine("  tier=${village.tier}")
            appendLine("  biome=${village.biome}")
            appendLine("  clerkPos=${village.clerkPos}")
            appendLine("  snapshot=missing (clerk table missing)")
            append("}")
        }

        val professionCounts = snapshot.professionCounts
            .entries
            .joinToString(", ") { (profession, count) ->
                "${professionDisplayName(profession)}=$count"
            }

        val totalRecords = snapshot.records.size
        val activeRecords = snapshot.records.count { it.state == VillagerState.ACTIVE }
        val missingRecords = snapshot.records.count { it.state == VillagerState.TEMPORARILY_MISSING }
        val deceasedRecords = snapshot.records.count { it.state == VillagerState.DECEASED }

        val slotLines = snapshot.hamletSlots.joinToString(", ") { slot ->
            val status = if (slot.occupied) "occupied" else "empty"
            val villager = slot.villagerUUID?.toString() ?: ""
            "${professionDisplayName(slot.requiredProfession)}:$status${if (villager.isNotBlank()) "($villager)" else ""}"
        }

        return buildString {
            appendLine("VillageInfoDump {")
            appendLine("  name=${village.name}")
            appendLine("  tier=${village.tier}")
            appendLine("  experience=${village.experience}/${nextTierExperience(village.tier)}")
            appendLine("  founder=${village.founderId ?: "unknown"}")
            appendLine("  biome=${village.biome}")
            appendLine("  clerkPos=${village.clerkPos}")
            appendLine("  population=${snapshot.population}")
            appendLine("  active=${snapshot.activeCount}")
            appendLine("  missing=${snapshot.temporarilyMissingCount}")
            appendLine("  deceased=${snapshot.deceasedCount}")
            appendLine("  records=total:$totalRecords,active:$activeRecords,missing:$missingRecords,deceased:$deceasedRecords")
            appendLine("  professionCounts=$professionCounts")
            appendLine("  slotCount=${snapshot.hamletSlots.size}")
            appendLine("  produceStock=${formatStringIntMap(village.sellStockRemainingByProfession)}")
            appendLine("  needsTotal=${formatStringIntMap(village.buyDemandTotalByProfession)}")
            appendLine("  needsFulfilled=${formatStringIntMap(village.buyDemandFulfilledByProfession)}")
            appendLine("  wantsTotal=${formatStringIntMap(village.wantDemandTotalByProfession)}")
            appendLine("  wantsFulfilled=${formatStringIntMap(village.wantDemandFulfilledByProfession)}")
            append("  slots=[")
            append(slotLines)
            appendLine("]")
            append("}")
        }
    }

    private fun buildVillageInfoText(world: ServerWorld, village: VillageData): Text {
        val blockEntity = world.getBlockEntity(village.clerkPos) as? ClerkTableBlockEntity
        val snapshot = blockEntity?.getSnapshot(village)
        val nextXp = nextTierExperience(village.tier)
        val xpLine = if (village.tier == VillageTier.CITY) "${village.experience} XP (max tier)" else "${village.experience}/$nextXp XP"

        val populationLine = if (snapshot != null) {
            "${snapshot.population} total, ${snapshot.activeCount} active, ${snapshot.temporarilyMissingCount} missing, ${snapshot.deceasedCount} deceased"
        } else {
            "unavailable (clerk table missing)"
        }

        val professionsLine = snapshot?.sortedProfessionCounts()
            ?.joinToString(", ") { (profession, count) ->
                "${professionDisplayName(profession)} x$count"
            }
            ?: "unavailable"

        val slotLines = snapshot
            ?.hamletSlots
            ?.let(::formatSlots)
            ?.ifBlank { "none" }
            ?: "unavailable"

        return Text.literal("")
            .append(Text.literal("==== ${village.name} ====\n").formatted(Formatting.GOLD, Formatting.BOLD))
            .append(infoLine("Level", village.tier.displayName(), Formatting.AQUA))
            .append(infoLine("XP", xpLine, Formatting.GREEN))
            .append(infoLine("Population", populationLine, Formatting.YELLOW))
            .append(infoLine("Professions", professionsLine, Formatting.WHITE))
            .append(infoLine("Village Sells", formatTradeMap(village.sellStockRemainingByProfession), Formatting.GREEN))
            .append(infoLine("Village Needs", formatDemandMap(village.buyDemandTotalByProfession, village.buyDemandFulfilledByProfession), Formatting.RED))
            .append(infoLine("Village Wants", formatDemandMap(village.wantDemandTotalByProfession, village.wantDemandFulfilledByProfession), Formatting.LIGHT_PURPLE))
            .append(Text.literal("Worker Slots\n").formatted(Formatting.GOLD))
            .append(Text.literal(slotLines).formatted(Formatting.GRAY))
    }

    private fun infoLine(label: String, value: String, valueColor: Formatting): Text {
        return Text.literal("")
            .append(Text.literal("$label: ").formatted(Formatting.GRAY))
            .append(Text.literal(value).formatted(valueColor))
            .append(Text.literal("\n"))
    }

    private fun formatSlots(slots: List<HamletSlot>): String {
        return slots
            .groupingBy { slot ->
                val status = if (slot.occupied) "filled" else "open"
                "${professionDisplayName(slot.requiredProfession)}:$status"
            }
            .eachCount()
            .entries
            .sortedBy { it.key }
            .joinToString("\n") { (key, count) ->
                val profession = key.substringBefore(":")
                val status = key.substringAfter(":")
                "$profession $status: $count"
            }
    }

    private fun formatTradeMap(values: Map<String, Int>): String {
        val active = values.filterValues { it > 0 }
        if (active.isEmpty()) return "none"
        return active.entries.joinToString(", ") { (key, value) ->
            "${tradeKeyLabel(key)}=$value"
        }
    }

    private fun formatDemandMap(total: Map<String, Int>, fulfilled: Map<String, Int>): String {
        val active = total.filterValues { it > 0 }
        if (active.isEmpty()) return "none"
        return active.entries.joinToString(", ") { (key, totalValue) ->
            val fulfilledValue = fulfilled[key] ?: 0
            "${tradeKeyLabel(key)}=${(totalValue - fulfilledValue).coerceAtLeast(0)}/$totalValue"
        }
    }

    private fun formatStringIntMap(values: Map<String, Int>): String {
        if (values.isEmpty()) return "{}"
        return values.entries.joinToString(", ", "{", "}") { (key, value) ->
            "${tradeKeyLabel(key)}=$value"
        }
    }

    private fun tradeKeyLabel(key: String): String {
        return when {
            key.startsWith("output|") -> {
                val body = key.removePrefix("output|")
                "${body.substringBefore("|").substringAfter(":")}:${body.substringAfter("|").substringAfter(":")}"
            }
            key.startsWith("want|") -> {
                val body = key.removePrefix("want|")
                "want:${body.substringBefore("|").substringAfter(":")}:${body.substringAfter("|").substringAfter(":")}"
            }
            else -> key.substringAfter(":")
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
}

