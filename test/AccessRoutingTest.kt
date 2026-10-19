package org.jetbrains.amper.ktor

import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import java.io.File
import kotlin.test.*

class AccessRoutingTest {
    @Test fun serverEnforcesRolesAndRecordOwnershipWithoutMerchantCredentials() = testApplication {
        val file = File.createTempFile("auth-route-", ".db")
        val store = LocalStore(file)
        val root = store.setupRoot("root-test-password")
        val alice = store.createUser(root, "alice", "alice-test-password")
        val bob = store.createUser(root, "bob", "bob-test-password")
        val rootToken = store.login("root", "root-test-password")!!.second
        val aliceToken = store.login("alice", "alice-test-password")!!.second
        val bobToken = store.login("bob", "bob-test-password")!!.second
        application { configureRouting(null, null, store) }
        try {
            assertEquals(HttpStatusCode.OK, client.get("/health").status)
            assertEquals(HttpStatusCode.Unauthorized, client.get("/records").status)
            assertEquals(HttpStatusCode.Unauthorized, client.post("/voucher-vend").status)
            assertEquals(HttpStatusCode.Forbidden, client.get("/admin/users") { bearerAuth(aliceToken) }.status)
            assertEquals(HttpStatusCode.Forbidden, client.post("/voucher-list") { bearerAuth(aliceToken) }.status)
            assertEquals(HttpStatusCode.Forbidden, client.post("/token-reprint?receipt=another") { bearerAuth(aliceToken) }.status)
            assertEquals(HttpStatusCode.OK, client.get("/admin/users") { bearerAuth(rootToken) }.status)
            val response = client.post("/voucher-vend?provider=VCL&product=mocha-o-chele&amount=10&password=do-not-save") { bearerAuth(aliceToken) }
            assertEquals(HttpStatusCode.Conflict, response.status)
            val rows = Json.parseToJsonElement(client.get("/records") { bearerAuth(aliceToken) }.bodyAsText()).jsonArray
            assertEquals(1, rows.size)
            assertFalse(rows.toString().contains("do-not-save"))
            val id = rows.single().jsonObject["id"]!!.jsonPrimitive.long
            assertEquals(HttpStatusCode.NotFound, client.get("/records/$id") { bearerAuth(bobToken) }.status)
            assertEquals(HttpStatusCode.OK, client.get("/records/$id") { bearerAuth(rootToken) }.status)
            assertEquals(HttpStatusCode.Conflict, client.post("/auth/setup") {
                contentType(ContentType.Application.Json); setBody("""{"password":"another-password"}""")
            }.status)
            client.post("/auth/logout") { bearerAuth(aliceToken) }
            assertEquals(HttpStatusCode.Unauthorized, client.get("/records") { bearerAuth(aliceToken) }.status)
            assertTrue(store.records(bob).isEmpty())
        } finally { store.close(); file.delete() }
    }
}
