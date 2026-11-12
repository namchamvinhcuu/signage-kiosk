package com.namchamvinhcuu.signagekiosk

/**
 * Structured app events. Local log only for now: the remote POST to the server's log endpoint is a later
 * plan step, so no server path or credential is baked in here. Never pass cookies, tokens or credentials in
 * [fields]; URLs go through [UrlRedactor.redactUrl].
 */
class EventLogger {
    fun event(kind: String, fields: Map<String, Any?> = emptyMap()) {
        KLog.i("$kind $fields")
    }
}
