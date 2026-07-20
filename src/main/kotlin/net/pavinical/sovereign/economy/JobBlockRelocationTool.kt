package net.pavinical.sovereign.economy

import net.minecraft.component.DataComponentTypes
import net.minecraft.component.type.NbtComponent
import net.minecraft.item.ItemStack
import net.minecraft.text.Text
import net.pavinical.sovereign.data.VillageData
import net.pavinical.sovereign.registry.ModItems

object JobBlockRelocationTool {
    const val OFFER_ID = "sovereign:job_block_relocation_tool"
    const val EMERALD_COST = 750
    private const val TAG_RELOCATION_TOOL = "sovereign_job_block_relocation_tool"
    private const val TAG_VILLAGE_ID = "sovereign_village_id"
    private const val TAG_VILLAGE_NAME = "sovereign_village_name"

    fun offer(): JobBlockOffer = JobBlockOffer(
        item = ItemStack(ModItems.STAMP_TOOL),
        displayName = "Village-bound Tool",
        professionId = OFFER_ID,
        professionName = "Relocation Tool",
        emeraldCost = EMERALD_COST
    )

    fun createStack(village: VillageData): ItemStack {
        val stack = ItemStack(ModItems.STAMP_TOOL)
        stack.set(DataComponentTypes.CUSTOM_NAME, Text.literal("${village.name} Job Relocation Tool"))
        NbtComponent.set(DataComponentTypes.CUSTOM_DATA, stack) { nbt ->
            nbt.putBoolean(TAG_RELOCATION_TOOL, true)
            nbt.putString(TAG_VILLAGE_ID, village.id.toString())
            nbt.putString(TAG_VILLAGE_NAME, village.name)
        }
        return stack
    }

    fun isValidForVillage(stack: ItemStack, village: VillageData): Boolean {
        if (!stack.isOf(ModItems.STAMP_TOOL)) return false
        val customData = stack.get(DataComponentTypes.CUSTOM_DATA) ?: return false
        val nbt = customData.copyNbt()
        return nbt.getBoolean(TAG_RELOCATION_TOOL) && nbt.getString(TAG_VILLAGE_ID) == village.id.toString()
    }
}
