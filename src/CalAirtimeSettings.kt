package org.jetbrains.amper.ktor

import io.ktor.server.application.*

data class CalAirtimeSettings(
    val baseUrl: String,
    val dealer: String,
    val terminal: String,
    val apiKey: String,
    val password: String
)

fun Application.calAirtimeSettings(): CalAirtimeSettings {
    val config = environment.config
    return CalAirtimeSettings(
        baseUrl = (System.getenv("CAL_AIRTIME_BASE_URL") ?: config.propertyOrNull("calairtime.baseUrl")?.getString() ?: ""),
        dealer = (System.getenv("CAL_AIRTIME_DEALER") ?: config.propertyOrNull("calairtime.dealer")?.getString() ?: ""),
        terminal = (System.getenv("CAL_AIRTIME_TERMINAL") ?: config.propertyOrNull("calairtime.terminal")?.getString() ?: ""),
        apiKey = (System.getenv("CAL_AIRTIME_API_KEY") ?: config.propertyOrNull("calairtime.apiKey")?.getString() ?: ""),
        password = (System.getenv("CAL_AIRTIME_PASSWORD") ?: config.propertyOrNull("calairtime.password")?.getString() ?: "")
    ).also { settings ->
        require(settings.baseUrl.startsWith("https://")) { "CAL_AIRTIME_BASE_URL must use HTTPS." }
        require(listOf(settings.dealer, settings.terminal, settings.apiKey, settings.password).all { it.isNotBlank() && it != "ZZZ" }) {
            "Configure the real dealer, terminal, API key, and password on the backend."
        }
    }
}

