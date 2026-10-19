package org.jetbrains.amper.ktor

import io.ktor.client.HttpClient

class CalAirtimeVoucherVend(client: HttpClient, settings: CalAirtimeSettings, messageIdManager: MessageIdManager) {
    private val gateway = MerchantGateway(client, settings, messageIdManager)
    suspend fun voucherVend(provider: String, product: String, amount: Double): String = gateway.voucherVend(provider, product, listOf(amount))
}
