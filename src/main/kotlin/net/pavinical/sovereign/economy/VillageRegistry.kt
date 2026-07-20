package net.pavinical.sovereign.economy

import net.minecraft.nbt.NbtCompound
import net.minecraft.nbt.NbtElement
import net.minecraft.nbt.NbtList
import net.minecraft.registry.RegistryWrapper
import net.minecraft.item.ItemStack
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Direction
import net.minecraft.world.PersistentState
import net.pavinical.sovereign.data.ResourceType
import net.pavinical.sovereign.data.VillageCommissionData
import net.pavinical.sovereign.data.VillageData
import net.pavinical.sovereign.data.VillagePlotData
import net.pavinical.sovereign.data.VillageTier
import java.util.UUID

/**
 * Stores all village data using Minecraft's PersistentState system.
 */
class VillageRegistry : PersistentState() {

    val villages: MutableMap<UUID, VillageData> = mutableMapOf()

    fun addVillage(village: VillageData) {
        villages[village.id] = village
        markDirty()
    }

    fun getVillageAtPos(pos: BlockPos): VillageData? =
        villages.values.firstOrNull { it.clerkPos == pos }

    fun getVillageById(id: UUID): VillageData? = villages[id]

    fun getVillageWithinRadius(pos: BlockPos, radius: Int): VillageData? {
        val radiusSquared = radius * radius
        return villages.values.firstOrNull { village ->
            val dx = village.centerPos.x - pos.x
            val dz = village.centerPos.z - pos.z
            dx * dx + dz * dz <= radiusSquared
        }
    }

    fun getVillageByName(name: String): VillageData? {
        val normalized = name.trim().lowercase()
        return villages.values.firstOrNull { it.name.lowercase() == normalized }
    }

    fun isVillageNameTaken(name: String): Boolean {
        return getVillageByName(name) != null
    }

    private fun writeVillageNbt(nbt: NbtCompound, registryLookup: RegistryWrapper.WrapperLookup) {
        val villageList = NbtList()

        for (village in villages.values) {
            val tag = NbtCompound()
            tag.putString("id", village.id.toString())
            tag.putString("name", village.name)
            tag.putString("biome", village.biome)
            village.founderId?.let { tag.putString("founderId", it.toString()) }
            tag.putString("specializationProfessionId", village.specializationProfessionId)
            tag.putString("tier", village.tier.name)
            tag.putInt("experience", village.experience)
            tag.putInt("tradeExperience", village.tradeExperience)
            tag.putInt("clerkX", village.clerkPos.x)
            tag.putInt("clerkY", village.clerkPos.y)
            tag.putInt("clerkZ", village.clerkPos.z)
            tag.putInt("centerX", village.centerPos.x)
            tag.putInt("centerY", village.centerPos.y)
            tag.putInt("centerZ", village.centerPos.z)

            val resourcesTag = NbtCompound()
            for ((type, amount) in village.resources) {
                resourcesTag.putInt(type.name, amount)
            }
            tag.put("resources", resourcesTag)

            tag.putLong("lastEconomyDay", village.lastEconomyDay)
            tag.putLong("lastEconomyTick", village.lastEconomyTick)
            tag.putLong("lastProductionTick", village.lastProductionTick)
            tag.putLong("lastNeedTick", village.lastNeedTick)
            tag.putLong("priceModifierDay", village.priceModifierDay)
            tag.put("dailyPriceModifiers", serializeProfessionMap(village.dailyPriceModifiers))

            tag.put("producedResources", serializeResourceMap(village.producedResources))
            tag.put("consumedResources", serializeResourceMap(village.consumedResources))
            tag.put("surplusResources", serializeResourceMap(village.surplusResources))
            tag.put("deficitResources", serializeResourceMap(village.deficitResources))
            tag.put("sellStockRemaining", serializeResourceMap(village.sellStockRemaining))
            tag.put("buyDemandTotal", serializeResourceMap(village.buyDemandTotal))
            tag.put("buyDemandFulfilled", serializeResourceMap(village.buyDemandFulfilled))
            tag.put("sellStockRemainingByProfession", serializeProfessionMap(village.sellStockRemainingByProfession))
            tag.put("artisanRefillTickByProfession", serializeLongMap(village.artisanRefillTickByProfession))
            tag.put("artisanRefillCountByProfession", serializeProfessionMap(village.artisanRefillCountByProfession))
            tag.put("buyDemandTotalByProfession", serializeProfessionMap(village.buyDemandTotalByProfession))
            tag.put("buyDemandFulfilledByProfession", serializeProfessionMap(village.buyDemandFulfilledByProfession))
            tag.put("wantDemandTotalByProfession", serializeProfessionMap(village.wantDemandTotalByProfession))
            tag.put("wantDemandFulfilledByProfession", serializeProfessionMap(village.wantDemandFulfilledByProfession))
            tag.put("wantTradeCountByProfession", serializeProfessionMap(village.wantTradeCountByProfession))
            tag.put("commissions", serializeCommissions(village.commissions, registryLookup))
            tag.put("plots", serializePlots(village.plots))
            tag.putInt("treasuryEmeralds", village.treasuryEmeralds)
            tag.putLong("lastTreasuryReplenishTick", village.lastTreasuryReplenishTick)
            tag.put("buyPrices", serializeResourceMap(village.buyPrices))
            tag.put("sellPrices", serializeResourceMap(village.sellPrices))

            villageList.add(tag)
        }

        nbt.put("villages", villageList)
    }

    override fun writeNbt(nbt: NbtCompound, registryLookup: RegistryWrapper.WrapperLookup): NbtCompound {
        writeVillageNbt(nbt, registryLookup)
        return nbt
    }

    companion object {
        private fun readTierFromTag(tag: NbtCompound): VillageTier {
            return runCatching { VillageTier.valueOf(tag.getString("tier")) }
                .getOrDefault(VillageTier.HAMLET)
        }

    private fun createFromNbt(nbt: NbtCompound, registryLookup: RegistryWrapper.WrapperLookup): VillageRegistry {
        val registry = VillageRegistry()
        val villageList = nbt.getList("villages", NbtElement.COMPOUND_TYPE.toInt())

        for (i in 0 until villageList.size) {
                val tag = villageList[i] as NbtCompound
                val id = runCatching { UUID.fromString(tag.getString("id")) }
                    .getOrNull() ?: continue
                val name = tag.getString("name")
                val biome = tag.getString("biome")
                val founderId = runCatching { UUID.fromString(tag.getString("founderId")) }.getOrNull()
                val clerkPos = BlockPos(
                    tag.getInt("clerkX"),
                    tag.getInt("clerkY"),
                    tag.getInt("clerkZ")
                )
                val centerPos = if (tag.contains("centerX")) {
                    BlockPos(
                        tag.getInt("centerX"),
                        tag.getInt("centerY"),
                        tag.getInt("centerZ")
                    )
                } else {
                    clerkPos
                }

                val resources = ResourceType.entries.associateWith { 0 }.toMutableMap()
                val resourcesTag = tag.getCompound("resources")
                for (type in ResourceType.entries) {
                    resources[type] = resourcesTag.getInt(type.name)
                }

                val producedResources = deserializeResourceMap(tag.getCompound("producedResources"))
                val consumedResources = deserializeResourceMap(tag.getCompound("consumedResources"))
                val surplusResources = deserializeResourceMap(tag.getCompound("surplusResources"))
                val deficitResources = deserializeResourceMap(tag.getCompound("deficitResources"))
                val sellStockRemaining = deserializeResourceMap(tag.getCompound("sellStockRemaining"))
                val buyDemandTotal = deserializeResourceMap(tag.getCompound("buyDemandTotal"))
                val buyDemandFulfilled = deserializeResourceMap(tag.getCompound("buyDemandFulfilled"))
                val sellStockRemainingByProfession = deserializeProfessionMap(tag.getCompound("sellStockRemainingByProfession"))
                val artisanRefillTickByProfession = deserializeLongMap(tag.getCompound("artisanRefillTickByProfession"))
                val artisanRefillCountByProfession = deserializeProfessionMap(tag.getCompound("artisanRefillCountByProfession"))
                val buyDemandTotalByProfession = deserializeProfessionMap(tag.getCompound("buyDemandTotalByProfession"))
                val buyDemandFulfilledByProfession = deserializeProfessionMap(tag.getCompound("buyDemandFulfilledByProfession"))
                val wantDemandTotalByProfession = deserializeProfessionMap(tag.getCompound("wantDemandTotalByProfession"))
                val wantDemandFulfilledByProfession = deserializeProfessionMap(tag.getCompound("wantDemandFulfilledByProfession"))
                val wantTradeCountByProfession = deserializeProfessionMap(tag.getCompound("wantTradeCountByProfession"))
                val commissions = deserializeCommissions(
                    tag.getList("commissions", NbtElement.COMPOUND_TYPE.toInt()),
                    registryLookup
                )
                val plots = deserializePlots(tag.getList("plots", NbtElement.COMPOUND_TYPE.toInt()))
                val treasuryEmeralds = if (tag.contains("treasuryEmeralds")) tag.getInt("treasuryEmeralds") else -1
                val lastTreasuryReplenishTick = if (tag.contains("lastTreasuryReplenishTick")) tag.getLong("lastTreasuryReplenishTick") else -1L
                val dailyPriceModifiers = deserializeProfessionMap(tag.getCompound("dailyPriceModifiers"))
                val buyPrices = deserializeResourceMap(tag.getCompound("buyPrices"))
                val sellPrices = deserializeResourceMap(tag.getCompound("sellPrices"))

                registry.villages[id] = VillageData(
                    id = id,
                    name = name,
                    biome = biome,
                    clerkPos = clerkPos,
                    centerPos = centerPos,
                    founderId = founderId,
                    specializationProfessionId = tag.getString("specializationProfessionId"),
                    tier = readTierFromTag(tag),
                    experience = tag.getInt("experience"),
                    tradeExperience = if (tag.contains("tradeExperience")) tag.getInt("tradeExperience") else 0,
                    resources = resources,
                    lastEconomyDay = tag.getLong("lastEconomyDay"),
                    lastEconomyTick = tag.getLong("lastEconomyTick"),
                    lastProductionTick = tag.getLong("lastProductionTick"),
                    lastNeedTick = tag.getLong("lastNeedTick"),
                    priceModifierDay = tag.getLong("priceModifierDay"),
                    dailyPriceModifiers = dailyPriceModifiers,
                    producedResources = producedResources,
                    consumedResources = consumedResources,
                    surplusResources = surplusResources,
                    deficitResources = deficitResources,
                    sellStockRemaining = sellStockRemaining,
                    buyDemandTotal = buyDemandTotal,
                    buyDemandFulfilled = buyDemandFulfilled,
                    sellStockRemainingByProfession = sellStockRemainingByProfession,
                    artisanRefillTickByProfession = artisanRefillTickByProfession,
                    artisanRefillCountByProfession = artisanRefillCountByProfession,
                    buyDemandTotalByProfession = buyDemandTotalByProfession,
                    buyDemandFulfilledByProfession = buyDemandFulfilledByProfession,
                    wantDemandTotalByProfession = wantDemandTotalByProfession,
                    wantDemandFulfilledByProfession = wantDemandFulfilledByProfession,
                    wantTradeCountByProfession = wantTradeCountByProfession,
                    commissions = commissions,
                    plots = plots,
                    treasuryEmeralds = treasuryEmeralds,
                    lastTreasuryReplenishTick = lastTreasuryReplenishTick,
                    buyPrices = buyPrices,
                    sellPrices = sellPrices,
                ).also(VillageProgression::migrateTradeExperience)
            }

            return registry
        }

        private fun serializeResourceMap(map: Map<ResourceType, Int>): NbtCompound {
            val tag = NbtCompound()
            for (type in ResourceType.entries) {
                tag.putInt(type.name, map[type] ?: 0)
            }
            return tag
        }

        private fun deserializeResourceMap(tag: NbtCompound): MutableMap<ResourceType, Int> {
            val values = ResourceType.entries.associateWith { 0 }.toMutableMap()
            for (type in ResourceType.entries) {
                values[type] = tag.getInt(type.name)
            }
            return values
        }

        private fun serializeProfessionMap(map: Map<String, Int>): NbtCompound {
            val tag = NbtCompound()
            for ((profession, amount) in map) {
                tag.putInt(profession, amount)
            }
            return tag
        }

        private fun serializeLongMap(map: Map<String, Long>): NbtCompound {
            val tag = NbtCompound()
            for ((profession, tick) in map) {
                tag.putLong(profession, tick)
            }
            return tag
        }

        private fun serializeCommissions(
            commissions: List<VillageCommissionData>,
            registryLookup: RegistryWrapper.WrapperLookup
        ): NbtList {
            val list = NbtList()
            for (commission in commissions) {
                val tag = NbtCompound()
                tag.putString("id", commission.id)
                tag.putString("playerId", commission.playerId.toString())
                tag.putString("offerId", commission.offerId)
                tag.putString("itemId", commission.itemId)
                tag.putString("displayName", commission.displayName)
                tag.putString("professionId", commission.professionId)
                tag.putString("professionName", commission.professionName)
                tag.putInt("emeraldCost", commission.emeraldCost)
                tag.putLong("orderedTick", commission.orderedTick)
                tag.putLong("readyTick", commission.readyTick)
                tag.putInt("quantity", commission.quantity)
                if (!commission.inputStack.isEmpty) {
                    tag.put("inputStack", commission.inputStack.encodeAllowEmpty(registryLookup))
                }
                tag.putBoolean("claimed", commission.claimed)
                list.add(tag)
            }
            return list
        }

        private fun deserializeCommissions(
            list: NbtList,
            registryLookup: RegistryWrapper.WrapperLookup
        ): MutableList<VillageCommissionData> {
            val commissions = mutableListOf<VillageCommissionData>()
            for (i in 0 until list.size) {
                val tag = list[i] as? NbtCompound ?: continue
                val playerId = runCatching { UUID.fromString(tag.getString("playerId")) }.getOrNull() ?: continue
                val id = tag.getString("id").takeIf { it.isNotBlank() } ?: continue
                val inputStack = if (tag.contains("inputStack", NbtElement.COMPOUND_TYPE.toInt())) {
                    ItemStack.fromNbtOrEmpty(registryLookup, tag.getCompound("inputStack"))
                } else {
                    ItemStack.EMPTY
                }
                commissions.add(
                    VillageCommissionData(
                        id = id,
                        playerId = playerId,
                        offerId = tag.getString("offerId"),
                        itemId = tag.getString("itemId"),
                        displayName = tag.getString("displayName"),
                        professionId = tag.getString("professionId"),
                        professionName = tag.getString("professionName"),
                        emeraldCost = tag.getInt("emeraldCost"),
                        orderedTick = tag.getLong("orderedTick"),
                        readyTick = tag.getLong("readyTick"),
                        quantity = tag.getInt("quantity").coerceAtLeast(1),
                        inputStack = inputStack,
                        claimed = tag.getBoolean("claimed")
                    )
                )
            }
            return commissions
        }

        private fun serializePlots(plots: List<VillagePlotData>): NbtList {
            val list = NbtList()
            for (plot in plots) {
                val tag = NbtCompound()
                tag.putString("id", plot.id)
                tag.putInt("originX", plot.origin.x)
                tag.putInt("originY", plot.origin.y)
                tag.putInt("originZ", plot.origin.z)
                tag.putInt("sizeX", plot.sizeX)
                tag.putInt("sizeZ", plot.sizeZ)
                tag.putString("allowedType", plot.allowedType)
                tag.putString("facing", plot.facing.asString())
                tag.putInt("buildingLevel", plot.buildingLevel)
                tag.putString("builtType", plot.builtType)
                tag.putString("structureId", plot.structureId)
                tag.putString("pendingBuiltType", plot.pendingBuiltType)
                tag.putInt("pendingLevel", plot.pendingLevel)
                tag.putLong("buildReadyTick", plot.buildReadyTick)
                tag.putBoolean("manualPlacement", plot.manualPlacement)
                plot.reservedBy?.let { tag.putString("reservedBy", it.toString()) }
                list.add(tag)
            }
            return list
        }

        private fun deserializePlots(list: NbtList): MutableList<VillagePlotData> {
            val plots = mutableListOf<VillagePlotData>()
            for (i in 0 until list.size) {
                val tag = list[i] as? NbtCompound ?: continue
                val sizeX = tag.getInt("sizeX").coerceAtLeast(5)
                val sizeZ = tag.getInt("sizeZ").coerceAtLeast(5)
                val origin = if (tag.contains("originX")) {
                    BlockPos(tag.getInt("originX"), tag.getInt("originY"), tag.getInt("originZ"))
                } else {
                    BlockPos(tag.getInt("centerX") - sizeX / 2, tag.getInt("centerY"), tag.getInt("centerZ") - sizeZ / 2)
                }
                plots += VillagePlotData(
                    id = tag.getString("id").takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString(),
                    origin = origin,
                    sizeX = sizeX,
                    sizeZ = sizeZ,
                    allowedType = tag.getString("allowedType").ifBlank { "any" },
                    facing = Direction.byName(tag.getString("facing")) ?: Direction.NORTH,
                    buildingLevel = tag.getInt("buildingLevel"),
                    builtType = tag.getString("builtType"),
                    structureId = tag.getString("structureId"),
                    pendingBuiltType = tag.getString("pendingBuiltType"),
                    pendingLevel = tag.getInt("pendingLevel"),
                    buildReadyTick = tag.getLong("buildReadyTick"),
                    reservedBy = runCatching { UUID.fromString(tag.getString("reservedBy")) }.getOrNull(),
                    manualPlacement = tag.getBoolean("manualPlacement")
                )
            }
            return plots
        }

        private fun deserializeProfessionMap(tag: NbtCompound): MutableMap<String, Int> {
            val values = mutableMapOf<String, Int>()
            for (entry in tag.keys) {
                values[entry] = tag.getInt(entry)
            }
            return values
        }

        private fun deserializeLongMap(tag: NbtCompound): MutableMap<String, Long> {
            val values = mutableMapOf<String, Long>()
            for (entry in tag.keys) {
                values[entry] = tag.getLong(entry)
            }
            return values
        }

        val TYPE = PersistentState.Type(
            ::VillageRegistry,
            ::createFromNbt,
            null
        )

        const val KEY = "sovereign_villages"
    }
}


