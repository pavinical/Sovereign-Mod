package net.pavinical.sovereign.block

import net.minecraft.block.Block
import net.minecraft.block.BlockEntityProvider
import net.minecraft.block.BlockState
import net.minecraft.block.ShapeContext
import net.minecraft.block.entity.BlockEntity
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.ActionResult
import net.minecraft.util.hit.BlockHitResult
import net.minecraft.util.math.BlockPos
import net.minecraft.util.shape.VoxelShape
import net.minecraft.world.BlockView
import net.minecraft.world.World
import net.pavinical.sovereign.economy.NamingSession
import net.pavinical.sovereign.economy.VillageRegistry
import net.pavinical.sovereign.registry.ModBlockEntities
import net.pavinical.sovereign.economy.VillageTradeHandler

/**
 * The Clerk Table block players interact with to found and view villages.
 *
 * Right-click behavior:
 * - If no village exists at this position, prompts player to name a new village.
 * - If a village already exists here, opens the village trading post.
 */
class ClerkTableBlock(settings: Settings) : Block(settings), BlockEntityProvider {
    companion object {
        const val VILLAGE_SCAN_RADIUS_BLOCKS = 100
        const val MIN_VILLAGE_DISTANCE_BLOCKS = VILLAGE_SCAN_RADIUS_BLOCKS * 2

        // Slightly shorter than a full block so it reads as a table.
        val SHAPE: VoxelShape = createCuboidShape(0.0, 0.0, 0.0, 16.0, 12.0, 16.0)
    }

    override fun onUse(
        state: BlockState,
        world: World,
        pos: BlockPos,
        player: PlayerEntity,
        hit: BlockHitResult
    ): ActionResult {
        if (world.isClient) return ActionResult.SUCCESS

        val serverPlayer = player as? ServerPlayerEntity ?: return ActionResult.PASS
        val server = world.server ?: return ActionResult.PASS

        val registry = server.overworld.persistentStateManager
            .getOrCreate(VillageRegistry.TYPE, VillageRegistry.KEY)

        val clerkTable = world.getBlockEntity(pos) as? ClerkTableBlockEntity
        val existing = clerkTable?.villageId?.let { registry.getVillageById(it) }
            ?: registry.getVillageAtPos(pos)

        if (existing != null) {
            clerkTable?.markVillage(existing.id)
            if (!VillageTradeHandler.openFor(serverPlayer, server.overworld, pos)) {
                serverPlayer.sendMessage(
                    Text.literal("The trading post could not be opened right now."),
                    false
                )
            }
            return ActionResult.SUCCESS
        }

        val sharedVillage = registry.getVillageWithinRadius(pos, VILLAGE_SCAN_RADIUS_BLOCKS)
        if (sharedVillage != null) {
            clerkTable?.markVillage(sharedVillage.id)
            if (!VillageTradeHandler.openFor(serverPlayer, server.overworld, pos)) {
                serverPlayer.sendMessage(
                    Text.literal("The trading post could not be opened right now."),
                    false
                )
            }
            return ActionResult.SUCCESS
        }

        val claimedVillage = registry.getVillageWithinRadius(pos, MIN_VILLAGE_DISTANCE_BLOCKS)
        if (claimedVillage != null) {
            serverPlayer.sendMessage(
                Text.literal("Land is claimed by ${claimedVillage.name}.").formatted(Formatting.RED),
                false
            )
            return ActionResult.SUCCESS
        }

        val biome = world.getBiome(pos).key
            .map { it.value.path }
            .orElse("unknown")

        NamingSession.start(serverPlayer.uuid, pos, biome)
        VillageTradeHandler.openNamingScreen(serverPlayer, pos)

        return ActionResult.SUCCESS
    }

    override fun createBlockEntity(pos: BlockPos, state: BlockState): BlockEntity {
        return ClerkTableBlockEntity(pos, state)
    }

    override fun getOutlineShape(
        state: BlockState,
        world: BlockView,
        pos: BlockPos,
        context: ShapeContext
    ): VoxelShape = SHAPE
}

