package net.pavinical.sovereign.client

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.ingame.HandledScreen
import net.minecraft.client.gui.widget.ButtonWidget
import net.minecraft.entity.player.PlayerInventory
import net.minecraft.item.ItemStack
import net.minecraft.item.Items
import net.minecraft.text.Text
import net.pavinical.sovereign.economy.VillageTradeMenu
import net.pavinical.sovereign.economy.VillageTradeNetwork
import net.pavinical.sovereign.economy.VillageTradeRefreshPayload
import net.pavinical.sovereign.economy.VillageTradeRequestPayload
import net.pavinical.sovereign.economy.VillageTradeSyncPayload

class VillageTradeScreen(
    handler: VillageTradeMenu,
    playerInventory: PlayerInventory,
    title: Text
) : HandledScreen<VillageTradeMenu>(handler, playerInventory, title) {
    private data class TooltipLine(val text: String, val color: Int)

    private enum class SelectedSide {
        BUY,
        SELL
    }

    private var selectedSellIndex = -1
    private var selectedBuyIndex = -1
    private var selectedSide: SelectedSide? = null
    private var statusMessage: Text = Text.literal("")
    private var localTicksSinceSnapshot = 0L
    private var refreshRequested = false
    private var sellScrollOffset = 0
    private var buyScrollOffset = 0
    private var draggingScroll: SelectedSide? = null
    private lateinit var tradeButton: ButtonWidget
    private lateinit var tradeAllButton: ButtonWidget
    private val emeraldStack = ItemStack(Items.EMERALD)

    private val sellOffers
        get() = handler.snapshot.sellOffers

    private val buyOffers
        get() = handler.snapshot.buyOffers

    init {
        backgroundHeight = 172
        backgroundWidth = 376
        titleY = -1000
        playerInventoryTitleY = -1000
    }

    override fun init() {
        super.init()

        tradeButton = addDrawableChild(
            ButtonWidget.builder(Text.literal("Trade")) { onTradeClicked() }
                .dimensions(x + 150, y + 43, 76, 18)
                .build()
        )
        tradeAllButton = addDrawableChild(
            ButtonWidget.builder(Text.literal("Trade All")) { onTradeAllClicked() }
                .dimensions(x + 150, y + 64, 76, 18)
                .build()
        )
    }

    override fun drawBackground(context: DrawContext, delta: Float, mouseX: Int, mouseY: Int) {
        context.fill(x, y, x + backgroundWidth, y + backgroundHeight, SCREEN_BG)
        drawRaisedBox(context, x, y, backgroundWidth, backgroundHeight)

        drawPanel(context, x + 4, y + 18, 96, 140, sellScrollOffset, maxSellScroll())
        drawPanel(context, x + 275, y + 18, 96, 140, buyScrollOffset, maxBuyScroll())
        drawInventoryGrid(context, x + 107, y + 90)

        context.drawText(textRenderer, "Buy", x + 38, y + 6, TEXT_DARK, false)
        context.drawText(textRenderer, "Sell", x + 313, y + 6, TEXT_DARK, false)
        context.drawCenteredTextWithShadow(
            textRenderer,
            trimToWidth("${handler.villageName} Trading Post", 150),
            x + backgroundWidth / 2,
            y + 14,
            TEXT_LIGHT
        )
        drawVillageXpBar(context, x + 128, y + 27, 120, 7)
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        super.render(context, mouseX, mouseY, delta)

        drawOfferRows(context)
        drawHoverInfo(context, mouseX, mouseY)

        tradeButton.active = selectedSide != null
        tradeAllButton.active = selectedSide != null
    }

    override fun handledScreenTick() {
        super.handledScreenTick()
        localTicksSinceSnapshot++
        if (!refreshRequested && handler.snapshot.refreshTicks > 0L && localTicksSinceSnapshot >= handler.snapshot.refreshTicks) {
            refreshRequested = true
            requestSnapshotRefresh()
        }
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (button != 0) {
            return super.mouseClicked(mouseX, mouseY, button)
        }

        val clickedX = mouseX.toInt()
        val clickedY = mouseY.toInt()

        if (hitTestScrollGutter(clickedX, clickedY, x + 4, y + 18, 96, 140)) {
            draggingScroll = SelectedSide.BUY
            updateScrollFromMouse(SelectedSide.BUY, clickedY)
            return true
        }

        if (hitTestScrollGutter(clickedX, clickedY, x + 275, y + 18, 96, 140)) {
            draggingScroll = SelectedSide.SELL
            updateScrollFromMouse(SelectedSide.SELL, clickedY)
            return true
        }

        val sellIndex = hitTest(clickedX, clickedY, x + 8, x + 91, y + 22, 17, visibleSellCount())
            ?.let { it + sellScrollOffset }
            ?.takeIf { it in sellOffers.indices }
        if (sellIndex != null) {
            selectedSellIndex = sellIndex
            selectedBuyIndex = -1
            selectedSide = SelectedSide.BUY
            statusMessage = Text.literal("Buy ${sellOffers[sellIndex].displayName}")
            return true
        }

        val buyIndex = hitTest(clickedX, clickedY, x + 279, x + 362, y + 22, 17, visibleBuyCount())
            ?.let { it + buyScrollOffset }
            ?.takeIf { it in buyOffers.indices }
        if (buyIndex != null) {
            selectedBuyIndex = buyIndex
            selectedSellIndex = -1
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
        return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY)
    }

    override fun mouseReleased(mouseX: Double, mouseY: Double, button: Int): Boolean {
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

        if (hitTestPanel(mouseXi, mouseYi, x + 4, y + 18, 96, 140)) {
            sellScrollOffset = (sellScrollOffset + scrollDelta).coerceIn(0, maxSellScroll())
            return true
        }

        if (hitTestPanel(mouseXi, mouseYi, x + 275, y + 18, 96, 140)) {
            buyScrollOffset = (buyScrollOffset + scrollDelta).coerceIn(0, maxBuyScroll())
            return true
        }

        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)
    }

    private fun drawOfferRows(context: DrawContext) {
        val rowHeight = 17
        var rowY = y + 22

        sellOffers.drop(sellScrollOffset).take(VISIBLE_ROWS).forEachIndexed { visibleIndex, offer ->
            val offerIndex = sellScrollOffset + visibleIndex
            drawTradeRow(context, x + 8, rowY, 83, selectedSide == SelectedSide.BUY && selectedSellIndex == offerIndex)
            context.drawItem(offer.item, x + 9, rowY)
            context.drawText(
                textRenderer,
                offer.remainingToday.toString(),
                x + 28,
                rowY + 5,
                TEXT_LIGHT,
                false
            )
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

        rowY = y + 22
        buyOffers.drop(buyScrollOffset).take(VISIBLE_ROWS).forEachIndexed { visibleIndex, offer ->
            val offerIndex = buyScrollOffset + visibleIndex
            drawTradeRow(context, x + 279, rowY, 83, selectedSide == SelectedSide.SELL && selectedBuyIndex == offerIndex)
            context.drawItem(offer.item, x + 280, rowY)
            context.drawText(
                textRenderer,
                buyOfferQuantityLabel(offer.grantsExperience, offer.remainingNeed),
                x + 299,
                rowY + 5,
                TEXT_LIGHT,
                false
            )
            context.drawItem(emeraldStack, x + 330, rowY)
            context.drawText(
                textRenderer,
                offer.emeraldReward.toString(),
                x + 347,
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
        val fillWidth = if (snapshot.villageExperience >= tierEnd) width else (width * progress) / progressRange
        val label = if (snapshot.villageExperience >= tierEnd && snapshot.tierName == "City") {
            "${snapshot.tierName} XP ${snapshot.villageExperience}"
        } else {
            "${snapshot.tierName} XP ${snapshot.villageExperience}/$tierEnd"
        }

        drawSunkenBox(context, left, top, width, height)
        context.fill(left + 1, top + 1, left + width - 1, top + height - 1, XP_BAR_BG)
        context.fill(left + 1, top + 1, left + 1 + fillWidth.coerceAtMost(width - 2), top + height - 1, XP_BAR_FILL)
        context.drawCenteredTextWithShadow(textRenderer, label, left + width / 2, top - 1, XP_TEXT)
    }

    private fun drawHoverInfo(context: DrawContext, mouseX: Int, mouseY: Int) {
        val lines = hoverInfoLines(mouseX, mouseY) ?: return
        val width = lines.maxOf { textRenderer.getWidth(it.text) } + 10
        val height = lines.size * 10 + 8
        val left = (mouseX + 12).coerceAtMost(x + backgroundWidth - width - 4)
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

    private fun hoverInfoLines(mouseX: Int, mouseY: Int): List<TooltipLine>? {
        val sellIndex = hitTest(mouseX, mouseY, x + 8, x + 91, y + 22, 17, visibleSellCount())
            ?.let { it + sellScrollOffset }
            ?.takeIf { it in sellOffers.indices }
        if (sellIndex != null) {
            val offer = sellOffers[sellIndex]
            return listOf(
                TooltipLine("Village sells ${offer.displayName}", TOOLTIP_TITLE),
                TooltipLine("Current Price: ${offer.emeraldCost} emerald each", TOOLTIP_PRICE),
                TooltipLine("Base Value: ${offer.baseValue}", TOOLTIP_DETAIL),
                TooltipLine("Stock: ${offer.stockPercent}% (${offer.remainingToday}/${offer.maxDailyProduction})", stockColor(offer.remainingToday, offer.maxDailyProduction)),
                TooltipLine("${offer.stockLabel}: ${signed(offer.stockModifier)}", modifierColor(offer.stockModifier)),
                TooltipLine("Daily Market: ${signed(offer.dailyModifier)}", modifierColor(offer.dailyModifier)),
                TooltipLine("Produced by ${offer.producerCount} ${offer.producerProfession}(s)", TOOLTIP_DETAIL),
                TooltipLine("Restocks in ${formatRestockTime(handler.snapshot.produceRefreshTicks)}", TOOLTIP_TIMER)
            )
        }

        val buyIndex = hitTest(mouseX, mouseY, x + 279, x + 362, y + 22, 17, visibleBuyCount())
            ?.let { it + buyScrollOffset }
            ?.takeIf { it in buyOffers.indices }
        if (buyIndex != null) {
            val offer = buyOffers[buyIndex]
            val action = if (offer.grantsExperience) "Village wants" else "Village needs"
            val payment = if (offer.grantsExperience) "Want Payment" else "Current Payment"
            return listOf(
                TooltipLine("$action ${offer.displayName}", TOOLTIP_TITLE),
                TooltipLine("$payment: ${offer.emeraldReward} emerald each", TOOLTIP_PRICE),
                TooltipLine("Base Value: ${offer.baseValue}", TOOLTIP_DETAIL),
                if (offer.grantsExperience) {
                    TooltipLine("Demand: unlimited", TOOLTIP_FULL)
                } else {
                    TooltipLine("Demand: ${offer.demandPercent}% (${offer.remainingNeed}/${offer.maxDailyNeed})", stockColor(offer.remainingNeed, offer.maxDailyNeed))
                },
                if (offer.grantsExperience) {
                    TooltipLine("Want turn-ins do not run out", TOOLTIP_DETAIL)
                } else {
                    TooltipLine("${offer.demandLabel}: ${signed(offer.demandModifier)}", modifierColor(offer.demandModifier))
                },
                TooltipLine("Daily Market: ${signed(offer.dailyModifier)}", modifierColor(offer.dailyModifier)),
                if (offer.experienceReward > 0) TooltipLine("Village XP: +${offer.experienceReward} each", TOOLTIP_PRICE) else TooltipLine("", TOOLTIP_DETAIL),
                TooltipLine("Requested by ${offer.requesterCount} ${offer.requesterProfession}(s)", TOOLTIP_DETAIL),
                TooltipLine("Refreshes in ${formatRestockTime(handler.snapshot.needRefreshTicks)}", TOOLTIP_TIMER)
            ).filter { it.text.isNotBlank() }
        }

        return null
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

    private fun formatRestockTime(snapshotTicks: Long): String {
        val remainingTicks = (snapshotTicks - localTicksSinceSnapshot).coerceAtLeast(0L)
        val totalSeconds = (remainingTicks / 20L).coerceAtLeast(0L)
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        return if (minutes > 0) "${minutes}m ${seconds}s" else "${seconds}s"
    }

    private fun buyOfferQuantityLabel(grantsExperience: Boolean, quantity: Int): String {
        return if (grantsExperience) "Any" else quantity.toString()
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

    private fun drawPanel(context: DrawContext, left: Int, top: Int, width: Int, height: Int, scrollOffset: Int, maxScroll: Int) {
        drawSunkenBox(context, left, top, width, height)
        context.fill(left + 2, top + 2, left + width - 8, top + height - 2, PANEL_FILL)
        drawScrollGutter(context, left + width - 8, top + 2, height - 4, scrollOffset, maxScroll)
    }

    private fun drawScrollGutter(context: DrawContext, left: Int, top: Int, height: Int, scrollOffset: Int, maxScroll: Int) {
        context.fill(left, top, left + 6, top + height, PANEL_FILL)
        context.fill(left, top, left + 1, top + height, DARK_EDGE)
        context.fill(left + 5, top, left + 6, top + height, LIGHT_EDGE)
        val trackTop = top + 2
        val trackHeight = height - 4
        val thumbHeight = if (maxScroll <= 0) {
            trackHeight
        } else {
            ((trackHeight * VISIBLE_ROWS) / (VISIBLE_ROWS + maxScroll)).coerceAtLeast(14)
        }
        val thumbTravel = (trackHeight - thumbHeight).coerceAtLeast(0)
        val thumbOffset = if (maxScroll <= 0) 0 else (thumbTravel * scrollOffset) / maxScroll
        context.fill(left + 2, trackTop + thumbOffset, left + 4, trackTop + thumbOffset + thumbHeight, SCROLL_THUMB)
    }

    private fun drawSlot(context: DrawContext, left: Int, top: Int) {
        drawSunkenBox(context, left, top, 17, 17)
        context.fill(left + 2, top + 2, left + 16, top + 16, SLOT_FILL)
    }

    private fun drawBorder(context: DrawContext, left: Int, top: Int, width: Int, height: Int, color: Int) {
        context.fill(left, top, left + width, top + 1, color)
        context.fill(left, top + height - 1, left + width, top + height, color)
        context.fill(left, top, left + 1, top + height, color)
        context.fill(left + width - 1, top, left + width, top + height, color)
    }

    private fun drawRaisedBox(context: DrawContext, left: Int, top: Int, width: Int, height: Int) {
        context.fill(left, top, left + width, top + height, SCREEN_BG)
        context.fill(left, top, left + width, top + 1, LIGHT_EDGE)
        context.fill(left, top, left + 1, top + height, LIGHT_EDGE)
        context.fill(left, top + height - 1, left + width, top + height, DARK_EDGE)
        context.fill(left + width - 1, top, left + width, top + height, DARK_EDGE)
    }

    private fun drawSunkenBox(context: DrawContext, left: Int, top: Int, width: Int, height: Int) {
        context.fill(left, top, left + width, top + height, PANEL_FILL)
        context.fill(left, top, left + width, top + 1, DARK_EDGE)
        context.fill(left, top, left + 1, top + height, DARK_EDGE)
        context.fill(left, top + height - 1, left + width, top + height, LIGHT_EDGE)
        context.fill(left + width - 1, top, left + width, top + height, LIGHT_EDGE)
    }

    private fun drawTradeRow(context: DrawContext, left: Int, top: Int, width: Int, selected: Boolean) {
        val fill = if (selected) SELECTED_ROW else TRADE_ROW
        context.fill(left, top, left + width, top + 16, fill)
        context.fill(left, top, left + width, top + 1, ROW_LIGHT)
        context.fill(left, top + 15, left + width, top + 16, ROW_DARK)
        context.fill(left, top, left + 1, top + 16, ROW_LIGHT)
        context.fill(left + width - 1, top, left + width, top + 16, ROW_DARK)
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
        val panelTop = y + 18
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

    private fun maxSellScroll(): Int = (sellOffers.size - VISIBLE_ROWS).coerceAtLeast(0)

    private fun maxBuyScroll(): Int = (buyOffers.size - VISIBLE_ROWS).coerceAtLeast(0)

    private fun visibleSellCount(): Int = sellOffers.size.coerceAtMost(VISIBLE_ROWS)

    private fun visibleBuyCount(): Int = buyOffers.size.coerceAtMost(VISIBLE_ROWS)

    private fun trimToWidth(value: String, maxWidth: Int): String {
        if (textRenderer.getWidth(value) <= maxWidth) return value
        var trimmed = value
        while (trimmed.isNotEmpty() && textRenderer.getWidth("$trimmed...") > maxWidth) {
            trimmed = trimmed.dropLast(1)
        }
        return "$trimmed..."
    }

    private fun onTradeClicked() {
        when (selectedSide) {
            SelectedSide.BUY -> onBuyClicked()
            SelectedSide.SELL -> onSellClicked()
            null -> Unit
        }
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

    private fun sendTradeRequest(direction: Int, professionId: String, quantity: Int) {
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

    private fun requestSnapshotRefresh() {
        ClientPlayNetworking.send(
            VillageTradeRefreshPayload(
                clerkX = handler.clerkX,
                clerkY = handler.clerkY,
                clerkZ = handler.clerkZ
            )
        )
    }

    fun applyServerSnapshot(payload: VillageTradeSyncPayload) {
        handler.updateSnapshot(payload.snapshot.toRuntime())
        localTicksSinceSnapshot = 0L
        refreshRequested = false
        sellScrollOffset = sellScrollOffset.coerceIn(0, maxSellScroll())
        buyScrollOffset = buyScrollOffset.coerceIn(0, maxBuyScroll())
        if (payload.message.isNotBlank()) {
            statusMessage = Text.literal(payload.message)
        }
        if (selectedSellIndex !in sellOffers.indices) {
            selectedSellIndex = -1
        }
        if (selectedBuyIndex !in buyOffers.indices) {
            selectedBuyIndex = -1
        }
        if (selectedSellIndex == -1 && selectedBuyIndex == -1) {
            selectedSide = null
        }
    }

    companion object {
        private const val TRADE_ALL_QUANTITY = Int.MAX_VALUE
        private const val VISIBLE_ROWS = 8
        private const val SCREEN_BG = 0xFFC7C7C7.toInt()
        private const val PANEL_FILL = 0xFF969696.toInt()
        private const val SLOT_FILL = 0xFF8F8F8F.toInt()
        private const val TRADE_ROW = 0xFF777777.toInt()
        private const val SELECTED_ROW = 0xFF8B948F.toInt()
        private const val LIGHT_EDGE = 0xFFFFFFFF.toInt()
        private const val DARK_EDGE = 0xFF373737.toInt()
        private const val ROW_LIGHT = 0xFF9E9E9E.toInt()
        private const val ROW_DARK = 0xFF4B4B4B.toInt()
        private const val SCROLL_THUMB = 0xFF777777.toInt()
        private const val TOOLTIP_BG = 0xF0101010.toInt()
        private const val TOOLTIP_BORDER = 0xFFE0E0E0.toInt()
        private const val TEXT_DARK = 0xFF303030.toInt()
        private const val TEXT_LIGHT = 0xFFFFFFFF.toInt()
        private const val EMERALD_TEXT = 0xFF2D6A2D.toInt()
        private const val XP_BAR_BG = 0xFF3B3B3B.toInt()
        private const val XP_BAR_FILL = 0xFF48C45B.toInt()
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
    }
}


