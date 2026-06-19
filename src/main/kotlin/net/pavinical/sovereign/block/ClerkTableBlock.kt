package net.pavinical.sovereign.block

import net.minecraft.block.Block
import net.minecraft.block.BlockState
import net.minecraft.block.ShapeContext
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.text.Text
import net.minecraft.util.ActionResult
import net.minecraft.util.hit.BlockHitResult
import net.minecraft.util.math.BlockPos
import net.minecraft.util.shape.VoxelShape
import net.minecraft.world.BlockView
import net.minecraft.world.World
import net.pavinical.sovereign.economy.NamingSession
import net.pavinical.sovereign.economy.VillageRegistry

/**
 * The Clerk Table — the block players interact with to found and view villages.
 *
 * Right-click behaviour:
 *   • If no village exists at this position → prompts player to name a new village.
 *   • If a village already exists here → shows that village's current status.
 *
 * Per the design doc, this block cannot be crafted and does not drop when broken.
 * It can only be obtained via /give for now (testing) and will spawn naturally later.
 */
class ClerkTableBlock(settings: Settings) : Block(settings) {

    companion object {
        const val MIN_VILLAGE_DISTANCE_BLOCKS = 100

        // Slightly shorter than a full block — looks like a table
        val SHAPE: VoxelShape = createCuboidShape(0.0, 0.0, 0.0, 16.0, 12.0, 16.0)
    }

    override fun onUse(
        state: BlockState,
        world: World,
        pos: BlockPos,
        player: PlayerEntity,
        hit: BlockHitResult
    ): ActionResult {
        // Logic runs only on the server; return early on client to avoid double execution
        if (world.isClient) return ActionResult.SUCCESS

        val serverPlayer = player as? ServerPlayerEntity ?: return ActionResult.PASS
        val server = world.server ?: return ActionResult.PASS

        // The registry lives in the overworld's persistent state
        val registry = server.overworld.persistentStateManager
            .getOrCreate<VillageRegistry>(VillageRegistry.TYPE)

        val existing = registry.getVillageAtPos(pos)

        if (existing != null) {
            // Village already founded here — show a quick status line
            serverPlayer.sendMessage(
                Text.literal(
                    "§6[${existing.name}]  §rTier: §e${existing.tier.name}  §rBiome: §a${existing.biome}"
                ),
                false
            )
            return ActionResult.SUCCESS
        }

        // No village yet — kick off the naming flow
        val nearby = registry.getVillageWithinRadius(pos, MIN_VILLAGE_DISTANCE_BLOCKS)
        if (nearby != null) {
            serverPlayer.sendMessage(
                Text.literal("Â§cA village is already registered within $MIN_VILLAGE_DISTANCE_BLOCKS blocks: Â§6${nearby.name}"),
                false
            )
            return ActionResult.SUCCESS
        }

        val biome = world.getBiome(pos).key
            .map { it.value.path }
            .orElse("unknown")

        NamingSession.start(serverPlayer.uuid, pos, biome)
        serverPlayer.sendMessage(
            Text.literal("§eType a name for this village in chat. Your next message will be used as the name."),
            false
        )

        return ActionResult.SUCCESS
    }

    override fun getOutlineShape(
        state: BlockState,
        world: BlockView,
        pos: BlockPos,
        context: ShapeContext
    ): VoxelShape = SHAPE
}
