package com.m57.hermescontrol.data.session

import com.m57.hermescontrol.data.config.AutoSwitchConfig
import com.m57.hermescontrol.data.config.AutoSwitchSource
import com.m57.hermescontrol.data.config.AutoSwitchStatus
import com.m57.hermescontrol.data.remote.HomeDashboardProbe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * End-to-end automatic-switch orchestration with every network/Android seam
 * injected. Covers triggering, debounce, manual priority, verification and
 * rollback without ever touching a real socket.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NetworkAutoSwitchCoordinatorTest {
    private val config =
        AutoSwitchConfig(
            enabled = true,
            homeAddress = "http://192.168.66.88:9119/",
            remoteAddress = "https://nas.example.com/hermes/",
            homeProfileId = "home",
            remoteProfileId = "remote",
        )

    private var clock = 100_000L
    private var outcome = HomeDashboardProbe.Result.REACHABLE
    private var verifyResult = NetworkAutoSwitchCoordinator.VerifyOutcome(true, true)
    private var currentId: String? = "remote"
    private var probeCalls = 0
    private val switches = mutableListOf<String?>()
    private val published = mutableListOf<AutoSwitchStatus>()

    @Before
    fun setUp() {
        NetworkAutoSwitchCoordinator.resetForTest()
        clock = 100_000L
        outcome = HomeDashboardProbe.Result.REACHABLE
        verifyResult = NetworkAutoSwitchCoordinator.VerifyOutcome(true, true)
        currentId = "remote"
        probeCalls = 0
        switches.clear()
        published.clear()

        NetworkAutoSwitchCoordinator.nowMs = { clock }
        NetworkAutoSwitchCoordinator.probe = { _ ->
            probeCalls++
            HomeDashboardProbe.Report(outcome)
        }
        NetworkAutoSwitchCoordinator.switchConnection = { id, _ ->
            switches.add(id)
            currentId = id
            Unit
        }
        NetworkAutoSwitchCoordinator.verifyTarget = { verifyResult }
        NetworkAutoSwitchCoordinator.currentProfileId = { currentId }
        NetworkAutoSwitchCoordinator.persist = { status -> published.add(status) }
        NetworkAutoSwitchCoordinator.setConfigForTest(config)
    }

    @After
    fun tearDown() {
        NetworkAutoSwitchCoordinator.resetForTest()
    }

    @Test
    fun `home reachable switches to the home profile`() =
        runTest {
            outcome = HomeDashboardProbe.Result.REACHABLE
            NetworkAutoSwitchCoordinator.check(AutoSwitchTrigger.COLD_START, force = true)
            assertEquals(listOf("home"), switches)
            assertEquals(AutoSwitchSource.HOME, published.last().activeSource)
            assertNull(published.last().lastError)
            assertEquals(1, probeCalls)
        }

    @Test
    fun `home unreachable retries once then switches to the remote profile`() =
        runTest {
            outcome = HomeDashboardProbe.Result.UNREACHABLE
            currentId = "home"
            NetworkAutoSwitchCoordinator.check(AutoSwitchTrigger.NETWORK_CHANGE, force = true)
            assertEquals(listOf("remote"), switches)
            assertEquals(AutoSwitchSource.REMOTE, published.last().activeSource)
            assertEquals(2, probeCalls)
        }

    @Test
    fun `automatic switching off never changes the connection`() =
        runTest {
            NetworkAutoSwitchCoordinator.setConfigForTest(config.copy(enabled = false))
            NetworkAutoSwitchCoordinator.check(AutoSwitchTrigger.COLD_START, force = true)
            assertTrue(switches.isEmpty())
            assertEquals("Automatic switching is off", published.last().lastResult)
            assertEquals(0, probeCalls)
        }

    @Test
    fun `incomplete configuration reports a readable error`() =
        runTest {
            NetworkAutoSwitchCoordinator.setConfigForTest(AutoSwitchConfig(enabled = true))
            NetworkAutoSwitchCoordinator.check(AutoSwitchTrigger.COLD_START, force = true)
            assertTrue(switches.isEmpty())
            assertTrue(published.last().lastError!!.contains("Configure both"))
        }

    @Test
    fun `manual connection selection wins until the explicit check-now trigger`() =
        runTest {
            NetworkAutoSwitchCoordinator.noteManualSelection()
            NetworkAutoSwitchCoordinator.check(AutoSwitchTrigger.COLD_START, force = true)
            assertTrue(switches.isEmpty())
            assertEquals(AutoSwitchSource.MANUAL, published.last().activeSource)

            // "Check now" is explicit user intent: MANUAL bypasses the cool-down.
            NetworkAutoSwitchCoordinator.check(AutoSwitchTrigger.MANUAL, force = true)
            assertEquals(listOf("home"), switches)
        }

    @Test
    fun `rapid network changes produce a single serialized switch`() =
        runTest {
            outcome = HomeDashboardProbe.Result.REACHABLE
            val jobs =
                List(3) {
                    launch { NetworkAutoSwitchCoordinator.check(AutoSwitchTrigger.NETWORK_CHANGE) }
                }
            jobs.forEach { it.join() }
            assertEquals(1, switches.size)
            assertEquals(1, probeCalls)
        }

    @Test
    fun `contradictory probe results cancel the switch instead of flapping`() =
        runTest {
            NetworkAutoSwitchCoordinator.probe = { _ ->
                probeCalls++
                HomeDashboardProbe.Report(
                    if (probeCalls == 1) {
                        HomeDashboardProbe.Result.UNREACHABLE
                    } else {
                        HomeDashboardProbe.Result.REACHABLE
                    },
                )
            }
            NetworkAutoSwitchCoordinator.check(AutoSwitchTrigger.COLD_START, force = true)
            assertTrue(switches.isEmpty())
            assertTrue(published.last().lastResult!!.contains("unstable"))
        }

    @Test
    fun `failed target verification rolls back to the original connection`() =
        runTest {
            verifyResult = NetworkAutoSwitchCoordinator.VerifyOutcome(false, false)
            NetworkAutoSwitchCoordinator.check(AutoSwitchTrigger.COLD_START, force = true)
            assertEquals(listOf("home", "remote"), switches)
            assertTrue(published.last().lastError!!.contains("rolled back"))
            assertEquals(AutoSwitchSource.MANUAL, published.last().activeSource)
        }

    @Test
    fun `rest success alone never forges a working websocket connection`() =
        runTest {
            verifyResult = NetworkAutoSwitchCoordinator.VerifyOutcome(restOk = true, webSocketOk = false)
            NetworkAutoSwitchCoordinator.check(AutoSwitchTrigger.COLD_START, force = true)
            assertEquals(listOf("home", "remote"), switches)
            assertTrue(published.last().lastError!!.contains("WebSocket never reached GatewayReady"))
        }

    @Test
    fun `already using the target connection does not re-dial the socket`() =
        runTest {
            currentId = "home"
            NetworkAutoSwitchCoordinator.check(AutoSwitchTrigger.FOREGROUND, force = true)
            assertTrue(switches.isEmpty())
            assertEquals(AutoSwitchSource.HOME, published.last().activeSource)
        }
}
