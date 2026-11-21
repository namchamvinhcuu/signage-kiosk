package com.namchamvinhcuu.signagekiosk

/**
 * Pure decisions of FrontWatch: while the kiosk is stopped (the customer opened another app, or the system killed
 * the cached process) one inexact alarm brings it back to the front. No android.* imports so JUnit covers it.
 * Field data from the sibling Youngmin kiosk (one MiTV, NOT ours): the real delivery of a 5 min
 * `setAndAllowWhileIdle` alarm was 5-9 min; the Javadoc allows about 15 min in idle modes.
 */
object FrontWatchPolicy {
    /** Requested delay of every arm; the platform may deliver later (inexact alarm). */
    const val DELAY_MS = 5 * 60_000L

    /**
     * [interactive] = the screen is on, [overlayGranted] = the SYSTEM_ALERT_WINDOW appop (the exemption from the
     * background-activity-start block), [visible] = the activity is between onStart and onStop. An alarm already
     * in flight when the customer comes back finds [visible] true and must not start anything.
     */
    fun shouldStart(interactive: Boolean, overlayGranted: Boolean, visible: Boolean): Boolean =
        interactive && overlayGranted && !visible

    /** The receiver re-arms after every fire unless the kiosk is in front again (then onStart already cancelled). */
    fun shouldRearm(visible: Boolean): Boolean = !visible

    /** Time since the activity stopped; null when this activity instance has no away timestamp (new process). */
    fun sinceAwayMs(awayAtMs: Long?, nowMs: Long): Long? =
        awayAtMs?.let { nowMs - it }?.takeIf { it >= 0 }
}
