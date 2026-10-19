package org.jetbrains.amper.ktor

import io.ktor.client.HttpClient

class CalAirtimeTokenVend(client: HttpClient, settings: CalAirtimeSettings, messageIdManager: MessageIdManager) {
    private val gateway = MerchantGateway(client, settings, messageIdManager)
    suspend fun tokenVend(meterNumber: String, amount: Double): String = gateway.tokenVend(meterNumber, amount)
}
