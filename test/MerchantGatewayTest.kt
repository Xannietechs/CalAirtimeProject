package org.jetbrains.amper.ktor

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.io.File
import kotlin.test.*

class MerchantGatewayTest {
    private fun state() = File.createTempFile("merchant-", ".txt").apply { writeText("seq=11\ndateTime=\n") }
    private val settings = CalAirtimeSettings("https://example.test/custom/merchant", "dealer", "terminal", "key", "p\"ass")

    @Test fun pairedVendUsesExactIdAndEscapesFields() = runBlocking {
        val file = state()
        val bodies = mutableListOf<JsonObject>()
        val client = HttpClient(MockEngine { request ->
            assertEquals("/custom/merchant", request.url.encodedPath)
            assertEquals("key", request.url.parameters["apikey"])
            bodies += Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
            respond("""{ "status": { "success": 1 } }""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        try {
            val gateway = MerchantGateway(client, settings, MessageIdManager(file))
            gateway.consumerCheck("00000000000", 100.0)
            gateway.tokenVend("00000000000", 100.0)
            val check = bodies[0]["consumer-check"]!!.jsonObject
            val vend = bodies[1]["token-vend"]!!.jsonObject
            assertEquals(check["message"], vend["message"])
            assertEquals("p\"ass", vend["security"]!!.jsonObject["password"]!!.jsonPrimitive.content)
            gateway.voucherList()
            assertEquals(13, bodies[2]["voucher-list"]!!.jsonObject["message"]!!.jsonObject["seqNumber"]!!.jsonPrimitive.int)
        } finally { client.close(); file.delete() }
    }

    @Test fun failedCheckCannotVend() = runBlocking {
        val file = state()
        val client = HttpClient(MockEngine { respond("""{"status":{"success":0,"code":111111}}""") })
        try {
            val ids = MessageIdManager(file)
            val gateway = MerchantGateway(client, settings, ids)
            gateway.consumerCheck("07032796387", 100.0)
            assertFailsWith<IllegalStateException> { gateway.tokenVend("07032796387", 100.0) }
            assertEquals(13, ids.next().seqNumber)
        } finally { client.close(); file.delete() }
    }

    @Test fun invalidProductDoesNotConsumeSequence() = runBlocking {
        val file = state()
        val client = HttpClient(MockEngine { error("No request expected") })
        try {
            val ids = MessageIdManager(file)
            val gateway = MerchantGateway(client, settings, ids)
            assertFailsWith<IllegalArgumentException> { gateway.voucherVend("VCL", "buddie", listOf(10.0)) }
            assertFailsWith<IllegalArgumentException> { gateway.voucherVend("VCL", "mocha-o-chele", listOf(7.0)) }
            assertEquals(12, ids.next().seqNumber)
        } finally { client.close(); file.delete() }
    }
}
