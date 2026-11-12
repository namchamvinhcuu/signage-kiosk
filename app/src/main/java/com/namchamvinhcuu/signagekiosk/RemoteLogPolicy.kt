package com.namchamvinhcuu.signagekiosk

import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder

// Pure Kotlin (no android.* imports) so it runs in JVM unit tests.

/**
 * The contract of the server's remote-log endpoint `POST <player url>/log?token=...` (owned by the server, see
 * `tv.py`): event whitelist, body at most 2 KB, at most 20 data keys of 40 / 200 chars. Everything here only
 * shapes what the app may send; nothing in a built body or in [Endpoint.toString] carries the token.
 */
object RemoteLogPolicy {
    const val MAX_BODY_BYTES = 2048
    const val MAX_KEYS = 20
    const val MAX_KEY_LEN = 40
    const val MAX_VALUE_LEN = 200

    /** What the server accepts as `event`. */
    val SERVER_EVENTS = setOf("boot", "heartbeat", "js_error", "http_error", "auth_403", "offline", "online", "reload", "watchdog")

    /**
     * App event -> server event. An app event that is not listed stays in the local log only. `app_auth_403` /
     * `app_auth_revoked` are deliberately not mapped: the server would answer that very POST with 403 (bad token).
     */
    private val MAPPING = mapOf(
        "app_start" to "boot",
        "app_status" to "heartbeat",
        "app_load_error" to "http_error",
        "app_render_gone" to "watchdog",
        "app_recreate" to "watchdog",
        "app_hang" to "watchdog",
        "app_restart" to "watchdog",
        "app_trim" to "watchdog",
        "app_refront" to "watchdog",
        "app_error" to "watchdog",
    )

    private val TOKEN_IN_TEXT = Regex("(?i)token=[^&\\s\"']*")
    private val URL_IN_TEXT = Regex("(?i)\\bhttps?://[^\\s\"']+")

    fun serverEvent(appEvent: String): String? = MAPPING[appEvent]

    /** Where to POST and the token to scrub from the text. [toString] hides both: it may end up in a log line. */
    class Endpoint internal constructor(val url: String, internal val token: String) {
        override fun toString(): String = "Endpoint(<redacted>)"
    }

    /**
     * `<player url>/log?token=<token>` for a stored player URL, only when [PairingPolicy] accepts it for [baseUrl]
     * (same server, one plain code segment, a token). Other query parameters of the player URL are not forwarded.
     */
    fun endpoint(baseUrl: String, pairedUrl: String?): Endpoint? {
        if (pairedUrl == null || !PairingPolicy.isPairedUrl(baseUrl, pairedUrl)) return null
        val uri = try {
            URI(pairedUrl.trim())
        } catch (e: URISyntaxException) {
            return null
        }
        val rawToken = uri.rawQuery.orEmpty().split('&').first { it.substringBefore('=') == "token" }.substringAfter('=', "")
        val token = try {
            URLDecoder.decode(rawToken, "UTF-8")
        } catch (e: IllegalArgumentException) {
            return null
        }
        val port = if (uri.port == -1) "" else ":${uri.port}"
        val path = uri.rawPath.orEmpty().trimEnd('/')
        return Endpoint("${uri.scheme}://${uri.host}$port$path/log?token=$rawToken", token)
    }

    /**
     * JSON body `{"event":...,"data":{...}}`, UTF-8, at most [MAX_BODY_BYTES]. Null values are dropped, a value is cut
     * to [MAX_VALUE_LEN] after URLs are redacted and any `token=...` or the token itself is removed. Keys beyond
     * [MAX_KEYS], or beyond what fits in the byte budget, are dropped from the end.
     */
    fun body(serverEvent: String, fields: Map<String, Any?>, token: String): ByteArray? {
        if (serverEvent !in SERVER_EVENTS) return null
        val entries = fields.entries
            .filter { it.value != null }
            .take(MAX_KEYS)
            .map { scrub(it.key, token).take(MAX_KEY_LEN) to encodeValue(it.value, token) }
            .toMutableList()
        while (true) {
            val json = render(serverEvent, entries)
            val bytes = json.toByteArray(Charsets.UTF_8)
            if (bytes.size <= MAX_BODY_BYTES) return bytes
            if (entries.isEmpty()) return null
            entries.removeAt(entries.size - 1)
        }
    }

    /** Text safe to send or log: URLs reduced to scheme/host/path, `token=` values and the token itself removed. */
    fun scrub(text: String, token: String): String {
        var s = URL_IN_TEXT.replace(text) { UrlRedactor.redactUrl(it.value) ?: "<url>" }
        s = TOKEN_IN_TEXT.replace(s, "token=<redacted>")
        if (token.length >= 4) s = s.replace(token, "<redacted>")
        return s
    }

    private fun encodeValue(value: Any?, token: String): String = when (value) {
        is Boolean, is Int, is Long -> value.toString()
        is Double, is Float -> if ((value as Number).toDouble().isFinite()) value.toString() else JsonText.quote("")
        else -> JsonText.quote(scrub(value.toString(), token).take(MAX_VALUE_LEN))
    }

    private fun render(event: String, entries: List<Pair<String, String>>): String =
        "{\"event\":${JsonText.quote(event)},\"data\":{" + entries.joinToString(",") { "${JsonText.quote(it.first)}:${it.second}" } + "}}"
}
