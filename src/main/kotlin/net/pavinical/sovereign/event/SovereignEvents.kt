package net.pavinical.sovereign.event

import net.fabricmc.fabric.api.message.v1.ServerMessageEvents
import net.minecraft.text.Text
import net.pavinical.sovereign.block.ClerkTableBlock
import net.pavinical.sovereign.data.VillageData
import net.pavinical.sovereign.economy.NamingSession
import net.pavinical.sovereign.economy.VillageRegistry
import java.util.UUID

/**
 * Registers all mod event listeners.
 *
 * Currently handles:
 *   • Chat interception for the village naming flow.
 */
object SovereignEvents {

    fun register() {
        registerNamingListener()
    }

    /**
     * When a player has an active NamingSession, their next chat message becomes
     * the village name instead of being broadcast to all players.
     *
     * ALLOW_CHAT_MESSAGE: returning false cancels the message (it won't appear in chat).
     */
    private fun registerNamingListener() {
        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register { message, sender, _ ->

            // If this player isn't naming a village, let the message through normally
            if (!NamingSession.hasPending(sender.uuid)) {
                return@register true
            }

            val session = NamingSession.getPending(sender.uuid)!!
            val villageName = message.content.string.trim()

            // Validate — don't allow an empty name
            if (villageName.isBlank()) {
                sender.sendMessage(
                    Text.literal("§cVillage name cannot be blank. Type a name in chat to try again:"),
                    false
                )
                return@register false // cancel the empty message, keep the session open
            }

            // Register the village
            val registry = sender.entityWorld.server!!.overworld.persistentStateManager
                .getOrCreate(VillageRegistry.TYPE)

            val nearby = registry.getVillageWithinRadius(session.clerkPos, ClerkTableBlock.MIN_VILLAGE_DISTANCE_BLOCKS)
            if (nearby != null) {
                NamingSession.clear(sender.uuid)
                sender.sendMessage(
                    Text.literal("Â§cVillage registration cancelled. Â§6${nearby.name}Â§c is already within ${ClerkTableBlock.MIN_VILLAGE_DISTANCE_BLOCKS} blocks."),
                    false
                )
                return@register false
            }

            val newVillage = VillageData(
                id = UUID.randomUUID(),
                name = villageName,
                biome = session.biome,
                clerkPos = session.clerkPos
            )
            registry.addVillage(newVillage)
            NamingSession.clear(sender.uuid)

            sender.sendMessage(
                Text.literal("§aVillage '§6$villageName§a' has been founded! Biome: §e${session.biome}"),
                false
            )

            false // cancel the chat message — the name stays private between player and mod
        }
    }
}
