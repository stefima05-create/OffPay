package com.example.offpay

data class Transaction(
    val id: String,
    val sender: String,
    val receiver: String,
    val amount: Double,
    val timestamp: Long,
    val synced: Boolean
)