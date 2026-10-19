package com.calairtime.api.model

import kotlinx.serialization.Serializable

@Serializable
data class MessageId(
    val provider: String,
    val category: String,
    val product: String,
    val type: String,
    val dateTime: String,
    val seqNumber: Int
)