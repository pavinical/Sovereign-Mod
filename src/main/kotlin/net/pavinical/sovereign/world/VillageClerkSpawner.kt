package net.pavinical.sovereign.world

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents
import net.minecraft.block.Blocks
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Direction
import net.minecraft.world.Heightmap
import net.minecraft.world.chunk.WorldChunk
import net.pavinical.sovereign.registry.ModBlocks

/**
 * Places Clerk Tables into newly generated villages.
 *
 * Vanilla villages always generate a bell near their town center, so this uses
 * that bell as the anchor instead of replacing vanilla structure pool JSON.
 */
object VillageClerkSpawner {
    private const val BELL_SCAN_RADIUS = 6
    private const val EXISTING_CLERK_RADIUS = 24
    private const val SURFACE_SCAN_BELOW = 4
    private const val SURFACE_SCAN_ABOVE = 6

    fun register() {
        ServerChunkEvents.CHUNK_GENERATE.register { world, chunk ->
            tryPlaceNearVillageBell(world, chunk)
        }
    }

    private fun tryPlaceNearVillageBell(world: ServerWorld, chunk: WorldChunk) {
        val chunkPos = chunk.pos
        val minX = chunkPos.startX
        val minZ = chunkPos.startZ
        val maxX = minX + 15
        val maxZ = minZ + 15

        val mutable = BlockPos.Mutable()
        for (localX in 0..15) {
            for (localZ in 0..15) {
                val x = minX + localX
                val z = minZ + localZ
                val surfaceY = chunk.sampleHeightmap(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, localX, localZ)
                val minY = maxOf(world.bottomY, surfaceY - SURFACE_SCAN_BELOW)
                val maxY = minOf(world.topY - 1, surfaceY + SURFACE_SCAN_ABOVE)

                for (y in minY..maxY) {
                    mutable.set(x, y, z)
                    if (chunk.getBlockState(mutable).isOf(Blocks.BELL)) {
                        placeClerkTableNearBell(world, chunk, mutable.toImmutable(), minX, maxX, minZ, maxZ)
                        return
                    }
                }
            }
        }
    }

    private fun placeClerkTableNearBell(
        world: ServerWorld,
        chunk: WorldChunk,
        bellPos: BlockPos,
        minX: Int,
        maxX: Int,
        minZ: Int,
        maxZ: Int
    ) {
        if (hasNearbyClerkTable(chunk, bellPos, minX, maxX, minZ, maxZ)) return

        for (radius in 2..BELL_SCAN_RADIUS) {
            for (xOffset in -radius..radius) {
                for (zOffset in -radius..radius) {
                    if (kotlin.math.abs(xOffset) != radius && kotlin.math.abs(zOffset) != radius) continue

                    for (yOffset in -2..2) {
                        val candidate = bellPos.add(xOffset, yOffset, zOffset)
                        if (candidate.x !in minX..maxX || candidate.z !in minZ..maxZ) continue
                        if (!canPlaceClerkTableAt(world, chunk, candidate)) continue

                        chunk.setBlockState(candidate, ModBlocks.CLERK_TABLE.defaultState, false)
                        return
                    }
                }
            }
        }
    }

    private fun hasNearbyClerkTable(
        chunk: WorldChunk,
        center: BlockPos,
        minX: Int,
        maxX: Int,
        minZ: Int,
        maxZ: Int
    ): Boolean {
        val mutable = BlockPos.Mutable()
        val scanMinX = maxOf(center.x - EXISTING_CLERK_RADIUS, minX)
        val scanMaxX = minOf(center.x + EXISTING_CLERK_RADIUS, maxX)
        val scanMinZ = maxOf(center.z - EXISTING_CLERK_RADIUS, minZ)
        val scanMaxZ = minOf(center.z + EXISTING_CLERK_RADIUS, maxZ)

        for (x in scanMinX..scanMaxX) {
            for (z in scanMinZ..scanMaxZ) {
                for (y in center.y - 4..center.y + 4) {
                    mutable.set(x, y, z)
                    if (chunk.getBlockState(mutable).isOf(ModBlocks.CLERK_TABLE)) {
                        return true
                    }
                }
            }
        }

        return false
    }

    private fun canPlaceClerkTableAt(world: ServerWorld, chunk: WorldChunk, pos: BlockPos): Boolean {
        if (!chunk.getBlockState(pos).isAir) return false
        if (!chunk.getBlockState(pos.up()).isAir) return false

        val floorPos = pos.down()
        val floorState = chunk.getBlockState(floorPos)
        return floorState.isSideSolidFullSquare(world, floorPos, Direction.UP)
    }
}

