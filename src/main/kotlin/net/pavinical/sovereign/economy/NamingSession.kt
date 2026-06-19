package net.pavinical.sovereign.economy

import net.minecraft.util.math.BlockPos
import java.util.UUID

/**
 * Tracks players who are in the middle of naming a new village.
 *
 * Flow:
 *   1. Player right-clicks a Clerk Table → NamingSession.start() is called.
 *   2. Player types a name in chat → SovereignEvents intercepts it.
 *   3. Village is created → NamingSession.clear() is called.
 *
 * This lives in memory only (not saved), so if the server restarts mid-naming,
 * the player just needs to right-click the Clerk Table again.
 */
object NamingSession {

    data class PendingVillage(val clerkPos: BlockPos, val biome: String)

    private val sessions: MutableMap<UUID, PendingVillage> = mutableMapOf()

    fun start(playerId: UUID, clerkPos: BlockPos, biome: String) {
        sessions[playerId] = PendingVillage(clerkPos, biome)
    }

    fun getPending(playerId: UUID): PendingVillage? = sessions[playerId]

    fun hasPending(playerId: UUID): Boolean = sessions.containsKey(playerId)

    fun clear(playerId: UUID) {
        sessions.remove(playerId)
    }
}
