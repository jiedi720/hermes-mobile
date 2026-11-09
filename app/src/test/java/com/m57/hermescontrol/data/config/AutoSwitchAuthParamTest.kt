package com.m57.hermescontrol.data.config

import org.junit.Assert.assertEquals
import org.junit.Test

class AutoSwitchAuthParamTest {
    @Test
    fun `aligns both preset profiles with the current ticket mode`() {
        val home = ConnectionProfile(id = "home", name = "Home", baseUrl = "http://192.168.1.1:9119/")
        val remote = ConnectionProfile(id = "remote", name = "Remote", baseUrl = "https://example.com/")
        val unrelated = ConnectionProfile(id = "other", name = "Other", wsAuthParam = "token")

        val result = alignAutoSwitchAuthParam(listOf(home, remote, unrelated), "home", "remote", "ticket")

        assertEquals("ticket", result[0].wsAuthParam)
        assertEquals("ticket", result[1].wsAuthParam)
        assertEquals("token", result[2].wsAuthParam)
    }

    @Test
    fun `repairs a stale token-mode preset when presets are re-saved`() {
        val stale = ConnectionProfile(id = "remote", name = "Remote", wsAuthParam = "token")

        val result = alignAutoSwitchAuthParam(listOf(stale), "home", "remote", "ticket")

        assertEquals("ticket", result.single().wsAuthParam)
    }

    @Test
    fun `keeps token mode when the live connection uses token mode`() {
        val home = ConnectionProfile(id = "home", name = "Home")
        val remote = ConnectionProfile(id = "remote", name = "Remote")

        val result = alignAutoSwitchAuthParam(listOf(home, remote), "home", "remote", "token")

        assertEquals("token", result[0].wsAuthParam)
        assertEquals("token", result[1].wsAuthParam)
    }
}
