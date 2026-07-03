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
    val nextTierExperience: Int
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
        sellOffers = sellOffers.map { it.toNetworkOffer() },
        buyOffers = buyOffers.map { it.toNetworkOffer() }
    )
}

data class VillageTradeOfferData(
    val itemId: String,
    val displayName: String,
    val emeraldValue: Int,
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

