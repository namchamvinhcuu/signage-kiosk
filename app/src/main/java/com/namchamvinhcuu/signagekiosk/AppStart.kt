package com.namchamvinhcuu.signagekiosk

/** `start` / `prev_reason` of the `app_start` event. [pendingNotInstalled]: an update was being installed but never arrived. */
data class AppStartInfo(val start: String, val prevReason: String?, val pendingNotInstalled: Boolean = false)

object AppStart {
    /**
     * [extraStart] = EXTRA_START of the launching intent ("boot", "update"); [prevExitReason] = reason saved by the
     * previous process (restart, crash, exit); [pendingUpdateCode] = version the previous process was installing
     * (0 = none) and [versionCode] = the running one. The update wins over an exit reason: a restart racing a slow
     * install must not hide the update.
     */
    fun derive(extraStart: String?, prevExitReason: String?, pendingUpdateCode: Int = 0, versionCode: Int = 0): AppStartInfo {
        val updated = pendingUpdateCode != 0 && pendingUpdateCode == versionCode
        val start = extraStart ?: when {
            updated -> "update"
            prevExitReason != null -> "restart"
            else -> "launch"
        }
        val prevReason = if (updated) "update_$pendingUpdateCode" else prevExitReason
        return AppStartInfo(start, prevReason, pendingNotInstalled = pendingUpdateCode != 0 && !updated)
    }
}
