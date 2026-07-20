package net.pavinical.sovereign.event

import net.pavinical.sovereign.economy.PlotPlacementTool

object SovereignEvents {
    fun register() {
        PlotPlacementTool.register()
        CivilianJobBlockService.register()
        ProfessionBlockProtection.register()
    }
}

