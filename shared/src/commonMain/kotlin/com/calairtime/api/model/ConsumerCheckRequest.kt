package com.calairtime.api.model

import kotlinx.serialization.Serializable

@Serializable
data class ConsumerCheckApiRequest(
    val `consumer-check`: ConsumerCheckRequest
)

@Serializable
data class ConsumerCheckRequest(
    val message: MessageId,
    val security: Security,
    val meterNumber: String,
    val payment: Payment
)

@Serializable
data class Security(
    val dealer: String,
    val terminal: String,
    val password: String
)

@Serializable
data class Payment(
    val type: String,
    val amount: Double,
    val tendered: Double
)