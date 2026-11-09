package com.m57.hermescontrol.data.session

import com.m57.hermescontrol.data.config.AutoSwitchConfig
import com.m57.hermescontrol.data.config.AutoSwitchSource
import com.m57.hermescontrol.data.config.AutoSwitchStatus
import com.m57.hermescontrol.data.config.ProbeOutcome
import com.m57.hermescontrol.data.config.decideAutoSwitch
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.HomeDashboardProbe
import com.m57.hermescontrol.data.remote.NetworkMonitor
import com.m57.hermescontrol.data.remote.NetworkResult
import com.m57.hermescontrol.data.remote.safeApiCall
import com.m57.hermescontrol.data.ws.ConnectionStatus
import com.m57.hermescontrol.data.ws.HermesWsClient
import com.m57.hermescontrol.profilelock.ProfileLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/** Why a reachability check was triggered. */
enum class AutoSwitchTrigger {
    COLD_START,
    FOREGROUND,
    NETWORK_CHANGE,
    MANUAL,
}

/** Everything the settings UI needs, in one observable snapshot. */
data class AutoSwitchRuntime(
    val config: AutoSwitchConfig = AutoSwitchConfig(),
    val status: AutoSwitchStatus = AutoSwitchStatus(),
    val checking: Boolean = false,
    val manualOverrideUntilMs: Long = 0L,
)

/**
 * Owns automatic Home / Remote connection switching.
 *
 * It does NOT guess from Wi-Fi vs cellular: every decision follows a real HTTP
 * probe of the Home dashboard's `/api/status`. Switching itself is delegated to
 * [ProfileSwitchCoordinator], which already re-homes token, cookie scope,
 * custom headers, Retrofit and the WebSocket atomically.
 *
 * Triggers: cold start, returning to the foreground, default-network changes,
 * and the explicit "Check now" button. A [Mutex] serializes checks, a minimum
 * interval plus a debounce window absorb network flapping, and a contradictory
 * probe retry result cancels the switch instead of flapping the connection.
 */
object NetworkAutoSwitchCoordinator {
    /** Automatic triggers closer together than this are ignored. */
    const val MIN_CHECK_INTERVAL_MS = 3_000L

    /** Network change events are merged into one check after this quiet period. */
    const val NETWORK_DEBOUNCE_MS = 1_200L

    /** Manual selection wins for this long; "Check now" clears it immediately. */
    const val MANUAL_OVERRIDE_MS = 5 * 60_000L

    const val PROBE_RETRY_DELAY_MS = 250L
    const val VERIFY_TIMEOUT_MS = 8_000L

    internal var nowMs: () -> Long = { System.currentTimeMillis() }

    /** Injectable seams so the whole flow is unit-testable without Android. */
    internal var probe: suspend (String) -> HomeDashboardProbe.Report = { HomeDashboardProbe.probe(it) }
    internal var switchConnection: suspend (String?, Boolean) -> Unit = { _, _ -> }
    internal var verifyTarget: suspend () -> VerifyOutcome = { VerifyOutcome(true, true) }
    internal var currentProfileId: () -> String? = { AuthManager.getSelectedProfileId() }
    internal var persist: (AutoSwitchStatus) -> Unit = { status ->
        runCatching { AuthManager.serverStore.update { it.copy(autoSwitchStatus = status) } }
    }

    data class VerifyOutcome(
        val restOk: Boolean,
        val webSocketOk: Boolean,
    ) {
        val isSuccess: Boolean get() = restOk && webSocketOk
        val reason: String
            get() =
                when {
                    !restOk && !webSocketOk -> "REST and WebSocket both failed on the target connection"
                    !restOk -> "Target REST API failed (authentication or server error)"
                    else -> "WebSocket never reached GatewayReady (WSS upgrade may be unsupported)"
                }
    }

    private val _state = MutableStateFlow(AutoSwitchRuntime())
    val state: StateFlow<AutoSwitchRuntime> = _state.asStateFlow()

    private val checkMutex = Mutex()
    private val started = AtomicBoolean(false)
    private var scope: CoroutineScope? = null
    private var debounceJob: Job? = null

    @Volatile
    private var lastCheckStartedAtMs = 0L

    /** Wire production seams and start listening. Called once from Application.onCreate. */
    fun start(appScope: CoroutineScope) {
        if (!started.compareAndSet(false, true)) return
        scope = appScope
        installProductionSeams()
        appScope.launch {
            AuthManager.initializationState.first { it == AuthManager.InitializationState.Ready }
            reloadFromStore()
            appScope.launch {
                AuthManager.serverStore.stateFlow.collect { latest ->
                    _state.update { it.copy(config = latest.autoSwitch, status = latest.autoSwitchStatus) }
                }
            }
            appScope.launch {
                NetworkMonitor.networkChanges.collect { scheduleCheck(AutoSwitchTrigger.NETWORK_CHANGE) }
            }
            appScope.launch {
                NetworkMonitor.transportChanges.collect { scheduleCheck(AutoSwitchTrigger.NETWORK_CHANGE) }
            }
            check(AutoSwitchTrigger.COLD_START)
        }
    }

    private fun installProductionSeams() {
        probe = { address -> HomeDashboardProbe.probe(address) }
        switchConnection = { profileId, preserve ->
            ProfileSwitchCoordinator.switchConnectionProfile(profileId, preserve)
        }
        verifyTarget = { verifySwitchedTarget() }
        currentProfileId = { AuthManager.getSelectedProfileId() }
        persist = { status ->
            runCatching { AuthManager.serverStore.update { it.copy(autoSwitchStatus = status) } }
        }
    }

    private fun reloadFromStore() {
        val latest = AuthManager.serverStore.getLatestState()
        _state.update { it.copy(config = latest.autoSwitch, status = latest.autoSwitchStatus) }
    }

    /** Called from MainActivity.onResume(). */
    fun onAppForeground() {
        val owner = scope ?: return
        owner.launch { check(AutoSwitchTrigger.FOREGROUND) }
    }

    /** "Check now" button: explicit user intent, so it bypasses the manual cool-down. */
    fun checkNow() {
        val owner = scope ?: return
        owner.launch {
            _state.update { it.copy(manualOverrideUntilMs = 0L) }
            check(AutoSwitchTrigger.MANUAL, force = true)
        }
    }

    /** A manual profile selection must not be overwritten immediately. */
    fun noteManualSelection() {
        _state.update { it.copy(manualOverrideUntilMs = nowMs() + MANUAL_OVERRIDE_MS) }
        persist(
            AutoSwitchStatus(
                lastCheckAtMs = _state.value.status.lastCheckAtMs,
                lastResult = "Manual connection selected",
                lastError = null,
                homeReachable = _state.value.status.homeReachable,
                activeSource = AutoSwitchSource.MANUAL,
            ),
        )
    }

    private fun scheduleCheck(trigger: AutoSwitchTrigger) {
        val owner = scope ?: return
        debounceJob?.cancel()
        debounceJob =
            owner.launch {
                delay(NETWORK_DEBOUNCE_MS)
                check(trigger)
            }
    }

    /**
     * Run one reachability check. Serialized by [checkMutex] so overlapping
     * network changes can never produce two concurrent switches.
     */
    internal suspend fun check(
        trigger: AutoSwitchTrigger,
        force: Boolean = false,
    ) {
        val now = nowMs()
        if (!force && now - lastCheckStartedAtMs < MIN_CHECK_INTERVAL_MS) return
        checkMutex.withLock {
            val startedAt = nowMs()
            if (!force && startedAt - lastCheckStartedAtMs < MIN_CHECK_INTERVAL_MS) return@withLock
            lastCheckStartedAtMs = startedAt
            _state.update { it.copy(checking = true) }
            try {
                runCheck(_state.value.config, trigger, startedAt)
            } finally {
                _state.update { it.copy(checking = false) }
            }
        }
    }

    private suspend fun runCheck(
        config: AutoSwitchConfig,
        trigger: AutoSwitchTrigger,
        startedAt: Long,
    ) {
        if (!config.enabled) {
            publish(
                AutoSwitchStatus(
                    lastCheckAtMs = startedAt,
                    lastResult = "Automatic switching is off",
                    lastError = null,
                    homeReachable = null,
                    activeSource = _state.value.status.activeSource,
                ),
            )
            return
        }
        if (!config.isConfigured) {
            publish(
                AutoSwitchStatus(
                    lastCheckAtMs = startedAt,
                    lastResult = null,
                    lastError = "Configure both Home/Remote addresses and their profiles first",
                    homeReachable = null,
                    activeSource = AutoSwitchSource.MANUAL,
                ),
            )
            return
        }

        val outcome = resolveOutcome(config)
        val reachable = outcome == ProbeOutcome.REACHABLE
        val manualActive = _state.value.manualOverrideUntilMs > startedAt
        val decision =
            decideAutoSwitch(
                config = config,
                outcome = outcome,
                currentProfileId = currentProfileId(),
                manualOverrideActive = manualActive && trigger != AutoSwitchTrigger.MANUAL,
            )

        if (!decision.shouldSwitch) {
            publish(
                AutoSwitchStatus(
                    lastCheckAtMs = startedAt,
                    lastResult = decision.reason,
                    lastError = null,
                    homeReachable = if (outcome == ProbeOutcome.UNSTABLE) null else reachable,
                    activeSource = decision.source ?: _state.value.status.activeSource,
                ),
            )
            return
        }

        val targetId = decision.targetProfileId ?: return
        if (!ProfileLock.allowsConnection(targetId)) {
            publish(
                AutoSwitchStatus(
                    lastCheckAtMs = startedAt,
                    lastResult = null,
                    lastError = "Connection is locked by the profile lock",
                    homeReachable = reachable,
                    activeSource = _state.value.status.activeSource,
                ),
            )
            return
        }
        val previousId = currentProfileId()
        val switched = applySwitch(targetId, reachable, startedAt)
        if (!switched) {
            // The target is unusable: roll back to the original connection so no
            // half-switched state is left behind.
            runCatching { switchConnection(previousId, true) }
            publish(
                AutoSwitchStatus(
                    lastCheckAtMs = startedAt,
                    lastResult = null,
                    lastError = lastFailure ?: "Target connection failed - rolled back to the original",
                    homeReachable = reachable,
                    activeSource = AutoSwitchSource.MANUAL,
                ),
            )
        }
    }

    @Volatile
    private var lastFailure: String? = null

    private suspend fun applySwitch(
        targetId: String,
        reachable: Boolean,
        startedAt: Long,
    ): Boolean {
        lastFailure = null
        return try {
            // preservePendingMessages = true keeps in-flight user messages in a
            // per-connection stash instead of dropping or misrouting them.
            switchConnection(targetId, true)
            val verified = verifyTarget()
            if (verified.isSuccess) {
                publish(
                    AutoSwitchStatus(
                        lastCheckAtMs = startedAt,
                        lastResult = if (reachable) "Switched to Home" else "Switched to Remote",
                        lastError = null,
                        homeReachable = reachable,
                        activeSource = if (reachable) AutoSwitchSource.HOME else AutoSwitchSource.REMOTE,
                    ),
                )
                true
            } else {
                lastFailure = "${verified.reason}; rolled back to the original connection"
                false
            }
        } catch (error: Exception) {
            lastFailure = "Switch failed: ${error.javaClass.simpleName}; rolled back to the original connection"
            false
        }
    }

    /** One quick retry absorbs a transient blip; contradictory results switch nothing. */
    private suspend fun resolveOutcome(config: AutoSwitchConfig): ProbeOutcome {
        val first = probe(config.homeAddress)
        if (first.result == HomeDashboardProbe.Result.REACHABLE) return ProbeOutcome.REACHABLE
        delay(PROBE_RETRY_DELAY_MS)
        val second = probe(config.homeAddress)
        return if (second.result == HomeDashboardProbe.Result.REACHABLE) {
            ProbeOutcome.UNSTABLE
        } else {
            ProbeOutcome.UNREACHABLE
        }
    }

    private fun publish(status: AutoSwitchStatus) {
        _state.update { it.copy(status = status) }
        persist(status)
    }

    /**
     * Confirm the NEW connection really works. REST goes through the rebuilt
     * ApiClient (new profile's token/cookie/headers), and we wait for the
     * WebSocket to actually reach CONNECTED — a successful REST call alone does
     * NOT prove the remote proxy supports WebSocket Upgrade / WSS.
     */
    private suspend fun verifySwitchedTarget(): VerifyOutcome {
        val result =
            withTimeoutOrNull(VERIFY_TIMEOUT_MS) {
                coroutineScope {
                    val rest =
                        async {
                            runCatching { safeApiCall { ApiClient.hermesApi.getStatus() } }.getOrNull()
                        }
                    val webSocket =
                        async {
                            runCatching {
                                HermesWsClient.connectionStatus.first { status ->
                                    status == ConnectionStatus.CONNECTED ||
                                        status == ConnectionStatus.AUTH_EXPIRED
                                }
                            }.getOrNull()
                        }
                    val restResult = rest.await()
                    val wsStatus = webSocket.await()
                    VerifyOutcome(
                        restOk = restResult is NetworkResult.Success<*>,
                        webSocketOk = wsStatus == ConnectionStatus.CONNECTED,
                    )
                }
            }
        // Timeout cancels both children: report a real failure, never a forged success.
        return result ?: VerifyOutcome(false, false)
    }

    /** Test-only: inject a config without starting the Application-level listeners. */
    internal fun setConfigForTest(config: AutoSwitchConfig) {
        _state.update { it.copy(config = config) }
    }

    /** Test-only: reset all seams and state between cases. */
    internal fun resetForTest() {
        started.set(false)
        scope = null
        debounceJob?.cancel()
        debounceJob = null
        lastCheckStartedAtMs = 0L
        lastFailure = null
        _state.value = AutoSwitchRuntime()
        nowMs = { System.currentTimeMillis() }
        probe = { HomeDashboardProbe.probe(it) }
        switchConnection = { _, _ -> }
        verifyTarget = { VerifyOutcome(true, true) }
        currentProfileId = { null }
        persist = { }
    }
}
