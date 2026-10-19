package com.calairtime.api.model

import kotlinx.serialization.Serializable

@Serializable
data class ConsumerCheckResponse(
    val status: ApiStatus,
    val message: MessageId,
    val consumer: Consumer? = null,
    val poc: Poc? = null,
    val minVendAmnt: Double? = null,
    val maxVendAmnt: Double? = null,
    val meter: Meter? = null,
    val fbe: Double? = null,
    val vendAmount: Double? = null
)

@Serializable
data class ApiStatus(
    val success: Int,
    val description: String,
    val code: Int
)

@Serializable
data class Consumer(
    val address: Address,
    val title: String,
    val firstname: String,
    val lastname: String,
    val fullname: String,
    val accNo: String
)

@Serializable
data class Poc(
    val name: String,
    val standNo: String,
    val address: Address
)

@Serializable
data class Address(
    val line1: String,
    val line2: String,
    val line3: String,
    val postCode: String? = null,
    val town: String? = null
)

@Serializable
data class Meter(
    val number: String,
    val tt: String,
    val sgc: String,
    val ti: String,
    val krn: String,
    val ken: String
)