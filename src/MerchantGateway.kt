package org.jetbrains.amper.ktor

import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.time.LocalDate

/** One gateway per terminal. All calls share the same durable sequence and lock. */
class MerchantGateway(
    private val client: HttpClient,
    private val settings: CalAirtimeSettings,
    private val ids: MessageIdManager
) {
    private suspend fun send(operation: String, id: MessageIdValue, provider: String,
                             category: String, product: String,
                             fields: JsonObjectBuilder.() -> Unit = {}): String {
        val payload = buildJsonObject {
            putJsonObject(operation) {
                putJsonObject("message") {
                    put("provider", provider); put("category", category); put("product", product)
                    put("type", "request"); put("dateTime", id.dateTime); put("seqNumber", id.seqNumber)
                }
                putJsonObject("security") {
                    put("dealer", settings.dealer); put("terminal", settings.terminal)
                    put("password", settings.password)
                }
                fields()
            }
        }
        try {
            val response = client.post(settings.baseUrl) {
                parameter("apikey", settings.apiKey)
                contentType(ContentType.Application.Json)
                setBody(payload.toString())
            }
            if (!response.status.isSuccess()) {
                val code = response.status.value
                val detail = when (code) {
                    401, 403 -> "Merchant gateway rejected authentication (HTTP $code). Verify the real API key and terminal credentials with the administrator."
                    404 -> "Merchant endpoint not found (HTTP 404). Check CAL_AIRTIME_BASE_URL on the backend."
                    else -> "Merchant gateway returned HTTP $code without a confirmed transaction result."
                }
                throw MerchantFailure("HTTP_$code", detail, id)
            }
            val body = response.bodyAsText()
            val validStatus = runCatching {
                Json.parseToJsonElement(body).jsonObject["status"]?.jsonObject
                    ?.get("success")?.jsonPrimitive?.intOrNull in setOf(0, 1)
            }.getOrDefault(false)
            if (!validStatus) throw MerchantFailure("INVALID_RESPONSE", "Merchant returned an unreadable or missing transaction status. Ask the administrator to inspect the response.", id)
            return body
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            throw merchantFailure(e, id)
        }
    }

    suspend fun consumerCheck(meter: String, amount: Double, owner: String? = null): String = ids.requestMutex.withLock {
        validatePayment(meter, amount)
        val id = ids.startConsumerCheck(meter, amount, owner)
        val body = send("consumer-check", id, "LEC", "electricity", "prepaid-elec") {
            payment(meter, amount)
        }
        if (merchantSuccess(body)) ids.markConsumerCheckSuccessful() else ids.clearConsumerCheck()
        body
    }

    suspend fun tokenVend(meter: String, amount: Double, owner: String? = null): String = ids.requestMutex.withLock {
        validatePayment(meter, amount)
        val id = ids.claimVend(meter, amount, owner)
        // Persist the claim before sending: a timeout must never trigger a second purchase.
        val body = send("token-vend", id, "LEC", "electricity", "prepaid-elec") { payment(meter, amount) }
        if (merchantSuccess(body)) ids.clearConsumerCheck()
        body
    }

    suspend fun tokenReprint(value: String, byReceipt: Boolean): String = ids.requestMutex.withLock {
        require(value.isNotBlank()) { "Receipt or meter number is required." }
        send("token-reprint", ids.next(), "LEC", "electricity", "prepaid-elec") {
            put("receiptNo", value); put("reprintByReceipt", byReceipt)
        }
    }

    suspend fun voucherVend(provider: String, product: String, amounts: List<Double>): String = ids.requestMutex.withLock {
        val allowed = airtimeDenominations(provider, product)
        require(amounts.isNotEmpty() && amounts.size <= 100 && amounts.all { it in allowed }) {
            "Select valid denominations for this provider and product (maximum 100 vouchers)."
        }
        send("voucher-vend", ids.next(), provider, "airtime", product) {
            putJsonObject("denoms") { putJsonArray("denomination") { amounts.forEach { add(it) } } }
        }
    }

    suspend fun voucherList(): String = ids.requestMutex.withLock {
        send("voucher-list", ids.next(), "ALL", "airtime", "all")
    }

    suspend fun voucherReprint(provider: String, product: String, id: String, denom: Double, teller: String): String = ids.requestMutex.withLock {
        require(id.isNotBlank()) { "Voucher transaction ID is required." }
        require(denom in airtimeDenominations(provider, product)) { "Invalid denomination." }
        send("voucher-reprint", ids.next(), provider, "airtime", product) {
            put("id", id); put("denom", denom); put("teller", teller)
        }
    }

    suspend fun eos(date: String): String = ids.requestMutex.withLock {
        require(runCatching { LocalDate.parse(date) }.isSuccess) { "Shift date must use YYYY-MM-DD format." }
        send("eos", ids.next(), "ALL", "all", "all") { put("shiftDate", date) }
    }
}

internal fun merchantSuccess(body: String): Boolean =
    Json.parseToJsonElement(body).jsonObject["status"]?.jsonObject
        ?.get("success")?.jsonPrimitive?.intOrNull == 1

internal fun airtimeDenominations(provider: String, product: String): Set<Double> = when {
    provider == "VCL" && product == "mocha-o-chele" -> setOf(5.0, 10.0, 20.0, 50.0, 100.0)
    provider == "ETL" && product == "buddie" -> setOf(5.0, 10.0, 20.0)
    provider == "ETL" && product == "libertie" -> setOf(50.0, 100.0)
    else -> throw IllegalArgumentException("Unsupported provider and product combination.")
}

private fun validatePayment(meter: String, amount: Double) {
    require(meter.length == 11 && meter.all { it in '0'..'9' }) { "Enter an 11-digit meter number." }
    require(amount.isFinite() && amount > 0) { "Amount must be greater than zero." }
}

private fun JsonObjectBuilder.payment(meter: String, amount: Double) {
    put("meterNumber", meter)
    putJsonObject("payment") { put("type", "cash"); put("amount", amount); put("tendered", amount) }
}



