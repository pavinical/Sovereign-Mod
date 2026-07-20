package net.pavinical.sovereign.registry

import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents
import net.minecraft.item.Item
import net.minecraft.item.ItemGroups
import net.minecraft.registry.Registries
import net.minecraft.registry.Registry
import net.minecraft.util.Identifier
import net.pavinical.sovereign.Sovereign

object ModItems {
    val STAMP_TOOL: Item = Registry.register(
        Registries.ITEM,
        Identifier.of(Sovereign.MOD_ID, "stamp_tool"),
        Item(Item.Settings().maxCount(1))
    )

    fun register() {
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.TOOLS).register { entries ->
            entries.add(STAMP_TOOL)
        }
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.SEARCH).register { entries ->
            entries.add(STAMP_TOOL)
        }
    }
}
