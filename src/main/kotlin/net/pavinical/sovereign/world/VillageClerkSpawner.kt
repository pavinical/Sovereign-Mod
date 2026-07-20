package net.pavinical.sovereign.world

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.minecraft.block.Blocks
import net.minecraft.registry.RegistryKey
import net.minecraft.registry.RegistryKeys
import net.minecraft.server.world.ServerWorld
import net.minecraft.structure.StructureTemplate
import net.minecraft.structure.StructurePlacementData
import net.minecraft.structure.processor.BlockIgnoreStructureProcessor
import net.minecraft.util.BlockRotation
import net.minecraft.util.Identifier
import net.minecraft.util.math.BlockBox
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Direction
import net.minecraft.world.Heightmap
import net.minecraft.world.World
import net.minecraft.world.chunk.WorldChunk
import net.pavinical.sovereign.Sovereign
import kotlin.math.abs

/**
 * Places one Clerk Hall into newly generated vanilla villages.
 *
 * Vanilla villages are recorded as minecraft:village_* structure starts, so
 * this uses the village structure bounding box as the anchor instead of trying
 * to find generated blocks like bells. Placement is processed later on a normal
 * server tick to avoid doing expensive structure work during chunk gen.
 */
object VillageClerkSpawner {
    private const val EXISTING_CLERK_RADIUS = 96
    private const val HALL_OFFSET_FROM_CENTER = 18
    private const val SITE_SEARCH_RADIUS = 48
    private const val MAX_SITE_GRADE_DELTA = 2
    private const val MAX_PATH_BLOCKS_IN_FOOTPRINT = 4
    private const val MAX_HALLS_PLACED_PER_TICK = 1
    private const val MAX_PLACEMENT_ATTEMPTS = 200

    private val placements = listOf(
        Placement(Direction.NORTH, BlockRotation.NONE, Direction.SOUTH),
        Placement(Direction.EAST, BlockRotation.CLOCKWISE_90, Direction.WEST),
        Placement(Direction.SOUTH, BlockRotation.CLOCKWISE_180, Direction.NORTH),
        Placement(Direction.WEST, BlockRotation.COUNTERCLOCKWISE_90, Direction.EAST)
    )

    private val pendingHalls = mutableMapOf<RegistryKey<World>, MutableList<PendingHall>>()

    fun register() {
        ServerChunkEvents.CHUNK_GENERATE.register { world, chunk ->
            queueHallForVillageStructure(world, chunk)
        }

        ServerChunkEvents.CHUNK_LOAD.register { world, chunk ->
            queueHallForVillageStructure(world, chunk)
        }

        ServerTickEvents.END_WORLD_TICK.register { world ->
            placeQueuedHalls(world)
        }
    }

    private fun queueHallForVillageStructure(world: ServerWorld, chunk: WorldChunk) {
        val structureRegistry = world.registryManager.get(RegistryKeys.STRUCTURE)

        for ((structure, start) in chunk.getStructureStarts()) {
            if (!start.hasChildren()) continue

            val id = structureRegistry.getId(structure) ?: continue
            if (id.namespace != "minecraft" || !id.path.startsWith("village_")) continue

            queueHall(world, start.getBoundingBox().getCenter())
        }
    }

    private fun queueHall(world: ServerWorld, bellPos: BlockPos) {
        val registry = GeneratedClerkHallRegistry.get(world)
        if (registry.hasHallNear(bellPos, EXISTING_CLERK_RADIUS)) return

        val pending = pendingHalls.getOrPut(world.registryKey) { mutableListOf() }
        if (pending.any { isNear(it.bellPos, bellPos, EXISTING_CLERK_RADIUS) }) return

        pending += PendingHall(bellPos)
    }

    private fun placeQueuedHalls(world: ServerWorld) {
        val pending = pendingHalls[world.registryKey] ?: return
        var placedThisTick = 0
        val retry = mutableListOf<PendingHall>()

        val iterator = pending.iterator()
        while (iterator.hasNext() && placedThisTick < MAX_HALLS_PLACED_PER_TICK) {
            val pendingHall = iterator.next()
            iterator.remove()

            if (placeClerkHallNearBell(world, pendingHall.bellPos)) {
                placedThisTick++
            } else if (pendingHall.attempts + 1 < MAX_PLACEMENT_ATTEMPTS) {
                retry += pendingHall.copy(attempts = pendingHall.attempts + 1)
            }
        }

        pending += retry

        if (pending.isEmpty()) {
            pendingHalls.remove(world.registryKey)
        }
    }

    private fun placeClerkHallNearBell(world: ServerWorld, villageCenter: BlockPos): Boolean {
        val registry = GeneratedClerkHallRegistry.get(world)
        if (registry.hasHallNear(villageCenter, EXISTING_CLERK_RADIUS)) return false

        val structureName = structureForBiome(world, villageCenter)
        val template = getTemplate(world, structureName) ?: return false
        val candidates = findRoadsideCandidates(world, template, villageCenter)

        for (candidate in candidates) {
            if (placeStructure(world, template, candidate.origin, candidate.placement.rotation)) {
                registry.markHallPlaced(villageCenter)
                return true
            }
        }

        return false
    }

    private fun getTemplate(world: ServerWorld, structureName: String): StructureTemplate? {
        val templateManager = world.server.structureTemplateManager
        return templateManager.getTemplate(Identifier.of(Sovereign.MOD_ID, structureName)).orElse(null)
            ?: templateManager.getTemplate(Identifier.ofVanilla(structureName)).orElse(null)
    }

    private fun placeStructure(
        world: ServerWorld,
        template: StructureTemplate,
        origin: BlockPos,
        rotation: BlockRotation
    ): Boolean {
        val placementData = StructurePlacementData()
            .setRotation(rotation)
            .addProcessor(BlockIgnoreStructureProcessor.IGNORE_STRUCTURE_BLOCKS)
        val box = template.calculateBoundingBox(placementData, origin)

        if (!areTemplateChunksLoaded(world, box)) return false

        return template.place(world, origin, origin, placementData, world.random, 2)
    }

    private fun areTemplateChunksLoaded(world: ServerWorld, box: net.minecraft.util.math.BlockBox): Boolean {
        val minChunkX = Math.floorDiv(box.getMinX(), 16)
        val maxChunkX = Math.floorDiv(box.getMaxX(), 16)
        val minChunkZ = Math.floorDiv(box.getMinZ(), 16)
        val maxChunkZ = Math.floorDiv(box.getMaxZ(), 16)

        for (chunkX in minChunkX..maxChunkX) {
            for (chunkZ in minChunkZ..maxChunkZ) {
                if (!world.getChunkManager().isChunkLoaded(chunkX, chunkZ)) return false
            }
        }

        return true
    }

    private fun findRoadsideCandidates(
        world: ServerWorld,
        template: StructureTemplate,
        villageCenter: BlockPos
    ): List<PlacementCandidate> {
        val candidates = mutableListOf<PlacementCandidate>()
        val roads = roadPositionsNear(world, villageCenter, SITE_SEARCH_RADIUS)

        for (road in roads) {
            for (placement in placements) {
                val origin = originFacingRoad(template, road, placement.rotation, placement.frontDirection)
                val placementData = StructurePlacementData().setRotation(placement.rotation)
                val box = template.calculateBoundingBox(placementData, origin)
                val footprintScore = footprintScore(world, box, road.y) ?: continue
                val front = frontCenter(box, placement.frontDirection)
                val dx = front.x - road.x
                val dz = front.z - road.z
                val roadScore = dx * dx + dz * dz

                candidates += PlacementCandidate(
                    placement = placement,
                    origin = origin,
                    score = footprintScore + roadScore
                )
            }
        }

        return candidates.sortedBy { it.score }
    }

    private fun originFacingRoad(
        template: StructureTemplate,
        road: BlockPos,
        rotation: BlockRotation,
        frontDirection: Direction
    ): BlockPos {
        val initialOrigin = BlockPos(road.x, road.y + 1, road.z)
        val box = template.calculateBoundingBox(StructurePlacementData().setRotation(rotation), initialOrigin)
        val front = frontCenter(box, frontDirection)

        return initialOrigin.add(
            road.x - front.x,
            (road.y + 1) - front.y,
            road.z - front.z
        )
    }

    private fun footprintScore(world: ServerWorld, box: BlockBox, roadY: Int): Int? {
        var score = 0
        var pathBlocks = 0

        for (x in box.getMinX()..box.getMaxX()) {
            for (z in box.getMinZ()..box.getMaxZ()) {
                val surfaceY = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z) - 1
                val delta = abs(surfaceY - roadY)
                if (delta > MAX_SITE_GRADE_DELTA) return null

                val ground = world.getBlockState(BlockPos(x, surfaceY, z))
                if (ground.isOf(Blocks.DIRT_PATH)) pathBlocks++
                if (pathBlocks > MAX_PATH_BLOCKS_IN_FOOTPRINT) return null

                score += delta
            }
        }

        return score + pathBlocks * 20
    }

    private fun frontCenter(box: BlockBox, frontDirection: Direction): BlockPos {
        val centerX = (box.getMinX() + box.getMaxX()) / 2
        val centerZ = (box.getMinZ() + box.getMaxZ()) / 2
        val y = box.getMinY()
        return when (frontDirection) {
            Direction.NORTH -> BlockPos(centerX, y, box.getMinZ() - 1)
            Direction.SOUTH -> BlockPos(centerX, y, box.getMaxZ() + 1)
            Direction.WEST -> BlockPos(box.getMinX() - 1, y, centerZ)
            Direction.EAST -> BlockPos(box.getMaxX() + 1, y, centerZ)
            else -> BlockPos(centerX, y, centerZ)
        }
    }

    private fun roadPositionsNear(world: ServerWorld, center: BlockPos, radius: Int): List<BlockPos> {
        val roads = mutableListOf<BlockPos>()

        for (x in center.x - radius..center.x + radius) {
            for (z in center.z - radius..center.z + radius) {
                val y = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z) - 1
                val pos = BlockPos(x, y, z)
                if (!isVillageRoadBlock(world, pos)) continue

                roads += pos
            }
        }

        return roads
    }

    private fun isVillageRoadBlock(world: ServerWorld, pos: BlockPos): Boolean {
        val state = world.getBlockState(pos)
        return state.isOf(Blocks.DIRT_PATH) ||
            state.isOf(Blocks.SANDSTONE) ||
            state.isOf(Blocks.SMOOTH_SANDSTONE) ||
            state.isOf(Blocks.CUT_SANDSTONE)
    }

    private fun structureForBiome(world: ServerWorld, pos: BlockPos): String {
        val biomePath = world.getBiome(pos).key.map { it.value.path }.orElse("plains")
        return when {
            "desert" in biomePath -> "clerk_hall_desert"
            "savanna" in biomePath -> "clerk_hall_savanna"
            "taiga" in biomePath -> "clerk_hall_taiga"
            "snow" in biomePath || "frozen" in biomePath || "ice" in biomePath -> "clerk_hall_snowy"
            else -> "clerk_hall_plains"
        }
    }

    private fun isNear(first: BlockPos, second: BlockPos, radius: Int): Boolean {
        val radiusSquared = radius * radius
        val dx = first.x - second.x
        val dz = first.z - second.z
        return dx * dx + dz * dz <= radiusSquared
    }

    private data class Placement(
        val direction: Direction,
        val rotation: BlockRotation,
        val frontDirection: Direction
    )

    private data class PlacementCandidate(
        val placement: Placement,
        val origin: BlockPos,
        val score: Int
    )

    private data class PendingHall(
        val bellPos: BlockPos,
        val attempts: Int = 0
    )
}
