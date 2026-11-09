package com.m57.hermescontrol.data.remote

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Reachability rules and an actual HTTP probe against a local test server. */
class HomeDashboardProbeTest {
    private lateinit var server: MockWebServer
    private val client = OkHttpClient()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    private fun baseUrl(path: String = "/"): String = server.url(path).toString()

    @Test
    fun `redirect is never treated as the dashboard`() {
        val report = HomeDashboardProbe.classify(302, "<html>portal</html>")
        assertEquals(HomeDashboardProbe.Result.UNREACHABLE, report.result)
    }

    @Test
    fun `401 and 403 prove the target server answered`() {
        assertEquals(
            HomeDashboardProbe.Result.REACHABLE,
            HomeDashboardProbe.classify(401, "").result,
        )
        assertEquals(
            HomeDashboardProbe.Result.REACHABLE,
            HomeDashboardProbe.classify(403, "").result,
        )
    }

    @Test
    fun `200 without a Hermes status payload is a portal page`() {
        assertEquals(
            HomeDashboardProbe.Result.UNREACHABLE,
            HomeDashboardProbe.classify(200, "<html><body>Login</body></html>").result,
        )
        assertEquals(
            HomeDashboardProbe.Result.REACHABLE,
            HomeDashboardProbe.classify(200, "{\"version\":\"1.2.3\"}").result,
        )
    }

    @Test
    fun `5xx from the dashboard still proves reachability`() {
        assertEquals(
            HomeDashboardProbe.Result.REACHABLE,
            HomeDashboardProbe.classify(500, "oops").result,
        )
    }

    @Test
    fun `probe keeps the reverse proxy path prefix for the status request`() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(401))
            val report = HomeDashboardProbe.probe(baseUrl("/hermes/"), client)
            assertEquals(HomeDashboardProbe.Result.REACHABLE, report.result)
            assertEquals("/hermes/api/status", server.takeRequest().path)
        }

    @Test
    fun `probe rejects a captive portal redirect`() =
        runBlocking {
            server.enqueue(
                MockResponse()
                    .setResponseCode(302)
                    .setHeader("Location", "http://portal.example.com/"),
            )
            val report = HomeDashboardProbe.probe(baseUrl(), client)
            assertEquals(HomeDashboardProbe.Result.UNREACHABLE, report.result)
        }

    @Test
    fun `probe reports a connection failure when the host is down`() =
        runBlocking {
            val address = baseUrl()
            server.shutdown()
            val report = HomeDashboardProbe.probe(address, client)
            assertEquals(HomeDashboardProbe.Result.UNREACHABLE, report.result)
            assertTrue(report.detail.isNotBlank())
        }

    @Test
    fun `probe rejects an invalid address before any network call`() =
        runBlocking {
            val report = HomeDashboardProbe.probe("not a url", client)
            assertEquals(HomeDashboardProbe.Result.UNREACHABLE, report.result)
            assertTrue(report.detail.contains("Invalid Home address"))
        }

    @Test
    fun `probe does not send cookies or follow redirects`() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(200).setBody("{\"overall\":\"ok\"}"))
            val report = HomeDashboardProbe.probe(baseUrl(), client)
            assertEquals(HomeDashboardProbe.Result.REACHABLE, report.result)
            val request = server.takeRequest()
            assertEquals(null, request.getHeader("Cookie"))
            assertEquals("application/json", request.getHeader("Accept"))
        }
}
