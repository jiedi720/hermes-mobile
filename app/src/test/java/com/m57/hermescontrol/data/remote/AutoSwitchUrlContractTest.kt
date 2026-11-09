package com.m57.hermescontrol.data.remote

import com.m57.hermescontrol.data.config.validateDashboardAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Home/Remote presets must reach BOTH REST and WebSocket through the same
 * normalized endpoint: scheme -> ws/wss, port and reverse-proxy path prefix
 * preserved. These are the exact shapes used by a LAN address and by a
 * ugdocker.link style HTTPS tunnel.
 */
class AutoSwitchUrlContractTest {
    private fun endpoint(raw: String): ServerEndpoint =
        ServerEndpoint.parse(
            validateDashboardAddress(raw).normalized!!,
            CleartextPolicy.ALLOW_WITH_WARNING,
        )

    @Test
    fun `home http address converts to ws and keeps the port`() {
        val ep = endpoint("192.168.66.88:9119")
        assertEquals("http://192.168.66.88:9119/", ep.baseUrl.toString())
        assertEquals("ws://192.168.66.88:9119/api/ws?token=abc", ep.webSocketUrl("token", "abc"))
        assertEquals("http://192.168.66.88:9119/api/status", ep.resolve("api/status").toString())
    }

    @Test
    fun `remote https address converts to wss and keeps the path prefix`() {
        val ep = endpoint("https://app-9119-account.cn60.ugdocker.link/prefix")
        assertEquals("https://app-9119-account.cn60.ugdocker.link/prefix/", ep.baseUrl.toString())
        assertEquals(
            "wss://app-9119-account.cn60.ugdocker.link/prefix/api/ws?ticket=t1",
            ep.webSocketUrl("ticket", "t1"),
        )
        assertEquals(
            "https://app-9119-account.cn60.ugdocker.link/prefix/api/status",
            ep.resolve("api/status").toString(),
        )
    }

    @Test
    fun `non default port is preserved for both rest and websocket`() {
        val ep = endpoint("https://nas.example.com:8443/hermes")
        assertEquals("wss://nas.example.com:8443/hermes/api/ws?token=abc", ep.webSocketUrl("token", "abc"))
        assertEquals("https://nas.example.com:8443/hermes/api/status", ep.resolve("api/status").toString())
    }

    @Test
    fun `address validation never leaks credentials query or fragment into the ws url`() {
        val rejected = validateDashboardAddress("https://user:pass@nas.example.com/prefix?x=1")
        assertTrue(rejected.error != null)
        val accepted = endpoint("https://nas.example.com/prefix/")
        assertTrue(!accepted.webSocketUrl("token", "abc").contains("user:pass"))
        assertTrue(!accepted.webSocketUrl("token", "abc").contains("?x=1"))
    }
}
