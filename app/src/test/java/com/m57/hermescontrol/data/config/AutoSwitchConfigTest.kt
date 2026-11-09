package com.m57.hermescontrol.data.config

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure decision + persistence tests for the Home/Remote auto-switch config. */
class AutoSwitchConfigTest {
    private val configured =
        AutoSwitchConfig(
            enabled = true,
            homeAddress = "http://192.168.66.88:9119/",
            remoteAddress = "https://nas.example.com/hermes/",
            homeProfileId = "home",
            remoteProfileId = "remote",
        )

    @Test
    fun `isConfigured requires two addresses and two distinct profile ids`() {
        assertTrue(configured.isConfigured)
        assertFalse(configured.copy(homeAddress = "").isConfigured)
        assertFalse(configured.copy(remoteAddress = "").isConfigured)
        assertFalse(configured.copy(homeProfileId = null).isConfigured)
        assertFalse(configured.copy(remoteProfileId = null).isConfigured)
        assertFalse(configured.copy(remoteProfileId = "home").isConfigured)
    }

    @Test
    fun `home reachable selects the home profile`() {
        val decision = decideAutoSwitch(configured, ProbeOutcome.REACHABLE, "remote", false)
        assertTrue(decision.shouldSwitch)
        assertEquals("home", decision.targetProfileId)
        assertEquals(AutoSwitchSource.HOME, decision.source)
    }

    @Test
    fun `home unreachable selects the remote profile`() {
        val decision = decideAutoSwitch(configured, ProbeOutcome.UNREACHABLE, "home", false)
        assertTrue(decision.shouldSwitch)
        assertEquals("remote", decision.targetProfileId)
        assertEquals(AutoSwitchSource.REMOTE, decision.source)
    }

    @Test
    fun `automatic switching off never changes the connection`() {
        val decision = decideAutoSwitch(configured.copy(enabled = false), ProbeOutcome.REACHABLE, "remote", false)
        assertFalse(decision.shouldSwitch)
        assertNull(decision.targetProfileId)
        assertEquals(AutoSwitchSource.MANUAL, decision.source)
    }

    @Test
    fun `incomplete configuration never changes the connection`() {
        val decision =
            decideAutoSwitch(
                AutoSwitchConfig(enabled = true),
                ProbeOutcome.REACHABLE,
                "remote",
                false,
            )
        assertFalse(decision.shouldSwitch)
        assertEquals(AutoSwitchSource.MANUAL, decision.source)
    }

    @Test
    fun `unstable probe never changes the connection`() {
        val decision = decideAutoSwitch(configured, ProbeOutcome.UNSTABLE, "remote", false)
        assertFalse(decision.shouldSwitch)
        assertNull(decision.source)
        assertTrue(decision.reason.contains("unstable"))
    }

    @Test
    fun `manual selection has priority during the cool-down`() {
        val decision = decideAutoSwitch(configured, ProbeOutcome.UNREACHABLE, "home", true)
        assertFalse(decision.shouldSwitch)
        assertEquals(AutoSwitchSource.MANUAL, decision.source)
        assertTrue(decision.reason.contains("Manual"))
    }

    @Test
    fun `target already active is a no-op but still reports the truthful source`() {
        val decision = decideAutoSwitch(configured, ProbeOutcome.REACHABLE, "home", false)
        assertFalse(decision.shouldSwitch)
        assertEquals(AutoSwitchSource.HOME, decision.source)
    }

    @Test
    fun `validateDashboardAddress adds http scheme and keeps port and path prefix`() {
        assertEquals(
            "http://192.168.66.88:9119/",
            validateDashboardAddress("192.168.66.88:9119").normalized,
        )
        assertEquals(
            "https://nas.example.com/hermes/",
            validateDashboardAddress("https://nas.example.com/hermes").normalized,
        )
        assertEquals(
            "https://nas.example.com:8443/prefix/",
            validateDashboardAddress("https://nas.example.com:8443/prefix").normalized,
        )
    }

    @Test
    fun `validateDashboardAddress rejects invalid or unsafe addresses`() {
        assertNotNull(validateDashboardAddress("").error)
        assertNotNull(validateDashboardAddress("http://a b/").error)
        assertNotNull(validateDashboardAddress("http://user:pass@example.com/").error)
        assertNotNull(validateDashboardAddress("http://example.com/?q=1").error)
        assertNotNull(validateDashboardAddress("http://example.com/#frag").error)
        assertNotNull(validateDashboardAddress("ftp://example.com/").error)
        assertTrue(validateDashboardAddress("http://192.168.66.88:9119").isValid)
    }

    @Test
    fun `config and status survive a serialization round trip`() {
        val status =
            AutoSwitchStatus(
                lastCheckAtMs = 123L,
                lastResult = "Switched to Home",
                lastError = null,
                homeReachable = true,
                activeSource = AutoSwitchSource.HOME,
            )
        val decodedStatus =
            Json.decodeFromString(
                AutoSwitchStatus.serializer(),
                Json.encodeToString(AutoSwitchStatus.serializer(), status),
            )
        assertEquals(status, decodedStatus)
        val decodedConfig =
            Json.decodeFromString(
                AutoSwitchConfig.serializer(),
                Json.encodeToString(AutoSwitchConfig.serializer(), configured),
            )
        assertEquals(configured, decodedConfig)
    }
}
