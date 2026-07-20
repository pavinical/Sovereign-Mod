package net.pavinical.sovereign.economy

import net.pavinical.sovereign.data.VillageData
import net.pavinical.sovereign.data.VillageTier

object VillageProgression {
    fun addTradeExperience(village: VillageData, requestedAmount: Int): Int {
        val amount = requestedAmount.coerceAtLeast(0)
        if (amount == 0 || village.tier == VillageTier.CITY) return 0
        val cap = tradeExperienceCapFor(village.tier)
        val granted = amount.coerceAtMost((cap - village.tradeExperience).coerceAtLeast(0))
        village.tradeExperience += granted
        village.experience += granted
        updateTier(village)
        return granted
    }

    fun addBuildingExperience(village: VillageData, builtType: String, level: Int): Int {
        val amount = buildingExperienceReward(builtType, level)
        if (amount == 0) return 0
        village.experience += amount
        updateTier(village)
        return amount
    }

    fun updateTier(village: VillageData) {
        village.tier = when {
            village.experience >= tierMinExperience(VillageTier.CITY) && hasBuiltCastle(village) -> VillageTier.CITY
            village.experience >= tierMinExperience(VillageTier.TOWN) -> VillageTier.TOWN
            village.experience >= tierMinExperience(VillageTier.VILLAGE) -> VillageTier.VILLAGE
            village.experience >= tierMinExperience(VillageTier.SETTLEMENT) -> VillageTier.SETTLEMENT
            else -> VillageTier.HAMLET
        }
    }

    fun migrateTradeExperience(village: VillageData) {
        if (village.tradeExperience > 0 || village.experience <= 0) return
        village.tradeExperience = village.experience.coerceAtMost(tradeExperienceCapFor(village.tier))
        updateTier(village)
    }

    fun tierMinExperience(tier: VillageTier): Int = when (tier) {
        VillageTier.HAMLET -> 0
        VillageTier.SETTLEMENT -> 300
        VillageTier.VILLAGE -> 1600
        VillageTier.TOWN -> 5000
        VillageTier.CITY -> 15000
    }

    fun nextTierExperience(tier: VillageTier): Int = when (tier) {
        VillageTier.HAMLET -> 300
        VillageTier.SETTLEMENT -> 1600
        VillageTier.VILLAGE -> 5000
        VillageTier.TOWN -> 15000
        VillageTier.CITY -> 15000
    }

    fun buildingExperienceReward(builtType: String, level: Int): Int {
        return when (builtType.lowercase()) {
            "house" -> when (level.coerceIn(1, 3)) {
                1 -> 425
                2 -> 850
                else -> 2500
            }
            "castle" -> 2500
            else -> 0
        }
    }

    fun hasBuiltCastle(village: VillageData): Boolean {
        return village.plots.any { it.builtType == "castle" && !it.isPending() }
    }

    fun tradeExperienceCapFor(tier: VillageTier): Int {
        return when (tier) {
            VillageTier.HAMLET,
            VillageTier.SETTLEMENT -> tierMinExperience(VillageTier.VILLAGE)
            VillageTier.VILLAGE -> tierMinExperience(VillageTier.VILLAGE) +
                (tierMinExperience(VillageTier.TOWN) - tierMinExperience(VillageTier.VILLAGE)) / 2
            VillageTier.TOWN -> tierMinExperience(VillageTier.TOWN) +
                (tierMinExperience(VillageTier.CITY) - tierMinExperience(VillageTier.TOWN)) / 2
            VillageTier.CITY -> tierMinExperience(VillageTier.CITY)
        }
    }
}
