package net.pavinical.sovereign.economy

import net.fabricmc.fabric.api.event.player.UseBlockCallback
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.component.DataComponentTypes
import net.minecraft.component.type.NbtComponent
import net.minecraft.item.ItemStack
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.text.Text
import net.minecraft.util.ActionResult
import net.minecraft.util.Formatting
import net.minecraft.util.Hand
import net.minecraft.util.math.BlockPos
import net.pavinical.sovereign.data.VillageData
import net.pavinical.sovereign.registry.ModItems
import java.util.UUID

object PlotPlacementTool {
    private const val TAG_PLOT_MARKER = "sovereign_plot_marker"
    private const val TAG_VILLAGE_ID = "sovereign_village_id"
    private const val TAG_VILLAGE_NAME = "sovereign_village_name"
    private const val TAG_STRUCTURE_TYPE = "sovereign_structure_type"
    private const val TAG_PLOT_INDEX = "sovereign_plot_index"

    private data class PendingPlacement(
        val playerId: UUID,
        val hand: Hand,
        val villageId: UUID,
        val placement: VillagePlotService.PendingPlotPlacement
    )

    private val pendingPlacements: MutableMap<String, PendingPlacement> = mutableMapOf()

    fun register() {
        PayloadTypeRegistry.playS2C().register(VillageTradePacket.PLOT_PLACEMENT_CONFIRM_ID, PlotPlacementConfirmPayload.PACKET_CODEC)
        PayloadTypeRegistry.playC2S().register(VillageTradePacket.PLOT_PLACEMENT_RESPONSE_ID, PlotPlacementResponsePayload.PACKET_CODEC)

        UseBlockCallback.EVENT.register callback@{ player, world, hand, hit ->
            val serverPlayer = player as? ServerPlayerEntity ?: return@callback ActionResult.PASS
            val serverWorld = world as? ServerWorld ?: return@callback ActionResult.PASS
            val stack = serverPlayer.getStackInHand(hand)
            if (!isPlotMarker(stack)) return@callback ActionResult.PASS
            beginPlacement(serverWorld, serverPlayer, hand, stack, hit.blockPos.offset(hit.side))
            ActionResult.SUCCESS
        }

        ServerPlayNetworking.registerGlobalReceiver(VillageTradePacket.PLOT_PLACEMENT_RESPONSE_ID) { payload, context ->
            context.server().execute {
                handlePlacementResponse(context.player(), payload)
            }
        }
    }

    fun createStack(village: VillageData, structureType: String, oneBasedPlotIndex: Int = 0): ItemStack {
        val normalizedType = structureType.lowercase()
        val stack = ItemStack(ModItems.STAMP_TOOL)
        val action = if (oneBasedPlotIndex > 0) "Relocate" else "Mark"
        val target = normalizedType.replaceFirstChar { it.uppercase() }
        stack.set(DataComponentTypes.CUSTOM_NAME, Text.literal("${village.name} $target Plot Marker"))
        NbtComponent.set(DataComponentTypes.CUSTOM_DATA, stack) { nbt ->
            nbt.putBoolean(TAG_PLOT_MARKER, true)
            nbt.putString(TAG_VILLAGE_ID, village.id.toString())
            nbt.putString(TAG_VILLAGE_NAME, village.name)
            nbt.putString(TAG_STRUCTURE_TYPE, normalizedType)
            nbt.putInt(TAG_PLOT_INDEX, oneBasedPlotIndex)
            nbt.putString("sovereign_plot_marker_action", action)
        }
        return stack
    }

    fun isPlotMarker(stack: ItemStack): Boolean {
        if (!stack.isOf(ModItems.STAMP_TOOL)) return false
        val customData = stack.get(DataComponentTypes.CUSTOM_DATA) ?: return false
        return customData.copyNbt().getBoolean(TAG_PLOT_MARKER)
    }

    fun removeMarkersOutsideVillage(
        player: ServerPlayerEntity,
        registry: VillageRegistry,
        inOverworld: Boolean,
        radius: Int
    ): Int {
        var removed = 0
        val radiusSquared = radius * radius
        val inventory = player.inventory
        for (slot in 0 until inventory.size()) {
            val stack = inventory.getStack(slot)
            if (!isPlotMarker(stack)) continue
            val villageId = markerVillageId(stack)
            val village = villageId?.let { registry.getVillageById(it) }
            val insideVillage = inOverworld && village != null && horizontalDistanceSquared(player.blockPos, village.centerPos) <= radiusSquared
            if (insideVillage) continue
            inventory.setStack(slot, ItemStack.EMPTY)
            removed++
        }
        if (removed > 0) {
            pendingPlacements.entries.removeIf { it.value.playerId == player.uuid }
            inventory.markDirty()
        }
        return removed
    }

    private fun markerVillageId(stack: ItemStack): UUID? {
        val nbt = stack.get(DataComponentTypes.CUSTOM_DATA)?.copyNbt() ?: return null
        if (!nbt.getBoolean(TAG_PLOT_MARKER)) return null
        return runCatching { UUID.fromString(nbt.getString(TAG_VILLAGE_ID)) }.getOrNull()
    }

    private fun horizontalDistanceSquared(first: BlockPos, second: BlockPos): Int {
        val dx = first.x - second.x
        val dz = first.z - second.z
        return dx * dx + dz * dz
    }

    private fun beginPlacement(
        world: ServerWorld,
        player: ServerPlayerEntity,
        hand: Hand,
        stack: ItemStack,
        frontPos: BlockPos
    ) {
        val nbt = stack.get(DataComponentTypes.CUSTOM_DATA)?.copyNbt()
            ?: return player.sendMessage(Text.literal("That marker has lost its seal.").formatted(Formatting.RED), false)
        val villageId = runCatching { UUID.fromString(nbt.getString(TAG_VILLAGE_ID)) }.getOrNull()
            ?: return player.sendMessage(Text.literal("That marker has lost its village seal.").formatted(Formatting.RED), false)
        val village = world.persistentStateManager
            .getOrCreate(VillageRegistry.TYPE, VillageRegistry.KEY)
            .getVillageById(villageId)
            ?: return player.sendMessage(Text.literal("That village is no longer registered.").formatted(Formatting.RED), false)
        val structureType = nbt.getString(TAG_STRUCTURE_TYPE)
        val plotIndex = nbt.getInt(TAG_PLOT_INDEX)
        val placement = VillagePlotService.previewManualPlot(world, village, player, structureType, plotIndex, frontPos)
            ?: return player.sendMessage(Text.literal("That site is blocked by roads, buildings, or another plot.").formatted(Formatting.RED), false)
        val placementId = UUID.randomUUID().toString()
        pendingPlacements[placementId] = PendingPlacement(player.uuid, hand, village.id, placement)
        ServerPlayNetworking.send(player, PlotPlacementConfirmPayload(placementId, placement.displayName()))
    }

    private fun handlePlacementResponse(player: ServerPlayerEntity, payload: PlotPlacementResponsePayload) {
        val pending = pendingPlacements.remove(payload.placementId) ?: return
        if (pending.playerId != player.uuid) return
        if (!payload.accepted) {
            player.sendMessage(Text.literal("Plot marker lifted.").formatted(Formatting.YELLOW), false)
            return
        }
        val world = player.serverWorld
        val village = world.persistentStateManager
            .getOrCreate(VillageRegistry.TYPE, VillageRegistry.KEY)
            .getVillageById(pending.villageId)
            ?: return player.sendMessage(Text.literal("That village is no longer registered.").formatted(Formatting.RED), false)
        val message = VillagePlotService.commitManualPlot(world, village, pending.placement)
        player.sendMessage(message, false)
        if (!message.style.color?.name.equals("red", ignoreCase = true)) {
            val stack = player.getStackInHand(pending.hand)
            if (isPlotMarker(stack)) {
                stack.decrement(1)
            }
        }
    }
}
