package com.m57.hermescontrol.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.m57.hermescontrol.data.config.ConnectionProfile
import com.m57.hermescontrol.data.remote.SESSION_COOKIE_NAME
import com.m57.hermescontrol.data.remote.buildFakePersistentCookieJar
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Home and Remote are two LOCAL connection profiles. Their credentials must
 * never cross: tokens are keyed by profile id and cookies are stored in a
 * per-connection-profile scope.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AutoSwitchAuthIsolationTest {
    @get:Rule
    val tempFolder = TemporaryFolder()
    private lateinit var prefs: SharedPreferences
    private lateinit var editor: SharedPreferences.Editor
    private lateinit var context: Context

    @Before
    fun setUp() {
        prefs = mockk(relaxed = true)
        editor = mockk(relaxed = true)
        context = TestContext(tempFolder.root, mockk(relaxed = true))
        every { prefs.edit() } returns editor
        every { editor.putString(any(), any()) } returns editor
        every { editor.putBoolean(any(), any()) } returns editor
        every { editor.putInt(any(), any()) } returns editor
        every { prefs.getString("server_custom_headers", null) } returns null
        every { prefs.getString("ssh_tunnel_configs", null) } returns null
        every { prefs.getBoolean("migrated_to_datastore", any()) } returns true
        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any(), any()) } returns 0
        every { android.util.Log.e(any(), any()) } returns 0
        every { android.util.Log.e(any(), any(), any()) } returns 0
        every { android.util.Log.w(any(), any<String>()) } returns 0
        every { android.util.Log.i(any(), any()) } returns 0
        mockkStatic(EncryptedSharedPreferences::class)
        every {
            EncryptedSharedPreferences.create(
                any<String>(),
                any<String>(),
                any<Context>(),
                any<EncryptedSharedPreferences.PrefKeyEncryptionScheme>(),
                any<EncryptedSharedPreferences.PrefValueEncryptionScheme>(),
            )
        } returns prefs
        mockkStatic(MasterKeys::class)
        every { MasterKeys.getOrCreate(any()) } returns "mockMasterKey"
        runBlocking { AuthManager.resetAndAwaitForTest() }
        AuthManager.init(context)
        runBlocking { AuthManager.awaitInitialization() }
    }

    @After
    fun tearDown() {
        runBlocking { AuthManager.resetAndAwaitForTest() }
        unmockkAll()
    }

    private fun twoProfiles() {
        AuthManager.saveConnectionProfiles(
            listOf(
                ConnectionProfile(id = "home", name = "Home", baseUrl = "http://192.168.66.88:9119/"),
                ConnectionProfile(id = "remote", name = "Remote", baseUrl = "https://nas.example.com/hermes/"),
            ),
        )
    }

    @Test
    fun `each connection profile keeps its own token across switches`() {
        twoProfiles()
        every { prefs.getString("token_home", null) } returns "home-token"
        every { prefs.getString("token_remote", null) } returns "remote-token"

        AuthManager.setSelectedProfileId("home")
        assertEquals("home-token", AuthManager.getToken())
        AuthManager.setSelectedProfileId("remote")
        assertEquals("remote-token", AuthManager.getToken())
        AuthManager.setSelectedProfileId("home")
        assertEquals("home-token", AuthManager.getToken())
    }

    @Test
    fun `writing a token never targets another profile key`() {
        twoProfiles()
        AuthManager.setProfileToken("home", "home-secret")
        AuthManager.setProfileToken("remote", "remote-secret")
        verify { editor.putString("token_home", "home-secret") }
        verify { editor.putString("token_remote", "remote-secret") }
    }

    @Test
    fun `home and remote cookie scopes never leak into each other`() =
        runTest {
            val jar = buildFakePersistentCookieJar()
            jar.useStore("home")
            jar.saveFromResponse(
                "http://192.168.66.88:9119/".toHttpUrl(),
                listOf(scopedCookie("192.168.66.88", "home-cookie")),
            )
            jar.useStore("remote")
            jar.saveFromResponse(
                "https://nas.example.com/".toHttpUrl(),
                listOf(scopedCookie("nas.example.com", "remote-cookie")),
            )

            val remote = jar.loadForRequest("https://nas.example.com/hermes/api/status".toHttpUrl())
            assertEquals("remote-cookie", remote.firstOrNull()?.value)
            assertTrue(remote.none { it.value == "home-cookie" })

            jar.useStore("home")
            val home = jar.loadForRequest("http://192.168.66.88:9119/api/status".toHttpUrl())
            assertEquals("home-cookie", home.firstOrNull()?.value)
            assertTrue(home.none { it.value == "remote-cookie" })
        }

    private fun scopedCookie(
        host: String,
        value: String,
    ): Cookie =
        Cookie
            .Builder()
            .name(SESSION_COOKIE_NAME)
            .value(value)
            .expiresAt(System.currentTimeMillis() + 86_400_000L)
            .hostOnlyDomain(host)
            .path("/")
            .build()
}
