package net.pavinical.sovereign.economy

import net.minecraft.entity.player.PlayerEntity
import net.minecraft.entity.player.PlayerInventory
import net.minecraft.inventory.Inventory
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
    var inventorySlotsVisible: Boolean = true

    val clerkPos = BlockPos(clerkX, clerkY, clerkZ)

    init {
        for (row in 0 until 3) {
            for (col in 0 until 9) {
                val slotX = 107 + col * 18
                val slotY = 90 + row * 18
                addSlot(VisibilityControlledSlot(playerInventory, col + row * 9 + 9, slotX, slotY) { inventorySlotsVisible })
            }
        }
        for (col in 0 until 9) {
            val slotX = 107 + col * 18
            val slotY = 148
            addSlot(VisibilityControlledSlot(playerInventory, col, slotX, slotY) { inventorySlotsVisible })
        }
    }

    fun updateSnapshot(newSnapshot: VillageMarketSnapshot) {
        snapshot = newSnapshot
    }

    fun updateInventorySlotVisibility(visible: Boolean) {
        inventorySlotsVisible = visible
    }

    override fun quickMove(player: PlayerEntity?, index: Int): ItemStack = ItemStack.EMPTY

    override fun canUse(player: PlayerEntity): Boolean {
        return player.squaredDistanceTo(clerkPos.x + 0.5, clerkPos.y + 0.5, clerkPos.z + 0.5) <= 64.0
    }

    override fun onClosed(player: PlayerEntity) {
        super.onClosed(player)
        VillageTradeHandler.closeFor(player, clerkPos)
    }

}

private class VisibilityControlledSlot(
    inventory: Inventory,
    index: Int,
    x: Int,
    y: Int,
    private val visible: () -> Boolean
) : Slot(inventory, index, x, y) {
    override fun isEnabled(): Boolean {
        return visible()
    }
}

