@file:Suppress("DEPRECATION")

package net.pavinical.sovereign.client

import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext

object VillageFoundingOverlay {
    private const val DISPLAY_MILLIS = 3500L
    private const val TEXT_COLOR = 0xFFFFF3A6.toInt()
    private const val BACKGROUND = 0x99000000.toInt()
    private const val BORDER = 0x99FFE08A.toInt()

    private var message: String = ""
    private var visibleUntil: Long = 0L

    @Suppress("DEPRECATION")
    fun register() {
        HudRenderCallback.EVENT.register { context, _ ->
            render(context)
        }
    }

    fun show(value: String) {
        message = value
        visibleUntil = System.currentTimeMillis() + DISPLAY_MILLIS
    }

    private fun render(context: DrawContext) {
        if (message.isBlank() || System.currentTimeMillis() > visibleUntil) return

        val client = MinecraftClient.getInstance()
        val textRenderer = client.textRenderer
        val screenWidth = client.window.scaledWidth
        val textWidth = textRenderer.getWidth(message)
        val left = (screenWidth - textWidth) / 2 - 10
        val top = 10
        val right = left + textWidth + 20
        val bottom = top + 19

        context.fill(left, top, right, bottom, BACKGROUND)
        context.fill(left, top, right, top + 1, BORDER)
        context.fill(left, bottom - 1, right, bottom, BORDER)
        context.fill(left, top, left + 1, bottom, BORDER)
        context.fill(right - 1, top, right, bottom, BORDER)
        context.drawCenteredTextWithShadow(textRenderer, message, screenWidth / 2, top + 6, TEXT_COLOR)
    }
}

