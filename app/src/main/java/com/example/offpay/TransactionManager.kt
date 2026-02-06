package com.example.offpay

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class PendingTx(
    val id: String,
    val amount: Double,
    val direction: String,
    val timestamp: Long,
    val status: String = "pending"
)

class TransactionManager(private val context: Context) {

    private val prefs = context.getSharedPreferences("offpay_wallet", Context.MODE_PRIVATE)

    private val KEY_SETTLED_BALANCE = "settled_balance"
    private val KEY_PENDING_TX = "pending_transactions"
    private val KEY_APP_PIN = "app_pin"

    fun getSettledBalance(): Double {
        return prefs.getFloat(KEY_SETTLED_BALANCE, 1000f).toDouble()
    }

    private fun setSettledBalance(value: Double) {
        prefs.edit().putFloat(KEY_SETTLED_BALANCE, value.toFloat()).apply()
    }

    fun getAvailableBalance(): Double {
        var provisional = 0.0
        getPendingTransactions().forEach { tx ->
            if (tx.status == "pending") {
                provisional += if (tx.direction == "credit") tx.amount else -tx.amount
            }
        }
        return getSettledBalance() + provisional
    }

    fun getPendingTransactions(): List<PendingTx> {
        val jsonStr = prefs.getString(KEY_PENDING_TX, "[]") ?: "[]"
        val array = JSONArray(jsonStr)
        val list = mutableListOf<PendingTx>()

        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            list.add(
                PendingTx(
                    id = obj.optString("id", UUID.randomUUID().toString()),
                    amount = obj.optDouble("amount", 0.0),
                    direction = obj.optString("direction", "unknown"),
                    timestamp = obj.optLong("timestamp", 0L),
                    status = obj.optString("status", "pending")
                )
            )
        }
        return list
    }

    private fun savePendingTransactions(txList: List<PendingTx>) {
        val array = JSONArray()
        txList.forEach { tx ->
            val obj = JSONObject().apply {
                put("id", tx.id)
                put("amount", tx.amount)
                put("direction", tx.direction)
                put("timestamp", tx.timestamp)
                put("status", tx.status)
            }
            array.put(obj)
        }
        prefs.edit().putString(KEY_PENDING_TX, array.toString()).apply()
    }

    fun addPendingTransaction(amount: Double, direction: String) {
        val list = getPendingTransactions().toMutableList()
        val tx = PendingTx(
            id = UUID.randomUUID().toString(),
            amount = amount,
            direction = direction,
            timestamp = System.currentTimeMillis()
        )
        list.add(tx)
        savePendingTransactions(list)
    }

    fun settlePendingTransactions(): Int {
        val list = getPendingTransactions().toMutableList()
        var settledCount = 0

        list.forEachIndexed { index, tx ->
            if (tx.status == "pending") {
                val adjustment = if (tx.direction == "credit") tx.amount else -tx.amount
                setSettledBalance(getSettledBalance() + adjustment)
                list[index] = tx.copy(status = "settled")
                settledCount++
            }
        }

        savePendingTransactions(list)
        return settledCount
    }

    fun getPendingCount(): Int {
        return getPendingTransactions().count { it.status == "pending" }
    }

    fun isPinSet(): Boolean = prefs.contains(KEY_APP_PIN)

    fun setPin(pin: String) {
        if (pin.length == 4 && pin.all { it.isDigit() }) {
            prefs.edit().putString(KEY_APP_PIN, pin).apply()
        }
    }

    fun verifyPin(inputPin: String): Boolean {
        val stored = prefs.getString(KEY_APP_PIN, null) ?: return false
        return stored == inputPin
    }
}