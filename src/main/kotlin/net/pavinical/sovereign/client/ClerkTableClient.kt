package net.pavinical.sovereign.client

import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.gui.screen.ConfirmScreen
import net.minecraft.client.gui.screen.ingame.HandledScreens
import net.minecraft.text.Text
import net.pavinical.sovereign.economy.PlotPlacementResponsePayload
import net.pavinical.sovereign.economy.VillageTradeHandler
import net.pavinical.sovereign.economy.VillageTradeMenu
import net.pavinical.sovereign.economy.VillageTradePacket

object ClerkTableClient : ClientModInitializer {
    override fun onInitializeClient() {
        VillageFoundingOverlay.register()

        HandledScreens.register(VillageTradeHandler.TYPE) { handler, playerInventory, title ->
            VillageTradeScreen(handler as VillageTradeMenu, playerInventory, title)
        }

        ClientPlayNetworking.registerGlobalReceiver(VillageTradePacket.TRADE_SYNC_ID) { payload, context ->
            context.client().execute {
                val screen = context.client().currentScreen
                if (screen is VillageTradeScreen) {
                    screen.applyServerSnapshot(payload)
                }
            }
        }

        ClientPlayNetworking.registerGlobalReceiver(VillageTradePacket.VILLAGE_NAME_OPEN_ID) { payload, context ->
            context.client().execute {
                context.client().setScreen(
                    VillageNameScreen(
                        clerkX = payload.clerkX,
                        clerkY = payload.clerkY,
                        clerkZ = payload.clerkZ,
                        biome = payload.biome
                    )
                )
            }
        }

        ClientPlayNetworking.registerGlobalReceiver(VillageTradePacket.VILLAGE_FOUNDED_ID) { payload, context ->
            context.client().execute {
                VillageFoundingOverlay.show(payload.message)
            }
        }

        ClientPlayNetworking.registerGlobalReceiver(VillageTradePacket.PLOT_PLACEMENT_CONFIRM_ID) { payload, context ->
            context.client().execute {
                context.client().setScreen(
                    ConfirmScreen(
                        { accepted ->
                            ClientPlayNetworking.send(PlotPlacementResponsePayload(payload.placementId, accepted))
                            context.client().setScreen(null)
                        },
                        Text.literal("Mark this plot?"),
                        Text.literal(payload.description),
                        Text.literal("Place"),
                        Text.literal("Cancel")
                    )
                )
            }
        }
    }
}

