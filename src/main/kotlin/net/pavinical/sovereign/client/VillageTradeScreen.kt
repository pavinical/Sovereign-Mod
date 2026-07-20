package net.pavinical.sovereign.client

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.gui.screen.ingame.InventoryScreen
import net.minecraft.component.DataComponentTypes
import net.minecraft.component.type.PotionContentsComponent
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.ingame.HandledScreen
import net.minecraft.client.gui.widget.ButtonWidget
import net.minecraft.client.sound.PositionedSoundInstance
import net.minecraft.entity.EquipmentSlot
import net.minecraft.entity.decoration.ArmorStandEntity
import net.minecraft.entity.player.PlayerInventory
import net.minecraft.item.ItemStack
import net.minecraft.item.Items
import net.minecraft.item.trim.ArmorTrim
import net.minecraft.item.trim.ArmorTrimMaterials
import net.minecraft.item.trim.ArmorTrimPatterns
import net.minecraft.potion.Potion
import net.minecraft.potion.Potions
import net.minecraft.registry.RegistryKey
import net.minecraft.registry.RegistryKeys
import net.minecraft.registry.entry.RegistryEntry
import net.minecraft.sound.SoundEvents
import net.minecraft.text.Text
import net.minecraft.util.Identifier
import org.lwjgl.glfw.GLFW
import net.pavinical.sovereign.Sovereign
import net.pavinical.sovereign.economy.VillageTradeMenu
import net.pavinical.sovereign.economy.VillageTradeNetwork
import net.pavinical.sovereign.economy.BuildingPlot
import net.pavinical.sovereign.economy.VillageLedgerTransferPayload
import net.pavinical.sovereign.economy.JobBlockRelocationTool
import net.pavinical.sovereign.economy.VillageTradeRefreshPayload
import net.pavinical.sovereign.economy.VillageTradeRequestPayload
import net.pavinical.sovereign.economy.VillageTradeSyncPayload

class VillageTradeScreen(
    handler: VillageTradeMenu,
    private val playerInventoryRef: PlayerInventory,
    title: Text
) : HandledScreen<VillageTradeMenu>(handler, playerInventoryRef, title) {
    private data class TooltipLine(val text: String, val color: Int)

    private enum class ArmorerOptionKind {
        ARMOR,
        TRIM,
        MATERIAL
    }

    private data class ArmorerOption(
        val kind: ArmorerOptionKind,
        val key: String,
        val stack: ItemStack,
        val label: String,
        val offerIndex: Int? = null,
        val price: Int? = null
    )

    private enum class SelectedSide {
        BUY,
        SELL
    }

    private enum class ActiveTab {
        TRADE,
        INFO,
        JOB_BLOCKS,
        BUILDINGS,
        COMMISSIONS,
        PICKUP,
        LEDGER
    }

    private var selectedSellIndex = -1
    private var selectedBuyIndex = -1
    private var selectedJobBlockIndex = -1
    private var selectedCommissionOfferIndex = -1
    private var selectedCommissionOrderIndex = -1
    private var selectedBuildingPlotIndex = -1
    private var selectedSellKey: String? = null
    private var selectedBuyKey: String? = null
    private var selectedJobBlockKey: String? = null
    private var selectedCommissionOfferKey: String? = null
    private var selectedCommissionOrderKey: String? = null
    private var selectedBuildingPlotKey: String? = null
    private var selectedSide: SelectedSide? = null
    private var activeTab = ActiveTab.TRADE
    private var statusMessage: Text = Text.literal("")
    private var localTicksSinceSnapshot = 0L
    private var refreshRequested = false
    private var tooltipsVisible = false
    private var sellScrollOffset = 0
    private var buyScrollOffset = 0
    private var jobBlockScrollOffset = 0
    private var commissionScrollOffset = 0
    private var pickupScrollOffset = 0
    private var buildingScrollOffset = 0
    private var draggingScroll: SelectedSide? = null
    private var draggingJobBlockScroll = false
    private var draggingCommissionScroll = false
    private var draggingPickupScroll = false
    private var draggingBuildingScroll = false
    private var draggingPotionSlider = false
    private var commissionProfessionDropdownOpen = false
    private var selectedCommissionProfessionId: String? = null
    private var selectedPotionQuantity = 1
    private var selectedArmorerArmorKey: String? = null
    private var selectedArmorerTrimKey: String? = null
    private var selectedArmorerMaterialKey: String? = null
    private lateinit var tradeButton: ButtonWidget
    private lateinit var tradeAllButton: ButtonWidget
    private lateinit var buyJobBlockButton: ButtonWidget
    private lateinit var repairCommissionButton: ButtonWidget
    private lateinit var reforgeCommissionButton: ButtonWidget
    private lateinit var orderCommissionButton: ButtonWidget
    private lateinit var claimCommissionButton: ButtonWidget
    private lateinit var buildStructureButton: ButtonWidget
    private lateinit var upgradeBuildingButton: ButtonWidget
    private lateinit var relocateBuildingButton: ButtonWidget
    private lateinit var depositOneButton: ButtonWidget
    private lateinit var depositAllButton: ButtonWidget
    private lateinit var withdrawOneButton: ButtonWidget
    private lateinit var withdrawAllButton: ButtonWidget
    private val emeraldStack = ItemStack(Items.EMERALD)

    private val sellOffers
        get() = handler.snapshot.sellOffers

    private val buyOffers
        get() = handler.snapshot.buyOffers

    private val jobBlockOffers
        get() = handler.snapshot.jobBlockOffers

    private val commissionOffers
        get() = handler.snapshot.commissionOffers

    private val commissionOrders
        get() = handler.snapshot.commissionOrders

    private val buildingPlots
        get() = handler.snapshot.buildingPlots

    private val commissionProfessionOptions
        get() = commissionOffers.map { it.professionId to it.professionName }
            .distinctBy { it.first }
            .sortedBy { it.second }

    private val selectedCommissionProfession
        get() = selectedCommissionProfessionId
            ?.let { id -> commissionProfessionOptions.firstOrNull { it.first == id } }
            ?: commissionProfessionOptions.firstOrNull()

    private val filteredCommissionOffers
        get() = selectedCommissionProfession?.first
            ?.let { professionId ->
                commissionOffers
                    .filter { it.professionId == professionId }
                    .filterNot { isServiceProfession(professionId) && isRepairCommissionOffer(it) }
            }
            ?: emptyList()

    private val filteredCommissionOrders
        get() = commissionOrders

    init {
        backgroundHeight = 175
        backgroundWidth = 378
        titleY = -1000
        playerInventoryTitleY = -1000
    }

    override fun init() {
        super.init()

        tradeButton = addDrawableChild(
            ButtonWidget.builder(Text.literal("Trade")) { onTradeClicked() }
                .dimensions(x + 150, y + 45, 76, 18)
                .build()
        )
        tradeAllButton = addDrawableChild(
            ButtonWidget.builder(Text.literal("Trade All")) { onTradeAllClicked() }
                .dimensions(x + 150, y + 66, 76, 18)
                .build()
        )
        depositOneButton = addDrawableChild(
            ButtonWidget.builder(Text.literal("+1")) { sendLedgerTransfer(VillageTradeNetwork.LEDGER_DIRECTION_DEPOSIT, 1) }
                .dimensions(x + 56, y + 68, 50, 18)
                .build()
        )
        depositAllButton = addDrawableChild(
            ButtonWidget.builder(Text.literal("+All")) { sendLedgerTransfer(VillageTradeNetwork.LEDGER_DIRECTION_DEPOSIT, LEDGER_ALL_QUANTITY) }
                .dimensions(x + 112, y + 68, 62, 18)
                .build()
        )
        withdrawOneButton = addDrawableChild(
            ButtonWidget.builder(Text.literal("-1")) { sendLedgerTransfer(VillageTradeNetwork.LEDGER_DIRECTION_WITHDRAW, 1) }
                .dimensions(x + 202, y + 68, 50, 18)
                .build()
        )
        withdrawAllButton = addDrawableChild(
            ButtonWidget.builder(Text.literal("-All")) { sendLedgerTransfer(VillageTradeNetwork.LEDGER_DIRECTION_WITHDRAW, LEDGER_ALL_QUANTITY) }
                .dimensions(x + 258, y + 68, 62, 18)
                .build()
        )
        buyJobBlockButton = addDrawableChild(
            ButtonWidget.builder(Text.literal("Buy Permit")) { onBuyJobBlockClicked() }
                .dimensions(x + 246, y + 146, 92, 18)
                .build()
        )
        repairCommissionButton = addDrawableChild(
            ButtonWidget.builder(Text.literal("Repair")) { onRepairCommissionClicked() }
                .dimensions(x + 105, y + 66, 50, 18)
                .build()
        )
        reforgeCommissionButton = addDrawableChild(
            ButtonWidget.builder(Text.literal("Reforge")) { onReforgeCommissionClicked() }
                .dimensions(x + 163, y + 66, 50, 18)
                .build()
        )
        orderCommissionButton = addDrawableChild(
            ButtonWidget.builder(Text.literal("Order")) { onOrderCommissionClicked() }
                .dimensions(x + 221, y + 66, 50, 18)
                .build()
        )
        claimCommissionButton = addDrawableChild(
            ButtonWidget.builder(Text.literal("Claim")) { onClaimCommissionClicked() }
                .dimensions(x + 230, y + 76, 48, 18)
                .build()
        )
        buildStructureButton = addDrawableChild(
            ButtonWidget.builder(Text.literal("Build")) { onBuildStructureClicked() }
                .dimensions(x + 141, y + 64, 50, 18)
                .build()
        )
        upgradeBuildingButton = addDrawableChild(
            ButtonWidget.builder(Text.literal("Upgrade")) { onUpgradeBuildingClicked() }
                .dimensions(x + 197, y + 64, 58, 18)
                .build()
        )
        relocateBuildingButton = addDrawableChild(
            ButtonWidget.builder(Text.literal("Relocate")) { onRelocateBuildingClicked() }
                .dimensions(x + 258, y + 64, 62, 18)
                .build()
        )
    }

    override fun drawBackground(context: DrawContext, delta: Float, mouseX: Int, mouseY: Int) {
        drawClerkShellTexture(context)
        if (activeTab != ActiveTab.TRADE) {
            drawBlankContentLayer(context)
        }
        drawTabs(context)

        if (activeTab != ActiveTab.COMMISSIONS && activeTab != ActiveTab.PICKUP) {
            context.drawCenteredTextWithShadow(
                textRenderer,
                trimToWidth("${handler.villageName} Trading Post", 150),
                x + backgroundWidth / 2,
                y + 16,
                TEXT_LIGHT
            )
            drawVillageXpBar(context, x + 128, y + 29, 120, 7)
        }

        if (activeTab == ActiveTab.TRADE) {
            drawScrollThumb(context, x + 92, y + 22, 136, sellScrollOffset, maxSellScroll(), VISIBLE_ROWS)
            drawScrollThumb(context, x + 367, y + 22, 136, buyScrollOffset, maxBuyScroll(), VISIBLE_ROWS)
            context.drawText(textRenderer, "Buy", x + 38, y + 8, TEXT_DARK, false)
            context.drawText(textRenderer, "Sell", x + 315, y + 8, TEXT_DARK, false)
            drawVillageWealth(context, x + 28, y + backgroundHeight - 17)
        } else {
            when (activeTab) {
                ActiveTab.INFO -> drawInfoTab(context)
                ActiveTab.JOB_BLOCKS -> drawJobBlockTab(context)
                ActiveTab.BUILDINGS -> drawBuildingsTab(context)
                ActiveTab.COMMISSIONS -> drawCommissionsTab(context)
                ActiveTab.PICKUP -> drawPickupTab(context)
                ActiveTab.LEDGER -> drawLedgerTab(context)
                ActiveTab.TRADE -> Unit
            }
        }
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        handler.updateInventorySlotVisibility(activeTab == ActiveTab.TRADE || activeTab == ActiveTab.LEDGER || activeTab == ActiveTab.COMMISSIONS)
        handler.updateCommissionInputSlotVisibility(
            activeTab == ActiveTab.COMMISSIONS,
            commissionInputSlotCount(selectedCommissionProfession?.first),
            commissionFirstInputSlot(selectedCommissionProfession?.first)
        )
        tradeButton.visible = activeTab == ActiveTab.TRADE
        tradeAllButton.visible = activeTab == ActiveTab.TRADE
        tradeButton.active = activeTab == ActiveTab.TRADE && selectedSide != null
        tradeAllButton.active = activeTab == ActiveTab.TRADE && selectedSide != null
        buyJobBlockButton.visible = activeTab == ActiveTab.JOB_BLOCKS
        buyJobBlockButton.active = activeTab == ActiveTab.JOB_BLOCKS && selectedJobBlockIndex in jobBlockOffers.indices
        val professionId = selectedCommissionProfession?.first
        val smithMenu = activeTab == ActiveTab.COMMISSIONS && isSmithProfession(professionId)
        val armorerMenu = activeTab == ActiveTab.COMMISSIONS && professionId == "minecraft:armorer"
        val serviceMenu = smithMenu || armorerMenu
        val serviceHasInput = hasServiceInput()
        val serviceInputAllowed = canClientWorkServiceInput(professionId)
        val armorerGhostFilled = armorerHasRightPanelSelection()
        repairCommissionButton.visible = serviceMenu
        repairCommissionButton.active = serviceMenu && serviceHasInput && serviceInputAllowed && !armorerGhostFilled && repairCommissionOfferFor(professionId) != null
        reforgeCommissionButton.visible = serviceMenu
        reforgeCommissionButton.active = serviceMenu && serviceHasInput && serviceInputAllowed && !armorerGhostFilled && activeReforgeOffer() != null
        orderCommissionButton.visible = activeTab == ActiveTab.COMMISSIONS
        orderCommissionButton.active = activeTab == ActiveTab.COMMISSIONS && if (professionId == "minecraft:armorer") {
            selectedArmorerArmorKey != null
        } else if (serviceMenu) {
            !serviceHasInput && selectedCommissionOfferIndex in filteredCommissionOffers.indices
        } else {
            selectedCommissionOfferIndex in filteredCommissionOffers.indices
        }
        claimCommissionButton.visible = activeTab == ActiveTab.PICKUP
        claimCommissionButton.active = activeTab == ActiveTab.PICKUP &&
            selectedCommissionOrderIndex in filteredCommissionOrders.indices &&
            filteredCommissionOrders[selectedCommissionOrderIndex].claimable
        val selectedBuilding = selectedBuildingPlot()
        buildStructureButton.visible = activeTab == ActiveTab.BUILDINGS
        buildStructureButton.active = selectedBuilding?.canBuild == true
        upgradeBuildingButton.visible = activeTab == ActiveTab.BUILDINGS
        upgradeBuildingButton.active = selectedBuilding?.canUpgrade == true
        relocateBuildingButton.visible = activeTab == ActiveTab.BUILDINGS
        relocateBuildingButton.active = selectedBuilding?.let { it.plotIndex > 0 && it.pendingType.isBlank() } == true
        depositOneButton.visible = activeTab == ActiveTab.LEDGER
        depositAllButton.visible = activeTab == ActiveTab.LEDGER
        withdrawOneButton.visible = activeTab == ActiveTab.LEDGER
        withdrawAllButton.visible = activeTab == ActiveTab.LEDGER
        orderCommissionButton.message = Text.literal("Order")
        if (serviceMenu) {
            repairCommissionButton.setPosition(x + 105, y + 66)
            reforgeCommissionButton.setPosition(x + 163, y + 66)
            orderCommissionButton.setPosition(x + 221, y + 66)
        }
        if (professionId == "minecraft:cleric") {
            orderCommissionButton.setPosition(x + 159, y + 63)
        } else if (!serviceMenu) {
            orderCommissionButton.setPosition(x + 159, y + 66)
        }
        claimCommissionButton.setPosition(x + 246, y + 146)
        buildStructureButton.setPosition(x + 99, y + 46)
        upgradeBuildingButton.setPosition(x + 155, y + 46)
        relocateBuildingButton.setPosition(x + 219, y + 46)

        super.render(context, mouseX, mouseY, delta)

        if (activeTab == ActiveTab.TRADE) {
            drawOfferRows(context)
            if (tooltipsVisible) {
                drawHoverInfo(context, mouseX, mouseY)
            }
        } else if (activeTab == ActiveTab.JOB_BLOCKS) {
            drawJobBlockRows(context)
        } else if (activeTab == ActiveTab.COMMISSIONS) {
            drawCommissionRows(context)
            drawCommissionHoverInfo(context, mouseX, mouseY)
        } else if (activeTab == ActiveTab.PICKUP) {
            drawPickupRows(context)
            drawPickupHoverInfo(context, mouseX, mouseY)
        } else if (activeTab == ActiveTab.BUILDINGS) {
            drawBuildingRows(context)
            drawBuildingHoverInfo(context, mouseX, mouseY)
        }
    }

    override fun handledScreenTick() {
        super.handledScreenTick()
        if (!isClientSessionActive()) return
        localTicksSinceSnapshot++
        if (!refreshRequested && handler.snapshot.refreshTicks > 0L && localTicksSinceSnapshot >= handler.snapshot.refreshTicks) {
            refreshRequested = true
            requestSnapshotRefresh()
        }
    }

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        if (keyCode == GLFW.GLFW_KEY_TAB) {
            tooltipsVisible = true
            return true
        }
        return super.keyPressed(keyCode, scanCode, modifiers)
    }

    override fun keyReleased(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        if (keyCode == GLFW.GLFW_KEY_TAB) {
            tooltipsVisible = false
            return true
        }
        return super.keyReleased(keyCode, scanCode, modifiers)
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        val clickedX = mouseX.toInt()
        val clickedY = mouseY.toInt()

        if (button == 1 && activeTab == ActiveTab.COMMISSIONS && isServiceProfession(selectedCommissionProfession?.first)) {
            if (selectedCommissionProfession?.first == "minecraft:armorer" &&
                hitTestPanel(clickedX, clickedY, x + 126, y + 44, 90, 18) &&
                !hasServiceInput() &&
                armorerHasRightPanelSelection()
            ) {
                playButtonClick()
                selectedArmorerArmorKey = null
                selectedArmorerTrimKey = null
                selectedArmorerMaterialKey = null
                selectedCommissionOfferIndex = -1
                selectedCommissionOfferKey = null
                statusMessage = Text.literal("Armor work cleared.")
                return true
            }
            if (hitTestPanel(clickedX, clickedY, x + 179, y + 44, 18, 18) && !hasServiceInput() && selectedCommissionOfferIndex in filteredCommissionOffers.indices) {
                playButtonClick()
                selectedCommissionOfferIndex = -1
                selectedCommissionOfferKey = null
                statusMessage = Text.literal("Order cleared.")
                return true
            }
        }

        if (button != 0) {
            return super.mouseClicked(mouseX, mouseY, button)
        }

        if (hitTestPanel(clickedX, clickedY, x + TAB_1_LEFT, y - GUI_TAB_HEIGHT, TAB_SMALL_WIDTH, GUI_TAB_HEIGHT)) {
            playButtonClick()
            activeTab = ActiveTab.TRADE
            return true
        }

        if (hitTestPanel(clickedX, clickedY, x + TAB_2_LEFT, y - GUI_TAB_HEIGHT, TAB_SMALL_WIDTH, GUI_TAB_HEIGHT)) {
            playButtonClick()
            activeTab = ActiveTab.BUILDINGS
            clearSelection()
            statusMessage = Text.literal("")
            return true
        }

        if (hitTestPanel(clickedX, clickedY, x + TAB_3_LEFT, y - GUI_TAB_HEIGHT, TAB_SMALL_WIDTH, GUI_TAB_HEIGHT)) {
            playButtonClick()
            activeTab = ActiveTab.JOB_BLOCKS
            clearSelection()
            statusMessage = Text.literal("")
            return true
        }

        if (hitTestPanel(clickedX, clickedY, x + TAB_4_LEFT, y - GUI_TAB_HEIGHT, TAB_SMALL_WIDTH, GUI_TAB_HEIGHT)) {
            playButtonClick()
            activeTab = ActiveTab.COMMISSIONS
            clearSelection()
            statusMessage = Text.literal("")
            return true
        }

        if (hitTestPanel(clickedX, clickedY, x + TAB_5_LEFT, y - GUI_TAB_HEIGHT, TAB_SMALL_WIDTH, GUI_TAB_HEIGHT)) {
            playButtonClick()
            activeTab = ActiveTab.PICKUP
            clearSelection()
            statusMessage = Text.literal("")
            return true
        }

        if (hitTestPanel(clickedX, clickedY, x + TAB_6_LEFT, y - GUI_TAB_HEIGHT, TAB_SMALL_WIDTH, GUI_TAB_HEIGHT)) {
            playButtonClick()
            activeTab = ActiveTab.INFO
            clearSelection()
            statusMessage = Text.literal("")
            return true
        }

        if (hitTestPanel(clickedX, clickedY, x + LEDGER_TAB_LEFT, y - GUI_TAB_HEIGHT, LEDGER_TAB_WIDTH, GUI_TAB_HEIGHT)) {
            playButtonClick()
            activeTab = ActiveTab.LEDGER
            clearSelection()
            statusMessage = Text.literal("")
            return true
        }

        if (activeTab == ActiveTab.JOB_BLOCKS) {
            if (hitTestScrollGutter(clickedX, clickedY, x + 38, y + 50, 300, 92)) {
                playButtonClick()
                draggingJobBlockScroll = true
                updateJobBlockScrollFromMouse(clickedY)
                return true
            }

            val jobBlockIndex = hitTest(clickedX, clickedY, x + 42, x + 329, y + 54, 17, visibleJobBlockCount())
                ?.let { it + jobBlockScrollOffset }
                ?.takeIf { it in jobBlockOffers.indices }
            if (jobBlockIndex != null) {
                playButtonClick()
                selectedJobBlockIndex = jobBlockIndex
                selectedJobBlockKey = jobBlockOffers[jobBlockIndex].professionId
                selectedSide = null
                statusMessage = Text.literal("Buy ${jobBlockOffers[jobBlockIndex].professionName} permit")
                return true
            }

            return super.mouseClicked(mouseX, mouseY, button)
        }

        if (activeTab == ActiveTab.COMMISSIONS) {
            val professionIndex = hitTest(clickedX, clickedY, x + 10, x + 94, y + 24, 17, commissionProfessionOptions.size.coerceAtMost(COMMISSION_SELECTOR_MAX_OPTIONS))
            if (professionIndex != null) {
                playButtonClick()
                selectCommissionProfession(commissionProfessionOptions[professionIndex].first)
                return true
            }

            if (selectedCommissionProfession?.first == "minecraft:cleric") {
                if (hitTestPanel(clickedX, clickedY, x + 131, y + 41, 112, 18)) {
                    updatePotionQuantityFromMouse(clickedX)
                    draggingPotionSlider = true
                    playButtonClick()
                    statusMessage = Text.literal("Potion batch x$selectedPotionQuantity")
                    return true
                }
            }

            if (hitTestScrollGutter(clickedX, clickedY, x + 276, y + 20, 96, 140)) {
                playButtonClick()
                draggingCommissionScroll = true
                updateCommissionScrollFromMouse(clickedY)
                return true
            }

            if (selectedCommissionProfession?.first == "minecraft:armorer") {
                val armorerOption = armorerOptionAt(clickedX, clickedY)
                if (armorerOption != null) {
                    playButtonClick()
                    selectedSide = null
                    selectArmorerOption(armorerOption)
                    return true
                }
            }

            val rowIndex = hitTest(clickedX, clickedY, x + 282, x + 365, y + 24, 17, visibleCommissionCount())
                ?.let { it + commissionScrollOffset }
                ?.takeIf { it in 0 until totalCommissionRows() }
            if (rowIndex != null) {
                playButtonClick()
                selectedSide = null
                if (rowIndex < filteredCommissionOffers.size) {
                    selectedCommissionOfferIndex = rowIndex
                    selectedCommissionOfferKey = filteredCommissionOffers[rowIndex].id
                    statusMessage = Text.literal("Order ${filteredCommissionOffers[rowIndex].displayName}")
                }
                return true
            }

            return super.mouseClicked(mouseX, mouseY, button)
        }

        if (activeTab == ActiveTab.PICKUP) {
            if (hitTestScrollGutter(clickedX, clickedY, BUILDING_PANEL_LEFT(), BUILDING_PANEL_TOP(), BUILDING_PANEL_WIDTH, BUILDING_PANEL_HEIGHT)) {
                playButtonClick()
                draggingPickupScroll = true
                updatePickupScrollFromMouse(clickedY)
                return true
            }

            val orderIndex = hitTest(clickedX, clickedY, x + 42, x + 329, y + 38, 17, visiblePickupCount())
                ?.let { it + pickupScrollOffset }
                ?.takeIf { it in filteredCommissionOrders.indices }
            if (orderIndex != null) {
                playButtonClick()
                selectedSide = null
                selectedCommissionOrderIndex = orderIndex
                selectedCommissionOrderKey = filteredCommissionOrders[orderIndex].id
                selectedCommissionOfferIndex = -1
                selectedCommissionOfferKey = null
                val order = filteredCommissionOrders[orderIndex]
                statusMessage = Text.literal(pickupStatus(order))
                return true
            }

            return super.mouseClicked(mouseX, mouseY, button)
        }

        if (activeTab == ActiveTab.BUILDINGS) {
            if (hitTestScrollGutter(clickedX, clickedY, x + 38, y + 34, 300, 108)) {
                playButtonClick()
                draggingBuildingScroll = true
                updateBuildingScrollFromMouse(clickedY)
                return true
            }

            val plotIndex = hitTest(clickedX, clickedY, BUILDING_ROW_LEFT(), BUILDING_ROW_RIGHT(), BUILDING_ROW_TOP(), 17, visibleBuildingCount())
                ?.let { it + buildingScrollOffset }
                ?.takeIf { it in buildingPlots.indices }
            if (plotIndex != null) {
                playButtonClick()
                selectedSide = null
                selectedBuildingPlotIndex = plotIndex
                selectedBuildingPlotKey = buildingPlots[plotIndex].plotIndex.toString()
                val plot = buildingPlots[plotIndex]
                statusMessage = Text.literal(plot.displayName)
                return true
            }

            return super.mouseClicked(mouseX, mouseY, button)
        }

        if (activeTab != ActiveTab.TRADE) {
            return super.mouseClicked(mouseX, mouseY, button)
        }

        if (hitTestScrollGutter(clickedX, clickedY, x + 4, y + 20, 96, 140)) {
            playButtonClick()
            draggingScroll = SelectedSide.BUY
            updateScrollFromMouse(SelectedSide.BUY, clickedY)
            return true
        }

        if (hitTestScrollGutter(clickedX, clickedY, x + 276, y + 20, 96, 140)) {
            playButtonClick()
            draggingScroll = SelectedSide.SELL
            updateScrollFromMouse(SelectedSide.SELL, clickedY)
            return true
        }

        val sellIndex = hitTest(clickedX, clickedY, x + 8, x + 91, y + 24, 17, visibleSellCount())
            ?.let { it + sellScrollOffset }
            ?.takeIf { it in sellOffers.indices }
        if (sellIndex != null) {
            playButtonClick()
            selectedSellIndex = sellIndex
            selectedSellKey = sellOffers[sellIndex].producerProfessionId
            selectedBuyIndex = -1
            selectedBuyKey = null
            selectedSide = SelectedSide.BUY
            statusMessage = Text.literal("Buy ${sellOffers[sellIndex].displayName}")
            return true
        }

        val buyIndex = hitTest(clickedX, clickedY, x + 282, x + 365, y + 24, 17, visibleBuyCount())
            ?.let { it + buyScrollOffset }
            ?.takeIf { it in buyOffers.indices }
        if (buyIndex != null) {
            playButtonClick()
            selectedBuyIndex = buyIndex
            selectedBuyKey = buyOffers[buyIndex].requesterProfessionId
            selectedSellIndex = -1
            selectedSellKey = null
            selectedSide = SelectedSide.SELL
            statusMessage = Text.literal("Sell ${buyOffers[buyIndex].displayName}")
            return true
        }

        return super.mouseClicked(mouseX, mouseY, button)
    }

    override fun mouseDragged(mouseX: Double, mouseY: Double, button: Int, deltaX: Double, deltaY: Double): Boolean {
        val side = draggingScroll
        if (button == 0 && side != null) {
            updateScrollFromMouse(side, mouseY.toInt())
            return true
        }
        if (button == 0 && draggingJobBlockScroll) {
            updateJobBlockScrollFromMouse(mouseY.toInt())
            return true
        }
        if (button == 0 && draggingCommissionScroll) {
            updateCommissionScrollFromMouse(mouseY.toInt())
            return true
        }
        if (button == 0 && draggingPotionSlider) {
            updatePotionQuantityFromMouse(mouseX.toInt())
            return true
        }
        if (button == 0 && draggingPickupScroll) {
            updatePickupScrollFromMouse(mouseY.toInt())
            return true
        }
        if (button == 0 && draggingBuildingScroll) {
            updateBuildingScrollFromMouse(mouseY.toInt())
            return true
        }
        return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY)
    }

    override fun mouseReleased(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (button == 0 && draggingJobBlockScroll) {
            draggingJobBlockScroll = false
            return true
        }
        if (button == 0 && draggingCommissionScroll) {
            draggingCommissionScroll = false
            return true
        }
        if (button == 0 && draggingPotionSlider) {
            draggingPotionSlider = false
            return true
        }
        if (button == 0 && draggingPickupScroll) {
            draggingPickupScroll = false
            return true
        }
        if (button == 0 && draggingBuildingScroll) {
            draggingBuildingScroll = false
            return true
        }
        if (button == 0 && draggingScroll != null) {
            draggingScroll = null
            return true
        }
        return super.mouseReleased(mouseX, mouseY, button)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
        val mouseXi = mouseX.toInt()
        val mouseYi = mouseY.toInt()
        val scrollDelta = if (verticalAmount > 0.0) -1 else 1

        if (activeTab == ActiveTab.JOB_BLOCKS) {
            if (hitTestPanel(mouseXi, mouseYi, x + 38, y + 50, 300, 92)) {
                jobBlockScrollOffset = (jobBlockScrollOffset + scrollDelta).coerceIn(0, maxJobBlockScroll())
                return true
            }
            return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)
        }

        if (activeTab == ActiveTab.COMMISSIONS) {
            if (hitTestPanel(mouseXi, mouseYi, x + 276, y + 20, 96, 140)) {
                commissionScrollOffset = (commissionScrollOffset + scrollDelta).coerceIn(0, maxCommissionScroll())
                return true
            }
            return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)
        }

        if (activeTab == ActiveTab.PICKUP) {
            if (hitTestPanel(mouseXi, mouseYi, BUILDING_PANEL_LEFT(), BUILDING_PANEL_TOP(), BUILDING_PANEL_WIDTH, BUILDING_PANEL_HEIGHT)) {
                pickupScrollOffset = (pickupScrollOffset + scrollDelta).coerceIn(0, maxPickupScroll())
                return true
            }
            return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)
        }

        if (activeTab == ActiveTab.BUILDINGS) {
            if (hitTestPanel(mouseXi, mouseYi, x + 38, y + 34, 300, 108)) {
                buildingScrollOffset = (buildingScrollOffset + scrollDelta).coerceIn(0, maxBuildingScroll())
                return true
            }
            return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)
        }

        if (activeTab != ActiveTab.TRADE) {
            return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)
        }

        if (hitTestPanel(mouseXi, mouseYi, x + 4, y + 20, 96, 140)) {
            sellScrollOffset = (sellScrollOffset + scrollDelta).coerceIn(0, maxSellScroll())
            return true
        }

        if (hitTestPanel(mouseXi, mouseYi, x + 276, y + 20, 96, 140)) {
            buyScrollOffset = (buyScrollOffset + scrollDelta).coerceIn(0, maxBuyScroll())
            return true
        }

        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)
    }

    private fun drawOfferRows(context: DrawContext) {
        val rowHeight = 17
        var rowY = y + 24

        sellOffers.drop(sellScrollOffset).take(VISIBLE_ROWS).forEachIndexed { visibleIndex, offer ->
            val offerIndex = sellScrollOffset + visibleIndex
            drawTradeRow(context, x + 8, rowY, 83, selectedSide == SelectedSide.BUY && selectedSellIndex == offerIndex)
            context.drawItem(offer.item, x + 9, rowY)
            context.drawText(
                textRenderer,
                offer.tradeItemCount.toString(),
                x + 28,
                rowY + 5,
                TEXT_LIGHT,
                false
            )
            context.drawText(textRenderer, ">", x + 45, rowY + 5, TEXT_DARK, false)
            context.drawItem(emeraldStack, x + 59, rowY)
            context.drawText(
                textRenderer,
                offer.emeraldCost.toString(),
                x + 76,
                rowY + 5,
                EMERALD_TEXT,
                false
            )
            rowY += rowHeight
        }

        rowY = y + 24
        buyOffers.drop(buyScrollOffset).take(VISIBLE_ROWS).forEachIndexed { visibleIndex, offer ->
            val offerIndex = buyScrollOffset + visibleIndex
            drawTradeRow(context, x + 282, rowY, 83, selectedSide == SelectedSide.SELL && selectedBuyIndex == offerIndex)
            context.drawItem(offer.item, x + 283, rowY)
            context.drawText(
                textRenderer,
                offer.tradeItemCount.toString(),
                x + 302,
                rowY + 5,
                TEXT_LIGHT,
                false
            )
            context.drawText(textRenderer, ">", x + 319, rowY + 5, TEXT_DARK, false)
            context.drawItem(emeraldStack, x + 333, rowY)
            context.drawText(
                textRenderer,
                offer.emeraldReward.toString(),
                x + 350,
                rowY + 5,
                EMERALD_TEXT,
                false
            )
            rowY += rowHeight
        }
    }

    private fun drawVillageXpBar(context: DrawContext, left: Int, top: Int, width: Int, height: Int) {
        val snapshot = handler.snapshot
        val tierStart = snapshot.currentTierMinExperience
        val tierEnd = snapshot.nextTierExperience
        val progressRange = (tierEnd - tierStart).coerceAtLeast(1)
        val progress = (snapshot.villageExperience - tierStart).coerceAtLeast(0).coerceAtMost(progressRange)
        val innerLeft = left + 2
        val innerTop = top + 2
        val innerWidth = width - 4
        val innerHeight = height - 4
        val fillWidth = if (snapshot.villageExperience >= tierEnd) innerWidth else (innerWidth * progress) / progressRange
        val label = if (snapshot.villageExperience >= tierEnd && snapshot.tierName == "City") {
            "${snapshot.tierName} XP ${snapshot.villageExperience}"
        } else {
            "${snapshot.tierName} XP ${snapshot.villageExperience}/$tierEnd"
        }

        drawSunkenBox(context, left, top, width, height)
        context.fill(innerLeft, innerTop, innerLeft + innerWidth, innerTop + innerHeight, XP_BAR_BG)
        val segments = 18
        for (segment in 0 until segments) {
            val segmentLeft = innerLeft + (segment * innerWidth) / segments
            val segmentRight = innerLeft + ((segment + 1) * innerWidth) / segments - 1
            if (segmentRight <= segmentLeft) continue
            val filledRight = (innerLeft + fillWidth).coerceAtMost(segmentRight)
            if (filledRight > segmentLeft) {
                context.fill(segmentLeft, innerTop, filledRight, innerTop + innerHeight, XP_BAR_FILL)
                context.fill(segmentLeft, innerTop, filledRight, innerTop + 1, XP_BAR_HIGHLIGHT)
            }
            context.fill(segmentRight, innerTop, segmentRight + 1, innerTop + innerHeight, XP_BAR_DIVIDER)
        }
        context.drawCenteredTextWithShadow(textRenderer, label, left + width / 2, top - 1, XP_TEXT)
    }

    private fun drawVillageWealth(context: DrawContext, left: Int, top: Int) {
        context.drawItem(emeraldStack, left, top)
        context.drawText(textRenderer, handler.snapshot.villageWealth.toString(), left + 18, top + 5, LEDGER_TEXT, false)
    }

    private fun drawClerkShellTexture(context: DrawContext) {
        context.drawTexture(
            GUI_CLERK_SHELL,
            x,
            y - GUI_TAB_HEIGHT,
            0.0f,
            0.0f,
            TRADE_FULL_TEXTURE_WIDTH,
            TRADE_FULL_TEXTURE_HEIGHT,
            TRADE_FULL_TEXTURE_WIDTH,
            TRADE_FULL_TEXTURE_HEIGHT
        )
    }

    private fun drawBlankContentLayer(context: DrawContext) {
        context.fill(x + 4, y + 4, x + backgroundWidth - 4, y + backgroundHeight - 4, SCREEN_BG)
    }

    private fun drawTabs(context: DrawContext) {
        drawTabStateLayer(context, TAB_1_LEFT, activeTab == ActiveTab.TRADE, GUI_TRADE_TAB_SELECTED, GUI_TRADE_TAB_UNSELECTED)
        drawTabStateLayer(context, TAB_2_LEFT, activeTab == ActiveTab.BUILDINGS, GUI_TAB_SELECTED, GUI_TAB_UNSELECTED)
        drawTabStateLayer(context, TAB_3_LEFT, activeTab == ActiveTab.JOB_BLOCKS, GUI_TAB_SELECTED, GUI_TAB_UNSELECTED)
        drawTabStateLayer(context, TAB_4_LEFT, activeTab == ActiveTab.COMMISSIONS, GUI_TAB_SELECTED, GUI_TAB_UNSELECTED)
        drawTabStateLayer(context, TAB_5_LEFT, activeTab == ActiveTab.PICKUP, GUI_TAB_SELECTED, GUI_TAB_UNSELECTED)
        drawTabStateLayer(context, TAB_6_LEFT, activeTab == ActiveTab.INFO, GUI_TAB_SELECTED, GUI_TAB_UNSELECTED)
        drawTabIcon(context, TAB_1_LEFT, ItemStack(Items.BUNDLE), activeTab == ActiveTab.TRADE)
        drawTabIcon(context, TAB_2_LEFT, ItemStack(Items.OAK_DOOR), activeTab == ActiveTab.BUILDINGS)
        drawTabIcon(context, TAB_3_LEFT, ItemStack(Items.COMPOSTER), activeTab == ActiveTab.JOB_BLOCKS)
        drawTabIcon(context, TAB_4_LEFT, ItemStack(Items.SMITHING_TABLE), activeTab == ActiveTab.COMMISSIONS)
        drawTabIcon(context, TAB_5_LEFT, ItemStack(Items.CHEST), activeTab == ActiveTab.PICKUP)
        drawTabIcon(context, TAB_6_LEFT, ItemStack(Items.PAPER), activeTab == ActiveTab.INFO)
        drawLedgerBalanceTab(context, x + LEDGER_TAB_LEFT, y - GUI_TAB_HEIGHT, LEDGER_TAB_WIDTH, activeTab == ActiveTab.LEDGER)
    }

    private fun drawTabStateLayer(
        context: DrawContext,
        left: Int,
        selected: Boolean,
        selectedTexture: Identifier,
        unselectedTexture: Identifier
    ) {
        context.drawTexture(
            if (selected) selectedTexture else unselectedTexture,
            x + left,
            y - GUI_TAB_HEIGHT,
            0.0f,
            0.0f,
            TAB_TEXTURE_WIDTH,
            TAB_TEXTURE_HEIGHT,
            TAB_TEXTURE_WIDTH,
            TAB_TEXTURE_HEIGHT
        )
    }

    private fun drawTabIcon(context: DrawContext, left: Int, stack: ItemStack, selected: Boolean) {
        val top = y - GUI_TAB_HEIGHT
        context.drawItem(stack, x + left + (TAB_SMALL_WIDTH - 16) / 2, top + 2)
    }

    private fun drawLedgerBalanceTab(context: DrawContext, left: Int, top: Int, width: Int, selected: Boolean) {
        context.drawTexture(
            if (selected) GUI_LEDGER_TAB_SELECTED else GUI_LEDGER_TAB_UNSELECTED,
            left,
            top,
            0.0f,
            0.0f,
            LEDGER_TAB_TEXTURE_WIDTH,
            TAB_TEXTURE_HEIGHT,
            LEDGER_TAB_TEXTURE_WIDTH,
            TAB_TEXTURE_HEIGHT
        )
        context.drawItem(emeraldStack, left + 10, top + 3)
        context.drawText(textRenderer, handler.snapshot.playerEmeralds.toString(), left + 30, top + 7, LEDGER_TEXT, false)
    }

    private fun drawTabChrome(context: DrawContext, left: Int, top: Int, width: Int, selected: Boolean) {
        drawNineSlice(context, if (selected) GUI_TAB_SELECTED else GUI_TAB, left, top, width, 18, openBottom = selected)
        if (selected) {
            context.fill(left + 2, top + 16, left + width - 2, top + 20, SCREEN_BG)
            context.fill(left, top + 18, left + width, top + 20, SCREEN_BG)
        }
    }

    private fun drawLedgerTab(context: DrawContext) {
        val panelLeft = x + 38
        val panelTop = y + 36
        val panelWidth = backgroundWidth - 76
        val panelHeight = 54
        drawSunkenBox(context, panelLeft, panelTop, panelWidth, panelHeight)
        context.fill(panelLeft + 2, panelTop + 2, panelLeft + panelWidth - 2, panelTop + panelHeight - 2, PANEL_FILL)
        context.fill(x + backgroundWidth / 2 - 1, panelTop + 23, x + backgroundWidth / 2 + 1, panelTop + panelHeight - 5, DARK_EDGE)
        context.drawText(textRenderer, "Emerald Ledger", panelLeft + 14, panelTop + 7, TEXT_LIGHT, false)
        context.drawItem(emeraldStack, panelLeft + panelWidth - 92, panelTop + 4)
        context.drawText(textRenderer, handler.snapshot.playerEmeralds.toString(), panelLeft + panelWidth - 72, panelTop + 9, LEDGER_TEXT, false)
        context.drawText(textRenderer, "stored", panelLeft + panelWidth - 42, panelTop + 9, TEXT_LIGHT, false)
        context.drawText(textRenderer, "Deposit", x + 82, y + 57, TEXT_DARK, false)
        context.drawText(textRenderer, "Withdraw", x + 218, y + 57, TEXT_DARK, false)
        drawInventoryGrid(context, x + 109, y + 92)
    }

    private fun drawJobBlockTab(context: DrawContext) {
        drawPanel(context, x + 38, y + 50, 300, 92, jobBlockScrollOffset, maxJobBlockScroll(), JOB_BLOCK_VISIBLE_ROWS)
        context.drawText(textRenderer, "Authorized Job Blocks", x + 42, y + 38, TEXT_LIGHT, false)
        context.drawItem(emeraldStack, x + 262, y + 35)
        context.drawText(textRenderer, handler.snapshot.playerEmeralds.toString(), x + 280, y + 40, LEDGER_TEXT, false)
        context.drawText(textRenderer, trimToWidth(statusMessage.string, 190), x + 42, y + 151, TEXT_DARK, false)
    }

    private fun drawJobBlockRows(context: DrawContext) {
        var rowY = y + 54
        jobBlockOffers.drop(jobBlockScrollOffset).take(JOB_BLOCK_VISIBLE_ROWS).forEachIndexed { visibleIndex, offer ->
            val offerIndex = jobBlockScrollOffset + visibleIndex
            drawTradeRow(context, x + 42, rowY, 287, selectedJobBlockIndex == offerIndex)
            context.drawItem(offer.item, x + 44, rowY)
            context.drawText(textRenderer, trimToWidth(offer.professionName, 86), x + 65, rowY + 5, TEXT_LIGHT, false)
            context.drawText(textRenderer, trimToWidth(offer.displayName, 92), x + 135, rowY + 5, TEXT_DARK, false)
            context.drawItem(emeraldStack, x + 252, rowY)
            context.drawText(textRenderer, offer.emeraldCost.toString(), x + 270, rowY + 5, EMERALD_TEXT, false)
            rowY += 17
        }
    }

    private fun drawBuildingsTab(context: DrawContext) {
        drawPanel(context, BUILDING_PANEL_LEFT(), BUILDING_PANEL_TOP(), BUILDING_PANEL_WIDTH, BUILDING_PANEL_HEIGHT, buildingScrollOffset, maxBuildingScroll(), BUILDING_VISIBLE_ROWS)
        val selected = selectedBuildingPlot()
        if (selected != null) {
            context.drawText(textRenderer, trimToWidth(statusMessage.string, 190), x + 93, y + 69, TEXT_DARK, false)
        }
    }

    private fun drawBuildingRows(context: DrawContext) {
        var rowY = BUILDING_ROW_TOP()
        buildingPlots.drop(buildingScrollOffset).take(BUILDING_VISIBLE_ROWS).forEachIndexed { visibleIndex, plot ->
            val plotIndex = buildingScrollOffset + visibleIndex
            drawTradeRow(context, BUILDING_ROW_LEFT(), rowY, BUILDING_ROW_WIDTH, selectedBuildingPlotIndex == plotIndex)
            context.drawItem(plot.item, BUILDING_ROW_LEFT() + 2, rowY)
            context.drawText(textRenderer, trimToWidth(plot.displayName, 126), BUILDING_ROW_LEFT() + 23, rowY + 5, TEXT_LIGHT, false)
            val rightText = buildingRowStatus(plot)
            context.drawText(textRenderer, trimToWidth(rightText, 62), BUILDING_ROW_LEFT() + 208, rowY + 5, buildingStatusColor(plot), false)
            rowY += 17
        }
    }

    private fun drawPickupTab(context: DrawContext) {
        drawPanel(context, x + 38, y + 34, 300, 108, pickupScrollOffset, maxPickupScroll(), PICKUP_VISIBLE_ROWS)
        context.drawText(textRenderer, "Commission Pickup", x + 42, y + 22, TEXT_LIGHT, false)
        context.drawText(textRenderer, trimToWidth(statusMessage.string, 190), x + 42, y + 151, TEXT_DARK, false)
    }

    private fun drawPickupRows(context: DrawContext) {
        var rowY = y + 38
        filteredCommissionOrders.drop(pickupScrollOffset).take(PICKUP_VISIBLE_ROWS).forEachIndexed { visibleIndex, order ->
            val orderIndex = pickupScrollOffset + visibleIndex
            drawTradeRow(context, x + 42, rowY, 287, selectedCommissionOrderIndex == orderIndex)
            context.drawItem(order.item, x + 44, rowY)
            context.drawText(textRenderer, trimToWidth(order.displayName, 128), x + 65, rowY + 5, TEXT_LIGHT, false)
            context.drawText(textRenderer, trimToWidth(order.professionName, 62), x + 198, rowY + 5, TEXT_DARK, false)
            val status = pickupRowStatus(order)
            context.drawText(textRenderer, status, x + 264, rowY + 5, if (order.claimable) LEDGER_TEXT else TEXT_DARK, false)
            rowY += 17
        }
    }

    private fun drawCommissionsTab(context: DrawContext) {
        drawCommissionProfessionSelector(context)
        drawPanel(context, x + 277, y + 20, 96, 140, commissionScrollOffset, maxCommissionScroll(), COMMISSION_VISIBLE_ROWS)
        drawCommissionWorkArea(context)
        drawInventoryGrid(context, x + 109, y + 92)
    }

    private fun drawCommissionProfessionSelector(context: DrawContext) {
        drawPanel(context, x + 4, y + 20, 96, 140, 0, 0)
        var rowY = y + 24
        for ((professionId, professionName) in commissionProfessionOptions.take(COMMISSION_SELECTOR_MAX_OPTIONS)) {
            val selected = professionId == selectedCommissionProfession?.first
            drawTradeRow(context, x + 8, rowY, 83, selected)
            context.drawText(textRenderer, trimToWidth(professionName, 76), x + 12, rowY + 5, if (selected) TEXT_LIGHT else TEXT_DARK, false)
            rowY += 17
        }
    }

    private fun commissionMenuHint(professionId: String?): String = when (professionId) {
        "minecraft:armorer" -> "Submit armor for reforging or trim work"
        "minecraft:librarian" -> "Submit a book or enchantable item"
        "minecraft:cleric" -> "Select a potion batch size"
        "minecraft:weaponsmith" -> "Submit a weapon for repair or order work"
        "minecraft:toolsmith" -> "Submit a tool for repair or order work"
        else -> "Choose an artisan service"
    }

    private fun isSmithProfession(professionId: String?): Boolean =
        professionId == "minecraft:weaponsmith" || professionId == "minecraft:toolsmith"

    private fun isServiceProfession(professionId: String?): Boolean =
        isSmithProfession(professionId) || professionId == "minecraft:armorer"

    private fun isRepairCommissionOffer(offer: net.pavinical.sovereign.economy.CommissionOffer): Boolean =
        offer.quality == "Repair" || offer.id.endsWith("_repair")

    private fun repairCommissionOfferFor(professionId: String?): net.pavinical.sovereign.economy.CommissionOffer? =
        commissionOffers.firstOrNull { it.professionId == professionId && isRepairCommissionOffer(it) }

    private fun hasServiceInput(): Boolean =
        (0 until COMMISSION_INPUT_SLOT_COUNT_CLIENT).any { !handler.commissionInputStack(it).isEmpty }

    private fun serviceInputStack(): ItemStack =
        (0 until COMMISSION_INPUT_SLOT_COUNT_CLIENT)
            .map { handler.commissionInputStack(it) }
            .firstOrNull { !it.isEmpty }
            ?: ItemStack.EMPTY

    private fun armorerHasRightPanelSelection(): Boolean =
        selectedCommissionProfession?.first == "minecraft:armorer" &&
            (selectedArmorerArmorKey != null || selectedArmorerTrimKey != null || selectedArmorerMaterialKey != null)

    private fun canClientWorkServiceInput(professionId: String?): Boolean {
        val stack = serviceInputStack()
        if (stack.isEmpty) return false
        val requiredTier = when (professionId) {
            "minecraft:armorer" -> clientArmorWorkTier(stack)
            "minecraft:toolsmith" -> clientToolWorkTier(stack)
            "minecraft:weaponsmith" -> clientWeaponWorkTier(stack)
            else -> null
        } ?: return false
        return currentClientTierRank() >= requiredTier
    }

    private fun currentClientTierRank(): Int = when (handler.snapshot.tierName.lowercase()) {
        "city" -> 4
        "town" -> 3
        "village" -> 2
        "settlement" -> 1
        else -> 0
    }

    private fun clientArmorWorkTier(stack: ItemStack): Int? = when (stack.item) {
        Items.LEATHER_HELMET, Items.LEATHER_CHESTPLATE, Items.LEATHER_LEGGINGS, Items.LEATHER_BOOTS,
        Items.CHAINMAIL_HELMET, Items.CHAINMAIL_CHESTPLATE, Items.CHAINMAIL_LEGGINGS, Items.CHAINMAIL_BOOTS,
        Items.IRON_HELMET, Items.IRON_CHESTPLATE, Items.IRON_LEGGINGS, Items.IRON_BOOTS,
        Items.GOLDEN_HELMET, Items.GOLDEN_CHESTPLATE, Items.GOLDEN_LEGGINGS, Items.GOLDEN_BOOTS -> 2
        Items.DIAMOND_HELMET, Items.DIAMOND_CHESTPLATE, Items.DIAMOND_LEGGINGS, Items.DIAMOND_BOOTS -> 3
        Items.NETHERITE_HELMET, Items.NETHERITE_CHESTPLATE, Items.NETHERITE_LEGGINGS, Items.NETHERITE_BOOTS -> 4
        else -> null
    }

    private fun clientToolWorkTier(stack: ItemStack): Int? = when (stack.item) {
        Items.WOODEN_PICKAXE, Items.WOODEN_AXE, Items.WOODEN_SHOVEL, Items.WOODEN_HOE,
        Items.STONE_PICKAXE, Items.STONE_AXE, Items.STONE_SHOVEL, Items.STONE_HOE,
        Items.IRON_PICKAXE, Items.IRON_AXE, Items.IRON_SHOVEL, Items.IRON_HOE,
        Items.GOLDEN_PICKAXE, Items.GOLDEN_AXE, Items.GOLDEN_SHOVEL, Items.GOLDEN_HOE -> 2
        Items.DIAMOND_PICKAXE, Items.DIAMOND_AXE, Items.DIAMOND_SHOVEL, Items.DIAMOND_HOE -> 3
        Items.NETHERITE_PICKAXE, Items.NETHERITE_AXE, Items.NETHERITE_SHOVEL, Items.NETHERITE_HOE -> 4
        else -> null
    }

    private fun clientWeaponWorkTier(stack: ItemStack): Int? = when (stack.item) {
        Items.WOODEN_SWORD, Items.STONE_SWORD, Items.IRON_SWORD, Items.GOLDEN_SWORD,
        Items.WOODEN_AXE, Items.STONE_AXE, Items.IRON_AXE, Items.GOLDEN_AXE,
        Items.BOW, Items.CROSSBOW, Items.TRIDENT -> 2
        Items.DIAMOND_SWORD, Items.DIAMOND_AXE -> 3
        Items.NETHERITE_SWORD, Items.NETHERITE_AXE -> 4
        else -> null
    }

    private fun activeReforgeOffer(): net.pavinical.sovereign.economy.CommissionOffer? {
        val selected = selectedCommissionOfferIndex
            .takeIf { it in filteredCommissionOffers.indices }
            ?.let { filteredCommissionOffers[it] }
        if (selected != null) return selected
        val input = serviceInputStack()
        return filteredCommissionOffers.firstOrNull { !input.isEmpty && it.item.item == input.item }
            ?: filteredCommissionOffers.firstOrNull()
    }

    private fun drawCommissionWorkArea(context: DrawContext) {
        val professionId = selectedCommissionProfession?.first
        val selectedOffer = selectedCommissionOfferIndex.takeIf { it in filteredCommissionOffers.indices }
            ?.let { filteredCommissionOffers[it] }
        if (professionId == "minecraft:cleric") {
            drawPotionQuantitySelector(context)
            return
        }
        if (professionId == "minecraft:armorer") {
            drawArmorerWorkArea(context, selectedOffer)
            return
        }
        if (professionId == "minecraft:librarian") {
            drawLibrarianWorkArea(context, selectedOffer)
            return
        }
        if (isSmithProfession(professionId)) {
            drawSmithWorkArea(context, selectedOffer)
            return
        }
        val slotCount = commissionWorkSlotCount(professionId)
        val startX = x + 126
        for (slot in 0 until slotCount) {
            val slotX = startX + slot * 24
            val slotY = y + 44
            drawSlot(context, slotX, slotY)
            if (handler.commissionInputStack(slot).isEmpty) {
                ghostStackForWorkSlot(professionId, selectedOffer, slot)?.let { context.drawItem(it, slotX, slotY) }
            }
        }
    }

    private fun drawSmithWorkArea(context: DrawContext, selectedOffer: net.pavinical.sovereign.economy.CommissionOffer?) {
        val slotX = x + 179
        val slotY = y + 44
        val hasInput = hasServiceInput()
        drawSlot(context, slotX, slotY)
        if (!hasInput) {
            selectedOffer?.item?.let { context.drawItem(it, slotX, slotY) }
        }
    }

    private fun drawLibrarianWorkArea(context: DrawContext, selectedOffer: net.pavinical.sovereign.economy.CommissionOffer?) {
        val inputX = x + 166
        val enchantX = x + 190
        val slotY = y + 44
        drawSlot(context, inputX, slotY)
        if (handler.commissionInputStack(1).isEmpty) {
            ghostStackForWorkSlot("minecraft:librarian", selectedOffer, 0)?.let { context.drawItem(it, inputX, slotY) }
        }
        drawSlot(context, enchantX, slotY)
        ghostStackForWorkSlot("minecraft:librarian", selectedOffer, 1)?.let { context.drawItem(it, enchantX, slotY) }
    }

    private fun drawArmorerWorkArea(context: DrawContext, selectedOffer: net.pavinical.sovereign.economy.CommissionOffer?) {
        val leftX = x + 126
        val slotY = y + 44
        val resultX = x + 220
        val armorStandX = x + 244
        val armorStandY = y + 20
        context.drawText(textRenderer, ">", x + 204, y + 49, TEXT_DARK, false)
        for (slot in 0 until 3) {
            val slotX = leftX + slot * 24
            drawSlot(context, slotX, slotY)
            if (handler.commissionInputStack(slot).isEmpty) {
                ghostStackForWorkSlot("minecraft:armorer", selectedOffer, slot)?.let { context.drawItem(it, slotX, slotY) }
            }
        }
        drawSlot(context, resultX, slotY)
        val previewStack = armorerPreviewArmorStack()
        if (!previewStack.isEmpty) {
            context.drawItem(previewStack, resultX, slotY)
            drawArmorStandPreview(context, armorStandX, armorStandY, previewStack)
        } else {
            context.drawItem(ItemStack(Items.ARMOR_STAND), armorStandX + 8, armorStandY + 24)
        }
    }

    private fun selectedArmorerCheckoutTotal(): Int {
        val suppliedArmor = !handler.commissionInputStack(0).isEmpty
        val armorCost = if (suppliedArmor) 0 else selectedArmorerArmorOption()?.price ?: 0
        val trimCost = if (selectedArmorerTrimKey != null && selectedArmorerMaterialKey != null) {
            (selectedArmorerTrimOption()?.price ?: 0) + (selectedArmorerMaterialOption()?.price ?: 0)
        } else {
            0
        }
        return armorCost + trimCost
    }

    private fun drawPotionQuantitySelector(context: DrawContext) {
        val selectedOffer = selectedCommissionOfferIndex.takeIf { it in filteredCommissionOffers.indices }
            ?.let { filteredCommissionOffers[it] }

        val previewStack = selectedOffer?.let { commissionOfferDisplayStack(it) }
        if (previewStack != null && !previewStack.isEmpty) {
            drawSlot(context, x + 220, y + 15)
            context.drawItem(previewStack, x + 220, y + 15)
        }

        val trackLeft = x + 133
        val trackTop = y + 46
        val trackWidth = 108
        context.fill(trackLeft, trackTop + 5, trackLeft + trackWidth, trackTop + 8, DARK_EDGE)
        context.fill(trackLeft, trackTop + 4, trackLeft + trackWidth, trackTop + 5, LIGHT_EDGE)
        for (step in 1..5) {
            val tickX = potionSliderTickX(step)
            context.fill(tickX, trackTop + 2, tickX + 1, trackTop + 11, DARK_EDGE)
        }
        val knobX = potionSliderTickX(selectedPotionQuantity) - 5
        drawTradeRow(context, knobX, trackTop - 2, 11, true)
        context.drawCenteredTextWithShadow(textRenderer, selectedPotionQuantity.toString(), knobX + 5, trackTop + 1, TEXT_LIGHT)
    }

    private fun drawHammerRepairButton(context: DrawContext, left: Int, top: Int) {
        drawTradeRow(context, left, top, 22, true)
        context.drawText(textRenderer, "*", left + 8, top + 6, TEXT_LIGHT, false)
    }

    private fun potionSliderTickX(quantity: Int): Int {
        val trackLeft = x + 133
        val trackWidth = 108
        return trackLeft + ((quantity.coerceIn(1, 5) - 1) * trackWidth) / 4
    }

    private fun updatePotionQuantityFromMouse(mouseX: Int) {
        val trackLeft = x + 133
        val trackWidth = 108
        val relative = (mouseX - trackLeft).coerceIn(0, trackWidth)
        selectedPotionQuantity = ((relative * 4 + trackWidth / 2) / trackWidth + 1).coerceIn(1, 5)
        statusMessage = Text.literal("Potion batch x$selectedPotionQuantity")
    }

    private fun commissionOfferDisplayStack(offer: net.pavinical.sovereign.economy.CommissionOffer): ItemStack {
        if (offer.professionId != "minecraft:cleric") return offer.item
        val potionId = clericPotionId(offer.id) ?: return offer.item
        return PotionContentsComponent.createStack(offer.item.item, clientPotionEntry(potionId))
    }

    private fun clericPotionId(offerId: String): String? = when (offerId) {
        "cleric_healing_order" -> "healing"
        "cleric_strong_healing_order" -> "strong_healing"
        "cleric_swiftness_order" -> "swiftness"
        "cleric_strong_swiftness_order" -> "strong_swiftness"
        "cleric_regeneration_order" -> "regeneration"
        "cleric_strength_order" -> "strong_strength"
        "cleric_fire_resistance_order" -> "fire_resistance"
        "cleric_water_breathing_order" -> "water_breathing"
        else -> null
    }

    private fun clientPotionEntry(potionId: String): RegistryEntry<Potion> = when (potionId) {
        "healing" -> Potions.HEALING
        "strong_healing" -> Potions.STRONG_HEALING
        "swiftness" -> Potions.SWIFTNESS
        "strong_swiftness" -> Potions.STRONG_SWIFTNESS
        "regeneration" -> Potions.REGENERATION
        "strong_strength" -> Potions.STRONG_STRENGTH
        "fire_resistance" -> Potions.FIRE_RESISTANCE
        "water_breathing" -> Potions.WATER_BREATHING
        else -> Potions.HEALING
    }

    private fun drawArmorStandPreview(context: DrawContext, left: Int, top: Int, armorStack: ItemStack) {
        val world = client?.world ?: return
        val stand = ArmorStandEntity(world, 0.0, 0.0, 0.0)
        stand.setShowArms(true)
        stand.setHideBasePlate(true)
        stand.equipStack(equipmentSlotForArmor(armorStack), armorStack.copyWithCount(1))
        InventoryScreen.drawEntity(context, left, top, left + 28, top + 44, 17, 0.0f, 0.0f, 0.0f, stand)
    }

    private fun armorerPreviewArmorStack(): ItemStack {
        val stack = selectedArmorerArmorOption()?.stack?.copyWithCount(1) ?: return ItemStack.EMPTY
        val trimKey = selectedArmorerTrimKey
        val materialKey = selectedArmorerMaterialKey
        if (trimKey != null && materialKey != null) {
            val world = client?.world ?: return stack
            val pattern = world.registryManager.getWrapperOrThrow(RegistryKeys.TRIM_PATTERN).getOrThrow(clientPatternKeyFor(trimKey))
            val material = world.registryManager.getWrapperOrThrow(RegistryKeys.TRIM_MATERIAL).getOrThrow(clientMaterialKeyFor(materialKey))
            stack.set(DataComponentTypes.TRIM, ArmorTrim(material, pattern))
        }
        return stack
    }

    private fun equipmentSlotForArmor(stack: ItemStack): EquipmentSlot = when (stack.item) {
        Items.LEATHER_HELMET, Items.CHAINMAIL_HELMET, Items.IRON_HELMET, Items.GOLDEN_HELMET, Items.DIAMOND_HELMET, Items.NETHERITE_HELMET -> EquipmentSlot.HEAD
        Items.LEATHER_LEGGINGS, Items.CHAINMAIL_LEGGINGS, Items.IRON_LEGGINGS, Items.GOLDEN_LEGGINGS, Items.DIAMOND_LEGGINGS, Items.NETHERITE_LEGGINGS -> EquipmentSlot.LEGS
        Items.LEATHER_BOOTS, Items.CHAINMAIL_BOOTS, Items.IRON_BOOTS, Items.GOLDEN_BOOTS, Items.DIAMOND_BOOTS, Items.NETHERITE_BOOTS -> EquipmentSlot.FEET
        else -> EquipmentSlot.CHEST
    }

    private fun clientPatternKeyFor(key: String): RegistryKey<net.minecraft.item.trim.ArmorTrimPattern> = when (key) {
        "sentry" -> ArmorTrimPatterns.SENTRY
        "dune" -> ArmorTrimPatterns.DUNE
        "coast" -> ArmorTrimPatterns.COAST
        "wild" -> ArmorTrimPatterns.WILD
        "ward" -> ArmorTrimPatterns.WARD
        "eye" -> ArmorTrimPatterns.EYE
        "vex" -> ArmorTrimPatterns.VEX
        "tide" -> ArmorTrimPatterns.TIDE
        "snout" -> ArmorTrimPatterns.SNOUT
        "rib" -> ArmorTrimPatterns.RIB
        "spire" -> ArmorTrimPatterns.SPIRE
        "wayfinder" -> ArmorTrimPatterns.WAYFINDER
        "shaper" -> ArmorTrimPatterns.SHAPER
        "silence" -> ArmorTrimPatterns.SILENCE
        "raiser" -> ArmorTrimPatterns.RAISER
        "host" -> ArmorTrimPatterns.HOST
        "flow" -> ArmorTrimPatterns.FLOW
        "bolt" -> ArmorTrimPatterns.BOLT
        else -> ArmorTrimPatterns.COAST
    }

    private fun clientMaterialKeyFor(key: String): RegistryKey<net.minecraft.item.trim.ArmorTrimMaterial> = when (key) {
        "quartz" -> ArmorTrimMaterials.QUARTZ
        "iron" -> ArmorTrimMaterials.IRON
        "redstone" -> ArmorTrimMaterials.REDSTONE
        "copper" -> ArmorTrimMaterials.COPPER
        "gold" -> ArmorTrimMaterials.GOLD
        "emerald" -> ArmorTrimMaterials.EMERALD
        "diamond" -> ArmorTrimMaterials.DIAMOND
        "lapis" -> ArmorTrimMaterials.LAPIS
        else -> ArmorTrimMaterials.GOLD
    }

    private fun commissionInputSlotCount(professionId: String?): Int = when (professionId) {
        "minecraft:armorer",
        "minecraft:librarian",
        "minecraft:weaponsmith",
        "minecraft:toolsmith" -> 1
        else -> 0
    }

    private fun commissionFirstInputSlot(professionId: String?): Int = when (professionId) {
        "minecraft:librarian" -> 1
        "minecraft:weaponsmith",
        "minecraft:toolsmith" -> 2
        else -> 0
    }

    private fun commissionWorkSlotCount(professionId: String?): Int = when (professionId) {
        "minecraft:armorer" -> 3
        "minecraft:librarian" -> 2
        "minecraft:weaponsmith",
        "minecraft:toolsmith" -> 1
        "minecraft:cleric" -> 0
        else -> 1
    }

    private fun commissionWorkSlotLabels(professionId: String?): List<String> = when (professionId) {
        "minecraft:armorer" -> listOf("Armor", "Trim template", "Trim material")
        "minecraft:librarian" -> listOf("Item", "Enchant")
        "minecraft:weaponsmith" -> listOf("Weapon repair")
        "minecraft:toolsmith" -> listOf("Tool repair")
        else -> listOf("Order")
    }

    private fun ghostStackForWorkSlot(professionId: String?, offer: net.pavinical.sovereign.economy.CommissionOffer?, slot: Int): ItemStack? = when (professionId) {
        "minecraft:armorer" -> when (slot) {
            0 -> selectedArmorerArmorOption()?.stack
            1 -> selectedArmorerTrimOption()?.stack
            2 -> selectedArmorerMaterialOption()?.stack
            else -> null
        }
        "minecraft:librarian" -> if (slot == 1) offer?.item else null
        "minecraft:cleric" -> offer?.item?.takeIf { slot == 0 }
        else -> null
    }

    private fun drawCommissionRows(context: DrawContext) {
        if (selectedCommissionProfession?.first == "minecraft:armorer") {
            drawArmorerOptionRows(context)
            return
        }
        var rowY = y + 24
        val total = totalCommissionRows()
        val start = commissionScrollOffset
        val end = (start + COMMISSION_VISIBLE_ROWS).coerceAtMost(total)
        for (rowIndex in start until end) {
            val offer = filteredCommissionOffers[rowIndex]
            drawTradeRow(context, x + 282, rowY, 83, selectedCommissionOfferIndex == rowIndex)
            context.drawItem(commissionOfferDisplayStack(offer), x + 284, rowY)
            context.drawItem(emeraldStack, x + 321, rowY)
            context.drawText(textRenderer, offer.emeraldCost.toString(), x + 339, rowY + 5, EMERALD_TEXT, false)
            rowY += 17
        }
    }

    private fun drawArmorerOptionRows(context: DrawContext) {
        var rowY = y + 24
        val options = armorerOptions().drop(commissionScrollOffset).take(COMMISSION_VISIBLE_ROWS)
        for (option in options) {
            drawTradeRow(context, x + 282, rowY, 83, isArmorerOptionSelected(option))
            context.drawItem(option.stack, x + 284, rowY)
            option.price?.let { price ->
                context.drawItem(emeraldStack, x + 321, rowY)
                context.drawText(textRenderer, price.toString(), x + 339, rowY + 5, EMERALD_TEXT, false)
            }
            rowY += 17
        }
    }

    private fun trimTemplateForOffer(offer: net.pavinical.sovereign.economy.CommissionOffer): ItemStack {
        val name = offer.displayName.lowercase()
        val item = when {
            "sentry" in name -> Items.SENTRY_ARMOR_TRIM_SMITHING_TEMPLATE
            "dune" in name -> Items.DUNE_ARMOR_TRIM_SMITHING_TEMPLATE
            "coast" in name -> Items.COAST_ARMOR_TRIM_SMITHING_TEMPLATE
            "wild" in name -> Items.WILD_ARMOR_TRIM_SMITHING_TEMPLATE
            "ward" in name -> Items.WARD_ARMOR_TRIM_SMITHING_TEMPLATE
            "eye" in name -> Items.EYE_ARMOR_TRIM_SMITHING_TEMPLATE
            "vex" in name -> Items.VEX_ARMOR_TRIM_SMITHING_TEMPLATE
            "tide" in name -> Items.TIDE_ARMOR_TRIM_SMITHING_TEMPLATE
            "snout" in name -> Items.SNOUT_ARMOR_TRIM_SMITHING_TEMPLATE
            "rib" in name -> Items.RIB_ARMOR_TRIM_SMITHING_TEMPLATE
            "spire" in name -> Items.SPIRE_ARMOR_TRIM_SMITHING_TEMPLATE
            "wayfinder" in name -> Items.WAYFINDER_ARMOR_TRIM_SMITHING_TEMPLATE
            "shaper" in name -> Items.SHAPER_ARMOR_TRIM_SMITHING_TEMPLATE
            "silence" in name -> Items.SILENCE_ARMOR_TRIM_SMITHING_TEMPLATE
            "raiser" in name -> Items.RAISER_ARMOR_TRIM_SMITHING_TEMPLATE
            "host" in name -> Items.HOST_ARMOR_TRIM_SMITHING_TEMPLATE
            "flow" in name -> Items.FLOW_ARMOR_TRIM_SMITHING_TEMPLATE
            "bolt" in name -> Items.BOLT_ARMOR_TRIM_SMITHING_TEMPLATE
            else -> Items.COAST_ARMOR_TRIM_SMITHING_TEMPLATE
        }
        return ItemStack(item)
    }

    private fun armorerOptions(): List<ArmorerOption> {
        val armorOptions = filteredCommissionOffers
            .mapIndexedNotNull { index, offer ->
                if (offer.quality == "Trim") null else ArmorerOption(
                    kind = ArmorerOptionKind.ARMOR,
                    key = offer.id,
                    stack = offer.item,
                    label = offer.displayName,
                    offerIndex = index,
                    price = offer.emeraldCost
                )
            }
        val trimOptions = filteredCommissionOffers
            .filter { it.quality == "Trim" }
            .distinctBy { trimTemplateKey(it) }
            .map { offer ->
                ArmorerOption(
                    kind = ArmorerOptionKind.TRIM,
                    key = trimTemplateKey(offer),
                    stack = trimTemplateForOffer(offer),
                    label = trimTemplateLabel(offer),
                    price = trimAddonPrice(offer)
                )
            }
        val materialOptions = filteredCommissionOffers
            .filter { it.quality == "Trim" }
            .distinctBy { trimMaterialKey(it) }
            .map { offer ->
                ArmorerOption(
                    kind = ArmorerOptionKind.MATERIAL,
                    key = trimMaterialKey(offer),
                    stack = trimMaterialForOffer(offer),
                    label = trimMaterialLabel(offer),
                    price = materialAddonPrice(offer)
                )
            }
        return armorOptions + trimOptions + materialOptions
    }

    private fun armorerOptionAt(mouseX: Int, mouseY: Int): ArmorerOption? {
        val row = hitTest(mouseX, mouseY, x + 282, x + 365, y + 24, 17, visibleCommissionCount())
            ?: return null
        return armorerOptions().getOrNull(row + commissionScrollOffset)
    }

    private fun selectArmorerOption(option: ArmorerOption) {
        when (option.kind) {
            ArmorerOptionKind.ARMOR -> {
                selectedArmorerArmorKey = option.key
                selectedCommissionOfferIndex = option.offerIndex ?: -1
                selectedCommissionOfferKey = option.offerIndex?.let { filteredCommissionOffers.getOrNull(it)?.id }
            }
            ArmorerOptionKind.TRIM -> {
                selectedArmorerTrimKey = option.key
                selectMatchingArmorerTrimOffer()
            }
            ArmorerOptionKind.MATERIAL -> {
                selectedArmorerMaterialKey = option.key
                selectMatchingArmorerTrimOffer()
            }
        }
    }

    private fun selectMatchingArmorerTrimOffer() {
        val trimKey = selectedArmorerTrimKey
        val materialKey = selectedArmorerMaterialKey
        if (trimKey == null || materialKey == null) {
            selectedCommissionOfferIndex = -1
            selectedCommissionOfferKey = null
            return
        }
        val index = filteredCommissionOffers.indexOfFirst { offer -> isMatchingArmorerTrimOffer(offer, trimKey, materialKey) }
        if (index >= 0) {
            selectedCommissionOfferIndex = index
            selectedCommissionOfferKey = filteredCommissionOffers[index].id
        } else {
            selectedCommissionOfferIndex = -1
            selectedCommissionOfferKey = null
        }
    }

    private fun matchingArmorerTrimOffer(trimKey: String?, materialKey: String?): net.pavinical.sovereign.economy.CommissionOffer? {
        if (trimKey == null || materialKey == null) return null
        return filteredCommissionOffers.firstOrNull { offer -> isMatchingArmorerTrimOffer(offer, trimKey, materialKey) }
    }

    private fun isMatchingArmorerTrimOffer(
        offer: net.pavinical.sovereign.economy.CommissionOffer,
        trimKey: String,
        materialKey: String
    ): Boolean =
        offer.quality == "Trim" && trimTemplateKey(offer) == trimKey && trimMaterialKey(offer) == materialKey

    private fun selectedArmorerArmorOption(): ArmorerOption? =
        armorerOptions().firstOrNull { it.kind == ArmorerOptionKind.ARMOR && it.key == selectedArmorerArmorKey }

    private fun selectedArmorerTrimOption(): ArmorerOption? =
        armorerOptions().firstOrNull { it.kind == ArmorerOptionKind.TRIM && it.key == selectedArmorerTrimKey }

    private fun selectedArmorerMaterialOption(): ArmorerOption? =
        armorerOptions().firstOrNull { it.kind == ArmorerOptionKind.MATERIAL && it.key == selectedArmorerMaterialKey }

    private fun isArmorerOptionSelected(option: ArmorerOption): Boolean = when (option.kind) {
        ArmorerOptionKind.ARMOR -> option.key == selectedArmorerArmorKey
        ArmorerOptionKind.TRIM -> option.key == selectedArmorerTrimKey
        ArmorerOptionKind.MATERIAL -> option.key == selectedArmorerMaterialKey
    }

    private fun trimMaterialForOffer(offer: net.pavinical.sovereign.economy.CommissionOffer): ItemStack {
        val name = offer.displayName.lowercase()
        val item = when {
            "quartz" in name -> Items.QUARTZ
            "iron" in name -> Items.IRON_INGOT
            "redstone" in name -> Items.REDSTONE
            "copper" in name -> Items.COPPER_INGOT
            "gold" in name -> Items.GOLD_INGOT
            "emerald" in name -> Items.EMERALD
            "diamond" in name -> Items.DIAMOND
            "lapis" in name -> Items.LAPIS_LAZULI
            else -> Items.GOLD_INGOT
        }
        return ItemStack(item)
    }

    private fun trimTemplateKey(offer: net.pavinical.sovereign.economy.CommissionOffer): String {
        val name = offer.displayName.lowercase()
        return when {
            "sentry" in name -> "sentry"
            "dune" in name -> "dune"
            "coast" in name -> "coast"
            "wild" in name -> "wild"
            "ward" in name -> "ward"
            "eye" in name -> "eye"
            "vex" in name -> "vex"
            "tide" in name -> "tide"
            "snout" in name -> "snout"
            "rib" in name -> "rib"
            "spire" in name -> "spire"
            "wayfinder" in name -> "wayfinder"
            "shaper" in name -> "shaper"
            "silence" in name -> "silence"
            "raiser" in name -> "raiser"
            "host" in name -> "host"
            "flow" in name -> "flow"
            "bolt" in name -> "bolt"
            else -> "coast"
        }
    }

    private fun trimMaterialKey(offer: net.pavinical.sovereign.economy.CommissionOffer): String {
        val name = offer.displayName.lowercase()
        return when {
            "quartz" in name -> "quartz"
            "iron" in name -> "iron"
            "redstone" in name -> "redstone"
            "copper" in name -> "copper"
            "gold" in name -> "gold"
            "emerald" in name -> "emerald"
            "diamond" in name -> "diamond"
            "lapis" in name -> "lapis"
            else -> "gold"
        }
    }

    private fun trimTemplateLabel(offer: net.pavinical.sovereign.economy.CommissionOffer): String =
        trimTemplateKey(offer).replaceFirstChar { it.uppercase() }

    private fun trimMaterialLabel(offer: net.pavinical.sovereign.economy.CommissionOffer): String =
        trimMaterialKey(offer).replaceFirstChar { it.uppercase() }

    private fun trimAddonPrice(offer: net.pavinical.sovereign.economy.CommissionOffer): Int =
        200

    private fun materialAddonPrice(offer: net.pavinical.sovereign.economy.CommissionOffer): Int = when (trimMaterialKey(offer)) {
        "quartz" -> 80
        "iron" -> 70
        "redstone" -> 70
        "copper" -> 60
        "gold" -> 90
        "emerald" -> 110
        "diamond" -> 160
        "lapis" -> 80
        else -> 90
    }

    private fun drawInfoTab(context: DrawContext) {
        val info = handler.snapshot.info
        val panelLeft = x + 24
        val panelTop = y + 42
        val panelWidth = backgroundWidth - 48
        val panelHeight = 112
        drawSunkenBox(context, panelLeft, panelTop, panelWidth, panelHeight)
        context.fill(panelLeft + 2, panelTop + 2, panelLeft + panelWidth - 2, panelTop + panelHeight - 2, PANEL_FILL)

        val leftX = panelLeft + 12
        val rightX = panelLeft + 190
        var leftY = panelTop + 10
        drawInfoLine(context, "Tier: ${info.tierName}", leftX, leftY, TEXT_LIGHT, 168)
        leftY += 12
        drawInfoLine(context, "Pop: ${info.population} (${info.activeVillagers} active)", leftX, leftY, TEXT_LIGHT, 168)
        leftY += 12
        drawInfoLine(context, "Missing: ${info.missingVillagers}  Dead: ${info.deceasedVillagers}", leftX, leftY, TEXT_DARK, 168)
        leftY += 12
        drawInfoLine(context, "Slots: ${info.occupiedSlots}/${info.totalSlots}", leftX, leftY, TEXT_LIGHT, 168)
        leftY += 12
        val storageLabel = if (info.storageCapacity > 0 && info.storageUsed >= info.storageCapacity) {
            "Storage: ${info.storageUsed}/${info.storageCapacity} Full"
        } else {
            "Storage: ${info.storageUsed}/${info.storageCapacity}"
        }
        drawInfoLine(context, storageLabel, leftX, leftY, if (info.storageCapacity > 0 && info.storageUsed >= info.storageCapacity) TOOLTIP_EMPTY else TEXT_LIGHT, 168)

        var rightY = panelTop + 10
        context.drawText(textRenderer, "Professions", rightX, rightY, TEXT_LIGHT, false)
        rightY += 12
        val professionLines = if (info.professionLines.isEmpty()) listOf("No workers scanned") else info.professionLines
        professionLines.take(5).forEach { line ->
            drawInfoLine(context, line, rightX, rightY, TEXT_DARK, 122)
            rightY += 10
        }

        var slotY = panelTop + 76
        context.drawText(textRenderer, "Slot Groups", rightX, slotY, TEXT_LIGHT, false)
        slotY += 12
        val slotLines = if (info.slotLines.isEmpty()) listOf("No slots registered") else info.slotLines
        slotLines.take(2).forEach { line ->
            drawInfoLine(context, line.replace(" filled", ""), rightX, slotY, TEXT_DARK, 122)
            slotY += 10
        }
    }

    private fun drawInfoLine(context: DrawContext, text: String, left: Int, top: Int, color: Int, maxWidth: Int) {
        context.drawText(textRenderer, trimToWidth(text, maxWidth), left, top, color, false)
    }

    private fun drawHoverInfo(context: DrawContext, mouseX: Int, mouseY: Int) {
        val lines = hoverInfoLines(mouseX, mouseY) ?: return
        drawTooltipLines(context, mouseX, mouseY, lines)
    }

    private fun drawCommissionHoverInfo(context: DrawContext, mouseX: Int, mouseY: Int) {
        val lines = commissionHoverInfoLines(mouseX, mouseY) ?: return
        drawTooltipLines(context, mouseX, mouseY, lines)
    }

    private fun drawPickupHoverInfo(context: DrawContext, mouseX: Int, mouseY: Int) {
        val lines = pickupHoverInfoLines(mouseX, mouseY) ?: return
        drawTooltipLines(context, mouseX, mouseY, lines)
    }

    private fun drawBuildingHoverInfo(context: DrawContext, mouseX: Int, mouseY: Int) {
        val lines = buildingHoverInfoLines(mouseX, mouseY) ?: return
        drawTooltipLines(context, mouseX, mouseY, lines)
    }

    private fun drawTooltipLines(context: DrawContext, mouseX: Int, mouseY: Int, lines: List<TooltipLine>) {
        val width = lines.maxOf { textRenderer.getWidth(it.text) } + 10
        val height = lines.size * 10 + 8
        val left = tooltipLeft(mouseX, width)
        val top = (mouseY + 12).coerceAtMost(y + backgroundHeight - height - 4)

        val matrices = context.matrices
        matrices.push()
        matrices.translate(0.0f, 0.0f, TOOLTIP_Z)
        context.fill(left, top, left + width, top + height, TOOLTIP_BG)
        drawBorder(context, left, top, width, height, TOOLTIP_BORDER)
        lines.forEachIndexed { index, line ->
            context.drawText(textRenderer, line.text, left + 5, top + 5 + index * 10, line.color, false)
        }
        matrices.pop()
    }

    private fun commissionHoverInfoLines(mouseX: Int, mouseY: Int): List<TooltipLine>? {
        commissionButtonHoverInfoLines(mouseX, mouseY)?.let { return it }

        val professionIndex = hitTest(mouseX, mouseY, x + 10, x + 94, y + 24, 17, commissionProfessionOptions.size.coerceAtMost(COMMISSION_SELECTOR_MAX_OPTIONS))
        if (professionIndex != null) {
            val (_, name) = commissionProfessionOptions[professionIndex]
            return listOf(TooltipLine(name, TOOLTIP_TITLE), TooltipLine(commissionMenuHint(commissionProfessionOptions[professionIndex].first), TOOLTIP_DETAIL))
        }

        if (selectedCommissionProfession?.first == "minecraft:armorer") {
            val option = armorerOptionAt(mouseX, mouseY)
            if (option != null) {
                val offer = option.offerIndex?.let { filteredCommissionOffers.getOrNull(it) }
                return if (offer != null) {
                    listOf(
                        TooltipLine(offer.displayName, TOOLTIP_TITLE),
                        TooltipLine(offer.description, TOOLTIP_DETAIL),
                        TooltipLine("${offer.emeraldCost} emeralds", TOOLTIP_PRICE),
                        TooltipLine(formatRestockTime(offer.durationTicks), TOOLTIP_TIMER)
                    )
                } else {
                    listOf(
                        TooltipLine(option.label, TOOLTIP_TITLE),
                        TooltipLine(
                            if (option.kind == ArmorerOptionKind.TRIM) "Trim template" else "Trim material",
                            TOOLTIP_DETAIL
                        )
                    )
                }
            }
        }

        val rowIndex = hitTest(mouseX, mouseY, x + 282, x + 365, y + 24, 17, visibleCommissionCount())
            ?.let { it + commissionScrollOffset }
            ?.takeIf { it in 0 until totalCommissionRows() }
        if (rowIndex != null) {
            val offer = filteredCommissionOffers[rowIndex]
            return listOf(
                TooltipLine(offer.displayName, TOOLTIP_TITLE),
                TooltipLine(offer.description, TOOLTIP_DETAIL),
                TooltipLine("${offer.emeraldCost} emeralds", TOOLTIP_PRICE),
                TooltipLine(formatRestockTime(offer.durationTicks), TOOLTIP_TIMER)
            )
        }

        val slotIndex = if (selectedCommissionProfession?.first == "minecraft:librarian") {
            when {
                mouseY in (y + 44)..(y + 62) && mouseX in (x + 166)..(x + 184) -> 0
                mouseY in (y + 44)..(y + 62) && mouseX in (x + 190)..(x + 208) -> 1
                else -> null
            }
        } else if (isSmithProfession(selectedCommissionProfession?.first)) {
            if (mouseY in (y + 44)..(y + 62) && mouseX in (x + 179)..(x + 197)) 0 else null
        } else if (
            mouseY in (y + 44)..(y + 62) &&
            mouseX in (x + 126)..(x + 126 + commissionWorkSlotCount(selectedCommissionProfession?.first) * 24)
        ) {
            ((mouseX - (x + 126)) / 24).takeIf { it in 0 until commissionWorkSlotCount(selectedCommissionProfession?.first) }
        } else {
            null
        }
        if (slotIndex != null) {
            val slotName = commissionWorkSlotLabels(selectedCommissionProfession?.first).getOrElse(slotIndex) { "Work item" }
            return listOf(TooltipLine(slotName, TOOLTIP_TITLE), TooltipLine(commissionMenuHint(selectedCommissionProfession?.first), TOOLTIP_DETAIL))
        }

        if (selectedCommissionProfession?.first == "minecraft:cleric" && hitTestPanel(mouseX, mouseY, x + 131, y + 41, 112, 18)) {
            return listOf(
                TooltipLine("Potion quantity", TOOLTIP_TITLE),
                TooltipLine("Batch multiplier: $selectedPotionQuantity", TOOLTIP_DETAIL),
                TooltipLine("${currentCommissionOrderCost()} emeralds total", TOOLTIP_PRICE)
            )
        }

        return null
    }

    private fun commissionButtonHoverInfoLines(mouseX: Int, mouseY: Int): List<TooltipLine>? {
        if (activeTab != ActiveTab.COMMISSIONS) return null
        val professionId = selectedCommissionProfession?.first
        if (isServiceProfession(professionId) && hitTestPanel(mouseX, mouseY, x + 105, y + 66, 50, 18)) {
            val offer = repairCommissionOfferFor(selectedCommissionProfession?.first)
            return listOf(
                TooltipLine("Repair", TOOLTIP_TITLE),
                TooltipLine("Restore the item in the work slot", TOOLTIP_DETAIL),
                TooltipLine("${offer?.emeraldCost ?: 0} emeralds", TOOLTIP_PRICE)
            )
        }
        if (isServiceProfession(professionId) && hitTestPanel(mouseX, mouseY, x + 163, y + 66, 50, 18)) {
            val offer = activeReforgeOffer()
            val cost = offer?.emeraldCost?.let { (it - ORDER_PRODUCTION_PREMIUM_CLIENT).coerceIn(200, 400) } ?: 0
            val detail = if (hasServiceInput()) {
                "Apply ${offer?.quality ?: "artisan"} work to the item"
            } else {
                "Place an item in the work slot first"
            }
            return listOf(
                TooltipLine("Reforge", TOOLTIP_TITLE),
                TooltipLine(detail, TOOLTIP_DETAIL),
                TooltipLine("$cost emeralds", TOOLTIP_PRICE)
            )
        }
        val orderButtonX = if (isServiceProfession(professionId)) x + 221 else x + 159
        val orderButtonY = if (professionId == "minecraft:cleric") y + 63 else y + 66
        if (hitTestPanel(mouseX, mouseY, orderButtonX, orderButtonY, 50, 18)) {
            return listOf(
                TooltipLine("Order", TOOLTIP_TITLE),
                TooltipLine(orderCommissionTooltipDetail(professionId), TOOLTIP_DETAIL),
                TooltipLine("${currentCommissionOrderCost()} emeralds total", TOOLTIP_PRICE)
            )
        }
        return null
    }

    private fun orderCommissionTooltipDetail(professionId: String?): String = when {
        professionId == "minecraft:cleric" -> "Commission $selectedPotionQuantity potion${if (selectedPotionQuantity == 1) "" else "s"}"
        professionId == "minecraft:armorer" -> "Commission the selected armor work"
        isSmithProfession(professionId) -> "Commission the selected tool or weapon"
        professionId == "minecraft:librarian" -> "Commission the selected enchantment"
        else -> "Commission the selected artisan work"
    }

    private fun currentCommissionOrderCost(): Int {
        val selectedOffer = selectedCommissionOfferIndex.takeIf { it in filteredCommissionOffers.indices }
            ?.let { filteredCommissionOffers[it] }
        return when (selectedCommissionProfession?.first) {
            "minecraft:armorer" -> selectedArmorerCheckoutTotal()
            "minecraft:cleric" -> selectedOffer?.emeraldCost?.saturatingMultiplyClient(selectedPotionQuantity) ?: 0
            else -> selectedOffer?.emeraldCost ?: 0
        }
    }

    private fun pickupHoverInfoLines(mouseX: Int, mouseY: Int): List<TooltipLine>? {
        val orderIndex = hitTest(mouseX, mouseY, x + 42, x + 329, y + 38, 17, visiblePickupCount())
            ?.let { it + pickupScrollOffset }
            ?.takeIf { it in filteredCommissionOrders.indices }
            ?: return null
        val order = filteredCommissionOrders[orderIndex]
        return listOf(
            TooltipLine(order.displayName, TOOLTIP_TITLE),
            TooltipLine(pickupStatus(order), if (order.claimable) LEDGER_TEXT else TOOLTIP_TIMER),
            TooltipLine("${order.emeraldCost} emeralds paid", TOOLTIP_DETAIL),
            TooltipLine(order.professionName, TOOLTIP_DETAIL)
        )
    }

    private fun buildingHoverInfoLines(mouseX: Int, mouseY: Int): List<TooltipLine>? {
        if (hitTestPanel(mouseX, mouseY, x + 99, y + 46, 50, 18)) {
            val plot = selectedBuildingPlot()
            return listOf(
                TooltipLine("Build", TOOLTIP_TITLE),
                TooltipLine(plot?.let { buildingActionDetail(it) } ?: "Choose an empty plot", TOOLTIP_DETAIL),
                TooltipLine(if ((plot?.plotIndex ?: 0) <= 0) "Gives a plot marker" else "${plot?.buildCost ?: 0} emeralds", TOOLTIP_PRICE)
            )
        }
        if (hitTestPanel(mouseX, mouseY, x + 155, y + 46, 58, 18)) {
            val plot = selectedBuildingPlot()
            return listOf(
                TooltipLine("Upgrade", TOOLTIP_TITLE),
                TooltipLine(plot?.let { upgradeActionDetail(it) } ?: "Choose a built house", TOOLTIP_DETAIL),
                TooltipLine("${plot?.upgradeCost ?: 0} emeralds", TOOLTIP_PRICE)
            )
        }
        if (hitTestPanel(mouseX, mouseY, x + 219, y + 46, 62, 18)) {
            val plot = selectedBuildingPlot()
            return listOf(
                TooltipLine("Relocate", TOOLTIP_TITLE),
                TooltipLine(plot?.let { relocateActionDetail(it) } ?: "Choose an empty plot", TOOLTIP_DETAIL),
                TooltipLine("Gives a plot marker", TOOLTIP_PRICE)
            )
        }
        val plotIndex = hitTest(mouseX, mouseY, BUILDING_ROW_LEFT(), BUILDING_ROW_RIGHT(), BUILDING_ROW_TOP(), 17, visibleBuildingCount())
            ?.let { it + buildingScrollOffset }
            ?.takeIf { it in buildingPlots.indices }
            ?: return null
        val plot = buildingPlots[plotIndex]
        val lines = mutableListOf(
            TooltipLine(plot.displayName, TOOLTIP_TITLE),
            TooltipLine("${plot.sizeX}x${plot.sizeZ}, facing ${plot.facing}", TOOLTIP_DETAIL),
            TooltipLine(buildingRowStatus(plot), if (plot.pendingType.isNotBlank()) TOOLTIP_TIMER else TOOLTIP_DETAIL)
        )
        if (plot.canBuild && plot.plotIndex > 0) lines += TooltipLine("Build cost: ${plot.buildCost} emeralds", TOOLTIP_PRICE)
        if (plot.canBuild && plot.plotIndex <= 0) lines += TooltipLine("Use Build to receive a plot marker", TOOLTIP_PRICE)
        if (plot.canUpgrade) lines += TooltipLine("Upgrade cost: ${plot.upgradeCost} emeralds", TOOLTIP_PRICE)
        return lines
    }

    private fun hoverInfoLines(mouseX: Int, mouseY: Int): List<TooltipLine>? {
        val sellIndex = hitTest(mouseX, mouseY, x + 8, x + 91, y + 24, 17, visibleSellCount())
            ?.let { it + sellScrollOffset }
            ?.takeIf { it in sellOffers.indices }
        if (sellIndex != null) {
            val offer = sellOffers[sellIndex]
            return listOf(
                TooltipLine("Village sells ${offer.displayName}", TOOLTIP_TITLE),
                TooltipLine("Current Price: ${tradeRatioText(offer.tradeItemCount, offer.emeraldCost)}", TOOLTIP_PRICE),
                TooltipLine("Base Value: ${offer.baseValue}", TOOLTIP_DETAIL),
                TooltipLine("Village Stock: ${offer.remainingToday}/${offer.maxDailyProduction}", stockColor(offer.remainingToday, offer.maxDailyProduction)),
                TooltipLine("Stock Level: ${offer.stockPercent}%", stockColor(offer.remainingToday, offer.maxDailyProduction)),
                TooltipLine("${offer.stockLabel}: ${signed(offer.stockModifier)}", modifierColor(offer.stockModifier)),
                TooltipLine("Profession Supply: -${supplyDiscountPercent(offer.producerCount)}%", TOOLTIP_NEGATIVE),
                TooltipLine("Daily Market: ${signed(offer.dailyModifier)}", modifierColor(offer.dailyModifier)),
                TooltipLine("Produced by ${offer.producerCount} ${offer.producerProfession}(s)", TOOLTIP_DETAIL),
                TooltipLine("Restocks in ${formatRestockTime(handler.snapshot.produceRefreshTicks)}", TOOLTIP_TIMER)
            )
        }

        val buyIndex = hitTest(mouseX, mouseY, x + 282, x + 365, y + 24, 17, visibleBuyCount())
            ?.let { it + buyScrollOffset }
            ?.takeIf { it in buyOffers.indices }
        if (buyIndex != null) {
            val offer = buyOffers[buyIndex]
            val action = if (offer.grantsExperience) "Village wants" else "Village buys"
            val payment = if (offer.grantsExperience) "Request Payment" else "Current Payment"
            return listOf(
                TooltipLine("$action ${offer.displayName}", TOOLTIP_TITLE),
                TooltipLine("$payment: ${tradeRatioText(offer.tradeItemCount, offer.emeraldReward)}", TOOLTIP_PRICE),
                TooltipLine("Base Value: ${offer.baseValue}", TOOLTIP_DETAIL),
                if (offer.grantsExperience) {
                    TooltipLine("Daily Turn-ins: ${offer.remainingNeed}/${offer.maxDailyNeed}", stockColor(offer.remainingNeed, offer.maxDailyNeed))
                } else {
                    TooltipLine("Demand: ${offer.demandPercent}% (${offer.remainingNeed}/${offer.maxDailyNeed})", stockColor(offer.remainingNeed, offer.maxDailyNeed))
                },
                TooltipLine("${offer.demandLabel}: ${signed(offer.demandModifier)}", modifierColor(offer.demandModifier)),
                TooltipLine("Profession Demand: +${demandBonusPercent(offer.requesterCount)}%", TOOLTIP_POSITIVE),
                if (offer.grantsExperience) {
                    TooltipLine("Payment drops with repeated trades", TOOLTIP_DETAIL)
                } else {
                    TooltipLine("", TOOLTIP_DETAIL)
                },
                TooltipLine("Daily Market: ${signed(offer.dailyModifier)}", modifierColor(offer.dailyModifier)),
                if (offer.experienceReward > 0) TooltipLine("Village XP: +${offer.experienceReward} each", TOOLTIP_PRICE) else TooltipLine("", TOOLTIP_DETAIL),
                TooltipLine("Needed by: ${offer.requesterProfession}", TOOLTIP_DETAIL),
                TooltipLine("Refreshes in ${formatRestockTime(handler.snapshot.needRefreshTicks)}", TOOLTIP_TIMER)
            ).filter { it.text.isNotBlank() }
        }

        return null
    }

    private fun tooltipLeft(mouseX: Int, width: Int): Int {
        val leftPanelRight = x + 100
        val rightPanelLeft = x + 277
        return when {
            mouseX <= leftPanelRight -> (x + 108).coerceAtMost(x + backgroundWidth - width - 4)
            mouseX >= rightPanelLeft -> (x + 104).coerceAtMost(x + backgroundWidth - width - 4)
            else -> {
                val right = mouseX + 12
                if (right + width <= x + backgroundWidth - 4) right else mouseX - width - 12
            }
        }.coerceAtLeast(x + 4)
    }

    private fun stockColor(remaining: Int, total: Int): Int {
        if (remaining <= 0) return TOOLTIP_EMPTY
        if (total <= 0 || remaining >= total) return TOOLTIP_FULL
        return TOOLTIP_PARTIAL
    }

    private fun signed(value: Int): String {
        return if (value > 0) "+$value" else value.toString()
    }

    private fun modifierColor(value: Int): Int {
        return when {
            value > 0 -> TOOLTIP_POSITIVE
            value < 0 -> TOOLTIP_NEGATIVE
            else -> TOOLTIP_NEUTRAL
        }
    }

    private fun supplyDiscountPercent(professionCount: Int): Int {
        return ((professionCount.coerceIn(1, PROFESSION_PRICE_EFFECTIVE_COUNT_CAP) - 1) * PROFESSION_SUPPLY_PRICE_DISCOUNT_PERCENT_PER_EXTRA)
            .coerceAtMost(PROFESSION_SUPPLY_PRICE_DISCOUNT_PERCENT_MAX)
    }

    private fun demandBonusPercent(professionCount: Int): Int {
        return ((professionCount.coerceIn(1, PROFESSION_PRICE_EFFECTIVE_COUNT_CAP) - 1) * PROFESSION_DEMAND_PRICE_BONUS_PERCENT_PER_EXTRA)
            .coerceAtMost(PROFESSION_DEMAND_PRICE_BONUS_PERCENT_MAX)
    }

    private fun formatRestockTime(snapshotTicks: Long): String {
        return formatTicks(snapshotTicks - localTicksSinceSnapshot)
    }

    private fun formatSnapshotRemainingTime(snapshotRemainingTicks: Long): String {
        return formatTicks(snapshotRemainingTicks - localTicksSinceSnapshot)
    }

    private fun formatTicks(ticks: Long): String {
        val remainingTicks = ticks.coerceAtLeast(0L)
        val totalSeconds = (remainingTicks / 20L).coerceAtLeast(0L)
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        return if (minutes > 0) "${minutes}m ${seconds}s" else "${seconds}s"
    }

    private fun tradeRatioText(itemCount: Int, emeralds: Int): String {
        return if (itemCount <= 1) {
            "$emeralds emerald per item"
        } else {
            "$itemCount items for $emeralds emerald"
        }
    }

    private fun drawInventoryGrid(context: DrawContext, gridX: Int, gridY: Int) {
        val slot = 18
        for (row in 0 until 3) {
            for (col in 0 until 9) {
                drawSlot(context, gridX + col * slot, gridY + row * slot)
            }
        }
        for (col in 0 until 9) {
            drawSlot(context, gridX + col * slot, gridY + 58)
        }
    }

    private fun drawPanel(
        context: DrawContext,
        left: Int,
        top: Int,
        width: Int,
        height: Int,
        scrollOffset: Int,
        maxScroll: Int,
        visibleRows: Int = VISIBLE_ROWS
    ) {
        drawSunkenBox(context, left, top, width, height)
        drawScrollGutter(context, left + width - 8, top + 2, height - 4, scrollOffset, maxScroll, visibleRows)
    }

    private fun drawScrollGutter(
        context: DrawContext,
        left: Int,
        top: Int,
        height: Int,
        scrollOffset: Int,
        maxScroll: Int,
        visibleRows: Int
    ) {
        context.fill(left, top, left + 6, top + height, PANEL_FILL)
        context.fill(left, top, left + 1, top + height, DARK_EDGE)
        context.fill(left + 5, top, left + 6, top + height, LIGHT_EDGE)
        val trackTop = top + 2
        val trackHeight = height - 4
        val thumbHeight = if (maxScroll <= 0) {
            trackHeight
        } else {
            ((trackHeight * visibleRows) / (visibleRows + maxScroll)).coerceAtLeast(14)
        }
        val thumbTravel = (trackHeight - thumbHeight).coerceAtLeast(0)
        val thumbOffset = if (maxScroll <= 0) 0 else (thumbTravel * scrollOffset) / maxScroll
        context.fill(left + 2, trackTop + thumbOffset, left + 4, trackTop + thumbOffset + thumbHeight, SCROLL_THUMB)
    }

    private fun drawScrollThumb(
        context: DrawContext,
        left: Int,
        top: Int,
        height: Int,
        scrollOffset: Int,
        maxScroll: Int,
        visibleRows: Int
    ) {
        val thumbHeight = if (maxScroll <= 0) {
            height
        } else {
            ((height * visibleRows) / (visibleRows + maxScroll)).coerceAtLeast(14)
        }
        val thumbTravel = (height - thumbHeight).coerceAtLeast(0)
        val thumbOffset = if (maxScroll <= 0) 0 else (thumbTravel * scrollOffset) / maxScroll
        context.fill(left + 2, top + thumbOffset, left + 4, top + thumbOffset + thumbHeight, SCROLL_THUMB)
    }

    private fun drawSlot(context: DrawContext, left: Int, top: Int) {
        drawNineSlice(context, GUI_SLOT, left - 1, top - 1, VANILLA_SLOT_SIZE, VANILLA_SLOT_SIZE)
    }

    private fun drawBorder(context: DrawContext, left: Int, top: Int, width: Int, height: Int, color: Int) {
        context.fill(left, top, left + width, top + 1, color)
        context.fill(left, top + height - 1, left + width, top + height, color)
        context.fill(left, top, left + 1, top + height, color)
        context.fill(left + width - 1, top, left + width, top + height, color)
    }

    private fun drawRaisedBox(context: DrawContext, left: Int, top: Int, width: Int, height: Int) {
        drawNineSlice(context, GUI_PANEL_OUTER, left, top, width, height)
    }

    private fun drawSunkenBox(context: DrawContext, left: Int, top: Int, width: Int, height: Int) {
        drawNineSlice(context, GUI_PANEL_INNER, left, top, width, height)
    }

    private fun drawTradeRow(context: DrawContext, left: Int, top: Int, width: Int, selected: Boolean) {
        drawNineSlice(context, if (selected) GUI_TRADE_ROW_SELECTED else GUI_TRADE_ROW, left, top, width, 16)
    }

    private fun drawNineSlice(
        context: DrawContext,
        texture: Identifier,
        left: Int,
        top: Int,
        width: Int,
        height: Int,
        openBottom: Boolean = false
    ) {
        val border = GUI_TEXTURE_BORDER.coerceAtMost(width / 2).coerceAtMost(height / 2)
        val centerWidth = (width - border * 2).coerceAtLeast(0)
        val centerHeight = (height - border * 2).coerceAtLeast(0)
        val sourceCenter = GUI_TEXTURE_SIZE - border * 2
        val right = left + width - border
        val bottom = top + height - border

        drawTextureRegion(context, texture, left, top, border, border, 0, 0, border, border)
        drawTextureRegion(context, texture, right, top, border, border, GUI_TEXTURE_SIZE - border, 0, border, border)
        if (!openBottom) {
            drawTextureRegion(context, texture, left, bottom, border, border, 0, GUI_TEXTURE_SIZE - border, border, border)
            drawTextureRegion(context, texture, right, bottom, border, border, GUI_TEXTURE_SIZE - border, GUI_TEXTURE_SIZE - border, border, border)
        }

        if (centerWidth > 0) {
            drawTextureRegion(context, texture, left + border, top, centerWidth, border, border, 0, sourceCenter, border)
            if (!openBottom) {
                drawTextureRegion(context, texture, left + border, bottom, centerWidth, border, border, GUI_TEXTURE_SIZE - border, sourceCenter, border)
            }
        }
        if (centerHeight > 0) {
            drawTextureRegion(context, texture, left, top + border, border, centerHeight, 0, border, border, sourceCenter)
            drawTextureRegion(context, texture, right, top + border, border, centerHeight, GUI_TEXTURE_SIZE - border, border, border, sourceCenter)
        }
        if (centerWidth > 0 && centerHeight > 0) {
            drawTextureRegion(context, texture, left + border, top + border, centerWidth, centerHeight, border, border, sourceCenter, sourceCenter)
        }
    }

    private fun drawTextureRegion(
        context: DrawContext,
        texture: Identifier,
        left: Int,
        top: Int,
        width: Int,
        height: Int,
        sourceLeft: Int,
        sourceTop: Int,
        sourceWidth: Int,
        sourceHeight: Int
    ) {
        context.drawTexture(
            texture,
            left,
            top,
            width,
            height,
            sourceLeft.toFloat(),
            sourceTop.toFloat(),
            sourceWidth,
            sourceHeight,
            GUI_TEXTURE_SIZE,
            GUI_TEXTURE_SIZE
        )
    }

    private fun drawShellRegion(
        context: DrawContext,
        left: Int,
        top: Int,
        width: Int,
        height: Int,
        sourceLeft: Int,
        sourceTop: Int,
        sourceWidth: Int,
        sourceHeight: Int
    ) {
        context.drawTexture(
            GUI_CLERK_SHELL,
            left,
            top,
            width,
            height,
            sourceLeft.toFloat(),
            sourceTop.toFloat(),
            sourceWidth,
            sourceHeight,
            TRADE_FULL_TEXTURE_WIDTH,
            TRADE_FULL_TEXTURE_HEIGHT
        )
    }

    private fun hitTest(
        mouseX: Int,
        mouseY: Int,
        leftX: Int,
        rightX: Int,
        topY: Int,
        rowHeight: Int,
        count: Int
    ): Int? {
        if (mouseX !in leftX..rightX || mouseY < topY) return null
        val row = (mouseY - topY) / rowHeight
        return if (row in 0 until count && row < VISIBLE_ROWS) row else null
    }

    private fun hitTestPanel(mouseX: Int, mouseY: Int, left: Int, top: Int, width: Int, height: Int): Boolean {
        return mouseX in left..(left + width) && mouseY in top..(top + height)
    }

    private fun hitTestScrollGutter(mouseX: Int, mouseY: Int, left: Int, top: Int, width: Int, height: Int): Boolean {
        return mouseX in (left + width - 8)..(left + width - 2) && mouseY in (top + 2)..(top + height - 2)
    }

    private fun updateScrollFromMouse(side: SelectedSide, mouseY: Int) {
        val panelTop = y + 20
        val trackTop = panelTop + 4
        val trackHeight = 132
        val maxScroll = when (side) {
            SelectedSide.BUY -> maxSellScroll()
            SelectedSide.SELL -> maxBuyScroll()
        }
        val offset = if (maxScroll <= 0) {
            0
        } else {
            val relativeY = (mouseY - trackTop).coerceIn(0, trackHeight)
            (maxScroll * relativeY) / trackHeight
        }

        when (side) {
            SelectedSide.BUY -> sellScrollOffset = offset.coerceIn(0, maxScroll)
            SelectedSide.SELL -> buyScrollOffset = offset.coerceIn(0, maxScroll)
        }
    }

    private fun updateJobBlockScrollFromMouse(mouseY: Int) {
        val trackTop = y + 54
        val trackHeight = 84
        val maxScroll = maxJobBlockScroll()
        jobBlockScrollOffset = if (maxScroll <= 0) {
            0
        } else {
            val relativeY = (mouseY - trackTop).coerceIn(0, trackHeight)
            ((maxScroll * relativeY) / trackHeight).coerceIn(0, maxScroll)
        }
    }

    private fun updateCommissionScrollFromMouse(mouseY: Int) {
        val trackTop = y + 24
        val trackHeight = 132
        val maxScroll = maxCommissionScroll()
        commissionScrollOffset = if (maxScroll <= 0) {
            0
        } else {
            val relativeY = (mouseY - trackTop).coerceIn(0, trackHeight)
            ((maxScroll * relativeY) / trackHeight).coerceIn(0, maxScroll)
        }
    }

    private fun updatePickupScrollFromMouse(mouseY: Int) {
        val trackTop = y + 38
        val trackHeight = 100
        val maxScroll = maxPickupScroll()
        pickupScrollOffset = if (maxScroll <= 0) {
            0
        } else {
            val relativeY = (mouseY - trackTop).coerceIn(0, trackHeight)
            ((maxScroll * relativeY) / trackHeight).coerceIn(0, maxScroll)
        }
    }

    private fun updateBuildingScrollFromMouse(mouseY: Int) {
        val trackTop = BUILDING_ROW_TOP()
        val trackHeight = BUILDING_PANEL_HEIGHT - 8
        val maxScroll = maxBuildingScroll()
        buildingScrollOffset = if (maxScroll <= 0) {
            0
        } else {
            val relativeY = (mouseY - trackTop).coerceIn(0, trackHeight)
            ((maxScroll * relativeY) / trackHeight).coerceIn(0, maxScroll)
        }
    }

    private fun BUILDING_PANEL_LEFT(): Int = x + 38

    private fun BUILDING_PANEL_TOP(): Int = y + 78

    private fun BUILDING_ROW_LEFT(): Int = x + 42

    private fun BUILDING_ROW_TOP(): Int = y + 82

    private fun BUILDING_ROW_RIGHT(): Int = BUILDING_ROW_LEFT() + BUILDING_ROW_WIDTH

    private fun maxSellScroll(): Int = (sellOffers.size - VISIBLE_ROWS).coerceAtLeast(0)

    private fun maxBuyScroll(): Int = (buyOffers.size - VISIBLE_ROWS).coerceAtLeast(0)

    private fun maxJobBlockScroll(): Int = (jobBlockOffers.size - JOB_BLOCK_VISIBLE_ROWS).coerceAtLeast(0)

    private fun maxCommissionScroll(): Int = (totalCommissionRows() - COMMISSION_VISIBLE_ROWS).coerceAtLeast(0)

    private fun maxPickupScroll(): Int = (filteredCommissionOrders.size - PICKUP_VISIBLE_ROWS).coerceAtLeast(0)

    private fun maxBuildingScroll(): Int = (buildingPlots.size - BUILDING_VISIBLE_ROWS).coerceAtLeast(0)

    private fun visibleSellCount(): Int = sellOffers.size.coerceAtMost(VISIBLE_ROWS)

    private fun visibleBuyCount(): Int = buyOffers.size.coerceAtMost(VISIBLE_ROWS)

    private fun visibleJobBlockCount(): Int = jobBlockOffers.size.coerceAtMost(JOB_BLOCK_VISIBLE_ROWS)

    private fun visibleCommissionCount(): Int = totalCommissionRows().coerceAtMost(COMMISSION_VISIBLE_ROWS)

    private fun visiblePickupCount(): Int = filteredCommissionOrders.size.coerceAtMost(PICKUP_VISIBLE_ROWS)

    private fun visibleBuildingCount(): Int = buildingPlots.size.coerceAtMost(BUILDING_VISIBLE_ROWS)

    private fun totalCommissionRows(): Int =
        if (selectedCommissionProfession?.first == "minecraft:armorer") {
            armorerOptions().size
        } else {
            filteredCommissionOffers.size
        }

    private fun pickupRowStatus(order: net.pavinical.sovereign.economy.CommissionOrder): String = when {
        order.claimable && order.abandoned -> "Open"
        order.claimable -> "Ready"
        !order.ready -> "Wait"
        else -> "Held"
    }

    private fun pickupStatus(order: net.pavinical.sovereign.economy.CommissionOrder): String = when {
        order.claimable && order.abandoned -> "Abandoned order, open for pickup."
        order.claimable -> "Ready for your pickup."
        !order.ready -> "Ready in ${formatSnapshotRemainingTime(order.remainingTicks)}."
        else -> "Held for its patron ${formatSnapshotRemainingTime(order.abandonedInTicks)} longer."
    }

    private fun selectedBuildingPlot(): BuildingPlot? =
        selectedBuildingPlotIndex.takeIf { it in buildingPlots.indices }?.let { buildingPlots[it] }

    private fun buildingStructureType(plot: BuildingPlot): String =
        if (plot.allowedType == "castle" || plot.sizeX >= 25) "castle" else "house"

    private fun buildingRowStatus(plot: BuildingPlot): String = when {
        plot.pendingType.isNotBlank() -> formatSnapshotRemainingTime(plot.remainingTicks)
        plot.canUpgrade -> "Upgrade"
        plot.plotIndex <= 0 -> "Mark"
        plot.canBuild -> "${plot.buildCost}"
        plot.builtType.isNotBlank() -> "Built"
        else -> "Locked"
    }

    private fun buildingStatusColor(plot: BuildingPlot): Int = when {
        plot.pendingType.isNotBlank() -> TOOLTIP_TIMER
        plot.canBuild || plot.canUpgrade -> EMERALD_TEXT
        plot.builtType.isNotBlank() -> TOOLTIP_FULL
        else -> TOOLTIP_EMPTY
    }

    private fun buildingActionDetail(plot: BuildingPlot): String = when {
        plot.plotIndex <= 0 -> "Mark a ${buildingStructureType(plot)} site before building"
        plot.canBuild -> "Raise ${buildingStructureType(plot)} on plot #${plot.plotIndex}"
        plot.pendingType.isNotBlank() -> "Construction is underway"
        plot.builtType.isNotBlank() -> "This plot is already built"
        else -> "This plot is not ready for building"
    }

    private fun upgradeActionDetail(plot: BuildingPlot): String = when {
        plot.canUpgrade -> "Improve house #${plot.plotIndex}"
        plot.pendingType.isNotBlank() -> "Construction is underway"
        plot.builtType != "house" -> "Only houses can be upgraded"
        else -> "No upgrade is available yet"
    }

    private fun relocateActionDetail(plot: BuildingPlot): String = when {
        plot.plotIndex <= 0 -> "This is a new site marker"
        plot.pendingType.isNotBlank() -> "Construction is underway"
        plot.builtType.isNotBlank() -> "Move this structure to a new site"
        else -> "Move plot #${plot.plotIndex}"
    }

    private fun selectCommissionProfession(professionId: String?) {
        selectedCommissionProfessionId = professionId
        commissionProfessionDropdownOpen = false
        commissionScrollOffset = 0
        selectedCommissionOfferIndex = -1
        selectedCommissionOrderIndex = -1
        selectedArmorerArmorKey = null
        selectedArmorerTrimKey = null
        selectedArmorerMaterialKey = null
        selectedCommissionOfferKey = null
        selectedCommissionOrderKey = null
        statusMessage = professionId
            ?.let { id -> commissionProfessionOptions.firstOrNull { it.first == id }?.second }
            ?.let { Text.literal("$it commissions") }
            ?: Text.literal("Choose an artisan")
    }

    private fun trimToWidth(value: String, maxWidth: Int): String {
        if (textRenderer.getWidth(value) <= maxWidth) return value
        var trimmed = value
        while (trimmed.isNotEmpty() && textRenderer.getWidth("$trimmed...") > maxWidth) {
            trimmed = trimmed.dropLast(1)
        }
        return "$trimmed..."
    }

    private fun Int.saturatingMultiplyClient(amount: Int): Int {
        val result = toLong() * amount.coerceAtLeast(1).toLong()
        return result.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    private fun onTradeClicked() {
        when (selectedSide) {
            SelectedSide.BUY -> onBuyClicked()
            SelectedSide.SELL -> onSellClicked()
            null -> Unit
        }
    }

    private fun clearSelection() {
        selectedSide = null
        selectedSellIndex = -1
        selectedBuyIndex = -1
        selectedJobBlockIndex = -1
        selectedCommissionOfferIndex = -1
        selectedCommissionOrderIndex = -1
        selectedBuildingPlotIndex = -1
        selectedSellKey = null
        selectedBuyKey = null
        selectedJobBlockKey = null
        selectedCommissionOfferKey = null
        selectedCommissionOrderKey = null
        selectedBuildingPlotKey = null
        selectedArmorerArmorKey = null
        selectedArmorerTrimKey = null
        selectedArmorerMaterialKey = null
    }

    private fun onTradeAllClicked() {
        when (selectedSide) {
            SelectedSide.BUY -> onBuyAllClicked()
            SelectedSide.SELL -> onSellAllClicked()
            null -> Unit
        }
    }

    private fun onBuyClicked() {
        if (selectedSellIndex !in sellOffers.indices) return
        val offer = sellOffers[selectedSellIndex]
        sendTradeRequest(
            direction = VillageTradeNetwork.TRADE_DIRECTION_BUY,
            professionId = offer.producerProfessionId,
            quantity = 1
        )
        statusMessage = Text.literal("Processing buy...")
    }

    private fun onBuyAllClicked() {
        if (selectedSellIndex !in sellOffers.indices) return
        val offer = sellOffers[selectedSellIndex]
        sendTradeRequest(
            direction = VillageTradeNetwork.TRADE_DIRECTION_BUY,
            professionId = offer.producerProfessionId,
            quantity = TRADE_ALL_QUANTITY
        )
        statusMessage = Text.literal("Processing buy all...")
    }

    private fun onSellClicked() {
        if (selectedBuyIndex !in buyOffers.indices) return
        val offer = buyOffers[selectedBuyIndex]
        sendTradeRequest(
            direction = VillageTradeNetwork.TRADE_DIRECTION_SELL,
            professionId = offer.requesterProfessionId,
            quantity = 1
        )
        statusMessage = Text.literal("Processing sell...")
    }

    private fun onSellAllClicked() {
        if (selectedBuyIndex !in buyOffers.indices) return
        val offer = buyOffers[selectedBuyIndex]
        sendTradeRequest(
            direction = VillageTradeNetwork.TRADE_DIRECTION_SELL,
            professionId = offer.requesterProfessionId,
            quantity = TRADE_ALL_QUANTITY
        )
        statusMessage = Text.literal("Processing sell all...")
    }

    private fun onBuyJobBlockClicked() {
        if (selectedJobBlockIndex !in jobBlockOffers.indices) return
        val offer = jobBlockOffers[selectedJobBlockIndex]
            sendTradeRequest(
            direction = if (offer.professionId == JobBlockRelocationTool.OFFER_ID) {
                VillageTradeNetwork.TRADE_DIRECTION_RELOCATION_TOOL
            } else {
                VillageTradeNetwork.TRADE_DIRECTION_JOB_BLOCK
            },
            professionId = offer.professionId,
            quantity = 1
        )
        statusMessage = Text.literal("Processing permit...")
    }

    private fun onRepairCommissionClicked() {
        val offer = repairCommissionOfferFor(selectedCommissionProfession?.first) ?: return
        sendTradeRequest(
            direction = VillageTradeNetwork.TRADE_DIRECTION_COMMISSION_ORDER,
            professionId = offer.id,
            quantity = 1
        )
        statusMessage = Text.literal("Submitting repair...")
    }

    private fun onReforgeCommissionClicked() {
        val offer = activeReforgeOffer() ?: return
        sendTradeRequest(
            direction = VillageTradeNetwork.TRADE_DIRECTION_COMMISSION_ORDER,
            professionId = "smith_reforge|${offer.id}",
            quantity = 1
        )
        statusMessage = Text.literal("Submitting reforge...")
    }

    private fun onOrderCommissionClicked() {
        if (selectedCommissionProfession?.first == "minecraft:armorer") {
            val armorKey = selectedArmorerArmorKey ?: return
            if ((selectedArmorerTrimKey == null) != (selectedArmorerMaterialKey == null)) {
                statusMessage = Text.literal("Choose both trim and material.")
                return
            }
            if (selectedArmorerTrimKey != null && matchingArmorerTrimOffer(selectedArmorerTrimKey, selectedArmorerMaterialKey) == null) {
                statusMessage = Text.literal("Choose an available trim pairing.")
                return
            }
            sendTradeRequest(
                direction = VillageTradeNetwork.TRADE_DIRECTION_COMMISSION_ORDER,
                professionId = "armorer_custom|$armorKey|${selectedArmorerTrimKey.orEmpty()}|${selectedArmorerMaterialKey.orEmpty()}",
                quantity = 1
            )
            statusMessage = Text.literal("Submitting commission...")
            return
        }
        if (selectedCommissionOfferIndex !in filteredCommissionOffers.indices) return
        val offer = filteredCommissionOffers[selectedCommissionOfferIndex]
        sendTradeRequest(
            direction = VillageTradeNetwork.TRADE_DIRECTION_COMMISSION_ORDER,
            professionId = offer.id,
            quantity = if (offer.professionId == "minecraft:cleric") selectedPotionQuantity else 1
        )
        statusMessage = Text.literal("Submitting commission...")
    }

    private fun onClaimCommissionClicked() {
        if (selectedCommissionOrderIndex !in filteredCommissionOrders.indices) return
        val order = filteredCommissionOrders[selectedCommissionOrderIndex]
        sendTradeRequest(
            direction = VillageTradeNetwork.TRADE_DIRECTION_COMMISSION_CLAIM,
            professionId = order.id,
            quantity = 1
        )
        statusMessage = Text.literal("Claiming commission...")
    }

    private fun onBuildStructureClicked() {
        val plot = selectedBuildingPlot() ?: return
        sendTradeRequest(
            direction = VillageTradeNetwork.TRADE_DIRECTION_BUILD_STRUCTURE,
            professionId = buildingStructureType(plot),
            quantity = plot.plotIndex
        )
        statusMessage = Text.literal("Starting build...")
    }

    private fun onUpgradeBuildingClicked() {
        val plot = selectedBuildingPlot() ?: return
        sendTradeRequest(
            direction = VillageTradeNetwork.TRADE_DIRECTION_UPGRADE_HOUSE,
            professionId = "house",
            quantity = plot.plotIndex
        )
        statusMessage = Text.literal("Starting upgrade...")
    }

    private fun onRelocateBuildingClicked() {
        val plot = selectedBuildingPlot() ?: return
        sendTradeRequest(
            direction = VillageTradeNetwork.TRADE_DIRECTION_RELOCATE_PLOT,
            professionId = buildingStructureType(plot),
            quantity = plot.plotIndex
        )
        statusMessage = Text.literal("Preparing plot marker...")
    }

    private fun sendTradeRequest(direction: Int, professionId: String, quantity: Int) {
        if (!isClientSessionActive()) return
        ClientPlayNetworking.send(
            VillageTradeRequestPayload(
                clerkX = handler.clerkX,
                clerkY = handler.clerkY,
                clerkZ = handler.clerkZ,
                direction = direction,
                professionId = professionId,
                quantity = quantity
            )
        )
    }

    private fun sendLedgerTransfer(direction: Int, amount: Int) {
        if (!isClientSessionActive()) return
        ClientPlayNetworking.send(
            VillageLedgerTransferPayload(
                clerkX = handler.clerkX,
                clerkY = handler.clerkY,
                clerkZ = handler.clerkZ,
                direction = direction,
                amount = amount
            )
        )
    }

    private fun requestSnapshotRefresh() {
        if (!isClientSessionActive()) return
        ClientPlayNetworking.send(
            VillageTradeRefreshPayload(
                clerkX = handler.clerkX,
                clerkY = handler.clerkY,
                clerkZ = handler.clerkZ
            )
        )
    }

    private fun playButtonClick() {
        if (!isClientSessionActive()) return
        client?.soundManager?.play(PositionedSoundInstance.master(SoundEvents.UI_BUTTON_CLICK, 1.0f))
    }

    private fun isClientSessionActive(): Boolean {
        val currentClient = client ?: return false
        return currentClient.world != null && currentClient.networkHandler != null
    }

    fun applyServerSnapshot(payload: VillageTradeSyncPayload) {
        handler.updateSnapshot(payload.snapshot.toRuntime())
        localTicksSinceSnapshot = 0L
        refreshRequested = false
        if (payload.message.isNotBlank()) {
            statusMessage = Text.literal(payload.message)
        }
        if (selectedCommissionProfessionId == null || commissionProfessionOptions.none { it.first == selectedCommissionProfessionId }) {
            selectedCommissionProfessionId = commissionProfessionOptions.firstOrNull()?.first
            commissionScrollOffset = 0
        }

        selectedSellKey?.let { key ->
            selectedSellIndex = sellOffers.indexOfFirst { it.producerProfessionId == key }
        }
        selectedBuyKey?.let { key ->
            selectedBuyIndex = buyOffers.indexOfFirst { it.requesterProfessionId == key }
        }
        selectedJobBlockKey?.let { key ->
            selectedJobBlockIndex = jobBlockOffers.indexOfFirst { it.professionId == key }
        }
        selectedCommissionOfferKey?.let { key ->
            selectedCommissionOfferIndex = filteredCommissionOffers.indexOfFirst { it.id == key }
        }
        selectedCommissionOrderKey?.let { key ->
            selectedCommissionOrderIndex = filteredCommissionOrders.indexOfFirst { it.id == key }
        }
        selectedBuildingPlotKey?.let { key ->
            selectedBuildingPlotIndex = buildingPlots.indexOfFirst { it.plotIndex.toString() == key }
        }

        if (selectedSellIndex !in sellOffers.indices) {
            selectedSellIndex = -1
            selectedSellKey = null
        }
        if (selectedBuyIndex !in buyOffers.indices) {
            selectedBuyIndex = -1
            selectedBuyKey = null
        }
        if (selectedJobBlockIndex !in jobBlockOffers.indices) {
            selectedJobBlockIndex = -1
            selectedJobBlockKey = null
        }
        if (selectedCommissionOfferIndex !in filteredCommissionOffers.indices) {
            selectedCommissionOfferIndex = -1
            selectedCommissionOfferKey = null
        }
        if (selectedCommissionOrderIndex !in filteredCommissionOrders.indices) {
            selectedCommissionOrderIndex = -1
            selectedCommissionOrderKey = null
        }
        if (selectedBuildingPlotIndex !in buildingPlots.indices) {
            selectedBuildingPlotIndex = -1
            selectedBuildingPlotKey = null
        }
        if (selectedSide == SelectedSide.BUY && selectedSellIndex == -1) {
            clearSelection()
        }
        if (selectedSide == SelectedSide.SELL && selectedBuyIndex == -1) {
            clearSelection()
        }
        sellScrollOffset = scrollOffsetKeepingSelectionVisible(sellScrollOffset.coerceIn(0, maxSellScroll()), selectedSellIndex, maxSellScroll())
        buyScrollOffset = scrollOffsetKeepingSelectionVisible(buyScrollOffset.coerceIn(0, maxBuyScroll()), selectedBuyIndex, maxBuyScroll())
        jobBlockScrollOffset = scrollOffsetKeepingSelectionVisible(
            jobBlockScrollOffset.coerceIn(0, maxJobBlockScroll()),
            selectedJobBlockIndex,
            maxJobBlockScroll()
        )
        commissionScrollOffset = scrollOffsetKeepingSelectionVisible(
            commissionScrollOffset.coerceIn(0, maxCommissionScroll()),
            selectedCommissionOfferIndex,
            maxCommissionScroll(),
            COMMISSION_VISIBLE_ROWS
        )
        pickupScrollOffset = scrollOffsetKeepingSelectionVisible(
            pickupScrollOffset.coerceIn(0, maxPickupScroll()),
            selectedCommissionOrderIndex,
            maxPickupScroll(),
            PICKUP_VISIBLE_ROWS
        )
        buildingScrollOffset = scrollOffsetKeepingSelectionVisible(
            buildingScrollOffset.coerceIn(0, maxBuildingScroll()),
            selectedBuildingPlotIndex,
            maxBuildingScroll(),
            BUILDING_VISIBLE_ROWS
        )
    }

    private fun scrollOffsetKeepingSelectionVisible(
        currentOffset: Int,
        selectedIndex: Int,
        maxScroll: Int,
        visibleRows: Int = VISIBLE_ROWS
    ): Int {
        if (selectedIndex < 0) return currentOffset
        return when {
            selectedIndex < currentOffset -> selectedIndex
            selectedIndex >= currentOffset + visibleRows -> selectedIndex - visibleRows + 1
            else -> currentOffset
        }.coerceIn(0, maxScroll)
    }

    companion object {
        private const val TRADE_ALL_QUANTITY = Int.MAX_VALUE
        private const val LEDGER_ALL_QUANTITY = Int.MAX_VALUE
        private const val VISIBLE_ROWS = 8
        private const val JOB_BLOCK_VISIBLE_ROWS = 5
        private const val COMMISSION_VISIBLE_ROWS = 8
        private const val COMMISSION_INPUT_SLOT_COUNT_CLIENT = 4
        private const val PICKUP_VISIBLE_ROWS = 6
        private const val BUILDING_VISIBLE_ROWS = 4
        private const val BUILDING_PANEL_WIDTH = 300
        private const val BUILDING_PANEL_HEIGHT = 74
        private const val BUILDING_ROW_WIDTH = 287
        private const val ORDER_PRODUCTION_PREMIUM_CLIENT = 100
        private const val COMMISSION_SELECTOR_WIDTH = 88
        private const val COMMISSION_SELECTOR_MAX_OPTIONS = 6
        private const val VANILLA_SLOT_SIZE = 18
        private const val GUI_TAB_HEIGHT = 18
        private const val TRADE_FULL_TEXTURE_WIDTH = 378
        private const val TRADE_FULL_TEXTURE_HEIGHT = 193
        private const val TAB_SMALL_WIDTH = 40
        private const val LEDGER_TAB_WIDTH = 98
        private const val TAB_1_LEFT = 0
        private const val TAB_2_LEFT = 45
        private const val TAB_3_LEFT = 90
        private const val TAB_4_LEFT = 135
        private const val TAB_5_LEFT = 180
        private const val TAB_6_LEFT = 225
        private const val LEDGER_TAB_LEFT = 276
        private const val TAB_TEXTURE_WIDTH = 43
        private const val LEDGER_TAB_TEXTURE_WIDTH = 101
        private const val TAB_TEXTURE_HEIGHT = 22
        private const val SCREEN_BG = 0xFFC6C6C6.toInt()
        private const val PANEL_FILL = 0xFF949494.toInt()
        private const val SLOT_FILL = 0xFF8F8F8F.toInt()
        private const val TRADE_ROW = 0xFF777777.toInt()
        private const val SELECTED_ROW = 0xFF8B948F.toInt()
        private const val LIGHT_EDGE = 0xFFEFEFEF.toInt()
        private const val DARK_EDGE = 0xFF383838.toInt()
        private const val ROW_LIGHT = 0xFF9E9E9E.toInt()
        private const val ROW_DARK = 0xFF4B4B4B.toInt()
        private const val SCROLL_THUMB = 0xFF777777.toInt()
        private const val TOOLTIP_BG = 0xF0101010.toInt()
        private const val TOOLTIP_BORDER = 0xFFE0E0E0.toInt()
        private const val TEXT_DARK = 0xFF303030.toInt()
        private const val TEXT_LIGHT = 0xFFFFFFFF.toInt()
        private const val EMERALD_TEXT = 0xFF2D6A2D.toInt()
        private const val LEDGER_TEXT = 0xFF55FF55.toInt()
        private const val XP_BAR_BG = 0xFF3B3B3B.toInt()
        private const val XP_BAR_FILL = 0xFF48BF35.toInt()
        private const val XP_BAR_HIGHLIGHT = 0xFF80FF5A.toInt()
        private const val XP_BAR_DIVIDER = 0xFF25301E.toInt()
        private const val XP_TEXT = 0xFF2D5F30.toInt()
        private const val TOOLTIP_TITLE = 0xFFFFFFFF.toInt()
        private const val TOOLTIP_FULL = 0xFF55FF55.toInt()
        private const val TOOLTIP_PARTIAL = 0xFFFFD35A.toInt()
        private const val TOOLTIP_EMPTY = 0xFFFF5555.toInt()
        private const val TOOLTIP_PRICE = 0xFF7CFF7C.toInt()
        private const val TOOLTIP_DETAIL = 0xFFBFBFBF.toInt()
        private const val TOOLTIP_TIMER = 0xFF80C8FF.toInt()
        private const val TOOLTIP_POSITIVE = 0xFF55FF55.toInt()
        private const val TOOLTIP_NEGATIVE = 0xFFFF7777.toInt()
        private const val TOOLTIP_NEUTRAL = 0xFFE6E6E6.toInt()
        private const val TOOLTIP_Z = 600.0f
        private const val PROFESSION_PRICE_EFFECTIVE_COUNT_CAP = 6
        private const val PROFESSION_SUPPLY_PRICE_DISCOUNT_PERCENT_PER_EXTRA = 5
        private const val PROFESSION_SUPPLY_PRICE_DISCOUNT_PERCENT_MAX = 25
        private const val PROFESSION_DEMAND_PRICE_BONUS_PERCENT_PER_EXTRA = 8
        private const val PROFESSION_DEMAND_PRICE_BONUS_PERCENT_MAX = 40
        private const val GUI_TEXTURE_SIZE = 16
        private const val GUI_TEXTURE_BORDER = 4
        private val GUI_PANEL_OUTER = Identifier.of(Sovereign.MOD_ID, "textures/gui/panel_outer.png")
        private val GUI_PANEL_INNER = Identifier.of(Sovereign.MOD_ID, "textures/gui/panel_inner.png")
        private val GUI_SLOT = Identifier.of(Sovereign.MOD_ID, "textures/gui/slot.png")
        private val GUI_TRADE_ROW = Identifier.of(Sovereign.MOD_ID, "textures/gui/trade_row.png")
        private val GUI_TRADE_ROW_SELECTED = Identifier.of(Sovereign.MOD_ID, "textures/gui/trade_row_selected.png")
        private val GUI_TAB = Identifier.of(Sovereign.MOD_ID, "textures/gui/tab.png")
        private val GUI_TAB_SELECTED = Identifier.of(Sovereign.MOD_ID, "textures/gui/tab_selected.png")
        private val GUI_TAB_UNSELECTED = Identifier.of(Sovereign.MOD_ID, "textures/gui/tab_unselected.png")
        private val GUI_TRADE_TAB_SELECTED = Identifier.of(Sovereign.MOD_ID, "textures/gui/trade_tab_selected.png")
        private val GUI_TRADE_TAB_UNSELECTED = Identifier.of(Sovereign.MOD_ID, "textures/gui/trade_tab_unselected.png")
        private val GUI_LEDGER_TAB_SELECTED = Identifier.of(Sovereign.MOD_ID, "textures/gui/ledger_tab_selected.png")
        private val GUI_LEDGER_TAB_UNSELECTED = Identifier.of(Sovereign.MOD_ID, "textures/gui/ledger_tab_unselected.png")
        private val GUI_CLERK_SHELL = Identifier.of(Sovereign.MOD_ID, "textures/gui/clerk_trade_full.png")
    }
}


