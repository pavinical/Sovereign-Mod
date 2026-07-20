package net.pavinical.sovereign.economy

import net.minecraft.block.Blocks
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.BlockPos
import net.pavinical.sovereign.block.ClerkTableBlock
import net.pavinical.sovereign.data.VillageData

object VillageCenterService {
    fun ensureCenter(world: ServerWorld, village: VillageData): Boolean {
        if (world.getBlockState(village.centerPos).isOf(Blocks.BELL)) return false
        val nearestBell = nearestBell(world, village.clerkPos) ?: return false
        if (nearestBell == village.centerPos) return false
        village.centerPos = nearestBell
        return true
    }

    fun nearestBell(world: ServerWorld, origin: BlockPos): BlockPos? {
        val radius = ClerkTableBlock.VILLAGE_SCAN_RADIUS_BLOCKS
        val min = BlockPos(origin.x - radius, origin.y - 32, origin.z - radius)
        val max = BlockPos(origin.x + radius, origin.y + 32, origin.z + radius)
        var best: BlockPos? = null
        var bestDistance = Int.MAX_VALUE
        for (candidate in BlockPos.iterate(min, max)) {
            if (!world.getBlockState(candidate).isOf(Blocks.BELL)) continue
            val dx = candidate.x - origin.x
            val dz = candidate.z - origin.z
            val distance = dx * dx + dz * dz
            if (distance < bestDistance) {
                best = candidate.toImmutable()
                bestDistance = distance
            }
        }
        return best
    }
}
