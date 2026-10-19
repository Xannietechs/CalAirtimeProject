package org.jetbrains.amper.ktor

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import java.io.File
import kotlin.test.*

class RoutingTest {
    @Test fun validationErrorsAreJsonWithBadRequestStatus() = testApplication {
        val file = File.createTempFile("route-", ".txt").apply { writeText("seq=11\ndateTime=\n") }
        val upstream = HttpClient(MockEngine { error("No upstream call expected") })
        val settings = CalAirtimeSettings("https://example.test/merchant", "dealer", "terminal", "key", "password")
        val db = File.createTempFile("routing-db-", ".db")
        val store = LocalStore(db)
        store.setupRoot("test-password-123")
        val token = store.login("root", "test-password-123")!!.second
        application { configureRouting(MerchantGateway(upstream, settings, MessageIdManager(file)), settings, store) }
        try {
            val response = client.post("/consumer-check?meter=00000000000&amount=NaN") { bearerAuth(token) }
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals(ContentType.Application.Json, response.contentType()?.withoutParameters())
            assertEquals(0, Json.parseToJsonElement(response.bodyAsText()).jsonObject["status"]!!.jsonObject["success"]!!.jsonPrimitive.int)
            assertTrue(file.readText().contains("seq=11"))
        } finally { upstream.close(); file.delete(); store.close(); db.delete() }
    }

    @Test fun voucherHistoryIsExposedAsJson() = testApplication {
        val file = File.createTempFile("route-", ".txt").apply { writeText("seq=11\ndateTime=\n") }
        val upstream = HttpClient(MockEngine { respond("""{"status":{"success":1},"voucherLog":[]}""") })
        val settings = CalAirtimeSettings("https://example.test/merchant", "dealer", "terminal", "key", "password")
        val db = File.createTempFile("routing-db-", ".db")
        val store = LocalStore(db)
        store.setupRoot("test-password-123")
        val token = store.login("root", "test-password-123")!!.second
        application { configureRouting(MerchantGateway(upstream, settings, MessageIdManager(file)), settings, store) }
        try {
            val response = client.post("/voucher-list") { bearerAuth(token) }
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(ContentType.Application.Json, response.contentType()?.withoutParameters())
            assertTrue(Json.parseToJsonElement(response.bodyAsText()).jsonObject.containsKey("voucherLog"))
        } finally { upstream.close(); file.delete(); store.close(); db.delete() }
    }
}

