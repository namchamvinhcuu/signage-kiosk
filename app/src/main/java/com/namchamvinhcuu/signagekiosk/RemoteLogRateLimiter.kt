package com.namchamvinhcuu.signagekiosk

// Pure Kotlin (no android.* imports) so it runs in JVM unit tests.
// Time values are monotonic clock readings in milliseconds (SystemClock.elapsedRealtime on the device).

/**
 * Client side of the server's per-TV limit (30 events a minute, then 429): at most [maxPerWindow] sends inside a
 * sliding [windowMs], kept below the server limit so the player's own reports still fit. After the server did
 * answer 429, nothing is sent for [backoffMs].
 */
class RemoteLogRateLimiter(
    private val maxPerWindow: Int = 20,
    private val windowMs: Long = 60_000L,
    private val backoffMs: Long = 60_000L,
) {
    init {
        require(maxPerWindow >= 1) { "maxPerWindow must be at least 1" }
        require(windowMs > 0) { "windowMs must be positive" }
        require(backoffMs >= 0) { "backoffMs must not be negative" }
    }

    private val sent = ArrayDeque<Long>()
    private var blockedUntilMs = Long.MIN_VALUE

    @Synchronized
    fun tryAcquire(nowMs: Long): Boolean {
        if (nowMs < blockedUntilMs) return false
        while (sent.isNotEmpty() && nowMs - sent.first() >= windowMs) sent.removeFirst()
        if (sent.size >= maxPerWindow) return false
        sent.addLast(nowMs)
        return true
    }

    /** The server said 429: stay quiet for [backoffMs]. */
    @Synchronized
    fun onRateLimited(nowMs: Long) {
        blockedUntilMs = nowMs + backoffMs
    }
}
