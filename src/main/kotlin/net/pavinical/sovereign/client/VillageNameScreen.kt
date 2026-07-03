package net.pavinical.sovereign.client

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.widget.ButtonWidget
import net.minecraft.client.gui.widget.TextFieldWidget
import net.minecraft.text.Text
import net.pavinical.sovereign.economy.VillageNameSubmitPayload

class VillageNameScreen(
    private val clerkX: Int,
    private val clerkY: Int,
    private val clerkZ: Int
) : Screen(Text.literal("Establish Village")) {
    private lateinit var nameField: TextFieldWidget

    override fun init() {
        val centerX = width / 2
        val top = height / 2 - 58

        nameField = TextFieldWidget(
            textRenderer,
            centerX - 65,
            top + 57,
            130,
            20,
            Text.literal("Village Name")
        )
        nameField.setMaxLength(MAX_NAME_LENGTH)
        nameField.setDrawsBackground(false)
        nameField.setEditableColor(TEXT_DARK)
        nameField.setTextPredicate { value -> value.none { it.isDigit() } }
        addSelectableChild(nameField)
        setInitialFocus(nameField)

        addDrawableChild(
            ButtonWidget.builder(Text.literal("Done")) { submitName() }
                .dimensions(centerX - 82, top + 104, 76, 20)
                .build()
        )
        addDrawableChild(
            ButtonWidget.builder(Text.literal("Cancel")) { close() }
                .dimensions(centerX + 6, top + 104, 76, 20)
                .build()
        )
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        context.fill(0, 0, width, height, SCREEN_OVERLAY)
        super.render(context, mouseX, mouseY, delta)

        val centerX = width / 2
        val top = height / 2 - 58

        context.drawCenteredTextWithShadow(
            textRenderer,
            "Establish village with name",
            centerX,
            top,
            TEXT_LIGHT
        )
        drawSign(context, centerX - 92, top + 22, 184, 76)
        drawCenteredFieldText(context, centerX, top + 61)
    }

    private fun submitName() {
        ClientPlayNetworking.send(
            VillageNameSubmitPayload(
                clerkX = clerkX,
                clerkY = clerkY,
                clerkZ = clerkZ,
                name = nameField.text.take(MAX_NAME_LENGTH)
            )
        )
        close()
    }

    private fun drawCenteredFieldText(context: DrawContext, centerX: Int, baselineY: Int) {
        val shownName = nameField.text.ifBlank { "Village Name" }
        val color = if (nameField.text.isBlank()) PLACEHOLDER_TEXT else TEXT_DARK
        val scale = 1.45f
        val textWidth = textRenderer.getWidth(shownName) * scale
        val textX = ((centerX - textWidth / 2f) / scale)
        val textY = baselineY / scale

        context.matrices.push()
        context.matrices.scale(scale, scale, 1f)
        context.drawText(textRenderer, shownName, textX.toInt(), textY.toInt(), color, false)
        context.matrices.pop()
        nameField.setPosition((centerX - textRenderer.getWidth(nameField.text) / 2).coerceAtLeast(centerX - 65), baselineY - 4)
    }

    private fun drawSign(context: DrawContext, left: Int, top: Int, width: Int, height: Int) {
        context.fill(left, top, left + width, top + height, SIGN_EDGE)
        context.fill(left + 3, top + 3, left + width - 3, top + height - 3, SIGN_FACE)
        context.fill(left + 25, top + 34, left + width - 25, top + 57, SIGN_TEXT_BACKING)
        context.fill(left + 8, top + 9, left + width - 8, top + 11, SIGN_GRAIN)
        context.fill(left + 8, top + height - 13, left + width - 8, top + height - 11, SIGN_GRAIN)
        context.fill(left + width / 2 - 2, top + height, left + width / 2 + 2, top + height + 22, SIGN_POST)
    }

    companion object {
        private const val MAX_NAME_LENGTH = 16
        private const val SCREEN_OVERLAY = 0x99000000.toInt()
        private const val TEXT_LIGHT = 0xFFFFFFFF.toInt()
        private const val TEXT_DARK = 0xFF2F1F12.toInt()
        private const val PLACEHOLDER_TEXT = 0xFF7B5430.toInt()
        private const val SIGN_FACE = 0xFFC9914B.toInt()
        private const val SIGN_EDGE = 0xFF6D4421.toInt()
        private const val SIGN_GRAIN = 0xFFB17838.toInt()
        private const val SIGN_TEXT_BACKING = 0x33FFE0A3
        private const val SIGN_POST = 0xFF7A4A23.toInt()
    }
}

