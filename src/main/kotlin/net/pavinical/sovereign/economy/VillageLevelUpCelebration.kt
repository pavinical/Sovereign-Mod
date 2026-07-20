package net.pavinical.sovereign.economy

import it.unimi.dsi.fastutil.ints.IntArrayList
import net.minecraft.component.DataComponentTypes
import net.minecraft.component.type.FireworkExplosionComponent
import net.minecraft.component.type.FireworksComponent
import net.minecraft.entity.projectile.FireworkRocketEntity
import net.minecraft.item.ItemStack
import net.minecraft.item.Items
import net.minecraft.server.world.ServerWorld
import net.minecraft.sound.SoundCategory
import net.minecraft.sound.SoundEvents
import net.minecraft.util.math.BlockPos
import net.minecraft.world.Heightmap
import net.pavinical.sovereign.data.VillageData
import net.pavinical.sovereign.data.VillageTier

object VillageLevelUpCelebration {
    private const val CENTER_FIREWORK_RADIUS = 16
    private val FIREWORK_COLORS = intArrayOf(
        0xF0F0F0,
        0xFFD83D,
        0x50C878,
        0x44AAFF,
        0xFF6A9A,
        0xB57CFF
    )
    private val FIREWORK_SHAPES = listOf(
        FireworkExplosionComponent.Type.SMALL_BALL,
        FireworkExplosionComponent.Type.LARGE_BALL,
        FireworkExplosionComponent.Type.STAR,
        FireworkExplosionComponent.Type.BURST
    )

    fun play(world: ServerWorld, village: VillageData, oldTier: VillageTier, newTier: VillageTier = village.tier) {
        if (newTier.ordinal <= oldTier.ordinal) return

        world.playSound(
            null,
            village.centerPos,
            SoundEvents.UI_TOAST_CHALLENGE_COMPLETE,
            SoundCategory.MASTER,
            1.0f,
            1.0f
        )

        repeat(fireworkCountFor(newTier)) {
            launchFirework(world, village.centerPos)
        }
    }

    private fun launchFirework(world: ServerWorld, center: BlockPos) {
        val radius = CENTER_FIREWORK_RADIUS
        val x = center.x + world.random.nextInt(radius * 2 + 1) - radius
        val z = center.z + world.random.nextInt(radius * 2 + 1) - radius
        val y = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z)
        val stack = fireworkStack()
        val firework = FireworkRocketEntity(world, x + 0.5, y + 1.0, z + 0.5, stack)
        world.spawnEntity(firework)
    }

    private fun fireworkCountFor(tier: VillageTier): Int = when (tier) {
        VillageTier.HAMLET -> 1
        VillageTier.SETTLEMENT -> 5
        VillageTier.VILLAGE -> 10
        VillageTier.TOWN -> 15
        VillageTier.CITY -> 20
    }

    private fun fireworkStack(): ItemStack {
        val stack = ItemStack(Items.FIREWORK_ROCKET)
        val colors = IntArrayList()
        colors.add(FIREWORK_COLORS.random())
        colors.add(FIREWORK_COLORS.random())
        val fadeColors = IntArrayList()
        fadeColors.add(FIREWORK_COLORS.random())
        val explosion = FireworkExplosionComponent(
            FIREWORK_SHAPES.random(),
            colors,
            fadeColors,
            true,
            true
        )
        stack.set(DataComponentTypes.FIREWORKS, FireworksComponent(1, listOf(explosion)))
        return stack
    }
}
