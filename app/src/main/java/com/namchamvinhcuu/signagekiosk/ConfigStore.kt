package com.namchamvinhcuu.signagekiosk

import android.content.Context

/** SharedPreferences for the config and the reason the previous process ended. */
class ConfigStore(context: Context) {
    data class ExitRecord(val reason: String, val atWallMs: Long)

    private val prefs = context.getSharedPreferences("kiosk", Context.MODE_PRIVATE)

    /** Stored values go through the same validation as every other source; invalid → default. */
    fun load(): KioskConfig = KioskConfig(
        baseUrl = ConfigValidator.validateBaseUrl(prefs.getString(K_BASE_URL, null)) ?: KioskConfig.DEFAULT_BASE_URL,
        restartMin = ConfigValidator.normalizeRestartMin(prefs.getInt(K_RESTART_MIN, KioskConfig.DEFAULT_RESTART_MIN))
            ?: KioskConfig.DEFAULT_RESTART_MIN,
        lowMemMb = ConfigValidator.normalizeLowMemMb(prefs.getInt(K_LOW_MEM_MB, KioskConfig.DEFAULT_LOW_MEM_MB))
            ?: KioskConfig.DEFAULT_LOW_MEM_MB,
        updateChannel = ConfigValidator.normalizeUpdateChannel(prefs.getString(K_UPDATE_CHANNEL, null)) ?: UpdateChannel.OFF,
    )

    /** commit(): a restart may follow right away and must see the new values. */
    fun save(c: KioskConfig): Boolean = prefs.edit()
        .putString(K_BASE_URL, c.baseUrl)
        .putInt(K_RESTART_MIN, c.restartMin)
        .putInt(K_LOW_MEM_MB, c.lowMemMb)
        .putString(K_UPDATE_CHANNEL, c.updateChannel.wire)
        .commit()

    // ---- Self-update bookkeeping (see UpdateContract) -------------------------------------------------

    data class UpdateResultRecord(val status: String, val code: Int, val message: String?)

    fun loadUpdateState(): UpdateState = UpdateState(
        backoffUntilMs = prefs.getLong(K_UPD_BACKOFF_UNTIL, 0L),
        verifyFailedCode = prefs.getInt(K_UPD_VERIFY_CODE, 0),
        verifyFailedAtMs = prefs.getLong(K_UPD_VERIFY_AT, 0L),
    )

    /** Synchronous on purpose: the install may kill the process right after. */
    fun saveUpdateState(s: UpdateState): Boolean = prefs.edit()
        .putLong(K_UPD_BACKOFF_UNTIL, s.backoffUntilMs)
        .putInt(K_UPD_VERIFY_CODE, s.verifyFailedCode)
        .putLong(K_UPD_VERIFY_AT, s.verifyFailedAtMs)
        .commit()

    /** The versionCode being installed; read back at the next start to tell "updated" from "never arrived". */
    fun savePendingUpdateCode(code: Int): Boolean = prefs.edit().putInt(K_UPD_PENDING_CODE, code).commit()

    fun peekPendingUpdateCode(): Int = prefs.getInt(K_UPD_PENDING_CODE, 0)

    /** Returns and clears the pending code (0 = none). */
    fun takePendingUpdateCode(): Int {
        val code = prefs.getInt(K_UPD_PENDING_CODE, 0)
        if (code != 0) prefs.edit().remove(K_UPD_PENDING_CODE).commit()
        return code
    }

    /** Status text from the installer, cut to 200 chars; never a URL with a token (the installer knows none). */
    fun saveUpdateResult(status: String, code: Int, message: String?): Boolean = prefs.edit()
        .putString(K_UPD_RESULT_STATUS, status)
        .putInt(K_UPD_RESULT_CODE, code)
        .putString(K_UPD_RESULT_MSG, message?.take(200))
        .commit()

    fun peekUpdateResult(): UpdateResultRecord? {
        val status = prefs.getString(K_UPD_RESULT_STATUS, null) ?: return null
        return UpdateResultRecord(status, prefs.getInt(K_UPD_RESULT_CODE, 0), prefs.getString(K_UPD_RESULT_MSG, null))
    }

    /** Returns and clears the stored result, if any (reported once, after the next start). */
    fun takeUpdateResult(): UpdateResultRecord? {
        val r = peekUpdateResult() ?: return null
        prefs.edit().remove(K_UPD_RESULT_STATUS).remove(K_UPD_RESULT_CODE).remove(K_UPD_RESULT_MSG).commit()
        return r
    }

    /** Synchronous on purpose: the process exits right after this call. */
    fun saveExitReason(reason: String, atWallMs: Long): Boolean = prefs.edit()
        .putString(K_EXIT_REASON, reason)
        .putLong(K_EXIT_AT, atWallMs)
        .commit()

    /** Returns and clears the reason saved by the previous process, if any. */
    fun takeExitReason(): ExitRecord? {
        val reason = prefs.getString(K_EXIT_REASON, null) ?: return null
        val at = prefs.getLong(K_EXIT_AT, 0L)
        prefs.edit().remove(K_EXIT_REASON).remove(K_EXIT_AT).commit()
        return ExitRecord(reason, at)
    }

    /** Wall-clock time of the last restart a page asked for through the bridge; survives the restart it causes. */
    fun lastBridgeRestartAtWallMs(): Long? = if (prefs.contains(K_BRIDGE_RESTART_AT)) prefs.getLong(K_BRIDGE_RESTART_AT, 0L) else null

    /** Synchronous on purpose: the process exits right after this call. */
    fun saveBridgeRestartAt(atWallMs: Long): Boolean = prefs.edit().putLong(K_BRIDGE_RESTART_AT, atWallMs).commit()

    private companion object {
        const val K_UPDATE_CHANNEL = "update_channel"
        const val K_UPD_BACKOFF_UNTIL = "upd_backoff_until"
        const val K_UPD_VERIFY_CODE = "upd_verify_code"
        const val K_UPD_VERIFY_AT = "upd_verify_at"
        const val K_UPD_PENDING_CODE = "upd_pending_code"
        const val K_UPD_RESULT_STATUS = "upd_result_status"
        const val K_UPD_RESULT_CODE = "upd_result_code"
        const val K_UPD_RESULT_MSG = "upd_result_msg"
        const val K_BRIDGE_RESTART_AT = "bridge_restart_at"
        const val K_BASE_URL = "base_url"
        const val K_RESTART_MIN = "restart_min"
        const val K_LOW_MEM_MB = "low_mem_mb"
        const val K_EXIT_REASON = "exit_reason"
        const val K_EXIT_AT = "exit_at"
    }
}
