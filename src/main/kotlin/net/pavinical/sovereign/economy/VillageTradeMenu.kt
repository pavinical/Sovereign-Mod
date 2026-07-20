package net.pavinical.sovereign.economy

import net.minecraft.entity.player.PlayerEntity
import net.minecraft.entity.player.PlayerInventory
import net.minecraft.inventory.Inventory
import net.minecraft.inventory.SimpleInventory
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
    var commissionInputSlotVisible: Boolean = false
    var visibleCommissionInputSlots: Int = 1
    var firstVisibleCommissionInputSlot: Int = 0
    private val clientSide = playerInventory.player.world.isClient
    private val commissionInputInventory = SimpleInventory(COMMISSION_INPUT_SLOT_COUNT)

    val clerkPos = BlockPos(clerkX, clerkY, clerkZ)

    init {
        for (row in 0 until 3) {
            for (col in 0 until 9) {
                val slotX = 109 + col * 18
                val slotY = 92 + row * 18
                addSlot(VisibilityControlledSlot(playerInventory, col + row * 9 + 9, slotX, slotY) { inventorySlotsVisible })
            }
        }
        for (col in 0 until 9) {
            val slotX = 109 + col * 18
            val slotY = 150
            addSlot(VisibilityControlledSlot(playerInventory, col, slotX, slotY) { inventorySlotsVisible })
        }
        for (slot in 0 until COMMISSION_INPUT_SLOT_COUNT) {
            val slotX = COMMISSION_INPUT_SLOT_X[slot]
            val slotY = 44
            addSlot(VisibilityControlledSlot(commissionInputInventory, slot, slotX, slotY, serverAcceptsWhenHidden = !clientSide) {
                commissionInputSlotVisible &&
                    slot >= firstVisibleCommissionInputSlot &&
                    slot < firstVisibleCommissionInputSlot + visibleCommissionInputSlots
            })
        }
    }

    fun updateSnapshot(newSnapshot: VillageMarketSnapshot) {
        snapshot = newSnapshot
    }

    fun updateInventorySlotVisibility(visible: Boolean) {
        inventorySlotsVisible = visible
    }

    fun updateCommissionInputSlotVisibility(visible: Boolean, visibleSlots: Int = 1, firstVisibleSlot: Int = 0) {
        commissionInputSlotVisible = visible
        visibleCommissionInputSlots = visibleSlots.coerceIn(0, COMMISSION_INPUT_SLOT_COUNT)
        firstVisibleCommissionInputSlot = firstVisibleSlot.coerceIn(0, COMMISSION_INPUT_SLOT_COUNT - visibleCommissionInputSlots)
    }

    fun commissionInputStack(): ItemStack =
        (0 until COMMISSION_INPUT_SLOT_COUNT)
            .map { commissionInputInventory.getStack(it) }
            .firstOrNull { !it.isEmpty }
            ?: ItemStack.EMPTY

    fun commissionInputStack(slot: Int): ItemStack =
        if (slot in 0 until COMMISSION_INPUT_SLOT_COUNT) commissionInputInventory.getStack(slot) else ItemStack.EMPTY

    fun clearCommissionInput() {
        for (slot in 0 until COMMISSION_INPUT_SLOT_COUNT) {
            commissionInputInventory.setStack(slot, ItemStack.EMPTY)
        }
    }

    override fun quickMove(player: PlayerEntity?, index: Int): ItemStack {
        if (player == null || index !in slots.indices) return ItemStack.EMPTY
        val slot = slots[index]
        if (!slot.hasStack()) return ItemStack.EMPTY
        val original = slot.stack.copy()
        if (index in COMMISSION_INPUT_SLOT_INDEX until COMMISSION_INPUT_SLOT_INDEX + COMMISSION_INPUT_SLOT_COUNT) {
            if (!insertItem(slot.stack, 0, PLAYER_SLOT_COUNT, true)) return ItemStack.EMPTY
        } else if (commissionInputSlotVisible || !clientSide) {
            val inputSlot = (firstVisibleCommissionInputSlot until firstVisibleCommissionInputSlot + visibleCommissionInputSlots)
                .map { slots[COMMISSION_INPUT_SLOT_INDEX + it] }
                .firstOrNull { !it.hasStack() }
                ?: return ItemStack.EMPTY
            if (inputSlot.hasStack()) return ItemStack.EMPTY
            inputSlot.stack = slot.stack.split(1)
            inputSlot.markDirty()
        } else {
            return ItemStack.EMPTY
        }
        if (slot.stack.isEmpty) {
            slot.stack = ItemStack.EMPTY
        }
        slot.markDirty()
        return original
    }

    override fun canUse(player: PlayerEntity): Boolean {
        return player.squaredDistanceTo(clerkPos.x + 0.5, clerkPos.y + 0.5, clerkPos.z + 0.5) <= 64.0
    }

    override fun onClosed(player: PlayerEntity) {
        super.onClosed(player)
        if (!player.world.isClient) {
            for (slot in 0 until COMMISSION_INPUT_SLOT_COUNT) {
                val stack = commissionInputInventory.removeStack(slot)
                if (!stack.isEmpty && !player.inventory.insertStack(stack)) {
                    player.dropItem(stack, false)
                }
            }
        }
        VillageTradeHandler.closeFor(player, clerkPos)
    }

    companion object {
        private const val PLAYER_SLOT_COUNT = 36
        private const val COMMISSION_INPUT_SLOT_INDEX = 36
        private const val COMMISSION_INPUT_SLOT_COUNT = 4
        private val COMMISSION_INPUT_SLOT_X = intArrayOf(126, 166, 179, 214)
    }
}

private class VisibilityControlledSlot(
    inventory: Inventory,
    index: Int,
    x: Int,
    y: Int,
    private val serverAcceptsWhenHidden: Boolean = false,
    private val visible: () -> Boolean
) : Slot(inventory, index, x, y) {
    override fun isEnabled(): Boolean {
        return visible() || serverAcceptsWhenHidden
    }

    override fun canInsert(stack: ItemStack): Boolean {
        return visible() || serverAcceptsWhenHidden
    }
}

