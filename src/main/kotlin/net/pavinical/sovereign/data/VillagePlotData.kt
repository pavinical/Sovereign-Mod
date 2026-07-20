package net.pavinical.sovereign.data

import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Direction
import java.util.UUID

data class VillagePlotData(
    val id: String = UUID.randomUUID().toString(),
    val origin: BlockPos,
    var sizeX: Int,
    var sizeZ: Int,
    var allowedType: String = "any",
    var facing: Direction = Direction.NORTH,
    var buildingLevel: Int = 0,
    var builtType: String = "",
    var structureId: String = "",
    var pendingBuiltType: String = "",
    var pendingLevel: Int = 0,
    var buildReadyTick: Long = 0L,
    var reservedBy: UUID? = null,
    var manualPlacement: Boolean = false
) {
    fun isEmpty(): Boolean = builtType.isBlank()
    fun isPending(): Boolean = pendingBuiltType.isNotBlank()

    fun center(): BlockPos {
        val halfX = sizeX / 2
        val halfZ = sizeZ / 2
        return when (facing) {
            Direction.NORTH -> origin.add(0, 0, halfZ)
            Direction.SOUTH -> origin.add(0, 0, -halfZ)
            Direction.WEST -> origin.add(halfX, 0, 0)
            Direction.EAST -> origin.add(-halfX, 0, 0)
            else -> origin
        }
    }
}
