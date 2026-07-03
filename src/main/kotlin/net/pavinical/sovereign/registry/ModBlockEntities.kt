package net.pavinical.sovereign.registry

import net.fabricmc.fabric.api.`object`.builder.v1.block.entity.FabricBlockEntityTypeBuilder
import net.minecraft.block.entity.BlockEntityType
import net.minecraft.registry.Registries
import net.minecraft.registry.Registry
import net.minecraft.util.Identifier
import net.pavinical.sovereign.block.ClerkTableBlockEntity

object ModBlockEntities {
    private val CLERK_TABLE_ENTITY_ID: Identifier = Identifier.of("sovereign", "clerk_table")

    val CLERK_TABLE: BlockEntityType<ClerkTableBlockEntity> = Registry.register(
        Registries.BLOCK_ENTITY_TYPE,
        CLERK_TABLE_ENTITY_ID,
        FabricBlockEntityTypeBuilder.create(::ClerkTableBlockEntity, ModBlocks.CLERK_TABLE).build()
    )

    fun register() {
        // Accessing this object registers the static fields.
    }
}

