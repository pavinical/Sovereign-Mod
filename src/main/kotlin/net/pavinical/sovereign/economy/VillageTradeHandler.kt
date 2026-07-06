package net.pavinical.sovereign.economy

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerFactory
import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerType
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.entity.player.PlayerInventory
import net.minecraft.registry.Registries
import net.minecraft.registry.Registry
import net.minecraft.screen.NamedScreenHandlerFactory
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.text.Text
import net.minecraft.util.Identifier
import net.minecraft.util.math.BlockPos
import net.minecraft.sound.SoundCategory
import net.minecraft.sound.SoundEvents
import net.pavinical.sovereign.Sovereign
import net.pavinical.sovereign.block.ClerkTableBlock
import net.pavinical.sovereign.block.ClerkTableBlockEntity
import net.pavinical.sovereign.data.VillageData
import net.pavinical.sovereign.economy.VillageTradePacket.VILLAGE_FOUNDED_ID
import net.pavinical.sovereign.economy.VillageTradePacket.VILLAGE_NAME_OPEN_ID
import net.pavinical.sovereign.economy.VillageTradePacket.VILLAGE_NAME_SUBMIT_ID
import net.pavinical.sovereign.economy.VillageTradePacket.TRADE_REFRESH_ID
import net.pavinical.sovereign.economy.VillageTradePacket.TRADE_REQUEST_ID
import net.pavinical.sovereign.economy.VillageTradePacket.TRADE_SYNC_ID
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.Formatting
import java.util.UUID

object VillageTradeHandler {
    val TYPE_ID: Identifier = Identifier.of(Sovereign.MOD_ID, "village_trade")
    private val activeTables: MutableMap<BlockPos, UUID> = mutableMapOf()

    val TYPE: ExtendedScreenHandlerType<VillageTradeMenu, VillageTradeOpenData> = Registry.register(
        Registries.SCREEN_HANDLER,
        TYPE_ID,
        ExtendedScreenHandlerType(
            { syncId, inventory, data ->
                VillageTradeMenu(syncId, inventory, data)
            },
            VillageTradeOpenData.PACKET_CODEC
        )
    )

    fun registerServerNetworking() {
        PayloadTypeRegistry.playC2S().register(TRADE_REQUEST_ID, VillageTradeRequestPayload.PACKET_CODEC)
        PayloadTypeRegistry.playS2C().register(TRADE_SYNC_ID, VillageTradeSyncPayload.PACKET_CODEC)
        PayloadTypeRegistry.playC2S().register(TRADE_REFRESH_ID, VillageTradeRefreshPayload.PACKET_CODEC)
        PayloadTypeRegistry.playS2C().register(VILLAGE_NAME_OPEN_ID, VillageNameOpenPayload.PACKET_CODEC)
        PayloadTypeRegistry.playC2S().register(VILLAGE_NAME_SUBMIT_ID, VillageNameSubmitPayload.PACKET_CODEC)
        PayloadTypeRegistry.playS2C().register(VILLAGE_FOUNDED_ID, VillageFoundedPayload.PACKET_CODEC)

        ServerPlayNetworking.registerGlobalReceiver(TRADE_REQUEST_ID) { payload, context ->
            val player = context.player()
            val world = context.server().overworld

            val result = VillageEconomyService.executeTrade(
                world = world,
                player = player,
                clerkPos = payload.position(),
                direction = payload.direction,
                professionId = payload.professionId,
                requestedQuantity = payload.quantity
            )

            if (!result.accepted && !result.message.string.isBlank()) {
                player.sendMessage(result.message, false)
            }

            val snapshot = result.snapshot ?: VillageEconomyService.openSnapshot(world, payload.position())
            if (snapshot != null) {
                val syncPayload = VillageTradeSyncPayload(
                    clerkX = payload.clerkX,
                    clerkY = payload.clerkY,
                    clerkZ = payload.clerkZ,
                    message = result.message.string,
                    snapshot = snapshot.toNetworkData()
                )
                ServerPlayNetworking.send(player, syncPayload)
            }
        }

        ServerPlayNetworking.registerGlobalReceiver(TRADE_REFRESH_ID) { payload, context ->
            val world = context.server().overworld
            val snapshot = VillageEconomyService.openSnapshot(world, payload.position())
                ?: VillageEconomyService.emptySnapshotForRegisteredVillage(world, payload.position())
                ?: return@registerGlobalReceiver

            ServerPlayNetworking.send(
                context.player(),
                VillageTradeSyncPayload(
                    clerkX = payload.clerkX,
                    clerkY = payload.clerkY,
                    clerkZ = payload.clerkZ,
                    message = "",
                    snapshot = snapshot.toNetworkData()
                )
            )
        }

        ServerPlayNetworking.registerGlobalReceiver(VILLAGE_NAME_SUBMIT_ID) { payload, context ->
            handleVillageNameSubmit(payload, context.player(), context.server().overworld)
        }
    }

    fun openNamingScreen(player: ServerPlayerEntity, clerkPos: BlockPos) {
        ServerPlayNetworking.send(
            player,
            VillageNameOpenPayload(
                clerkX = clerkPos.x,
                clerkY = clerkPos.y,
                clerkZ = clerkPos.z
            )
        )
    }

    private fun handleVillageNameSubmit(
        payload: VillageNameSubmitPayload,
        player: ServerPlayerEntity,
        world: ServerWorld
    ) {
        val session = NamingSession.getPending(player.uuid)
            ?: return player.sendMessage(Text.literal("No pending village registration found.").formatted(Formatting.RED), false)
        val clerkPos = BlockPos(payload.clerkX, payload.clerkY, payload.clerkZ)
        if (session.clerkPos != clerkPos) {
            NamingSession.clear(player.uuid)
            return player.sendMessage(Text.literal("Village registration cancelled: clerk table changed.").formatted(Formatting.RED), false)
        }

        val villageName = payload.name.trim().take(16)
        val registry = world.persistentStateManager.getOrCreate(VillageRegistry.TYPE, VillageRegistry.KEY)

        if (villageName.isBlank()) {
            player.sendMessage(Text.literal("Village name cannot be blank.").formatted(Formatting.RED), false)
            return openNamingScreen(player, clerkPos)
        }

        if (registry.isVillageNameTaken(villageName)) {
            player.sendMessage(Text.literal("A village with that name already exists.").formatted(Formatting.RED), false)
            return openNamingScreen(player, clerkPos)
        }

        val sharedVillage = registry.getVillageWithinRadius(clerkPos, ClerkTableBlock.VILLAGE_SCAN_RADIUS_BLOCKS)
        if (sharedVillage != null) {
            NamingSession.clear(player.uuid)
            (world.getBlockEntity(clerkPos) as? ClerkTableBlockEntity)?.markVillage(sharedVillage.id)
            player.sendMessage(Text.literal("${sharedVillage.name} already claims this clerk table.").formatted(Formatting.RED), false)
            return
        }

        val claimedVillage = registry.getVillageWithinRadius(clerkPos, ClerkTableBlock.MIN_VILLAGE_DISTANCE_BLOCKS)
        if (claimedVillage != null) {
            NamingSession.clear(player.uuid)
            player.sendMessage(Text.literal("Land is claimed by ${claimedVillage.name}.").formatted(Formatting.RED), false)
            return
        }

        val village = VillageData(
            id = UUID.randomUUID(),
            name = villageName,
            biome = session.biome,
            clerkPos = clerkPos,
            founderId = player.uuid
        )
        registry.addVillage(village)
        (world.getBlockEntity(clerkPos) as? ClerkTableBlockEntity)?.markVillage(village.id)
        ClerkCensusService.forceSyncVillage(world, village)?.let { snapshot ->
            ClerkEconomyService.refreshVillageFromSnapshot(world, village, snapshot)
            registry.markDirty()
        }
        NamingSession.clear(player.uuid)

        VillageBorderTracker.suppressEnteringHeader(player.uuid)
        announceVillageFounded(world, villageName, player)
    }

    private fun announceVillageFounded(world: ServerWorld, villageName: String, founder: ServerPlayerEntity) {
        val title = "$villageName Founded by ${founder.name.string}"
        for (recipient in world.players) {
            ServerPlayNetworking.send(recipient, VillageFoundedPayload(title))
            world.playSound(
                null,
                recipient.blockPos,
                SoundEvents.UI_TOAST_CHALLENGE_COMPLETE,
                SoundCategory.MASTER,
                1.0f,
                1.0f
            )
        }
    }

    fun openFor(player: ServerPlayerEntity, world: ServerWorld, clerkPos: BlockPos): Boolean {
        val registry = world.persistentStateManager.getOrCreate(VillageRegistry.TYPE, VillageRegistry.KEY)
        val village = (world.getBlockEntity(clerkPos) as? ClerkTableBlockEntity)?.villageId?.let { registry.getVillageById(it) }
            ?: registry.getVillageAtPos(clerkPos)
            ?: return false
        val primaryClerkPos = village.clerkPos
        val activePlayerId = activeTables[primaryClerkPos]
        if (activePlayerId != null && activePlayerId != player.uuid) {
            player.sendMessage(Text.literal("Another player is already using this trading post.").formatted(Formatting.RED), false)
            return true
        }
        val snapshot = VillageEconomyService.openSnapshot(world, clerkPos)
            ?: VillageEconomyService.emptySnapshotForRegisteredVillage(world, clerkPos)
            ?: return false
        val openData = VillageTradeOpenData(
            clerkX = primaryClerkPos.x,
            clerkY = primaryClerkPos.y,
            clerkZ = primaryClerkPos.z,
            villageName = village.name,
            snapshot = snapshot.toNetworkData()
        )

        activeTables[primaryClerkPos] = player.uuid
        player.openHandledScreen(
            createFactoryFor(openData)
        )

        return true
    }

    fun closeFor(player: PlayerEntity, clerkPos: BlockPos) {
        if (activeTables[clerkPos] == player.uuid) {
            activeTables.remove(clerkPos)
        }
    }

    fun createFactoryFor(payload: VillageTradeOpenData): NamedScreenHandlerFactory {
        return object : ExtendedScreenHandlerFactory<VillageTradeOpenData> {
            override fun getDisplayName(): Text = Text.literal("${payload.villageName} Trading Post")
            override fun createMenu(syncId: Int, playerInventory: PlayerInventory, player: PlayerEntity): VillageTradeMenu {
                return VillageTradeMenu(syncId, playerInventory, payload)
            }

            override fun getScreenOpeningData(player: ServerPlayerEntity): VillageTradeOpenData = payload
        }
    }
}

private fun VillageTradeRequestPayload.position(): BlockPos =
    BlockPos(clerkX, clerkY, clerkZ)

private fun VillageTradeRefreshPayload.position(): BlockPos =
    BlockPos(clerkX, clerkY, clerkZ)

