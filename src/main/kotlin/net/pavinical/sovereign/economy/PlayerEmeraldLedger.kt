package net.pavinical.sovereign.economy

import net.minecraft.nbt.NbtCompound
import net.minecraft.registry.RegistryWrapper
import net.minecraft.server.world.ServerWorld
import net.minecraft.world.PersistentState
import java.util.UUID

class PlayerEmeraldLedger : PersistentState() {
    private val balances: MutableMap<UUID, Int> = mutableMapOf()

    fun balance(playerId: UUID): Int = balances[playerId] ?: 0

    fun setBalance(playerId: UUID, amount: Int): Int {
        val nextBalance = amount.coerceAtLeast(0)
        if (nextBalance > 0) {
            balances[playerId] = nextBalance
        } else {
            balances.remove(playerId)
        }
        markDirty()
        return nextBalance
    }

    fun deposit(playerId: UUID, amount: Int): Int {
        if (amount <= 0) return balance(playerId)
        val nextBalance = balance(playerId).saturatingAdd(amount)
        balances[playerId] = nextBalance
        markDirty()
        return nextBalance
    }

    fun withdraw(playerId: UUID, amount: Int): Boolean {
        if (amount <= 0) return true
        val currentBalance = balance(playerId)
        if (currentBalance < amount) return false
        val nextBalance = currentBalance - amount
        if (nextBalance > 0) {
            balances[playerId] = nextBalance
        } else {
            balances.remove(playerId)
        }
        markDirty()
        return true
    }

    override fun writeNbt(nbt: NbtCompound, registryLookup: RegistryWrapper.WrapperLookup): NbtCompound {
        val balancesTag = NbtCompound()
        for ((playerId, balance) in balances) {
            if (balance > 0) {
                balancesTag.putInt(playerId.toString(), balance)
            }
        }
        nbt.put("balances", balancesTag)
        return nbt
    }

    private fun Int.saturatingAdd(amount: Int): Int {
        val result = toLong() + amount.toLong()
        return result.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    companion object {
        private fun createFromNbt(nbt: NbtCompound, registryLookup: RegistryWrapper.WrapperLookup): PlayerEmeraldLedger {
            val ledger = PlayerEmeraldLedger()
            val balancesTag = nbt.getCompound("balances")
            for (key in balancesTag.keys) {
                val playerId = runCatching { UUID.fromString(key) }.getOrNull() ?: continue
                val balance = balancesTag.getInt(key)
                if (balance > 0) {
                    ledger.balances[playerId] = balance
                }
            }
            return ledger
        }

        val TYPE = PersistentState.Type(
            ::PlayerEmeraldLedger,
            ::createFromNbt,
            null
        )

        const val KEY = "sovereign_player_emerald_ledgers"

        fun get(world: ServerWorld): PlayerEmeraldLedger =
            world.persistentStateManager.getOrCreate(TYPE, KEY)
    }
}
