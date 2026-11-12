package com.namchamvinhcuu.signagekiosk

/** `start` / `prev_reason` of the `app_start` event. */
data class AppStartInfo(val start: String, val prevReason: String?)

object AppStart {
    /**
     * [extraStart] = EXTRA_START of the launching intent ("boot"); [prevExitReason] = reason saved by the
     * previous process (restart, crash, exit).
     */
    fun derive(extraStart: String?, prevExitReason: String?): AppStartInfo {
        val start = extraStart ?: if (prevExitReason != null) "restart" else "launch"
        return AppStartInfo(start, prevExitReason)
    }
}
