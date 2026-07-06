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
    val playerEmeralds: Int,
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
        tierName = tierName,
        villageExperience = villageExperience,
        currentTierMinExperience = currentTierMinExperience,
        nextTierExperience = nextTierExperience,
        info = info,
        sellOffers = sellOffers.map { it.toNetworkOffer() },
        buyOffers = buyOffers.map { it.toNetworkOffer() }
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
    val tierName: String,
    val villageExperience: Int,
    val currentTierMinExperience: Int,
    val nextTierExperience: Int,
    val info: VillageInfoData,
    val sellOffers: List<VillageTradeOfferData>,
    val buyOffers: List<VillageTradeOfferData>
) {
    fun toRuntime(): VillageMarketSnapshot = VillageMarketSnapshot(
        refreshTicks = refreshTicks,
        produceRefreshTicks = produceRefreshTicks,
        needRefreshTicks = needRefreshTicks,
        playerEmeralds = playerEmeralds,
        tierName = tierName,
        villageExperience = villageExperience,
        currentTierMinExperience = currentTierMinExperience,
        nextTierExperience = nextTierExperience,
        info = info,
        sellOffers = sellOffers.map { it.toSellOffer() },
        buyOffers = buyOffers.map { it.toBuyOffer() }
    )

    companion object {
        val PACKET_CODEC: PacketCodec<RegistryByteBuf, VillageMarketSnapshotData> = PacketCodec.of(
            { value, buf ->
                buf.writeLong(value.refreshTicks)
                buf.writeLong(value.produceRefreshTicks)
                buf.writeLong(value.needRefreshTicks)
                buf.writeInt(value.playerEmeralds)
                buf.writeString(value.tierName)
                buf.writeInt(value.villageExperience)
                buf.writeInt(value.currentTierMinExperience)
                buf.writeInt(value.nextTierExperience)
                VillageInfoData.write(buf, value.info)
                writeOfferList(buf, value.sellOffers)
                writeOfferList(buf, value.buyOffers)
            },
            { buf ->
                VillageMarketSnapshotData(
                    refreshTicks = buf.readLong(),
                    produceRefreshTicks = buf.readLong(),
                    needRefreshTicks = buf.readLong(),
                    playerEmeralds = buf.readInt(),
                    tierName = buf.readString(),
                    villageExperience = buf.readInt(),
                    currentTierMinExperience = buf.readInt(),
                    nextTierExperience = buf.readInt(),
                    info = VillageInfoData.read(buf),
                    sellOffers = readOfferList(buf),
                    buyOffers = readOfferList(buf)
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

object VillageTradeNetwork {
    const val TRADE_DIRECTION_BUY = 0
    const val TRADE_DIRECTION_SELL = 1
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

