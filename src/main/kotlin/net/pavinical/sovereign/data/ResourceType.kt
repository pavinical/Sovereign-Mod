package net.pavinical.sovereign.data

/**
 * The five resource categories tracked by the village economy.
 * FOOD, LUMBER, MINERAL, and FUR are NEEDS — all villages consume them.
 * LUXURY is a WANT — only introduced at the TOWN tier via Artisan villagers.
 */
enum class ResourceType {
    FOOD,
    LUMBER,
    MINERAL,
    FUR,
    LUXURY
}
