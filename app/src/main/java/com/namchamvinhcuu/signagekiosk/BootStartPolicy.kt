package com.namchamvinhcuu.signagekiosk

/**
 * Pure decision of [KioskStartService]: the system (re)connects an enabled accessibility service not only at boot
 * but after any process death (operator "exit", periodic restart, OS kill, self-update). Only a connect shortly
 * after the DEVICE booted, while the kiosk is not already in front, may open it; every other connect must leave
 * the kiosk alone (an "exit" stays an exit, the restart/update machinery keeps its own labels and timing).
 * No android.* imports so JUnit covers it.
 */
object BootStartPolicy {
    /**
     * Device uptime below which a connect counts as "just booted". Measured on the TCL test TV: the kiosk was in
     * front 56 s after boot; five minutes leaves room for a slow boot without reaching a normal process restart.
     */
    const val BOOT_WINDOW_MS = 5 * 60_000L

    /** [deviceUptimeMs] is the time since DEVICE boot (not process start); [kioskInFront] = FrontWatch.visible. */
    fun shouldStart(deviceUptimeMs: Long, kioskInFront: Boolean): Boolean =
        deviceUptimeMs in 0 until BOOT_WINDOW_MS && !kioskInFront
}
