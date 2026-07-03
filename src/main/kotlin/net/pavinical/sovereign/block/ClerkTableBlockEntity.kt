package net.pavinical.sovereign.block

import net.minecraft.block.BlockState
import net.minecraft.block.entity.BlockEntity
import net.minecraft.entity.passive.VillagerEntity
import net.minecraft.nbt.NbtCompound
import net.minecraft.nbt.NbtElement
import net.minecraft.nbt.NbtList
import net.minecraft.nbt.NbtString
import net.minecraft.registry.RegistryWrapper
import net.minecraft.registry.Registries
import net.minecraft.village.VillagerProfession
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.Identifier
import net.minecraft.util.math.BlockPos
import net.pavinical.sovereign.data.VillageData
import net.pavinical.sovereign.data.VillageTier
import net.pavinical.sovereign.registry.ModBlockEntities
import java.util.UUID

private const val TAG_RECORDS = "villagerRecords"
private const val TAG_LAST_UPDATED = "lastUpdatedTick"
private const val RECORD_VERSION = 1

private const val FARMER_ID = "minecraft:farmer"
private const val SHEPHERD_ID = "minecraft:shepherd"
private const val MASON_ID = "minecraft:mason"
private const val BUTCHER_ID = "minecraft:butcher"
private const val FLETCHER_ID = "minecraft:fletcher"
private const val FISHERMAN_ID = "minecraft:fisherman"
private const val ARMORER_ID = "minecraft:armorer"
private const val TOOLSMITH_ID = "minecraft:toolsmith"
private const val WEAPONSMITH_ID = "minecraft:weaponsmith"
private const val CLERIC_ID = "minecraft:cleric"
private const val LIBRARIAN_ID = "minecraft:librarian"
private const val LEATHERWORKER_ID = "minecraft:leatherworker"
private const val NITWIT_ID = "minecraft:nitwit"

private val PEASANT_PROFESSION_IDS = listOf(
    FARMER_ID,
    SHEPHERD_ID,
    MASON_ID,
    FLETCHER_ID,
    BUTCHER_ID,
    FISHERMAN_ID
)

enum class VillagerState {
    ACTIVE,
    TEMPORARILY_MISSING,
    DECEASED
}

enum class HamletBiomeArchetype(
    val biomeAliases: Set<String>,
    val staticPeasantProfessionId: String,
    val artisanProfessionId: String
) {
    PLAINS(setOf("plains"), FARMER_ID, LIBRARIAN_ID),
    DESERT(setOf("desert"), MASON_ID, CLERIC_ID),
    TAIGA(setOf("taiga"), FLETCHER_ID, TOOLSMITH_ID),
    SAVANNAH(setOf("savanna", "savannah"), SHEPHERD_ID, WEAPONSMITH_ID),
    SNOWY(setOf("snowy", "cold", "ice", "frozen"), FISHERMAN_ID, ARMORER_ID);

    fun requiredProfessions(tier: VillageTier, villageId: UUID?): List<VillagerProfession> =
        expandedSlotProfessionIds(tier, villageId).mapNotNull { professionById(it, null) }

    private fun expandedSlotProfessionIds(tier: VillageTier, villageId: UUID?): List<String> {
        val slots = hamletSlotProfessionIds(villageId).toMutableList()
        val selectedPeasants = selectedPeasantProfessionIds(villageId)
        val peasantAdds = when (tier) {
            VillageTier.HAMLET -> 0
            VillageTier.SETTLEMENT -> 2
            VillageTier.VILLAGE -> 6
            VillageTier.TOWN -> 10
            VillageTier.CITY -> 14
        }
        repeat(peasantAdds) { index ->
            val seed = "${villageId ?: "unregistered"}:${name}:peasant:$index".hashCode()
            slots.add(selectedPeasants[Math.floorMod(seed, selectedPeasants.size)])
        }
        if (tier == VillageTier.TOWN || tier == VillageTier.CITY) {
            slots.add(artisanProfessionId)
        }
        if (tier == VillageTier.CITY) {
            slots.add(artisanProfessionId)
        }
        return slots
    }

    private fun hamletSlotProfessionIds(villageId: UUID?): List<String> {
        val selectedPeasants = selectedPeasantProfessionIds(villageId)
        val slotCount = 3 + Math.floorMod("$villageId:$name:hamlet-size".hashCode(), 4)
        return List(slotCount) { index ->
            when (index) {
                0 -> staticPeasantProfessionId
                1 -> selectedPeasants[1]
                else -> {
                    val seed = "$villageId:$name:hamlet:$index".hashCode()
                    selectedPeasants[Math.floorMod(seed, selectedPeasants.size)]
                }
            }
        }
    }

    private fun selectedPeasantProfessionIds(villageId: UUID?): List<String> {
        val randomCandidates = PEASANT_PROFESSION_IDS.filter { it != staticPeasantProfessionId }
        val seed = "${villageId ?: "unregistered"}:${name}:random-peasant".hashCode()
        val randomProfessionId = randomCandidates[Math.floorMod(seed, randomCandidates.size)]
        return listOf(staticPeasantProfessionId, randomProfessionId)
    }
}

data class VillagerRecord(
    val uuid: UUID,
    var profession: VillagerProfession,
    var state: VillagerState,
    var position: BlockPos?,
    var lastSeenTick: Long
)

data class HamletSlot(
    val requiredProfession: VillagerProfession,
    val occupied: Boolean,
    val villagerUUID: UUID?
)

data class VillageEconomyState(
    val population: Int,
    val activeCount: Int,
    val temporarilyMissingCount: Int,
    val deceasedCount: Int,
    val professionCounts: Map<VillagerProfession, Int>,
    val hamletArchetype: HamletBiomeArchetype,
    val hamletSlots: List<HamletSlot>,
    val records: List<VillagerRecord>,
    val lastUpdatedTick: Long
) {
    fun sortedProfessionCounts(): List<Pair<VillagerProfession, Int>> = professionCounts
        .toList()
        .sortedWith(compareByDescending<Pair<VillagerProfession, Int>> { it.second }.thenBy { professionDisplayName(it.first) })
}

class ClerkTableBlockEntity(pos: BlockPos, state: BlockState) : BlockEntity(ModBlockEntities.CLERK_TABLE, pos, state) {
    var villageId: UUID? = null
    private val records = mutableMapOf<UUID, VillagerRecord>()
    private var lastUpdatedTick = 0L

    fun markVillage(villageId: UUID?) {
        if (this.villageId == villageId) return
        this.villageId = villageId
        markDirty()
    }

    fun upsertVillagerRecord(
        villagerId: UUID,
        profession: VillagerProfession,
        professionId: String,
        position: BlockPos,
        lastSeenTick: Long
    ): Boolean {
        if (isDeceased(villagerId)) return false

        val resolvedProfession = professionById(professionId, profession) ?: profession
        val normalizedProfession = normalizeProfessionForCensus(resolvedProfession)
        val existing = records[villagerId]
        if (existing == null) {
            records[villagerId] = VillagerRecord(
                villagerId,
                normalizedProfession,
                VillagerState.ACTIVE,
                position,
                lastSeenTick
            )
            lastUpdatedTick = lastSeenTick
            markDirty()
            return true
        }

        val changed = existing.profession != normalizedProfession ||
            existing.state != VillagerState.ACTIVE ||
            existing.position != position ||
            existing.lastSeenTick != lastSeenTick

        if (!changed) return false

        existing.profession = normalizedProfession
        existing.state = VillagerState.ACTIVE
        existing.position = position
        existing.lastSeenTick = lastSeenTick
        lastUpdatedTick = lastSeenTick
        markDirty()
        return true
    }

    fun markMissingForAbsentRecords(activeVillagerIds: Set<UUID>, nowTick: Long): Int {
        var changed = 0
        for (record in records.values) {
            if (record.state == VillagerState.DECEASED || activeVillagerIds.contains(record.uuid)) continue
            if (record.state != VillagerState.TEMPORARILY_MISSING) {
                record.state = VillagerState.TEMPORARILY_MISSING
                record.lastSeenTick = nowTick
                changed++
            }
        }
        if (changed > 0) markDirty()
        return changed
    }

    fun reconcileTrackedWithWorld(world: ServerWorld, activeVillagerIds: Set<UUID>, nowTick: Long): Int {
        var changed = 0
        for (record in records.values) {
            if (record.state == VillagerState.DECEASED || activeVillagerIds.contains(record.uuid)) continue

            val foundEntity = world.getEntity(record.uuid) ?: continue

            if (foundEntity !is VillagerEntity || foundEntity.isRemoved || !foundEntity.isAlive) {
                if (markVillagerAsDeceased(record.uuid)) changed++
            } else {
                if (record.state == VillagerState.TEMPORARILY_MISSING) {
                    record.lastSeenTick = nowTick
                }
            }
        }
        if (changed > 0) markDirty()
        return changed
    }

    fun markVillagerIdsDeceased(villagerIds: Set<UUID>): Set<UUID> {
        val changedIds = mutableSetOf<UUID>()
        for (villagerId in villagerIds) {
            if (markVillagerAsDeceased(villagerId)) {
                changedIds.add(villagerId)
            }
        }
        if (changedIds.isNotEmpty()) markDirty()
        return changedIds
    }

    private fun markVillagerAsDeceased(villagerId: UUID): Boolean {
        val existing = records[villagerId] ?: return false
        if (existing.state == VillagerState.DECEASED) return false

        existing.state = VillagerState.DECEASED
        return true
    }

    private fun isDeceased(villagerId: UUID): Boolean = records[villagerId]?.state == VillagerState.DECEASED

    fun removeStaleRecords(currentTick: Long, staleTimeoutTicks: Long): Int {
        val stale = records.filterValues { it.state == VillagerState.TEMPORARILY_MISSING && currentTick - it.lastSeenTick > staleTimeoutTicks }
        if (stale.isEmpty()) return 0

        stale.keys.forEach { records.remove(it) }
        lastUpdatedTick = currentTick
        markDirty()
        return stale.size
    }

    fun getSnapshot(village: VillageData): VillageEconomyState {
        return getSnapshot(resolveHamletArchetype(village.biome), village.tier, village.id)
    }

    private fun getSnapshot(
        hamletArchetype: HamletBiomeArchetype,
        tier: VillageTier = VillageTier.HAMLET,
        owningVillageId: UUID? = null
    ): VillageEconomyState {
        val trackedProfessionLookup = trackedProfessionLookup()
        val professionCounts = trackedProfessionLookup.values.associateWith { 0 }.toMutableMap()

        var activeCount = 0
        var temporarilyMissingCount = 0
        var deceasedCount = 0

        for (record in records.values) {
            when (record.state) {
                VillagerState.ACTIVE -> activeCount++
                VillagerState.TEMPORARILY_MISSING -> temporarilyMissingCount++
                VillagerState.DECEASED -> {
                    deceasedCount++
                    continue
                }
            }

            val censusProfession = normalizeProfessionForCensus(record.profession)
            val professionId = professionToId(censusProfession)
            trackedProfessionLookup[professionId]?.let { trackedProfession ->
                professionCounts[trackedProfession] = professionCounts.getOrDefault(trackedProfession, 0) + 1
            }
        }

        val hamletSlots = buildHamletSlots(
            records.values,
            hamletArchetype,
            tier,
            owningVillageId
        )

        return VillageEconomyState(
            population = activeCount + temporarilyMissingCount,
            activeCount = activeCount,
            temporarilyMissingCount = temporarilyMissingCount,
            deceasedCount = deceasedCount,
            professionCounts = professionCounts,
            hamletArchetype = hamletArchetype,
            hamletSlots = hamletSlots,
            records = records.values.toList(),
            lastUpdatedTick = lastUpdatedTick
        )
    }

    private fun buildHamletSlots(
        allRecords: Collection<VillagerRecord>,
        archetype: HamletBiomeArchetype,
        tier: VillageTier,
        owningVillageId: UUID?
    ): List<HamletSlot> {
        val candidates = allRecords
            .asSequence()
            .filter { it.state != VillagerState.DECEASED }
            .sortedBy { it.lastSeenTick }
            .toMutableList()

        val requiredSlots = archetype.requiredProfessions(tier, owningVillageId).map { profession ->
            HamletSlot(
                requiredProfession = profession,
                occupied = false,
                villagerUUID = null
            )
        }.toMutableList()

        for (index in requiredSlots.indices) {
            val requiredProfession = requiredSlots[index].requiredProfession
            val matchIndex = candidates.indexOfFirst { it.profession == requiredProfession }
            if (matchIndex < 0) continue

            val matched = candidates.removeAt(matchIndex)
            requiredSlots[index] = HamletSlot(
                requiredProfession = requiredProfession,
                occupied = true,
                villagerUUID = matched.uuid
            )
        }

        return requiredSlots.toList()
    }

    private fun resolveHamletArchetype(biomeKey: String): HamletBiomeArchetype {
        val key = biomeKey.lowercase()
        val normalized = key.substringAfterLast(':', key)
        val plain = normalized.substringAfterLast('/', normalized)

        val priorityOrder = listOf(
            HamletBiomeArchetype.SNOWY,
            HamletBiomeArchetype.DESERT,
            HamletBiomeArchetype.TAIGA,
            HamletBiomeArchetype.SAVANNAH,
            HamletBiomeArchetype.PLAINS
        )

        for (archetype in priorityOrder) {
            if (archetype.biomeAliases.any { alias -> plain.contains(alias) }) {
                return archetype
            }
        }

        return HamletBiomeArchetype.PLAINS
    }

    override fun writeNbt(nbt: NbtCompound, registryLookup: RegistryWrapper.WrapperLookup) {
        super.writeNbt(nbt, registryLookup)
        nbt.putInt("version", RECORD_VERSION)
        nbt.putString("villageId", villageId?.toString() ?: "")
        nbt.putLong(TAG_LAST_UPDATED, lastUpdatedTick)
        val recordList = NbtList()
        records.values.forEach { recordList.add(NbtString.of(serializeRecord(it))) }
        nbt.put(TAG_RECORDS, recordList)
    }

    override fun readNbt(nbt: NbtCompound, registryLookup: RegistryWrapper.WrapperLookup) {
        super.readNbt(nbt, registryLookup)
        val rawVillageId = nbt.getString("villageId")
        villageId = runCatching { UUID.fromString(rawVillageId) }.getOrNull()

        lastUpdatedTick = nbt.getLong(TAG_LAST_UPDATED)

        records.clear()
        val rawRecords = nbt.getList(TAG_RECORDS, NbtElement.STRING_TYPE.toInt())
        for (i in 0 until rawRecords.size) {
            val rawRecord = rawRecords.getString(i)
            parseRecord(rawRecord)?.let { record ->
                records[record.uuid] = record
            }
        }
    }

    private fun serializeRecord(record: VillagerRecord): String {
        val professionId = professionToId(record.profession)
        val position = record.position?.let { "${it.x},${it.y},${it.z}" } ?: "null"
        return "${record.uuid}|$professionId|${record.lastSeenTick}|${record.state.name}|$position"
    }

    private fun parseRecord(raw: String): VillagerRecord? {
        val pieces = raw.split("|")
        if (pieces.size !in 4..5) return null

        val uuid = runCatching { UUID.fromString(pieces[0]) }.getOrNull() ?: return null
        val profession = professionById(pieces[1], null) ?: return null
        val lastSeenTick = runCatching { pieces[2].toLong() }.getOrNull() ?: return null
        val state = if (pieces.size == 5) {
            runCatching { VillagerState.valueOf(pieces[3]) }.getOrNull() ?: VillagerState.TEMPORARILY_MISSING
        } else {
            VillagerState.ACTIVE
        }
        val position = parsePosition(if (pieces.size == 5) pieces[4] else pieces[3])
        return VillagerRecord(uuid, profession, state, position, lastSeenTick)
    }

    private fun parsePosition(raw: String): BlockPos? {
        if (raw == "null") return null
        val parts = raw.split(",")
        if (parts.size != 3) return null

        val x = runCatching { parts[0].toInt() }.getOrNull() ?: return null
        val y = runCatching { parts[1].toInt() }.getOrNull() ?: return null
        val z = runCatching { parts[2].toInt() }.getOrNull() ?: return null
        return BlockPos(x, y, z)
    }

    private fun trackedProfessionIds(): List<String> = listOf(
        FARMER_ID,
        SHEPHERD_ID,
        MASON_ID,
        BUTCHER_ID,
        FLETCHER_ID,
        FISHERMAN_ID,
        ARMORER_ID,
        TOOLSMITH_ID,
        WEAPONSMITH_ID,
        CLERIC_ID,
        LIBRARIAN_ID,
        NITWIT_ID
    )

    private fun trackedProfessionLookup(): Map<String, VillagerProfession> = buildMap {
        for (professionId in trackedProfessionIds()) {
            trackedProfessionById(professionId)?.let { profession ->
                put(professionId, profession)
            }
        }
    }
}

fun professionDisplayName(profession: VillagerProfession): String = when (professionToId(profession)) {
    FARMER_ID -> "Farmer"
    SHEPHERD_ID -> "Shepherd"
    MASON_ID -> "Miner"
    BUTCHER_ID -> "Butcher"
    FLETCHER_ID -> "Lumberjack"
    FISHERMAN_ID -> "Fisherman"
    ARMORER_ID -> "Armorer"
    TOOLSMITH_ID -> "Toolsmith"
    WEAPONSMITH_ID -> "Weaponsmith"
    CLERIC_ID -> "Cleric"
    LIBRARIAN_ID -> "Librarian"
    NITWIT_ID -> "Nitwit"
    else -> "Other"
}

private fun professionToId(profession: VillagerProfession): String =
    Registries.VILLAGER_PROFESSION.getId(profession).toString()

private fun normalizeProfessionForCensus(profession: VillagerProfession): VillagerProfession {
    if (professionToId(profession) != LEATHERWORKER_ID) return profession
    return professionById(BUTCHER_ID, profession) ?: profession
}

private fun professionById(rawId: String, fallback: VillagerProfession?): VillagerProfession? {
    val professionId = Identifier.tryParse(rawId) ?: return fallback
    return Registries.VILLAGER_PROFESSION.get(professionId) ?: fallback
}

private fun professionById(rawId: String): VillagerProfession? = professionById(rawId, null)

private fun trackedProfessionById(rawId: String): VillagerProfession? = professionById(rawId)

