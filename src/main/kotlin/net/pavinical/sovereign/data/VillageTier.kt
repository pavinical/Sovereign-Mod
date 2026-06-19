package net.pavinical.sovereign.data

/**
 * The five progression stages a village can reach.
 * A new village always starts as a HAMLET and upgrades
 * by accumulating surplus resources past each threshold.
 */
enum class VillageTier {
    HAMLET,
    SETTLEMENT,
    VILLAGE,
    TOWN,
    CITY
}
