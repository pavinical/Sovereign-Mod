package net.pavinical.sovereign.registry

import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents
import net.minecraft.block.AbstractBlock
import net.minecraft.block.Block
import net.minecraft.item.BlockItem
import net.minecraft.item.Item
import net.minecraft.item.ItemGroups
import net.minecraft.registry.Registries
import net.minecraft.registry.Registry
import net.minecraft.registry.RegistryKey
import net.minecraft.registry.RegistryKeys
import net.minecraft.util.Identifier
import net.pavinical.sovereign.block.ClerkTableBlock

/**
 * Registers all blocks (and their corresponding items) with Minecraft.
 *
 * Kotlin's object initializer runs the Registry.register() calls the moment
 * this object is first accessed — that happens when Sovereign.kt calls register().
 */
object ModBlocks {

    private val CLERK_TABLE_ID: Identifier = Identifier.of("sovereign", "clerk_table")
    private val CLERK_TABLE_BLOCK_KEY: RegistryKey<Block> =
        RegistryKey.of(RegistryKeys.BLOCK, CLERK_TABLE_ID)
    private val CLERK_TABLE_ITEM_KEY: RegistryKey<Item> =
        RegistryKey.of(RegistryKeys.ITEM, CLERK_TABLE_ID)

    val CLERK_TABLE: Block = Registry.register(
        Registries.BLOCK,
        CLERK_TABLE_ID,
        ClerkTableBlock(
            AbstractBlock.Settings.create()
                .registryKey(CLERK_TABLE_BLOCK_KEY)
                .strength(3.5f)   // medium hardness — harder than wood, softer than stone
                .requiresTool()
        )
    )

    /**
     * We register a BlockItem so the block can be given with /give for testing.
     * Per the design doc it won't be craftable and won't drop when broken —
     * that behaviour will be enforced in a future phase.
     */
    private val CLERK_TABLE_ITEM: BlockItem = Registry.register(
        Registries.ITEM,
        CLERK_TABLE_ID,
        BlockItem(CLERK_TABLE, Item.Settings().registryKey(CLERK_TABLE_ITEM_KEY))
    )

    fun register() {
        // Accessing this object triggers the static initializers above.
        // We also add the Clerk Table to the Functional Blocks creative tab
        // so it's easy to grab during testing without /give.
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.FUNCTIONAL).register { entries ->
            entries.add(CLERK_TABLE_ITEM)
        }
    }
}
