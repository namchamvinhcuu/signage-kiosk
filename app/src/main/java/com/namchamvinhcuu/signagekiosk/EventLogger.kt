package com.namchamvinhcuu.signagekiosk

/**
 * Structured app events: always the local log, and once paired also the server's log endpoint ([remote], see
 * [RemoteLogger]). Never pass cookies, tokens or credentials in [fields]; URLs go through [UrlRedactor.redactUrl]
 * (the remote path scrubs again, but the local log has no second line of defence).
 */
class EventLogger(private val remote: RemoteLogger? = null) {
    fun event(kind: String, fields: Map<String, Any?> = emptyMap()) {
        KLog.i("$kind $fields")
        try {
            remote?.report(kind, fields)
        } catch (e: RuntimeException) {
            // Reporting must never break the kiosk: say so locally and carry on.
            KLog.e("remote log report failed", e)
        }
    }
}
