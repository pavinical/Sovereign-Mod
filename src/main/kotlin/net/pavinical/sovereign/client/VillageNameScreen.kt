package net.pavinical.sovereign.client

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.widget.ButtonWidget
import net.minecraft.client.gui.widget.TextFieldWidget
import net.minecraft.client.gui.tooltip.Tooltip
import net.minecraft.text.Text
import net.pavinical.sovereign.economy.VillageNameSubmitPayload
import kotlin.random.Random

class VillageNameScreen(
    private val clerkX: Int,
    private val clerkY: Int,
    private val clerkZ: Int,
    private val biome: String
) : Screen(Text.literal("Establish Village")) {
    private lateinit var nameField: TextFieldWidget
    private lateinit var rollButton: ButtonWidget

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
        rollButton = ButtonWidget.builder(Text.empty()) { rollName() }
            .dimensions(centerX + 98, top + 22, DICE_BUTTON_SIZE, DICE_BUTTON_SIZE)
            .build()
        rollButton.tooltip = Tooltip.of(Text.literal("Roll name"))
        addDrawableChild(rollButton)
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
        drawDiceIcon(context, rollButton.x, rollButton.y)
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

    private fun rollName() {
        nameField.text = randomVillageName(biome)
        setInitialFocus(nameField)
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

    private fun drawDiceIcon(context: DrawContext, buttonX: Int, buttonY: Int) {
        val left = buttonX + 5
        val top = buttonY + 5
        context.fill(left, top, left + 10, top + 10, DICE_BORDER)
        context.fill(left + 1, top + 1, left + 9, top + 9, DICE_FACE)
        drawPip(context, left + 2, top + 2)
        drawPip(context, left + 6, top + 2)
        drawPip(context, left + 4, top + 4)
        drawPip(context, left + 2, top + 6)
        drawPip(context, left + 6, top + 6)
    }

    private fun drawPip(context: DrawContext, x: Int, y: Int) {
        context.fill(x, y, x + 2, y + 2, DICE_PIP)
    }

    companion object {
        private const val MAX_NAME_LENGTH = 16
        private const val DICE_BUTTON_SIZE = 20
        private const val SCREEN_OVERLAY = 0x99000000.toInt()
        private const val TEXT_LIGHT = 0xFFFFFFFF.toInt()
        private const val TEXT_DARK = 0xFF2F1F12.toInt()
        private const val PLACEHOLDER_TEXT = 0xFF7B5430.toInt()
        private const val SIGN_FACE = 0xFFC9914B.toInt()
        private const val SIGN_EDGE = 0xFF6D4421.toInt()
        private const val SIGN_GRAIN = 0xFFB17838.toInt()
        private const val SIGN_TEXT_BACKING = 0x33FFE0A3
        private const val SIGN_POST = 0xFF7A4A23.toInt()
        private const val DICE_BUTTON_BORDER = 0xFF111111.toInt()
        private const val DICE_BUTTON_FACE = 0xFF8C8C8C.toInt()
        private const val DICE_BUTTON_HOVER = 0xFFA6A6A6.toInt()
        private const val DICE_BUTTON_LIGHT = 0xFFDCDCDC.toInt()
        private const val DICE_BUTTON_SHADOW = 0xFF4F4F4F.toInt()
        private const val DICE_BORDER = 0xFF2F1F12.toInt()
        private const val DICE_FACE = 0xFFF4E0B8.toInt()
        private const val DICE_PIP = 0xFF2F1F12.toInt()

        private data class NameTheme(
            val aliases: Set<String>,
            val prefixes: List<String>,
            val suffixes: List<String>
        )

        private val PLAINS_THEME = NameTheme(
            aliases = setOf("plains", "meadow", "forest", "flower", "cherry", "birch"),
            prefixes = listOf("Alder", "Ash", "Brom", "Cott", "Elder", "Fen", "Harth", "King", "Mere", "Oak", "Shep", "Wold"),
            suffixes = listOf("bury", "ford", "ham", "ley", "mere", "stead", "stow", "ton", "wick", "worth")
        )

        private val DESERT_THEME = NameTheme(
            aliases = setOf("desert", "badlands", "mesa"),
            prefixes = listOf("Ain", "Al", "Bahr", "Dahab", "Dar", "Jabal", "Qasr", "Rimal", "Safa", "Shams", "Suk", "Wadi"),
            suffixes = listOf("abad", "amir", "ayn", "basra", "dara", "farah", "halim", "jari", "nahr", "qamar", "sahil", "zahir")
        )

        private val TAIGA_THEME = NameTheme(
            aliases = setOf("taiga", "old_growth", "spruce", "pine", "grove"),
            prefixes = listOf("Adal", "Berg", "Eik", "Falk", "Ger", "Grim", "Hagen", "Lind", "Nord", "Stein", "Tann", "Wulf"),
            suffixes = listOf("berg", "born", "bruck", "dorf", "fels", "hain", "heim", "holt", "mark", "wald")
        )

        private val SAVANNA_THEME = NameTheme(
            aliases = setOf("savanna", "savannah"),
            prefixes = listOf("Amani", "Bayo", "Dara", "Kito", "Kwame", "Mosi", "Nia", "Safi", "Sena", "Tano", "Zola", "Zuri"),
            suffixes = listOf("bala", "duma", "kora", "kunda", "mani", "mara", "nara", "saba", "tamu", "zuri")
        )

        private val SNOWY_THEME = NameTheme(
            aliases = setOf("snowy", "cold", "ice", "frozen", "snow", "jagged", "frost"),
            prefixes = listOf("Belo", "Bor", "Dmit", "Ivan", "Kras", "Luka", "Mira", "Nov", "Orel", "Sneg", "Volk", "Yaro"),
            suffixes = listOf("grad", "grod", "ino", "mir", "ovka", "sk", "slav", "vka", "yar", "zima")
        )

        private val FALLBACK_THEME = NameTheme(
            aliases = emptySet(),
            prefixes = listOf("Ash", "Barrow", "Briar", "Brindle", "Brook", "Cairn", "Dawn", "Elder", "Fen", "Fox", "Glen", "Harth", "High", "Iron", "Moss", "Oak", "Raven", "Red", "Stone", "Willow"),
            suffixes = listOf("barrow", "bridge", "brook", "bury", "dale", "fall", "field", "ford", "gate", "glen", "hall", "hollow", "mere", "stead", "ton", "vale", "watch", "wick", "wood", "worth")
        )

        private val THEMES = listOf(
            SNOWY_THEME,
            DESERT_THEME,
            TAIGA_THEME,
            SAVANNA_THEME,
            PLAINS_THEME
        )

        private fun randomVillageName(biome: String): String {
            val theme = themeForBiome(biome)
            repeat(20) {
                val name = theme.prefixes.random(Random.Default) + theme.suffixes.random(Random.Default)
                if (name.length <= MAX_NAME_LENGTH) return name
            }
            return "Oakstead"
        }

        private fun themeForBiome(biome: String): NameTheme {
            val key = biome.lowercase().substringAfterLast(':').substringAfterLast('/')
            return THEMES.firstOrNull { theme ->
                theme.aliases.any { alias -> key.contains(alias) }
            } ?: FALLBACK_THEME
        }
    }
}

