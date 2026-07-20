package net.pavinical.sovereign.data

import net.minecraft.item.ItemStack
import java.util.UUID

data class VillageCommissionData(
    val id: String,
    val playerId: UUID,
    val offerId: String,
    val itemId: String,
    val displayName: String,
    val professionId: String,
    val professionName: String,
    val emeraldCost: Int,
    val orderedTick: Long,
    var readyTick: Long,
    val quantity: Int = 1,
    val inputStack: ItemStack = ItemStack.EMPTY,
    var claimed: Boolean = false
)
