package org.jetbrains.amper.ktor

import io.ktor.client.HttpClient

class CalAirtimeEos(client: HttpClient, settings: CalAirtimeSettings, messageIdManager: MessageIdManager) {
    private val gateway = MerchantGateway(client, settings, messageIdManager)
    suspend fun eos(shiftDate: String): String = gateway.eos(shiftDate)
}
