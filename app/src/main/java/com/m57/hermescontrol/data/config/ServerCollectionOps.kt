package com.m57.hermescontrol.data.config

import com.m57.hermescontrol.data.remote.CleartextPolicy
import com.m57.hermescontrol.data.remote.ServerEndpoint

fun ServerStoreState.addOrUpdate(profile: ConnectionProfile): ServerStoreState {
    val updated = connectionProfiles.filter { it.id != profile.id } + profile
    return copy(connectionProfiles = updated)
}

fun ServerStoreState.migrateConnectionAuthParam(): ServerStoreState {
    if (connectionProfiles.none { it.wsAuthParam == null }) return this
    return copy(
        connectionProfiles =
            connectionProfiles.map { profile ->
                if (profile.wsAuthParam == null) profile.copy(wsAuthParam = wsAuthParam) else profile
            },
    )
}

fun ServerStoreState.selfHealed(): ServerStoreState {
    val hasActive = connectionProfiles.any { it.id == selectedProfileId }
    val newSelected = if (selectedProfileId != null && !hasActive) null else selectedProfileId

    return copy(
        selectedProfileId = newSelected,
    )
}

/**
 * Home and Remote presets point at the same dashboard service (LAN vs external
 * address), so both must use the WebSocket auth mode of the currently selected
 * connection. A freshly created profile defaults to token mode, which would
 * make a gated/ticket dashboard try `?token=` and never connect. This also
 * repairs a preset profile saved by an older build when the user re-saves.
 */
fun alignAutoSwitchAuthParam(
    profiles: List<ConnectionProfile>,
    homeProfileId: String?,
    remoteProfileId: String?,
    authParam: String,
): List<ConnectionProfile> =
    profiles.map { profile ->
        if (profile.id == homeProfileId || profile.id == remoteProfileId) {
            profile.copy(wsAuthParam = authParam)
        } else {
            profile
        }
    }

val ServerStoreState.resolvedBaseUrl: String
    get() {
        val selected = connectionProfiles.firstOrNull { it.id == selectedProfileId }
        if (selected != null) {
            // Prefer the selected profile's own URL, but fall back to the
            // top-level login URL (and only then to legacy loopback) so a profile
            // whose baseUrl was never stamped still shows the gateway used to
            // authenticate, not the hardcoded 127.0.0.1:9119 (issue #647).
            return selected.resolveBaseUrl(baseUrl)
        }
        return baseUrl
            ?.let {
                ServerEndpoint
                    .parse(
                        it,
                        CleartextPolicy.ALLOW_WITH_WARNING,
                    ).baseUrl
                    .toString()
            }
            ?: ServerEndpoint.fromLegacy(host, port).baseUrl.toString()
    }

val ServerStoreState.resolvedHost: String
    get() =
        ServerEndpoint
            .parse(
                resolvedBaseUrl,
                CleartextPolicy.ALLOW_WITH_WARNING,
            ).baseUrl
            .host

val ServerStoreState.resolvedPort: Int
    get() =
        ServerEndpoint
            .parse(
                resolvedBaseUrl,
                CleartextPolicy.ALLOW_WITH_WARNING,
            ).baseUrl
            .port
