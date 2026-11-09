package com.m57.hermescontrol.data.session

import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.HermesApiService
import com.m57.hermescontrol.data.ws.HermesWsClient
import com.m57.hermescontrol.profilelock.ProfileLock
import io.mockk.Ordering
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * An automatic switch must close the OLD socket and dial the NEW address, and
 * it must not lose in-flight user messages or replay them into the wrong
 * server. The existing message queue / ticket refresh / reconnect path in
 * [WsTransport][com.m57.hermescontrol.data.ws.WsTransport] is untouched.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AutoSwitchSwitchFlowTest {
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        ProfileSwitchCoordinator.ioDispatcher = testDispatcher
        PendingMessageStash.clear()

        mockkObject(AuthManager)
        mockkObject(ApiClient)
        mockkObject(HermesWsClient)
        mockkObject(ProfileLock)
        every { ProfileLock.allowsConnection(any()) } returns true
        every { AuthManager.getSelectedProfileId() } returns "home"
        every { AuthManager.setSelectedProfileId(any()) } returns Unit
        coEvery { AuthManager.syncCookieStoreForProfile(any()) } returns Unit
        every { ApiClient.hermesApi } returns mockk<HermesApiService>(relaxed = true)
        every { ApiClient.rebuild() } returns Unit
        every { HermesWsClient.disconnect(any()) } returns Unit
        every { HermesWsClient.connect() } returns Unit
        every { HermesWsClient.snapshotQueuedFrames() } returns emptyList()
        every { HermesWsClient.restoreQueuedFrames(any()) } returns Unit
    }

    @After
    fun tearDown() {
        ProfileSwitchCoordinator.ioDispatcher = Dispatchers.IO
        Dispatchers.resetMain()
        PendingMessageStash.clear()
        unmockkAll()
    }

    @Test
    fun `automatic switch closes the old socket and dials the new connection in order`() =
        runTest {
            ProfileSwitchCoordinator.switchConnectionProfile("remote", preservePendingMessages = true)

            coVerify(ordering = Ordering.ORDERED) {
                HermesWsClient.snapshotQueuedFrames()
                HermesWsClient.disconnect(clearPendingMessages = true)
                AuthManager.setSelectedProfileId("remote")
                AuthManager.syncCookieStoreForProfile("remote")
                ApiClient.rebuild()
                HermesWsClient.connect()
            }
        }

    @Test
    fun `frames from the origin profile are stashed and never sent to the new server`() =
        runTest {
            every { HermesWsClient.snapshotQueuedFrames() } returns listOf("frame-a", "frame-b")
            ProfileSwitchCoordinator.switchConnectionProfile("remote", preservePendingMessages = true)

            verify { HermesWsClient.restoreQueuedFrames(emptyList()) }
            assertEquals(2, PendingMessageStash.pendingCount("home"))
            assertEquals(0, PendingMessageStash.pendingCount("remote"))
        }

    @Test
    fun `returning to the origin connection restores only its own frames`() =
        runTest {
            every { AuthManager.getSelectedProfileId() } returns "remote"
            PendingMessageStash.save("home", listOf("frame-a"))

            ProfileSwitchCoordinator.switchConnectionProfile("home", preservePendingMessages = true)

            verify { HermesWsClient.restoreQueuedFrames(listOf("frame-a")) }
            assertEquals(0, PendingMessageStash.pendingCount("home"))
        }

    @Test
    fun `manual switch keeps legacy behaviour and never touches the stash`() =
        runTest {
            ProfileSwitchCoordinator.switchConnectionProfile("remote", preservePendingMessages = false)
            verify(exactly = 0) { HermesWsClient.snapshotQueuedFrames() }
            verify(exactly = 0) { HermesWsClient.restoreQueuedFrames(any()) }
            verify { HermesWsClient.disconnect(clearPendingMessages = true) }
            verify(exactly = 1) { HermesWsClient.connect() }
        }

    @Test
    fun `stash is scoped per connection profile and de-duplicates frames`() {
        PendingMessageStash.save("home", listOf("a", "b", "a"))
        PendingMessageStash.save("home", listOf("b", "c"))
        PendingMessageStash.save("remote", listOf("x"))

        assertEquals(listOf("a", "b", "c"), PendingMessageStash.take("home"))
        assertTrue(PendingMessageStash.take("home").isEmpty())
        assertEquals(listOf("x"), PendingMessageStash.take("remote"))
        assertTrue(PendingMessageStash.take("remote").isEmpty())
    }
}
