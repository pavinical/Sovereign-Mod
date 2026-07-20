package net.pavinical.sovereign.economy

import net.minecraft.block.Blocks
import net.minecraft.item.Item
import net.minecraft.item.ItemStack

object JobBlockMarket {
    private const val PEASANT_JOB_BLOCK_COST = 500
    private const val ARTISAN_JOB_BLOCK_COST = 1000

    data class Definition(
        val item: Item,
        val professionId: String,
        val professionName: String,
        val emeraldCost: Int
    ) {
        fun toOffer(): JobBlockOffer = JobBlockOffer(
            item = ItemStack(item),
            displayName = ItemStack(item).name.string,
            professionId = professionId,
            professionName = professionName,
            emeraldCost = emeraldCost
        )
    }

    private val offers = listOf(
        Definition(Blocks.COMPOSTER.asItem(), "minecraft:farmer", "Farmer", PEASANT_JOB_BLOCK_COST),
        Definition(Blocks.LOOM.asItem(), "minecraft:shepherd", "Shepherd", PEASANT_JOB_BLOCK_COST),
        Definition(Blocks.STONECUTTER.asItem(), "minecraft:mason", "Miner", PEASANT_JOB_BLOCK_COST),
        Definition(Blocks.FLETCHING_TABLE.asItem(), "minecraft:fletcher", "Lumberjack", PEASANT_JOB_BLOCK_COST),
        Definition(Blocks.SMOKER.asItem(), "minecraft:butcher", "Butcher", PEASANT_JOB_BLOCK_COST),
        Definition(Blocks.BARREL.asItem(), "minecraft:fisherman", "Fisherman", PEASANT_JOB_BLOCK_COST),
        Definition(Blocks.CAULDRON.asItem(), "minecraft:leatherworker", "Leatherworker", PEASANT_JOB_BLOCK_COST),
        Definition(Blocks.BLAST_FURNACE.asItem(), "minecraft:armorer", "Armorer", ARTISAN_JOB_BLOCK_COST),
        Definition(Blocks.SMITHING_TABLE.asItem(), "minecraft:toolsmith", "Toolsmith", ARTISAN_JOB_BLOCK_COST),
        Definition(Blocks.GRINDSTONE.asItem(), "minecraft:weaponsmith", "Weaponsmith", ARTISAN_JOB_BLOCK_COST),
        Definition(Blocks.BREWING_STAND.asItem(), "minecraft:cleric", "Cleric", ARTISAN_JOB_BLOCK_COST),
        Definition(Blocks.LECTERN.asItem(), "minecraft:librarian", "Librarian", ARTISAN_JOB_BLOCK_COST),
        Definition(Blocks.CARTOGRAPHY_TABLE.asItem(), "minecraft:cartographer", "Cartographer", ARTISAN_JOB_BLOCK_COST)
    )

    fun offers(): List<JobBlockOffer> = offers.map { it.toOffer() }

    fun clerkOffers(): List<JobBlockOffer> = offers() + JobBlockRelocationTool.offer()

    fun definitionForProfession(professionId: String): Definition? =
        offers.firstOrNull { it.professionId == professionId }
}
