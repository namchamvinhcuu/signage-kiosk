package com.namchamvinhcuu.signagekiosk

/**
 * Process-local, single-use note "FrontWatch is about to start MainActivity". After the system kills the cached
 * process its task record survives and replays the ORIGINAL launch intent (e.g. `start=update`) to onCreate, so
 * the start reason cannot be read from the intent; the receiver sets this mark and `AppStart.derive` lets it win.
 * The caller passes the clock (elapsedRealtime), keeping this class free of android.* imports.
 */
object FrontStartMark {
    /** A mark older than this is stale (a blocked start, or a manual launch long after). */
    const val WINDOW_MS = 30_000L

    private const val NONE = Long.MIN_VALUE
    private var setAtMs = NONE

    @Synchronized
    fun set(nowMs: Long) {
        setAtMs = nowMs
    }

    /** True once if a mark was set less than [WINDOW_MS] ago; always consumes it. */
    @Synchronized
    fun take(nowMs: Long): Boolean {
        val at = setAtMs
        setAtMs = NONE
        return at != NONE && nowMs - at in 0 until WINDOW_MS
    }

    @Synchronized
    fun clear() {
        setAtMs = NONE
    }
}
