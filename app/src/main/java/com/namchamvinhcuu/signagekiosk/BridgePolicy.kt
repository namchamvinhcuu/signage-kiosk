package com.namchamvinhcuu.signagekiosk

// Pure Kotlin (no android.* imports) so it runs in JVM unit tests.
// Time values are monotonic clock readings in milliseconds (SystemClock.elapsedRealtime on the device).

/**
 * Rules of the page-to-app bridge `window.SignageKiosk`. The bridge object is injected at WebView creation (an
 * injected object only shows up on the next page load) so the real gate is this check at call time: the main frame
 * must be the player page of THIS server and a session must be stored. WebView cannot tell which frame called, so
 * an iframe inside the player page could use it too: the methods therefore return only coarse device figures
 * and the two actions are rate guarded.
 */
object BridgePolicy {
    /** A page that just started loading is trusted only while it is the player URL of the configured server. */
    fun isAllowed(baseUrl: String, pairedUrl: String?, mainFrameUrl: String?): Boolean =
        pairedUrl != null && mainFrameUrl != null && PairingPolicy.isPairedUrl(baseUrl, mainFrameUrl)

    /** `restart()` is ignored in the first minutes of a process: a page that calls it on every load cannot loop the app. */
    fun restartAllowed(uptimeMs: Long): Boolean = uptimeMs >= MIN_UPTIME_FOR_RESTART_MS

    /**
     * One page-requested restart per [MIN_RESTART_GAP_MS] across processes (a third-party iframe in a playlist can call
     * `restart()` too, see the class doc). A clock that went backwards counts as passed: the page cannot move the clock.
     * A TV that booted with a wrong, older time (no RTC / NTP yet) therefore has no cooldown until the clock catches
     * up and only the 5 min uptime check holds; failing closed would lock the restart for an unbounded time instead.
     */
    fun restartCooldownPassed(nowWallMs: Long, lastWallMs: Long?): Boolean =
        lastWallMs == null || nowWallMs - lastWallMs !in 0 until MIN_RESTART_GAP_MS

    /** `onAuthRevoked()` reloads the stored URL (the 403 policy then counts the real answer); at most once per gap. */
    fun recheckAllowed(nowMs: Long, lastMs: Long?): Boolean = lastMs == null || nowMs - lastMs >= MIN_RECHECK_GAP_MS

    fun memoryJson(availMb: Long, totalMb: Long, lowMem: Boolean, pssMb: Long, upMin: Long): String =
        JsonText.flatObject(
            mapOf("availMb" to availMb, "totalMb" to totalMb, "lowMem" to lowMem, "pssMb" to pssMb, "upMin" to upMin),
        )

    fun infoJson(
        versionName: String,
        versionCode: Int,
        widthPx: Int,
        heightPx: Int,
        densityDpi: Int,
        density: Double,
        rotation: Int,
        webview: String?,
    ): String = JsonText.flatObject(
        mapOf(
            "app" to "SignageKiosk",
            "version" to versionName,
            "versionCode" to versionCode,
            "widthPx" to widthPx,
            "heightPx" to heightPx,
            "densityDpi" to densityDpi,
            "density" to density,
            "rotation" to rotation,
            "webview" to webview,
        ),
    )

    /** Exit reason saved by a bridge restart; [RefrontPolicy] treats it like the other self-started restarts. */
    const val RESTART_REASON = "bridge_restart"
    const val MIN_UPTIME_FOR_RESTART_MS = 5 * 60_000L
    const val MIN_RESTART_GAP_MS = 60 * 60_000L
    const val MIN_RECHECK_GAP_MS = 10 * 60_000L
}
