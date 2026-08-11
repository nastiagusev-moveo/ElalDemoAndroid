package com.elal.flightsdk.manager

import android.content.Context
import android.os.SystemClock
import com.elal.flightsdk.utils.Logger

/**
 * "Now" on the GMT timeline, sourced from the push backend rather than the handset.
 *
 * Every FCM message carries `google.sent_time` — an epoch-millis stamp written by Google's servers,
 * surfaced as `RemoteMessage.getSentTime()`. [anchor] pairs that stamp with a
 * [SystemClock.elapsedRealtime] reading; [nowMs] then projects the pair forward on the monotonic
 * clock. Neither the device's timezone nor its wall clock takes part, so a phone with auto-time off
 * and a hand-set hour still counts down to the right arrival.
 *
 * The anchor is persisted, because `elapsedRealtime` is measured from boot and therefore survives a
 * process kill — the projection stays valid across a restart mid-flight.
 *
 * Falls back to [System.currentTimeMillis] only when no push has ever been anchored (the in-app
 * debug and sample paths, which have no server stamp) or when a reboot has invalidated the
 * monotonic half of the pair.
 */
internal object ServerClock {

    private val log = Logger("ServerClock")

    // Written from the FCM-delivery thread, read from the main-thread tick — @Volatile for visibility.
    // The pair is only ever assigned together, and a torn read costs one skewed tick at worst.
    @Volatile private var anchorServerMs: Long = 0L
    @Volatile private var anchorRealtimeMs: Long = 0L

    val isAnchored: Boolean get() = anchorServerMs > 0L

    /**
     * Take [serverNowMs] (an `RemoteMessage.sentTime`) as the current instant. No-ops on a missing
     * stamp, so callers can pass it through unconditionally.
     */
    fun anchor(context: Context, serverNowMs: Long) {
        if (serverNowMs <= 0L) return
        // A push that waited in FCM's queue — device offline, or dozing — still carries the send time
        // it was originally stamped with. Taking it would drag the trusted clock backwards and make
        // the countdown jump *up*, so an anchor is accepted only when it is at least as recent as
        // what the existing one already projects.
        if (isAnchored) {
            val behindMs = nowMs() - serverNowMs
            if (behindMs > 0L) {
                log.i("Ignoring stale sentTime — ${behindMs / 1000}s behind the current anchor (queued push?)")
                return
            }
        }
        anchorServerMs = serverNowMs
        anchorRealtimeMs = SystemClock.elapsedRealtime()
        JourneyPrefs.saveClockAnchor(context, anchorServerMs, anchorRealtimeMs)
        val deviceSkewMs = System.currentTimeMillis() - serverNowMs
        log.d("Anchored to sentTime=$serverNowMs — device clock is ${deviceSkewMs / 1000}s off the server")
    }

    /** Rebuild the anchor after a process kill. Safe to call when none was ever persisted. */
    fun restore(context: Context) {
        if (isAnchored) return
        val (serverMs, realtimeMs) = JourneyPrefs.loadClockAnchor(context) ?: return
        // A process kill preserves elapsedRealtime; a reboot resets it to zero. A stored reading in
        // the future means the latter happened, leaving nothing to project from — drop it here rather
        // than re-detecting it on every read.
        if (SystemClock.elapsedRealtime() < realtimeMs) {
            log.i("Persisted clock anchor predates a reboot — discarding, will re-anchor on the next push")
            JourneyPrefs.saveClockAnchor(context, 0L, 0L)
            return
        }
        anchorServerMs = serverMs
        anchorRealtimeMs = realtimeMs
        log.d("Restored clock anchor serverMs=$serverMs realtimeMs=$realtimeMs")
    }

    fun reset() {
        anchorServerMs = 0L
        anchorRealtimeMs = 0L
    }

    /**
     * Epoch millis. Prefer this over [System.currentTimeMillis] anywhere a duration is measured
     * against a server-supplied timestamp.
     */
    fun nowMs(): Long {
        val serverMs = anchorServerMs
        if (serverMs <= 0L) return System.currentTimeMillis()
        // elapsedRealtime restarts at zero on boot, so a negative delta means the anchor predates a
        // reboot and its monotonic half no longer refers to anything.
        val elapsedMs = SystemClock.elapsedRealtime() - anchorRealtimeMs
        if (elapsedMs < 0L) {
            log.i("Clock anchor predates a reboot — falling back to the device clock")
            reset()
            return System.currentTimeMillis()
        }
        return serverMs + elapsedMs
    }
}
