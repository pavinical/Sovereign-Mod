package net.pavinical.sovereign.economy

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.minecraft.entity.passive.VillagerEntity
import net.minecraft.registry.Registries
import net.minecraft.server.MinecraftServer
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.Box
import net.pavinical.sovereign.block.ClerkTableBlockEntity
import net.pavinical.sovereign.data.VillageData
import java.util.UUID

object ClerkCensusService {
    private const val TICKS_BETWEEN_SCANS = 40
    private const val SCAN_RADIUS_BLOCKS = 100
    private const val STALE_RECORD_TIMEOUT_TICKS = 200L

    private var scanCooldown = 0
    private val pendingDeceasedVillagers = linkedSetOf<UUID>()

    fun register() {
        ServerTickEvents.END_SERVER_TICK.register(::tick)
    }

    fun forceSyncVillage(world: ServerWorld, village: VillageData): net.pavinical.sovereign.block.VillageEconomyState? {
        if (!syncVillage(world, village)) {
            val clerkTable = world.getBlockEntity(village.clerkPos) as? ClerkTableBlockEntity ?: return null
            return clerkTable.getSnapshot(village)
        }

        val clerkTable = world.getBlockEntity(village.clerkPos) as? ClerkTableBlockEntity ?: return null
        return clerkTable.getSnapshot(village)
    }

    private fun tick(server: MinecraftServer) {
        if (scanCooldown-- > 0) return
        scanCooldown = TICKS_BETWEEN_SCANS

        val world = server.overworld
        val registry = world.persistentStateManager.getOrCreate(VillageRegistry.TYPE, VillageRegistry.KEY)
        if (registry.villages.isEmpty()) return

        var changed = false
        for (village in registry.villages.values) {
            if (syncVillage(world, village)) {
                changed = true
            }
        }

        if (changed) {
            registry.markDirty()
        }
    }

    private fun syncVillage(world: ServerWorld, village: VillageData): Boolean {
        val clerkTable = world.getBlockEntity(village.clerkPos) as? ClerkTableBlockEntity ?: return false
        clerkTable.markVillage(village.id)

        val now = world.time
        val scanVolume = Box.of(
            village.clerkPos.toCenterPos(),
            SCAN_RADIUS_BLOCKS * 2.0,
            SCAN_RADIUS_BLOCKS * 2.0,
            SCAN_RADIUS_BLOCKS * 2.0
        )

        var changed = false
        val activeVillagerIds = HashSet<UUID>()
        val villagers = world.getEntitiesByClass(VillagerEntity::class.java, scanVolume) { villager ->
            villager.isAlive && !villager.isRemoved
        }

        for (villager in villagers) {
            val profession = villager.villagerData.profession
            val professionId = Registries.VILLAGER_PROFESSION.getId(profession).toString()

            activeVillagerIds.add(villager.uuid)
            changed = clerkTable.upsertVillagerRecord(
                villager.uuid,
                profession,
                professionId,
                villager.blockPos,
                now
            ) || changed
        }

        if (clerkTable.markMissingForAbsentRecords(activeVillagerIds, now) > 0) {
            changed = true
        }

        if (clerkTable.reconcileTrackedWithWorld(world, activeVillagerIds, now) > 0) {
            changed = true
        }

        if (pendingDeceasedVillagers.isNotEmpty()) {
            val resolved = clerkTable.markVillagerIdsDeceased(pendingDeceasedVillagers.toSet())
            if (resolved.isNotEmpty()) {
                pendingDeceasedVillagers.removeAll(resolved)
                changed = true
            }
        }

        if (clerkTable.removeStaleRecords(now, STALE_RECORD_TIMEOUT_TICKS) > 0) {
            changed = true
        }

        return changed
    }

    fun markVillagerDeceased(villagerId: UUID) {
        pendingDeceasedVillagers.add(villagerId)
    }

    fun currentScanRadius(): Int = SCAN_RADIUS_BLOCKS
    fun staleTimeoutTicks(): Long = STALE_RECORD_TIMEOUT_TICKS
}

