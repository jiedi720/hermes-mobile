package com.m57.hermescontrol.data.session

/**
 * Holds outbound frames that were queued on a connection when an automatic
 * switch moved the app to a DIFFERENT server.
 *
 * Frames are keyed by the originating local connection profile id so they are
 * never replayed into another server's socket (which would pollute the new
 * connection). They are restored only when the app returns to that same
 * connection, letting the existing [com.m57.hermescontrol.data.ws.WsTransport]
 * drain logic deliver them. Process death drops them, exactly like the normal
 * in-memory message queue.
 */
internal object PendingMessageStash {
    private val lock = Any()
    private val framesByProfile = mutableMapOf<String, List<String>>()

    fun save(
        profileId: String,
        frames: List<String>,
    ) {
        if (frames.isEmpty()) return
        synchronized(lock) {
            framesByProfile[profileId] = (framesByProfile[profileId].orEmpty() + frames).distinct()
        }
    }

    /** Remove and return the frames for [profileId] (one-shot restore). */
    fun take(profileId: String): List<String> = synchronized(lock) { framesByProfile.remove(profileId).orEmpty() }

    fun pendingCount(profileId: String): Int = synchronized(lock) { framesByProfile[profileId]?.size ?: 0 }

    fun clear() {
        synchronized(lock) { framesByProfile.clear() }
    }
}
