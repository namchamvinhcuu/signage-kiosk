package com.namchamvinhcuu.signagekiosk

import java.net.URI
import java.net.URISyntaxException

/** Pure Kotlin (no android.* imports) so it runs in JVM unit tests. */
object UrlRedactor {
    /**
     * URL safe to log: scheme, host, port, path and fragment only — userinfo and query, including a query
     * inside the fragment (which may carry credentials such as `?token=`), are dropped. Null when it does
     * not parse.
     */
    fun redactUrl(url: String?): String? {
        if (url == null) return null
        val uri = try {
            URI(url.trim())
        } catch (e: URISyntaxException) {
            return null
        }
        val scheme = uri.scheme ?: return null
        val host = uri.host ?: return null
        val port = if (uri.port == -1) "" else ":${uri.port}"
        val path = uri.rawPath.orEmpty()
        // Hash-SPA routes can carry their own query (`#checkin?token=…`): keep only the route.
        val fragment = uri.rawFragment?.let { "#" + it.substringBefore('?') }.orEmpty()
        return "$scheme://$host$port$path$fragment"
    }
}
