package org.jetbrains.amper.ktor

import io.ktor.client.HttpClient

class CalAirtimeService(client: HttpClient, settings: CalAirtimeSettings, messageIdManager: MessageIdManager) {
    private val gateway = MerchantGateway(client, settings, messageIdManager)
    suspend fun consumerCheck(meterNumber: String, amount: Double): String = gateway.consumerCheck(meterNumber, amount)
}
