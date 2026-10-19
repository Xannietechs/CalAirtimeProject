package org.jetbrains.amper.ktor

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.io.File
import java.net.ConnectException
import javax.net.ssl.SSLHandshakeException
import kotlin.test.*

class MerchantFailureTest {
    @Test fun diagnosticsNeverExposeCredentialBearingExceptions() {
        val id = MessageIdValue("2026-10-19 09:31:49", 13)
        val ssl = merchantFailure(java.io.IOException("secret-url?apikey=SECRET", SSLHandshakeException("PASSWORD")), id)
        assertEquals("TLS_CERTIFICATE", ssl.category)
        assertTrue(ssl.message!!.contains("sequence 13"))
        assertFalse(ssl.message!!.contains("SECRET"))
        assertFalse(ssl.message!!.contains("PASSWORD"))
        assertEquals("CONNECT", merchantFailure(ConnectException("private-url"), id).category)
        assertEquals("TIMEOUT", merchantFailure(java.net.SocketTimeoutException("private-url"), id).category)
    }
    @Test fun unauthorizedResponseIsReportedWithoutResponseBody() = runBlocking {
        val file = File.createTempFile("diagnostic-", ".txt").apply { writeText("seq=12\ndateTime=\n") }
        var calls = 0
        val client = HttpClient(MockEngine {
            calls++
            respond("secret gateway internals", HttpStatusCode.Unauthorized)
        })
        try {
            val gateway = MerchantGateway(client, CalAirtimeSettings("https://example.test/merchant", "dealer", "terminal", "SECRET", "PASSWORD"), MessageIdManager(file))
            val failure = assertFailsWith<MerchantFailure> { gateway.voucherVend("VCL", "mocha-o-chele", listOf(10.0)) }
            assertEquals("HTTP_401", failure.category)
            assertEquals(13, failure.messageId.seqNumber)
            assertFalse(failure.message!!.contains("secret gateway internals"))
            assertEquals(1, calls)
        } finally { client.close(); file.delete() }
    }
    @Test fun sequenceErrorsRemainVisibleAsMerchantResponses() = runBlocking {
        val file = File.createTempFile("sequence-error-", ".txt").apply { writeText("seq=12\ndateTime=\n") }
        val client = HttpClient(MockEngine { respond("""{"status":{"success":0,"description":"Message ID not unique","code":1004}}""") })
        try {
            val gateway = MerchantGateway(client, CalAirtimeSettings("https://example.test/merchant", "dealer", "terminal", "key", "password"), MessageIdManager(file))
            val response = gateway.voucherVend("VCL", "mocha-o-chele", listOf(10.0))
            assertEquals(1004, Json.parseToJsonElement(response).jsonObject["status"]!!.jsonObject["code"]!!.jsonPrimitive.int)
        } finally { client.close(); file.delete() }
    }
}
