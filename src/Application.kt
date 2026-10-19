package org.jetbrains.amper.ktor

import io.ktor.server.application.*
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.http.*
import java.io.File

fun main(args: Array<String>) = io.ktor.server.netty.EngineMain.main(args)

fun Application.module() {
    install(CORS) {
        (System.getenv("CAL_AIRTIME_WEB_ORIGINS") ?: "localhost:8081,127.0.0.1:8081").split(',').forEach {
            allowHost(it.trim(), schemes = listOf("http", "https"))
        }
        allowMethod(HttpMethod.Post)
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.ContentType)
        exposeHeader("X-Record-Id")
    }
    val store = LocalStore(File(System.getenv("CAL_AIRTIME_DB") ?: "data/calairtime.db"))
    val settings = runCatching { calAirtimeSettings() }.getOrNull()
    val client = createCalAirtimeHttpClient()
    val gateway = settings?.let { config ->
        runCatching { MerchantGateway(client, config, MessageIdManager()) }.getOrElse {
            log.warn("Merchant sequence needs configuration; local accounts and records remain available.")
            null
        }
    }
    if (settings == null) log.warn("Merchant credentials are not configured; local accounts and records remain available.")
    monitor.subscribe(ApplicationStopped) { client.close(); store.close() }
    configureRouting(gateway, settings, store)
}
