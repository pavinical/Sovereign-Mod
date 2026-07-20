package net.pavinical.sovereign.economy

import net.minecraft.item.ItemStack
import net.minecraft.item.Items
import net.minecraft.network.RegistryByteBuf
import net.minecraft.network.codec.PacketCodec
import net.minecraft.registry.Registries
import net.minecraft.util.Identifier

data class VillageMarketSnapshot(
    val sellOffers: List<SellOffer>,
    val buyOffers: List<BuyOffer>,
    val jobBlockOffers: List<JobBlockOffer>,
    val commissionOffers: List<CommissionOffer>,
    val commissionOrders: List<CommissionOrder>,
    val buildingPlots: List<BuildingPlot>,
    val playerEmeralds: Int,
    val villageWealth: Int,
    val refreshTicks: Long,
    val produceRefreshTicks: Long,
    val needRefreshTicks: Long,
    val tierName: String,
    val villageExperience: Int,
    val currentTierMinExperience: Int,
    val nextTierExperience: Int,
    val info: VillageInfoData
) {
    fun toNetworkData(): VillageMarketSnapshotData = VillageMarketSnapshotData(
        refreshTicks = refreshTicks,
        produceRefreshTicks = produceRefreshTicks,
        needRefreshTicks = needRefreshTicks,
        playerEmeralds = playerEmeralds,
        villageWealth = villageWealth,
        tierName = tierName,
        villageExperience = villageExperience,
        currentTierMinExperience = currentTierMinExperience,
        nextTierExperience = nextTierExperience,
        info = info,
        sellOffers = sellOffers.map { it.toNetworkOffer() },
        buyOffers = buyOffers.map { it.toNetworkOffer() },
        jobBlockOffers = jobBlockOffers.map { it.toNetworkOffer() },
        commissionOffers = commissionOffers.map { it.toNetworkOffer() },
        commissionOrders = commissionOrders.map { it.toNetworkOrder() },
        buildingPlots = buildingPlots.map { it.toNetworkPlot() }
    )
}

data class VillageInfoData(
    val tierName: String,
    val population: Int,
    val activeVillagers: Int,
    val missingVillagers: Int,
    val deceasedVillagers: Int,
    val occupiedSlots: Int,
    val totalSlots: Int,
    val storageUsed: Int,
    val storageCapacity: Int,
    val professionLines: List<String>,
    val slotLines: List<String>
) {
    companion object {
        val EMPTY = VillageInfoData(
            tierName = "Hamlet",
            population = 0,
            activeVillagers = 0,
            missingVillagers = 0,
            deceasedVillagers = 0,
            occupiedSlots = 0,
            totalSlots = 0,
            storageUsed = 0,
            storageCapacity = 0,
            professionLines = emptyList(),
            slotLines = emptyList()
        )

        fun write(buf: RegistryByteBuf, info: VillageInfoData) {
            buf.writeString(info.tierName)
            buf.writeInt(info.population)
            buf.writeInt(info.activeVillagers)
            buf.writeInt(info.missingVillagers)
            buf.writeInt(info.deceasedVillagers)
            buf.writeInt(info.occupiedSlots)
            buf.writeInt(info.totalSlots)
            buf.writeInt(info.storageUsed)
            buf.writeInt(info.storageCapacity)
            writeStringList(buf, info.professionLines)
            writeStringList(buf, info.slotLines)
        }

        fun read(buf: RegistryByteBuf): VillageInfoData = VillageInfoData(
            tierName = buf.readString(),
            population = buf.readInt(),
            activeVillagers = buf.readInt(),
            missingVillagers = buf.readInt(),
            deceasedVillagers = buf.readInt(),
            occupiedSlots = buf.readInt(),
            totalSlots = buf.readInt(),
            storageUsed = buf.readInt(),
            storageCapacity = buf.readInt(),
            professionLines = readStringList(buf),
            slotLines = readStringList(buf)
        )

        private fun writeStringList(buf: RegistryByteBuf, values: List<String>) {
            buf.writeInt(values.size)
            values.forEach(buf::writeString)
        }

        private fun readStringList(buf: RegistryByteBuf): List<String> {
            val size = buf.readInt()
            return List(size) { buf.readString() }
        }
    }
}

data class VillageTradeOfferData(
    val itemId: String,
    val displayName: String,
    val emeraldValue: Int,
    val tradeItemCount: Int,
    val totalDaily: Int,
    val remaining: Int,
    val maxDaily: Int,
    val baseValue: Int,
    val minPrice: Int,
    val maxPrice: Int,
    val availabilityPercent: Int,
    val availabilityModifier: Int,
    val availabilityLabel: String,
    val dailyModifier: Int,
    val grantsExperience: Boolean,
    val experienceReward: Int,
    val professionCount: Int,
    val professionId: String,
    val professionName: String
) {
    fun toSellOffer(): SellOffer = SellOffer(
        item = itemFromId(itemId),
        displayName = displayName,
        emeraldCost = emeraldValue,
        tradeItemCount = tradeItemCount,
        producedToday = totalDaily,
        remainingToday = remaining,
        maxDailyProduction = maxDaily,
        baseValue = baseValue,
        minPrice = minPrice,
        maxPrice = maxPrice,
        stockPercent = availabilityPercent,
        stockModifier = availabilityModifier,
        stockLabel = availabilityLabel,
        dailyModifier = dailyModifier,
        producerCount = professionCount,
        producerProfessionId = professionId,
        producerProfession = professionName
    )

    fun toBuyOffer(): BuyOffer = BuyOffer(
        item = itemFromId(itemId),
        displayName = displayName,
        emeraldReward = emeraldValue,
        tradeItemCount = tradeItemCount,
        neededToday = totalDaily,
        remainingNeed = remaining,
        maxDailyNeed = maxDaily,
        baseValue = baseValue,
        minPrice = minPrice,
        maxPrice = maxPrice,
        demandPercent = availabilityPercent,
        demandModifier = availabilityModifier,
        demandLabel = availabilityLabel,
        dailyModifier = dailyModifier,
        grantsExperience = grantsExperience,
        experienceReward = experienceReward,
        requesterCount = professionCount,
        requesterProfessionId = professionId,
        requesterProfession = professionName
    )

    companion object {
        fun fromSellOffer(offer: SellOffer): VillageTradeOfferData = VillageTradeOfferData(
            itemId = itemIdOf(offer.item),
            displayName = offer.displayName,
            emeraldValue = offer.emeraldCost,
            tradeItemCount = offer.tradeItemCount,
            totalDaily = offer.producedToday,
            remaining = offer.remainingToday,
            maxDaily = offer.maxDailyProduction,
            baseValue = offer.baseValue,
            minPrice = offer.minPrice,
            maxPrice = offer.maxPrice,
            availabilityPercent = offer.stockPercent,
            availabilityModifier = offer.stockModifier,
            availabilityLabel = offer.stockLabel,
            dailyModifier = offer.dailyModifier,
            grantsExperience = false,
            experienceReward = 0,
            professionCount = offer.producerCount,
            professionId = offer.producerProfessionId,
            professionName = offer.producerProfession
        )

        fun fromBuyOffer(offer: BuyOffer): VillageTradeOfferData = VillageTradeOfferData(
            itemId = itemIdOf(offer.item),
            displayName = offer.displayName,
            emeraldValue = offer.emeraldReward,
            tradeItemCount = offer.tradeItemCount,
            totalDaily = offer.neededToday,
            remaining = offer.remainingNeed,
            maxDaily = offer.maxDailyNeed,
            baseValue = offer.baseValue,
            minPrice = offer.minPrice,
            maxPrice = offer.maxPrice,
            availabilityPercent = offer.demandPercent,
            availabilityModifier = offer.demandModifier,
            availabilityLabel = offer.demandLabel,
            dailyModifier = offer.dailyModifier,
            grantsExperience = offer.grantsExperience,
            experienceReward = offer.experienceReward,
            professionCount = offer.requesterCount,
            professionId = offer.requesterProfessionId,
            professionName = offer.requesterProfession
        )
    }
}

data class VillageMarketSnapshotData(
    val refreshTicks: Long,
    val produceRefreshTicks: Long,
    val needRefreshTicks: Long,
    val playerEmeralds: Int,
    val villageWealth: Int,
    val tierName: String,
    val villageExperience: Int,
    val currentTierMinExperience: Int,
    val nextTierExperience: Int,
    val info: VillageInfoData,
    val sellOffers: List<VillageTradeOfferData>,
    val buyOffers: List<VillageTradeOfferData>,
    val jobBlockOffers: List<VillageJobBlockOfferData>,
    val commissionOffers: List<VillageCommissionOfferData>,
    val commissionOrders: List<VillageCommissionOrderData>,
    val buildingPlots: List<VillageBuildingPlotData>
) {
    fun toRuntime(): VillageMarketSnapshot = VillageMarketSnapshot(
        refreshTicks = refreshTicks,
        produceRefreshTicks = produceRefreshTicks,
        needRefreshTicks = needRefreshTicks,
        playerEmeralds = playerEmeralds,
        villageWealth = villageWealth,
        tierName = tierName,
        villageExperience = villageExperience,
        currentTierMinExperience = currentTierMinExperience,
        nextTierExperience = nextTierExperience,
        info = info,
        sellOffers = sellOffers.map { it.toSellOffer() },
        buyOffers = buyOffers.map { it.toBuyOffer() },
        jobBlockOffers = jobBlockOffers.map { it.toJobBlockOffer() },
        commissionOffers = commissionOffers.map { it.toCommissionOffer() },
        commissionOrders = commissionOrders.map { it.toCommissionOrder() },
        buildingPlots = buildingPlots.map { it.toBuildingPlot() }
    )

    companion object {
        val PACKET_CODEC: PacketCodec<RegistryByteBuf, VillageMarketSnapshotData> = PacketCodec.of(
            { value, buf ->
                buf.writeLong(value.refreshTicks)
                buf.writeLong(value.produceRefreshTicks)
                buf.writeLong(value.needRefreshTicks)
                buf.writeInt(value.playerEmeralds)
                buf.writeInt(value.villageWealth)
                buf.writeString(value.tierName)
                buf.writeInt(value.villageExperience)
                buf.writeInt(value.currentTierMinExperience)
                buf.writeInt(value.nextTierExperience)
                VillageInfoData.write(buf, value.info)
                writeOfferList(buf, value.sellOffers)
                writeOfferList(buf, value.buyOffers)
                writeJobBlockOfferList(buf, value.jobBlockOffers)
                writeCommissionOfferList(buf, value.commissionOffers)
                writeCommissionOrderList(buf, value.commissionOrders)
                writeBuildingPlotList(buf, value.buildingPlots)
            },
            { buf ->
                VillageMarketSnapshotData(
                    refreshTicks = buf.readLong(),
                    produceRefreshTicks = buf.readLong(),
                    needRefreshTicks = buf.readLong(),
                    playerEmeralds = buf.readInt(),
                    villageWealth = buf.readInt(),
                    tierName = buf.readString(),
                    villageExperience = buf.readInt(),
                    currentTierMinExperience = buf.readInt(),
                    nextTierExperience = buf.readInt(),
                    info = VillageInfoData.read(buf),
                    sellOffers = readOfferList(buf),
                    buyOffers = readOfferList(buf),
                    jobBlockOffers = readJobBlockOfferList(buf),
                    commissionOffers = readCommissionOfferList(buf),
                    commissionOrders = readCommissionOrderList(buf),
                    buildingPlots = readBuildingPlotList(buf)
                )
            }
        )

        private fun writeOfferList(buf: RegistryByteBuf, offers: List<VillageTradeOfferData>) {
            buf.writeInt(offers.size)
            for (offer in offers) {
                buf.writeString(offer.itemId)
                buf.writeString(offer.displayName)
                buf.writeInt(offer.emeraldValue)
                buf.writeInt(offer.tradeItemCount)
                buf.writeInt(offer.totalDaily)
                buf.writeInt(offer.remaining)
                buf.writeInt(offer.maxDaily)
                buf.writeInt(offer.baseValue)
                buf.writeInt(offer.minPrice)
                buf.writeInt(offer.maxPrice)
                buf.writeInt(offer.availabilityPercent)
                buf.writeInt(offer.availabilityModifier)
                buf.writeString(offer.availabilityLabel)
                buf.writeInt(offer.dailyModifier)
                buf.writeBoolean(offer.grantsExperience)
                buf.writeInt(offer.experienceReward)
                buf.writeInt(offer.professionCount)
                buf.writeString(offer.professionId)
                buf.writeString(offer.professionName)
            }
        }

        private fun readOfferList(buf: RegistryByteBuf): List<VillageTradeOfferData> {
            val size = buf.readInt()
            val offers = ArrayList<VillageTradeOfferData>(size)
            repeat(size) {
                offers.add(
                    VillageTradeOfferData(
                        itemId = buf.readString(),
                        displayName = buf.readString(),
                        emeraldValue = buf.readInt(),
                        tradeItemCount = buf.readInt(),
                        totalDaily = buf.readInt(),
                        remaining = buf.readInt(),
                        maxDaily = buf.readInt(),
                        baseValue = buf.readInt(),
                        minPrice = buf.readInt(),
                        maxPrice = buf.readInt(),
                        availabilityPercent = buf.readInt(),
                        availabilityModifier = buf.readInt(),
                        availabilityLabel = buf.readString(),
                        dailyModifier = buf.readInt(),
                        grantsExperience = buf.readBoolean(),
                        experienceReward = buf.readInt(),
                        professionCount = buf.readInt(),
                        professionId = buf.readString(),
                        professionName = buf.readString()
                    )
                )
            }
            return offers
        }

        private fun writeJobBlockOfferList(buf: RegistryByteBuf, offers: List<VillageJobBlockOfferData>) {
            buf.writeInt(offers.size)
            for (offer in offers) {
                buf.writeString(offer.itemId)
                buf.writeString(offer.displayName)
                buf.writeString(offer.professionId)
                buf.writeString(offer.professionName)
                buf.writeInt(offer.emeraldCost)
            }
        }

        private fun readJobBlockOfferList(buf: RegistryByteBuf): List<VillageJobBlockOfferData> {
            val size = buf.readInt()
            return List(size) {
                VillageJobBlockOfferData(
                    itemId = buf.readString(),
                    displayName = buf.readString(),
                    professionId = buf.readString(),
                    professionName = buf.readString(),
                    emeraldCost = buf.readInt()
                )
            }
        }

        private fun writeCommissionOfferList(buf: RegistryByteBuf, offers: List<VillageCommissionOfferData>) {
            buf.writeInt(offers.size)
            for (offer in offers) {
                buf.writeString(offer.id)
                buf.writeString(offer.itemId)
                buf.writeString(offer.displayName)
                buf.writeString(offer.professionId)
                buf.writeString(offer.professionName)
                buf.writeString(offer.quality)
                buf.writeString(offer.description)
                buf.writeInt(offer.emeraldCost)
                buf.writeLong(offer.durationTicks)
                buf.writeBoolean(offer.requiresInput)
            }
        }

        private fun readCommissionOfferList(buf: RegistryByteBuf): List<VillageCommissionOfferData> {
            val size = buf.readInt()
            return List(size) {
                VillageCommissionOfferData(
                    id = buf.readString(),
                    itemId = buf.readString(),
                    displayName = buf.readString(),
                    professionId = buf.readString(),
                    professionName = buf.readString(),
                    quality = buf.readString(),
                    description = buf.readString(),
                    emeraldCost = buf.readInt(),
                    durationTicks = buf.readLong(),
                    requiresInput = buf.readBoolean()
                )
            }
        }

        private fun writeCommissionOrderList(buf: RegistryByteBuf, orders: List<VillageCommissionOrderData>) {
            buf.writeInt(orders.size)
            for (order in orders) {
                buf.writeString(order.id)
                buf.writeString(order.itemId)
                buf.writeString(order.displayName)
                buf.writeString(order.professionId)
                buf.writeString(order.professionName)
                buf.writeInt(order.emeraldCost)
                buf.writeLong(order.readyTick)
                buf.writeLong(order.remainingTicks)
                buf.writeBoolean(order.ready)
                buf.writeBoolean(order.claimable)
                buf.writeBoolean(order.abandoned)
                buf.writeLong(order.abandonedInTicks)
            }
        }

        private fun readCommissionOrderList(buf: RegistryByteBuf): List<VillageCommissionOrderData> {
            val size = buf.readInt()
            return List(size) {
                VillageCommissionOrderData(
                    id = buf.readString(),
                    itemId = buf.readString(),
                    displayName = buf.readString(),
                    professionId = buf.readString(),
                    professionName = buf.readString(),
                    emeraldCost = buf.readInt(),
                    readyTick = buf.readLong(),
                    remainingTicks = buf.readLong(),
                    ready = buf.readBoolean(),
                    claimable = buf.readBoolean(),
                    abandoned = buf.readBoolean(),
                    abandonedInTicks = buf.readLong()
                )
            }
        }

        private fun writeBuildingPlotList(buf: RegistryByteBuf, plots: List<VillageBuildingPlotData>) {
            buf.writeInt(plots.size)
            for (plot in plots) {
                buf.writeInt(plot.plotIndex)
                buf.writeString(plot.itemId)
                buf.writeString(plot.displayName)
                buf.writeString(plot.allowedType)
                buf.writeString(plot.builtType)
                buf.writeString(plot.pendingType)
                buf.writeInt(plot.buildingLevel)
                buf.writeInt(plot.pendingLevel)
                buf.writeInt(plot.sizeX)
                buf.writeInt(plot.sizeZ)
                buf.writeString(plot.facing)
                buf.writeLong(plot.remainingTicks)
                buf.writeInt(plot.buildCost)
                buf.writeInt(plot.upgradeCost)
                buf.writeBoolean(plot.canBuild)
                buf.writeBoolean(plot.canUpgrade)
            }
        }

        private fun readBuildingPlotList(buf: RegistryByteBuf): List<VillageBuildingPlotData> {
            val size = buf.readInt()
            return List(size) {
                VillageBuildingPlotData(
                    plotIndex = buf.readInt(),
                    itemId = buf.readString(),
                    displayName = buf.readString(),
                    allowedType = buf.readString(),
                    builtType = buf.readString(),
                    pendingType = buf.readString(),
                    buildingLevel = buf.readInt(),
                    pendingLevel = buf.readInt(),
                    sizeX = buf.readInt(),
                    sizeZ = buf.readInt(),
                    facing = buf.readString(),
                    remainingTicks = buf.readLong(),
                    buildCost = buf.readInt(),
                    upgradeCost = buf.readInt(),
                    canBuild = buf.readBoolean(),
                    canUpgrade = buf.readBoolean()
                )
            }
        }
    }
}

data class VillageBuildingPlotData(
    val plotIndex: Int,
    val itemId: String,
    val displayName: String,
    val allowedType: String,
    val builtType: String,
    val pendingType: String,
    val buildingLevel: Int,
    val pendingLevel: Int,
    val sizeX: Int,
    val sizeZ: Int,
    val facing: String,
    val remainingTicks: Long,
    val buildCost: Int,
    val upgradeCost: Int,
    val canBuild: Boolean,
    val canUpgrade: Boolean
) {
    fun toBuildingPlot(): BuildingPlot = BuildingPlot(
        plotIndex = plotIndex,
        item = itemFromId(itemId),
        displayName = displayName,
        allowedType = allowedType,
        builtType = builtType,
        pendingType = pendingType,
        buildingLevel = buildingLevel,
        pendingLevel = pendingLevel,
        sizeX = sizeX,
        sizeZ = sizeZ,
        facing = facing,
        remainingTicks = remainingTicks,
        buildCost = buildCost,
        upgradeCost = upgradeCost,
        canBuild = canBuild,
        canUpgrade = canUpgrade
    )

    companion object {
        fun fromBuildingPlot(plot: BuildingPlot): VillageBuildingPlotData = VillageBuildingPlotData(
            plotIndex = plot.plotIndex,
            itemId = itemIdOf(plot.item),
            displayName = plot.displayName,
            allowedType = plot.allowedType,
            builtType = plot.builtType,
            pendingType = plot.pendingType,
            buildingLevel = plot.buildingLevel,
            pendingLevel = plot.pendingLevel,
            sizeX = plot.sizeX,
            sizeZ = plot.sizeZ,
            facing = plot.facing,
            remainingTicks = plot.remainingTicks,
            buildCost = plot.buildCost,
            upgradeCost = plot.upgradeCost,
            canBuild = plot.canBuild,
            canUpgrade = plot.canUpgrade
        )
    }
}

data class VillageJobBlockOfferData(
    val itemId: String,
    val displayName: String,
    val professionId: String,
    val professionName: String,
    val emeraldCost: Int
) {
    fun toJobBlockOffer(): JobBlockOffer = JobBlockOffer(
        item = itemFromId(itemId),
        displayName = displayName,
        professionId = professionId,
        professionName = professionName,
        emeraldCost = emeraldCost
    )

    companion object {
        fun fromJobBlockOffer(offer: JobBlockOffer): VillageJobBlockOfferData = VillageJobBlockOfferData(
            itemId = itemIdOf(offer.item),
            displayName = offer.displayName,
            professionId = offer.professionId,
            professionName = offer.professionName,
            emeraldCost = offer.emeraldCost
        )
    }
}

data class SellOffer(
    val item: ItemStack,
    val displayName: String,
    val emeraldCost: Int,
    val tradeItemCount: Int,
    val producedToday: Int,
    val remainingToday: Int,
    val maxDailyProduction: Int,
    val baseValue: Int,
    val minPrice: Int,
    val maxPrice: Int,
    val stockPercent: Int,
    val stockModifier: Int,
    val stockLabel: String,
    val dailyModifier: Int,
    val producerCount: Int,
    val producerProfessionId: String,
    val producerProfession: String
) {
    fun toNetworkOffer(): VillageTradeOfferData = VillageTradeOfferData.fromSellOffer(this)
}

data class BuyOffer(
    val item: ItemStack,
    val displayName: String,
    val emeraldReward: Int,
    val tradeItemCount: Int,
    val neededToday: Int,
    val remainingNeed: Int,
    val maxDailyNeed: Int,
    val baseValue: Int,
    val minPrice: Int,
    val maxPrice: Int,
    val demandPercent: Int,
    val demandModifier: Int,
    val demandLabel: String,
    val dailyModifier: Int,
    val grantsExperience: Boolean,
    val experienceReward: Int,
    val requesterCount: Int,
    val requesterProfessionId: String,
    val requesterProfession: String
) {
    fun toNetworkOffer(): VillageTradeOfferData = VillageTradeOfferData.fromBuyOffer(this)
}

data class JobBlockOffer(
    val item: ItemStack,
    val displayName: String,
    val professionId: String,
    val professionName: String,
    val emeraldCost: Int
) {
    fun toNetworkOffer(): VillageJobBlockOfferData = VillageJobBlockOfferData.fromJobBlockOffer(this)
}

data class VillageCommissionOfferData(
    val id: String,
    val itemId: String,
    val displayName: String,
    val professionId: String,
    val professionName: String,
    val quality: String,
    val description: String,
    val emeraldCost: Int,
    val durationTicks: Long,
    val requiresInput: Boolean
) {
    fun toCommissionOffer(): CommissionOffer = CommissionOffer(
        id = id,
        item = itemFromId(itemId),
        displayName = displayName,
        professionId = professionId,
        professionName = professionName,
        quality = quality,
        description = description,
        emeraldCost = emeraldCost,
        durationTicks = durationTicks,
        requiresInput = requiresInput
    )

    companion object {
        fun fromCommissionOffer(offer: CommissionOffer): VillageCommissionOfferData = VillageCommissionOfferData(
            id = offer.id,
            itemId = itemIdOf(offer.item),
            displayName = offer.displayName,
            professionId = offer.professionId,
            professionName = offer.professionName,
            quality = offer.quality,
            description = offer.description,
            emeraldCost = offer.emeraldCost,
            durationTicks = offer.durationTicks,
            requiresInput = offer.requiresInput
        )
    }
}

data class VillageCommissionOrderData(
    val id: String,
    val itemId: String,
    val displayName: String,
    val professionId: String,
    val professionName: String,
    val emeraldCost: Int,
    val readyTick: Long,
    val remainingTicks: Long,
    val ready: Boolean,
    val claimable: Boolean,
    val abandoned: Boolean,
    val abandonedInTicks: Long
) {
    fun toCommissionOrder(): CommissionOrder = CommissionOrder(
        id = id,
        item = itemFromId(itemId),
        displayName = displayName,
        professionId = professionId,
        professionName = professionName,
        emeraldCost = emeraldCost,
        readyTick = readyTick,
        remainingTicks = remainingTicks,
        ready = ready,
        claimable = claimable,
        abandoned = abandoned,
        abandonedInTicks = abandonedInTicks
    )

    companion object {
        fun fromCommissionOrder(order: CommissionOrder): VillageCommissionOrderData = VillageCommissionOrderData(
            id = order.id,
            itemId = itemIdOf(order.item),
            displayName = order.displayName,
            professionId = order.professionId,
            professionName = order.professionName,
            emeraldCost = order.emeraldCost,
            readyTick = order.readyTick,
            remainingTicks = order.remainingTicks,
            ready = order.ready,
            claimable = order.claimable,
            abandoned = order.abandoned,
            abandonedInTicks = order.abandonedInTicks
        )
    }
}

data class CommissionOffer(
    val id: String,
    val item: ItemStack,
    val displayName: String,
    val professionId: String,
    val professionName: String,
    val quality: String,
    val description: String,
    val emeraldCost: Int,
    val durationTicks: Long,
    val requiresInput: Boolean
) {
    fun toNetworkOffer(): VillageCommissionOfferData = VillageCommissionOfferData.fromCommissionOffer(this)
}

data class CommissionOrder(
    val id: String,
    val item: ItemStack,
    val displayName: String,
    val professionId: String,
    val professionName: String,
    val emeraldCost: Int,
    val readyTick: Long,
    val remainingTicks: Long,
    val ready: Boolean,
    val claimable: Boolean,
    val abandoned: Boolean,
    val abandonedInTicks: Long
) {
    fun toNetworkOrder(): VillageCommissionOrderData = VillageCommissionOrderData.fromCommissionOrder(this)
}

data class BuildingPlot(
    val plotIndex: Int,
    val item: ItemStack,
    val displayName: String,
    val allowedType: String,
    val builtType: String,
    val pendingType: String,
    val buildingLevel: Int,
    val pendingLevel: Int,
    val sizeX: Int,
    val sizeZ: Int,
    val facing: String,
    val remainingTicks: Long,
    val buildCost: Int,
    val upgradeCost: Int,
    val canBuild: Boolean,
    val canUpgrade: Boolean
) {
    fun toNetworkPlot(): VillageBuildingPlotData = VillageBuildingPlotData.fromBuildingPlot(this)
}

object VillageTradeNetwork {
    const val TRADE_DIRECTION_BUY = 0
    const val TRADE_DIRECTION_SELL = 1
    const val TRADE_DIRECTION_JOB_BLOCK = 2
    const val TRADE_DIRECTION_RELOCATION_TOOL = 3
    const val TRADE_DIRECTION_COMMISSION_ORDER = 4
    const val TRADE_DIRECTION_COMMISSION_CLAIM = 5
    const val TRADE_DIRECTION_BUILD_STRUCTURE = 6
    const val TRADE_DIRECTION_UPGRADE_HOUSE = 7
    const val TRADE_DIRECTION_RELOCATE_PLOT = 8
    const val LEDGER_DIRECTION_DEPOSIT = 0
    const val LEDGER_DIRECTION_WITHDRAW = 1
}

data class VillageTradeOpenData(
    val clerkX: Int,
    val clerkY: Int,
    val clerkZ: Int,
    val villageName: String,
    val snapshot: VillageMarketSnapshotData
) {
    companion object {
        val PACKET_CODEC: PacketCodec<RegistryByteBuf, VillageTradeOpenData> = PacketCodec.of(
            { value, buf ->
                buf.writeInt(value.clerkX)
                buf.writeInt(value.clerkY)
                buf.writeInt(value.clerkZ)
                buf.writeString(value.villageName)
                VillageMarketSnapshotData.PACKET_CODEC.encode(buf, value.snapshot)
            },
            { buf ->
                VillageTradeOpenData(
                    clerkX = buf.readInt(),
                    clerkY = buf.readInt(),
                    clerkZ = buf.readInt(),
                    villageName = buf.readString(),
                    snapshot = VillageMarketSnapshotData.PACKET_CODEC.decode(buf)
                )
            }
        )
    }
}

private fun itemIdOf(stack: ItemStack): String = Registries.ITEM.getId(stack.item).toString()

private fun itemFromId(itemId: String): ItemStack {
    val identifier = Identifier.tryParse(itemId) ?: return ItemStack.EMPTY
    val item = runCatching { Registries.ITEM.get(identifier) }.getOrNull() ?: Items.AIR
    return ItemStack(item)
}

