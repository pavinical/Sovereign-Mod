package net.pavinical.sovereign

import net.fabricmc.api.ModInitializer
import net.pavinical.sovereign.command.VillageInfoCommand
import net.pavinical.sovereign.event.SovereignEvents
import net.pavinical.sovereign.economy.ClerkCensusService
import net.pavinical.sovereign.economy.ClerkEconomyService
import net.pavinical.sovereign.economy.VillageBorderTracker
import net.pavinical.sovereign.economy.VillageTradeHandler
import net.pavinical.sovereign.registry.ModBlocks
import net.pavinical.sovereign.registry.ModBlockEntities
import net.pavinical.sovereign.registry.ModItems
import net.pavinical.sovereign.world.VillageClerkSpawner
import org.slf4j.LoggerFactory

object Sovereign : ModInitializer {
    const val MOD_ID = "sovereign"
    private val logger = LoggerFactory.getLogger(MOD_ID)

    override fun onInitialize() {
        logger.info("Sovereign initializing...")
        ModBlocks.register()
        ModItems.register()
        ModBlockEntities.register()
        VillageClerkSpawner.register()
        SovereignEvents.register()
        ClerkCensusService.register()
        ClerkEconomyService.register()
        VillageBorderTracker.register()
        VillageInfoCommand.register()
        VillageTradeHandler.registerServerNetworking()
        logger.info("Sovereign initialized.")
    }
}

