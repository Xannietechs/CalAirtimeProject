package org.jetbrains.amper.ktor

import io.ktor.client.HttpClient

class CalAirtimeTokenReprint(client: HttpClient, settings: CalAirtimeSettings, messageIdManager: MessageIdManager) {
    private val gateway = MerchantGateway(client, settings, messageIdManager)
    suspend fun reprintByReceipt(receiptNumber: String): String = gateway.tokenReprint(receiptNumber, true)
    suspend fun reprintByMeter(meterNumber: String): String = gateway.tokenReprint(meterNumber, false)
}
