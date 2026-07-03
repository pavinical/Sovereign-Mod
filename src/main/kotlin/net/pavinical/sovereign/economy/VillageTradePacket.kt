package net.pavinical.sovereign.economy

import net.minecraft.network.RegistryByteBuf
import net.minecraft.network.codec.PacketCodec
import net.minecraft.network.packet.CustomPayload

object VillageTradePacket {
    val TRADE_REQUEST_ID: CustomPayload.Id<VillageTradeRequestPayload> = CustomPayload.id(
        "sovereign_village_trade_request"
    )

    val TRADE_SYNC_ID: CustomPayload.Id<VillageTradeSyncPayload> = CustomPayload.id(
        "sovereign_village_trade_sync"
    )

    val TRADE_REFRESH_ID: CustomPayload.Id<VillageTradeRefreshPayload> = CustomPayload.id(
        "sovereign_village_trade_refresh"
    )

    val VILLAGE_NAME_OPEN_ID: CustomPayload.Id<VillageNameOpenPayload> = CustomPayload.id(
        "sovereign_village_name_open"
    )

    val VILLAGE_NAME_SUBMIT_ID: CustomPayload.Id<VillageNameSubmitPayload> = CustomPayload.id(
        "sovereign_village_name_submit"
    )

    val VILLAGE_FOUNDED_ID: CustomPayload.Id<VillageFoundedPayload> = CustomPayload.id(
        "sovereign_village_founded"
    )
}

data class VillageTradeRefreshPayload(
    val clerkX: Int,
    val clerkY: Int,
    val clerkZ: Int
) : CustomPayload {
    override fun getId(): CustomPayload.Id<VillageTradeRefreshPayload> = VillageTradePacket.TRADE_REFRESH_ID

    companion object {
        val PACKET_CODEC: PacketCodec<RegistryByteBuf, VillageTradeRefreshPayload> = PacketCodec.of(
            { value, buffer ->
                buffer.writeInt(value.clerkX)
                buffer.writeInt(value.clerkY)
                buffer.writeInt(value.clerkZ)
            },
            { buffer ->
                VillageTradeRefreshPayload(
                    clerkX = buffer.readInt(),
                    clerkY = buffer.readInt(),
                    clerkZ = buffer.readInt()
                )
            }
        )
    }
}

data class VillageTradeRequestPayload(
    val clerkX: Int,
    val clerkY: Int,
    val clerkZ: Int,
    val direction: Int,
    val professionId: String,
    val quantity: Int
) : CustomPayload {
    override fun getId(): CustomPayload.Id<VillageTradeRequestPayload> = VillageTradePacket.TRADE_REQUEST_ID

    companion object {
        val PACKET_CODEC: PacketCodec<RegistryByteBuf, VillageTradeRequestPayload> = PacketCodec.of(
            { value, buffer ->
                buffer.writeInt(value.clerkX)
                buffer.writeInt(value.clerkY)
                buffer.writeInt(value.clerkZ)
                buffer.writeInt(value.direction)
                buffer.writeString(value.professionId)
                buffer.writeInt(value.quantity)
            },
            { buffer ->
                VillageTradeRequestPayload(
                    clerkX = buffer.readInt(),
                    clerkY = buffer.readInt(),
                    clerkZ = buffer.readInt(),
                    direction = buffer.readInt(),
                    professionId = buffer.readString(),
                    quantity = buffer.readInt()
                )
            }
        )
    }
}

data class VillageTradeSyncPayload(
    val clerkX: Int,
    val clerkY: Int,
    val clerkZ: Int,
    val message: String,
    val snapshot: VillageMarketSnapshotData
) : CustomPayload {
    override fun getId(): CustomPayload.Id<VillageTradeSyncPayload> = VillageTradePacket.TRADE_SYNC_ID

    companion object {
        val PACKET_CODEC: PacketCodec<RegistryByteBuf, VillageTradeSyncPayload> = PacketCodec.of(
            { value, buffer ->
                buffer.writeInt(value.clerkX)
                buffer.writeInt(value.clerkY)
                buffer.writeInt(value.clerkZ)
                buffer.writeString(value.message)
                VillageMarketSnapshotData.PACKET_CODEC.encode(buffer, value.snapshot)
            },
            { buffer ->
                VillageTradeSyncPayload(
                    clerkX = buffer.readInt(),
                    clerkY = buffer.readInt(),
                    clerkZ = buffer.readInt(),
                    message = buffer.readString(),
                    snapshot = VillageMarketSnapshotData.PACKET_CODEC.decode(buffer)
                )
            }
        )
    }
}

data class VillageNameOpenPayload(
    val clerkX: Int,
    val clerkY: Int,
    val clerkZ: Int
) : CustomPayload {
    override fun getId(): CustomPayload.Id<VillageNameOpenPayload> = VillageTradePacket.VILLAGE_NAME_OPEN_ID

    companion object {
        val PACKET_CODEC: PacketCodec<RegistryByteBuf, VillageNameOpenPayload> = PacketCodec.of(
            { value, buffer ->
                buffer.writeInt(value.clerkX)
                buffer.writeInt(value.clerkY)
                buffer.writeInt(value.clerkZ)
            },
            { buffer ->
                VillageNameOpenPayload(
                    clerkX = buffer.readInt(),
                    clerkY = buffer.readInt(),
                    clerkZ = buffer.readInt()
                )
            }
        )
    }
}

data class VillageNameSubmitPayload(
    val clerkX: Int,
    val clerkY: Int,
    val clerkZ: Int,
    val name: String
) : CustomPayload {
    override fun getId(): CustomPayload.Id<VillageNameSubmitPayload> = VillageTradePacket.VILLAGE_NAME_SUBMIT_ID

    companion object {
        val PACKET_CODEC: PacketCodec<RegistryByteBuf, VillageNameSubmitPayload> = PacketCodec.of(
            { value, buffer ->
                buffer.writeInt(value.clerkX)
                buffer.writeInt(value.clerkY)
                buffer.writeInt(value.clerkZ)
                buffer.writeString(value.name)
            },
            { buffer ->
                VillageNameSubmitPayload(
                    clerkX = buffer.readInt(),
                    clerkY = buffer.readInt(),
                    clerkZ = buffer.readInt(),
                    name = buffer.readString()
                )
            }
        )
    }
}

data class VillageFoundedPayload(
    val message: String
) : CustomPayload {
    override fun getId(): CustomPayload.Id<VillageFoundedPayload> = VillageTradePacket.VILLAGE_FOUNDED_ID

    companion object {
        val PACKET_CODEC: PacketCodec<RegistryByteBuf, VillageFoundedPayload> = PacketCodec.of(
            { value, buffer ->
                buffer.writeString(value.message)
            },
            { buffer ->
                VillageFoundedPayload(
                    message = buffer.readString()
                )
            }
        )
    }
}

