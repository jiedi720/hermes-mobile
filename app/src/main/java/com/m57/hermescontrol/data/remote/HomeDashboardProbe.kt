package com.m57.hermescontrol.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/**
 * Lightweight, independent reachability probe for the Home dashboard.
 *
 * It deliberately does NOT use the authenticated REST stack: no bearer token,
 * no shared cookie jar, and no redirect following. A captive portal returning
 * a 302 (or a 200 HTML login page) must never be mistaken for the NAS.
 *
 * The client is derived from [OkHttpProvider.base] so mTLS client certificates,
 * SSH-tunnel routing and scoped Cloudflare Access / custom headers keep working,
 * but its cookie jar is replaced with an empty one and all timeouts are short.
 */
object HomeDashboardProbe {
    const val PROBE_PATH = "api/status"

    /** Single probe stays inside the recommended 2-3 second budget. */
    const val CONNECT_TIMEOUT_MS = 900L
    const val READ_TIMEOUT_MS = 900L
    const val WRITE_TIMEOUT_MS = 900L

    enum class Result {
        REACHABLE,
        UNREACHABLE,
    }

    data class Report(
        val result: Result,
        val httpCode: Int? = null,
        val detail: String = "",
    )

    private val NO_COOKIES =
        object : CookieJar {
            override fun loadForRequest(url: HttpUrl): List<Cookie> = emptyList()

            override fun saveFromResponse(
                url: HttpUrl,
                cookies: List<Cookie>,
            ) = Unit
        }

    /** Build the isolated probe client. Injectable in tests via [probe]. */
    fun createClient(): OkHttpClient =
        OkHttpProvider.base
            .newBuilder()
            .cookieJar(NO_COOKIES)
            .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .writeTimeout(WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

    suspend fun probe(
        baseUrl: String,
        client: OkHttpClient = createClient(),
    ): Report =
        withContext(Dispatchers.IO) {
            val endpoint =
                runCatching {
                    ServerEndpoint.parse(baseUrl, CleartextPolicy.ALLOW_WITH_WARNING)
                }.getOrElse { error ->
                    return@withContext Report(
                        Result.UNREACHABLE,
                        detail = "Invalid Home address: ${error.message}",
                    )
                }
            val url = endpoint.resolve(PROBE_PATH)
            val request =
                Request
                    .Builder()
                    .url(url)
                    .get()
                    .header("Accept", "application/json")
                    .build()
            try {
                client.newCall(request).await().use { response ->
                    classify(response.code, runCatching { response.body.string().take(4096) }.getOrDefault(""))
                }
            } catch (error: IOException) {
                Report(Result.UNREACHABLE, detail = transportReason(error))
            } catch (error: Exception) {
                Report(Result.UNREACHABLE, detail = "Probe failed: ${error.javaClass.simpleName}")
            }
        }

    /** Visible for pure unit tests of the classification rules. */
    internal fun classify(
        code: Int,
        body: String,
    ): Report =
        when {
            // Captive portals / HTTP->HTTPS landing pages answer with a redirect.
            code in 300..399 -> {
                Report(Result.UNREACHABLE, code, "HTTP $code redirect - not the dashboard")
            }

            // Any authenticated-server response proves the NAS answered, including
            // 401/403 (auth required) and 4xx/5xx (server up, request refused).
            code == 401 || code == 403 || code !in 200..299 -> {
                Report(Result.REACHABLE, code, "HTTP $code from dashboard")
            }

            code in 200..299 && looksLikeDashboard(body) -> {
                Report(Result.REACHABLE, code, "HTTP $code with Hermes status payload")
            }

            else -> {
                Report(Result.UNREACHABLE, code, "HTTP $code without a Hermes status payload")
            }
        }

    /** A 200 from a portal login page is not the Hermes dashboard. */
    private fun looksLikeDashboard(body: String): Boolean =
        body.contains("\"version\"") ||
            body.contains("\"overall\"") ||
            body.contains("\"gateway_running\"") ||
            body.contains("\"gateway\"")

    private fun transportReason(error: IOException): String =
        when (error) {
            is SSLException -> "TLS handshake failed"
            is java.net.UnknownHostException -> "DNS resolution failed"
            is java.net.SocketTimeoutException -> "Connection timed out"
            is java.net.ConnectException -> "Connection refused"
            else -> "Connection failed: ${error.javaClass.simpleName}"
        }
}
