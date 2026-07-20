package net.pavinical.sovereign.event

import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents
import net.fabricmc.fabric.api.event.player.UseBlockCallback
import net.minecraft.block.Block
import net.minecraft.block.BlockState
import net.minecraft.block.Blocks
import net.minecraft.component.DataComponentTypes
import net.minecraft.component.type.NbtComponent
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.item.BlockItem
import net.minecraft.item.ItemPlacementContext
import net.minecraft.item.ItemStack
import net.minecraft.nbt.NbtCompound
import net.minecraft.registry.Registries
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.text.Text
import net.minecraft.util.ActionResult
import net.minecraft.util.Formatting
import net.minecraft.util.math.BlockPos
import net.minecraft.village.VillagerProfession
import net.minecraft.world.World
import net.pavinical.sovereign.block.ClerkTableBlock
import net.pavinical.sovereign.block.ClerkTableBlockEntity
import net.pavinical.sovereign.data.VillageData
import net.pavinical.sovereign.economy.JobBlockRelocationTool
import net.pavinical.sovereign.economy.VillageRegistry
import net.pavinical.sovereign.registry.ModBlocks

object ProfessionBlockProtection {
    private const val TAG_AUTHORIZED_JOB_BLOCK = "sovereign_authorized_job_block"
    private const val TAG_JOB_PROFESSION = "sovereign_job_profession"
    private const val PENDING_CLERK_VERTICAL_SCAN_BLOCKS = 64
    private const val OUTER_JOB_SITE_BUFFER_BLOCKS = 48

    private data class PlacementProtectionZone(
        val name: String,
        val registeredVillage: VillageData?
    )

    private val jobBlockProfessions: Map<Block, String> = mapOf(
        Blocks.COMPOSTER to "minecraft:farmer",
        Blocks.LOOM to "minecraft:shepherd",
        Blocks.STONECUTTER to "minecraft:mason",
        Blocks.FLETCHING_TABLE to "minecraft:fletcher",
        Blocks.SMOKER to "minecraft:butcher",
        Blocks.BARREL to "minecraft:fisherman",
        Blocks.CAULDRON to "minecraft:leatherworker",
        Blocks.BLAST_FURNACE to "minecraft:armorer",
        Blocks.SMITHING_TABLE to "minecraft:toolsmith",
        Blocks.GRINDSTONE to "minecraft:weaponsmith",
        Blocks.BREWING_STAND to "minecraft:cleric",
        Blocks.LECTERN to "minecraft:librarian",
        Blocks.CARTOGRAPHY_TABLE to "minecraft:cartographer"
    )

    fun isJobBlock(block: Block): Boolean = block in jobBlockProfessions

    fun register() {
        PlayerBlockBreakEvents.BEFORE.register { world, player, pos, state, _ ->
            canBreakProfessionBlock(world, player, pos, state)
        }

        UseBlockCallback.EVENT.register { player, world, hand, hit ->
            val stack = player.getStackInHand(hand)
            val placementPos = placementPos(player, hand, stack, hit)
            if (placementPos != null && CivilianJobBlockService.isCivilianJobBlockStack(stack)) {
                if (world is ServerWorld) {
                    CivilianJobBlockService.queuePlacement(world, placementPos)
                }
                ActionResult.PASS
            } else if (placementPos != null && world is ServerWorld) {
                val zone = placementProtectionZone(world, placementPos)
                if (zone != null && isUnauthorizedJobBlockPlacement(stack)) {
                    rejectJobBlockPlacement(world, player, stack, zone)
                } else {
                    ActionResult.PASS
                }
            } else {
                ActionResult.PASS
            }
        }
    }

    fun authorizeJobBlockStack(stack: ItemStack, professionId: String): ItemStack {
        NbtComponent.set(DataComponentTypes.CUSTOM_DATA, stack) { nbt ->
            nbt.putBoolean(TAG_AUTHORIZED_JOB_BLOCK, true)
            nbt.putString(TAG_JOB_PROFESSION, professionId)
        }
        return stack
    }

    private fun isPlacementAttempt(
        player: PlayerEntity,
        hand: net.minecraft.util.Hand,
        stack: ItemStack,
        hit: net.minecraft.util.hit.BlockHitResult
    ): Boolean = placementPos(player, hand, stack, hit) != null

    private fun placementPos(
        player: PlayerEntity,
        hand: net.minecraft.util.Hand,
        stack: ItemStack,
        hit: net.minecraft.util.hit.BlockHitResult
    ): BlockPos? {
        val blockItem = stack.item as? BlockItem ?: return null
        val placementContext = blockItem.getPlacementContext(ItemPlacementContext(player, hand, stack, hit))
        return placementContext?.takeIf { it.canPlace() }?.blockPos
    }

    private fun canBreakProfessionBlock(
        world: World,
        player: PlayerEntity,
        pos: BlockPos,
        state: BlockState
    ): Boolean {
        val professionId = jobBlockProfessions[state.block] ?: return true
        val serverWorld = world as? ServerWorld ?: return true
        if (CivilianJobBlockService.isCivilianJobBlock(serverWorld, pos)) {
            pickUpCivilianProfessionBlock(serverWorld, player, pos, state)
            return false
        }
        val village = serverWorld.persistentStateManager
            .getOrCreate(VillageRegistry.TYPE, VillageRegistry.KEY)
            .getVillageWithinRadius(pos, ClerkTableBlock.VILLAGE_SCAN_RADIUS_BLOCKS)
            ?: return true

        if (player is ServerPlayerEntity && hasRelocationToolForVillage(player, village)) {
            moveProfessionBlock(serverWorld, player, pos, state, professionId, village)
            return false
        }

        val clerk = serverWorld.getBlockEntity(village.clerkPos) as? ClerkTableBlockEntity ?: return true
        val occupied = clerk.getSnapshot(village).hamletSlots.any { slot ->
            slot.occupied && professionId(slot.requiredProfession) == professionId
        }
        if (!occupied) return true

        (player as? ServerPlayerEntity)?.sendMessage(
            Text.literal("The ${professionName(professionId)} is still on duty in ${village.name}. Use ${village.name}'s relocation seal before moving their station.")
                .formatted(Formatting.RED),
            false
        )
        // Reputation hook: this is where a future system can allow the break and apply a penalty.
        return false
    }

    private fun hasRelocationToolForVillage(player: ServerPlayerEntity, village: VillageData): Boolean {
        return JobBlockRelocationTool.isValidForVillage(player.mainHandStack, village) ||
            JobBlockRelocationTool.isValidForVillage(player.offHandStack, village)
    }

    private fun moveProfessionBlock(
        world: ServerWorld,
        player: ServerPlayerEntity,
        pos: BlockPos,
        state: BlockState,
        professionId: String,
        village: VillageData
    ) {
        val stack = authorizeJobBlockStack(ItemStack(state.block.asItem()), professionId)
        world.setBlockState(pos, Blocks.AIR.defaultState, Block.NOTIFY_ALL)
        if (!player.inventory.insertStack(stack)) {
            player.dropItem(stack, false)
        }
        player.sendMessage(
            Text.literal("${village.name}'s ${professionName(professionId)} station has been packed for moving.")
                .formatted(Formatting.GREEN),
            false
        )
    }

    private fun pickUpCivilianProfessionBlock(
        world: ServerWorld,
        player: PlayerEntity,
        pos: BlockPos,
        state: BlockState
    ) {
        CivilianJobBlockService.remove(world, pos)
        val stack = CivilianJobBlockService.markCivilianJobBlockStack(ItemStack(state.block.asItem()))
        world.setBlockState(pos, Blocks.AIR.defaultState, Block.NOTIFY_ALL)
        if (player is ServerPlayerEntity) {
            if (!player.inventory.insertStack(stack)) {
                player.dropItem(stack, false)
            }
        } else {
            Block.dropStack(world, pos, stack)
        }
    }

    private fun isUnauthorizedJobBlockPlacement(stack: ItemStack): Boolean {
        val blockItem = stack.item as? BlockItem ?: return false
        val professionId = jobBlockProfessions[blockItem.block] ?: return false
        if (CivilianJobBlockService.isCivilianJobBlockStack(stack)) return false
        val customData = stack.get(DataComponentTypes.CUSTOM_DATA) ?: return true
        val nbt = customData.copyNbt()
        if (!nbt.getBoolean(TAG_AUTHORIZED_JOB_BLOCK)) return true
        val authorizedProfession = nbt.getString(TAG_JOB_PROFESSION)
        return authorizedProfession.isNotBlank() && authorizedProfession != professionId
    }

    private fun placementProtectionZone(world: ServerWorld, pos: BlockPos): PlacementProtectionZone? {
        val protectionRadius = ClerkTableBlock.VILLAGE_SCAN_RADIUS_BLOCKS + OUTER_JOB_SITE_BUFFER_BLOCKS
        val village = world.persistentStateManager
            .getOrCreate(VillageRegistry.TYPE, VillageRegistry.KEY)
            .getVillageWithinRadius(pos, protectionRadius)
        if (village != null) {
            return PlacementProtectionZone(village.name, village)
        }

        return pendingClerkTableNear(world, pos, protectionRadius)?.let {
            PlacementProtectionZone("this pending village", null)
        }
    }

    private fun pendingClerkTableNear(world: ServerWorld, pos: BlockPos, radius: Int): BlockPos? {
        val min = BlockPos(pos.x - radius, pos.y - PENDING_CLERK_VERTICAL_SCAN_BLOCKS, pos.z - radius)
        val max = BlockPos(pos.x + radius, pos.y + PENDING_CLERK_VERTICAL_SCAN_BLOCKS, pos.z + radius)
        val radiusSquared = radius * radius
        for (candidate in BlockPos.iterate(min, max)) {
            if (!world.getBlockState(candidate).isOf(ModBlocks.CLERK_TABLE)) continue
            val dx = candidate.x - pos.x
            val dz = candidate.z - pos.z
            if (dx * dx + dz * dz <= radiusSquared) {
                return candidate.toImmutable()
            }
        }
        return null
    }

    private fun rejectJobBlockPlacement(world: World, player: PlayerEntity, stack: ItemStack, zone: PlacementProtectionZone): ActionResult {
        val blockName = stack.name.string
        val destination = zone.registeredVillage?.let { "${it.name}" } ?: zone.name
        (player as? ServerPlayerEntity)?.sendMessage(
            Text.literal("The clerk of $destination has not stamped this $blockName. Use a crafted builder's block, or buy a work permit at the clerk table.")
                .formatted(Formatting.RED),
            false
        )
        return ActionResult.FAIL
    }

    private fun professionId(profession: VillagerProfession): String =
        Registries.VILLAGER_PROFESSION.getId(profession).toString()

    private fun professionName(professionId: String): String = when (professionId) {
        "minecraft:mason" -> "miner"
        "minecraft:fletcher" -> "lumberjack"
        else -> professionId.substringAfter(":").replace('_', ' ')
    }
}
