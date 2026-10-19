package org.jetbrains.amper.ktor

import java.io.IOException
import java.net.ConnectException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** Contains only allow-listed diagnostics, never upstream bodies, URLs or credential-bearing exception messages. */
class MerchantFailure(val category: String, detail: String, val messageId: MessageIdValue) : IOException(
    "$detail Request sequence ${messageId.seqNumber}, UTC ${messageId.dateTime}."
)

internal fun merchantFailure(error: Exception, id: MessageIdValue): MerchantFailure {
    if (error is MerchantFailure) return error
    val causes = generateSequence<Throwable>(error) { it.cause }.take(12).toList()
    val (category, detail) = when {
        causes.any { it is SSLException || it is java.security.cert.CertificateException } ->
            "TLS_CERTIFICATE" to "Secure connection failed during TLS/certificate verification. Ask the API administrator to check the gateway certificate and hostname."
        causes.any { it.javaClass.simpleName.contains("Timeout", ignoreCase = true) } ->
            "TIMEOUT" to "The merchant connection or response timed out. The request may have been processed."
        causes.any { it is UnknownHostException } ->
            "DNS" to "The merchant server address could not be resolved. Check the backend base URL and network."
        causes.any { it is ConnectException } ->
            "CONNECT" to "A connection to the merchant server could not be established. Check the network, gateway port and firewall."
        else -> "TRANSPORT" to "The merchant connection failed without a confirmed response. Ask the administrator to check gateway logs."
    }
    return MerchantFailure(category, detail, id)
}
