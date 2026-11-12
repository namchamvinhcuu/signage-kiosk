package com.namchamvinhcuu.signagekiosk

// Pure Kotlin (no android.* imports) so it runs in JVM unit tests.
// Time values are monotonic clock readings in milliseconds (SystemClock.elapsedRealtime on the device).

/**
 * Revocation ("Regenerate token" on the server makes the player URL answer 403). Only [maxFailures] main-frame 403s
 * on the paired player URL inside a sliding [windowMs] clear the stored session; a network error, a 5xx or a lone
 * 403 is not revocation. It deliberately depends on NO other WebView callback (the order of onPageStarted /
 * onReceivedHttpError / onPageFinished is not documented): a transient 403 simply ages out of the window.
 * Two reports less than [minGapMs] apart are one load reported twice and count once.
 */
class AuthFailurePolicy(
    private val maxFailures: Int = 3,
    private val windowMs: Long = 10 * 60_000L,
    private val minGapMs: Long = 5_000L,
) {
    enum class Decision { RETRY, CLEAR }

    init {
        require(maxFailures >= 1) { "maxFailures must be at least 1" }
        require(windowMs > 0) { "windowMs must be positive" }
        require(minGapMs >= 0) { "minGapMs must not be negative" }
    }

    private val recent = ArrayDeque<Long>()

    /** RETRY = load the stored URL again later; CLEAR = drop the stored session and go back to pairing. */
    fun onMainFrame403(nowMs: Long): Decision {
        while (recent.isNotEmpty() && nowMs - recent.first() >= windowMs) recent.removeFirst()
        if (recent.isNotEmpty() && nowMs - recent.last() < minGapMs) return Decision.RETRY
        recent.addLast(nowMs)
        if (recent.size < maxFailures) return Decision.RETRY
        recent.clear()
        return Decision.CLEAR
    }

    fun reset() {
        recent.clear()
    }
}
