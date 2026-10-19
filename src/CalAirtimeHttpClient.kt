package org.jetbrains.amper.ktor

import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import io.ktor.client.plugins.HttpTimeout
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

fun createCalAirtimeHttpClient(): HttpClient {
    val insecureTestTls = System.getenv("CAL_AIRTIME_INSECURE_TEST_TLS") == "true"
    val context = if (insecureTestTls) {
        System.setProperty("jdk.internal.httpclient.disableHostnameVerification", "true")
        val trust = object : X509TrustManager {
            override fun getAcceptedIssuers() = emptyArray<java.security.cert.X509Certificate>()
            override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
        }
        SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(trust), java.security.SecureRandom()) }
    } else null
    return HttpClient(Java) {
        engine { config { if (context != null) sslContext(context) } }
        install(HttpTimeout) { requestTimeoutMillis = 60_000; connectTimeoutMillis = 15_000 }
        // Purchases must never be automatically retried.
    }
}
