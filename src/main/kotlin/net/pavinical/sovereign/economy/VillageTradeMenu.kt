package net.pavinical.sovereign.economy

import net.minecraft.entity.player.PlayerEntity
import net.minecraft.entity.player.PlayerInventory
import net.minecraft.item.ItemStack
import net.minecraft.screen.ScreenHandler
import net.minecraft.screen.slot.Slot
import net.minecraft.util.math.BlockPos

class VillageTradeMenu(
    syncId: Int,
    playerInventory: PlayerInventory,
    openData: VillageTradeOpenData
) : ScreenHandler(VillageTradeHandler.TYPE, syncId) {
    val clerkX: Int = openData.clerkX
    val clerkY: Int = openData.clerkY
    val clerkZ: Int = openData.clerkZ
    val villageName: String = openData.villageName
    var snapshot: VillageMarketSnapshot = openData.snapshot.toRuntime()

    val clerkPos = BlockPos(clerkX, clerkY, clerkZ)

    init {
        for (row in 0 until 3) {
            for (col in 0 until 9) {
                addSlot(Slot(playerInventory, col + row * 9 + 9, 107 + col * 18, 90 + row * 18))
            }
        }
        for (col in 0 until 9) {
            addSlot(Slot(playerInventory, col, 107 + col * 18, 148))
        }
    }

    fun updateSnapshot(newSnapshot: VillageMarketSnapshot) {
        snapshot = newSnapshot
    }

    override fun quickMove(player: PlayerEntity?, index: Int): ItemStack = ItemStack.EMPTY

    override fun canUse(player: PlayerEntity): Boolean {
        return player.squaredDistanceTo(clerkPos.x + 0.5, clerkPos.y + 0.5, clerkPos.z + 0.5) <= 64.0
    }

}

