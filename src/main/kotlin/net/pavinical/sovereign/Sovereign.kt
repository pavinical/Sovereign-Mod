package net.pavinical.sovereign

import net.fabricmc.api.ModInitializer
import net.pavinical.sovereign.event.SovereignEvents
import net.pavinical.sovereign.registry.ModBlocks
import org.slf4j.LoggerFactory

object Sovereign : ModInitializer {
    const val MOD_ID = "sovereign"
    private val logger = LoggerFactory.getLogger(MOD_ID)

    override fun onInitialize() {
        logger.info("Sovereign initializing...")
        ModBlocks.register()
        SovereignEvents.register()
        logger.info("Sovereign initialized.")
    }
}
