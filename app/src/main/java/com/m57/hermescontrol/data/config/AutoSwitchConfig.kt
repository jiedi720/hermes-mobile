package com.m57.hermescontrol.data.config

import com.m57.hermescontrol.data.remote.CleartextPolicy
import com.m57.hermescontrol.data.remote.ServerEndpoint
import kotlinx.serialization.Serializable

/**
 * Persisted Home / Remote presets for automatic connection switching.
 *
 * These are LOCAL [ConnectionProfile] ids — they select which server this
 * device talks to. They are unrelated to Hermes server-side Agent Profiles.
 * Addresses are stored as normalized base URLs so scheme, port and any reverse
 * proxy path prefix survive a restart.
 */
@Serializable
data class AutoSwitchConfig(
    val enabled: Boolean = false,
    val homeAddress: String = "",
    val remoteAddress: String = "",
    val homeProfileId: String? = null,
    val remoteProfileId: String? = null,
) {
    /** Both addresses and BOTH profile ids must be set before switching is allowed. */
    val isConfigured: Boolean
        get() =
            homeAddress.isNotBlank() &&
                remoteAddress.isNotBlank() &&
                !homeProfileId.isNullOrBlank() &&
                !remoteProfileId.isNullOrBlank() &&
                homeProfileId != remoteProfileId
}

/** Which connection is currently in use, for the settings status readout. */
@Serializable
enum class AutoSwitchSource {
    HOME,
    REMOTE,
    MANUAL,
}

/** Persisted last-check readout so the settings page survives a cold start. */
@Serializable
data class AutoSwitchStatus(
    val lastCheckAtMs: Long = 0L,
    val lastResult: String? = null,
    val lastError: String? = null,
    val homeReachable: Boolean? = null,
    val activeSource: AutoSwitchSource = AutoSwitchSource.MANUAL,
)

/** Outcome of validating and normalizing one user-entered dashboard address. */
data class AddressValidation(
    val normalized: String?,
    val error: String?,
) {
    val isValid: Boolean get() = normalized != null
}

/**
 * Normalize a user-entered dashboard address with the SAME rules the rest of
 * the app uses ([ServerEndpoint.parse]). Plain host:port input without a scheme
 * is upgraded to http:// for convenience; https tunnels must be typed in full.
 * Query strings, fragments and embedded credentials are rejected.
 */
fun validateDashboardAddress(raw: String): AddressValidation {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return AddressValidation(null, "Address is required")
    if (trimmed.any { it.isWhitespace() }) return AddressValidation(null, "Address must not contain spaces")
    if (trimmed.length > 300) return AddressValidation(null, "Address is too long")
    val candidate = if ("://" in trimmed) trimmed else "http://$trimmed"
    val parsed =
        runCatching {
            ServerEndpoint.parse(candidate, CleartextPolicy.ALLOW_WITH_WARNING)
        }.getOrElse { error ->
            return AddressValidation(null, error.message ?: "Invalid URL")
        }
    return AddressValidation(parsed.baseUrl.toString(), null)
}

/** Result of a Home dashboard reachability probe, after any debounce retries. */
enum class ProbeOutcome {
    REACHABLE,
    UNREACHABLE,
    UNSTABLE,
}

/** Pure decision: no Android or network dependencies, so it is unit-testable. */
data class SwitchDecision(
    val targetProfileId: String?,
    val source: AutoSwitchSource?,
    val reason: String,
) {
    val shouldSwitch: Boolean get() = targetProfileId != null
}

/**
 * Decide whether an automatic check may change the active connection.
 *
 * Manual selections win for a bounded cool-down window. An unstable probe
 * (two contradictory results) never switches. A target already in use is a
 * no-op but still reports its source so the UI stays truthful.
 */
fun decideAutoSwitch(
    config: AutoSwitchConfig,
    outcome: ProbeOutcome,
    currentProfileId: String?,
    manualOverrideActive: Boolean,
): SwitchDecision {
    if (!config.enabled) {
        return SwitchDecision(null, AutoSwitchSource.MANUAL, "Automatic switching is off")
    }
    if (!config.isConfigured) {
        return SwitchDecision(null, AutoSwitchSource.MANUAL, "Configure both addresses and profiles first")
    }
    if (outcome == ProbeOutcome.UNSTABLE) {
        return SwitchDecision(null, null, "Probe result was unstable - keeping the current connection")
    }
    if (manualOverrideActive) {
        return SwitchDecision(null, AutoSwitchSource.MANUAL, "Manual connection selection has priority")
    }
    val reachable = outcome == ProbeOutcome.REACHABLE
    val target = if (reachable) config.homeProfileId!! else config.remoteProfileId!!
    val source = if (reachable) AutoSwitchSource.HOME else AutoSwitchSource.REMOTE
    if (target == currentProfileId) {
        return SwitchDecision(null, source, "Already using the ${source.name.lowercase()} connection")
    }
    return SwitchDecision(
        targetProfileId = target,
        source = source,
        reason =
            if (reachable) {
                "Home dashboard reachable - switching to Home"
            } else {
                "Home dashboard unreachable - switching to Remote"
            },
    )
}
