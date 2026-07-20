package net.pavinical.sovereign.economy

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.server.MinecraftServer
import net.minecraft.server.network.ServerPlayerEntity
import net.pavinical.sovereign.block.ClerkTableBlock
import java.util.UUID

object VillageBorderTracker {
    private const val CHECK_INTERVAL_TICKS = 10
    private const val FOUNDING_ENTER_GRACE_TICKS = 100

    private val playerVillage: MutableMap<UUID, UUID> = mutableMapOf()
    private val enterGraceTicks: MutableMap<UUID, Int> = mutableMapOf()
    private var ticksUntilCheck = CHECK_INTERVAL_TICKS

    fun register() {
        ServerTickEvents.END_SERVER_TICK.register { server ->
            ticksUntilCheck--
            if (ticksUntilCheck > 0) return@register

            ticksUntilCheck = CHECK_INTERVAL_TICKS
            updatePlayers(server)
        }
    }

    private fun updatePlayers(server: MinecraftServer) {
        val overworld = server.overworld
        val registry = overworld.persistentStateManager.getOrCreate(VillageRegistry.TYPE, VillageRegistry.KEY)
        var centerChanged = false
        registry.villages.values.forEach { village ->
            centerChanged = VillageCenterService.ensureCenter(overworld, village) || centerChanged
        }
        if (centerChanged) registry.markDirty()
        val onlinePlayers = server.playerManager.playerList.map { it.uuid }.toSet()

        playerVillage.keys.removeIf { it !in onlinePlayers }
        enterGraceTicks.keys.removeIf { it !in onlinePlayers }

        for (player in server.playerManager.playerList) {
            val previousVillageId = playerVillage[player.uuid]
            val inOverworld = player.entityWorld == overworld
            val currentVillage = if (inOverworld) {
                registry.getVillageWithinRadius(player.blockPos, ClerkTableBlock.VILLAGE_SCAN_RADIUS_BLOCKS)
            } else {
                null
            }
            val removedMarkers = PlotPlacementTool.removeMarkersOutsideVillage(
                player,
                registry,
                inOverworld,
                ClerkTableBlock.VILLAGE_SCAN_RADIUS_BLOCKS
            )
            if (removedMarkers > 0) {
                player.sendMessage(net.minecraft.text.Text.literal("Your plot marker fades beyond village bounds."), false)
            }
            val remainingGraceTicks = enterGraceTicks[player.uuid] ?: 0
            if (remainingGraceTicks > 0) {
                enterGraceTicks[player.uuid] = (remainingGraceTicks - CHECK_INTERVAL_TICKS).coerceAtLeast(0)
            }

            when {
                currentVillage == null && previousVillageId != null -> {
                    playerVillage.remove(player.uuid)
                    val previousVillage = registry.getVillageById(previousVillageId)
                    if (previousVillage != null) {
                        sendBorderMessage(player, "Leaving ${previousVillage.name}")
                    }
                }
                currentVillage != null && previousVillageId != currentVillage.id -> {
                    playerVillage[player.uuid] = currentVillage.id
                    if (remainingGraceTicks <= 0) {
                        sendBorderMessage(player, "Entering ${currentVillage.name}")
                    }
                }
            }
        }
    }

    fun suppressEnteringHeader(playerId: UUID) {
        enterGraceTicks[playerId] = FOUNDING_ENTER_GRACE_TICKS
    }

    private fun sendBorderMessage(player: ServerPlayerEntity, message: String) {
        ServerPlayNetworking.send(player, VillageFoundedPayload(message))
    }
}

