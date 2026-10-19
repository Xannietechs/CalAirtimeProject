package org.jetbrains.amper.ktor

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

private class AccessFailure(val code: HttpStatusCode, message: String) : RuntimeException(message)
private fun ApplicationCall.token() = request.headers[HttpHeaders.Authorization]?.removePrefix("Bearer ")
private suspend fun ApplicationCall.json(body: JsonElement, status: HttpStatusCode = HttpStatusCode.OK) =
    respondText(body.toString(), ContentType.Application.Json, status)
private suspend fun ApplicationCall.input(): JsonObject {
    require(request.contentType().withoutParameters() == ContentType.Application.Json) { "Send a JSON request body." }
    require((request.headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: 0) <= 8192) { "Request too large." }
    val text = receiveText()
    require(text.length <= 8192) { "Request too large." }
    return Json.parseToJsonElement(text).jsonObject
}
private fun JsonObject.field(name: String): String = this[name]?.jsonPrimitive?.contentOrNull
    ?: throw IllegalArgumentException("Required field: $name")
private fun ApplicationCall.operator(store: LocalStore): Operator = store.authenticate(token())
    ?: throw AccessFailure(HttpStatusCode.Unauthorized, "Sign in to continue.")
private fun Operator.root() {
    if (role != "root") throw AccessFailure(HttpStatusCode.Forbidden, "Root access required.")
}
private suspend fun ApplicationCall.handled(block: suspend () -> Unit) {
    response.headers.append(HttpHeaders.CacheControl, "no-store")
    try { withContext(Dispatchers.IO) { block() } }
    catch (e: CancellationException) { throw e }
    catch (e: Exception) {
        val status = when (e) {
            is AccessFailure -> e.code
            is IllegalArgumentException -> HttpStatusCode.BadRequest
            is IllegalStateException -> HttpStatusCode.Conflict
            else -> HttpStatusCode.InternalServerError
        }
        val description = if (status == HttpStatusCode.InternalServerError) "Local server error. Check server logs." else e.message ?: "Request failed."
        json(buildJsonObject { putJsonObject("status") {
            put("success", 0); put("description", description); put("code", status.value)
        } }, status)
    }
}

fun Application.configureRouting(gateway: MerchantGateway?, settings: CalAirtimeSettings?, store: LocalStore) {
    routing {
        get("/") { call.respondText("CalAirtime local server is running") }
        get("/health") { call.respondText("OK") }
        get("/auth/status") { call.handled { call.json(buildJsonObject { put("setupRequired", store.setupRequired()) }) } }
        post("/auth/setup") { call.handled {
            val body = call.input()
            val root = store.setupRoot(body.field("password"))
            call.json(root.json(), HttpStatusCode.Created)
        } }
        post("/auth/login") { call.handled {
            val body = call.input()
            val login = store.login(body.field("username"), body.field("password"))
                ?: throw AccessFailure(HttpStatusCode.Unauthorized, "Invalid username or password.")
            call.json(buildJsonObject { put("user", login.first.json()); put("token", login.second) })
        } }
        post("/auth/logout") { call.handled {
            call.operator(store); store.logout(call.token()!!)
            call.json(buildJsonObject { put("success", true) })
        } }
        get("/auth/me") { call.handled { call.json(call.operator(store).json()) } }
        post("/auth/password") { call.handled {
            val user = call.operator(store); val body = call.input()
            store.changePassword(user, body.field("oldPassword"), body.field("password"))
            call.json(buildJsonObject { put("success", true) })
        } }
        get("/admin/users") { call.handled {
            val user = call.operator(store); user.root(); call.json(store.users(user))
        } }
        post("/admin/users") { call.handled {
            val user = call.operator(store); user.root(); val body = call.input()
            call.json(store.createUser(user, body.field("username"), body.field("password")).json(), HttpStatusCode.Created)
        } }
        post("/admin/users/{id}/{action}") { call.handled {
            val user = call.operator(store); user.root(); val body = call.input()
            val id = call.parameters["id"]?.toLongOrNull() ?: throw IllegalArgumentException("Invalid user ID.")
            when (call.parameters["action"]) {
                "active" -> store.setActive(user, id, body["active"]?.jsonPrimitive?.booleanOrNull ?: throw IllegalArgumentException("Active must be true or false."))
                "password" -> store.resetPassword(user, id, body.field("password"))
                else -> throw AccessFailure(HttpStatusCode.NotFound, "Unknown action.")
            }
            call.json(buildJsonObject { put("success", true) })
        } }
        get("/records") { call.handled {
            val user = call.operator(store)
            call.json(store.records(user, call.request.queryParameters["before"]?.toLongOrNull() ?: Long.MAX_VALUE))
        } }
        get("/records/{id}") { call.handled {
            val user = call.operator(store)
            val id = call.parameters["id"]?.toLongOrNull() ?: throw IllegalArgumentException("Invalid record ID.")
            call.json(store.record(user, id) ?: throw AccessFailure(HttpStatusCode.NotFound, "Record not found."))
        } }
        get("/config-status") { call.handled {
            call.operator(store)
            call.json(buildJsonObject {
                put("configured", gateway != null)
                put("description", if (gateway == null) "Local accounts and records are ready. Configure merchant credentials and sequence before live sales."
                    else "Dealer=${settings?.dealer}, Terminal=${settings?.terminal}")
            })
        } }
        post("/{operation}") { call.handled {
            val user = call.operator(store)
            val operation = call.parameters["operation"]!!
            val allowed = setOf("consumer-check", "token-vend", "token-reprint", "voucher-vend", "voucher-list", "voucher-reprint", "eos")
            if (operation !in allowed) throw AccessFailure(HttpStatusCode.NotFound, "Unknown operation.")
            // Provider history/reprints span the whole terminal. Cashiers retrieve their own saved receipts via /records.
            if (operation in setOf("voucher-list", "voucher-reprint", "token-reprint", "eos")) user.root()
            val params = call.request.queryParameters
            fun required(name: String) = params[name]?.takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("Required parameter: $name")
            fun number(name: String) = required(name).toDoubleOrNull()
                ?: throw IllegalArgumentException("Invalid number: $name")
            val permittedFields = setOf("meter", "amount", "provider", "product", "receipt", "id", "denom", "shiftDate")
            val summary = buildJsonObject { params.names().filter { it in permittedFields }.forEach { put(it, params[it]) } }
            val record = store.beginRecord(user, operation, summary)
            try {
                val merchant = gateway ?: throw IllegalStateException("Merchant connection is not configured. Root must configure the API credentials and sequence on the local server.")
                val result = when (operation) {
                    "consumer-check" -> merchant.consumerCheck(required("meter"), number("amount"), user.id.toString())
                    "token-vend" -> merchant.tokenVend(required("meter"), number("amount"), user.id.toString())
                    "token-reprint" -> merchant.tokenReprint(params["receipt"] ?: required("meter"), params["receipt"] != null)
                    "voucher-vend" -> merchant.voucherVend(required("provider"), required("product"), required("amount").split(',').map {
                        it.trim().toDoubleOrNull() ?: throw IllegalArgumentException("Invalid denomination.")
                    })
                    "voucher-list" -> merchant.voucherList()
                    "voucher-reprint" -> merchant.voucherReprint(required("provider"), required("product"), required("id"), number("denom"), user.username)
                    else -> merchant.eos(required("shiftDate"))
                }
                val success = merchantSuccess(result)
                store.finishRecord(record, if (success) "success" else "failed", result)
                call.response.headers.append("X-Record-Id", record.toString())
                call.respondText(result, ContentType.Application.Json)
            } catch (e: CancellationException) {
                store.finishRecord(record, "unknown", "Request interrupted. Reconcile with the administrator before retrying.")
                throw e
            } catch (e: Exception) {
                val local = e is IllegalArgumentException || e is IllegalStateException
                val diagnostic = if (e is MerchantFailure) e.message!! else "No confirmed response."
                val saved = if (e is MerchantFailure) buildJsonObject {
                    put("category", e.category); put("description", diagnostic)
                    put("seqNumber", e.messageId.seqNumber); put("dateTime", e.messageId.dateTime)
                    put("outcome", "unconfirmed; reconcile before retrying")
                }.toString() else if (local) e.message ?: "Rejected" else diagnostic
                store.finishRecord(record, if (local) "rejected" else "unknown", saved)
                if (!local) throw AccessFailure(HttpStatusCode.BadGateway, "$diagnostic Record $record saved. Reconcile with the administrator before retrying.")
                throw e
            }
        } }
    }
}

