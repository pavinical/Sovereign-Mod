package net.pavinical.sovereign.event

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.minecraft.component.DataComponentTypes
import net.minecraft.component.type.NbtComponent
import net.minecraft.item.ItemStack
import net.minecraft.nbt.NbtCompound
import net.minecraft.nbt.NbtElement
import net.minecraft.nbt.NbtList
import net.minecraft.nbt.NbtLong
import net.minecraft.registry.RegistryWrapper
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.BlockPos
import net.minecraft.world.PersistentState

object CivilianJobBlockService {
    const val TAG_CIVILIAN_JOB_BLOCK = "sovereign_civilian_job_block"
    private const val CLEANUP_INTERVAL_TICKS = 100

    private val pendingPlacements = mutableMapOf<ServerWorld, MutableSet<BlockPos>>()

    fun register() {
        ServerTickEvents.END_WORLD_TICK.register { world ->
            processPendingPlacements(world)
            if (world.time % CLEANUP_INTERVAL_TICKS == 0L) {
                cleanupCivilianPois(world)
            }
        }
    }

    fun isCivilianJobBlockStack(stack: ItemStack): Boolean {
        val customData = stack.get(DataComponentTypes.CUSTOM_DATA) ?: return false
        return customData.copyNbt().getBoolean(TAG_CIVILIAN_JOB_BLOCK)
    }

    fun markCivilianJobBlockStack(stack: ItemStack): ItemStack {
        NbtComponent.set(DataComponentTypes.CUSTOM_DATA, stack) { nbt ->
            nbt.putBoolean(TAG_CIVILIAN_JOB_BLOCK, true)
        }
        return stack
    }

    fun queuePlacement(world: ServerWorld, pos: BlockPos) {
        pendingPlacements.getOrPut(world) { mutableSetOf() }.add(pos.toImmutable())
    }

    fun isCivilianJobBlock(world: ServerWorld, pos: BlockPos): Boolean =
        State.get(world).contains(pos)

    fun remove(world: ServerWorld, pos: BlockPos) {
        if (State.get(world).remove(pos)) {
            world.pointOfInterestStorage.remove(pos)
        }
    }

    private fun processPendingPlacements(world: ServerWorld) {
        val pending = pendingPlacements.remove(world).orEmpty()
        if (pending.isEmpty()) return

        val state = State.get(world)
        for (pos in pending) {
            if (ProfessionBlockProtection.isJobBlock(world.getBlockState(pos).block)) {
                state.add(pos)
                world.pointOfInterestStorage.remove(pos)
            }
        }
    }

    private fun cleanupCivilianPois(world: ServerWorld) {
        val state = State.get(world)
        for (pos in state.positions()) {
            if (ProfessionBlockProtection.isJobBlock(world.getBlockState(pos).block)) {
                world.pointOfInterestStorage.remove(pos)
            } else {
                state.remove(pos)
            }
        }
    }

    class State : PersistentState() {
        private val positions = mutableSetOf<Long>()

        fun add(pos: BlockPos) {
            positions.add(pos.asLong())
            markDirty()
        }

        fun remove(pos: BlockPos): Boolean {
            val removed = positions.remove(pos.asLong())
            if (removed) markDirty()
            return removed
        }

        fun contains(pos: BlockPos): Boolean =
            positions.contains(pos.asLong())

        fun positions(): List<BlockPos> =
            positions.map(BlockPos::fromLong)

        override fun writeNbt(nbt: NbtCompound, registryLookup: RegistryWrapper.WrapperLookup): NbtCompound {
            val list = NbtList()
            for (pos in positions) {
                list.add(net.minecraft.nbt.NbtLong.of(pos))
            }
            nbt.put("positions", list)
            return nbt
        }

        companion object {
            private fun createFromNbt(nbt: NbtCompound, registryLookup: RegistryWrapper.WrapperLookup): State {
                val state = State()
                val list = nbt.getList("positions", NbtElement.LONG_TYPE.toInt())
                for (i in 0 until list.size) {
                    state.positions.add((list[i] as NbtLong).longValue())
                }
                return state
            }

            private val TYPE = Type(::State, ::createFromNbt, null)
            private const val KEY = "sovereign_civilian_job_blocks"

            fun get(world: ServerWorld): State =
                world.persistentStateManager.getOrCreate(TYPE, KEY)
        }
    }
}
