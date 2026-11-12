package com.namchamvinhcuu.signagekiosk

import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder

// Pure Kotlin (no android.* imports) so it runs in JVM unit tests.

/**
 * Decides whether a URL is the player URL the server issued for THIS kiosk's server:
 * `<base>/signage/tv/<code>?token=<token>` on the origin of the configured base URL. Only such a URL may be
 * stored as the paired session. The URL carries the token: never log it, use [UrlRedactor.redactUrl].
 */
object PairingPolicy {
    const val TV_PATH = "/signage/tv/"
    private const val TOKEN_PARAM = "token"

    fun isPairedUrl(baseUrl: String, candidate: String?): Boolean {
        val base = parse(baseUrl) ?: return false
        val cand = parse(candidate ?: return false) ?: return false
        if (cand.rawUserInfo != null) return false
        if (!sameServer(base, cand)) return false
        val prefix = base.rawPath.orEmpty().trimEnd('/') + TV_PATH
        val path = cand.rawPath.orEmpty()
        if (!path.startsWith(prefix)) return false
        val code = path.substring(prefix.length)
        if (!isPlainSegment(code)) return false
        return hasToken(cand.rawQuery)
    }

    /** One real path segment: no separator (also percent-encoded) and not a dot segment (also percent-encoded). */
    private fun isPlainSegment(code: String): Boolean {
        if (code.isEmpty() || code.contains('/') || code.contains('\\')) return false
        val decoded = decode(code) ?: return false
        return decoded != "." && decoded != ".." && !decoded.contains('/') && !decoded.contains('\\')
    }

    private fun decode(s: String): String? = try {
        URLDecoder.decode(s, "UTF-8")
    } catch (e: IllegalArgumentException) {
        null
    }

    private fun parse(url: String): URI? {
        val uri = try {
            URI(url.trim())
        } catch (e: URISyntaxException) {
            return null
        }
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        if (uri.host.isNullOrEmpty()) return null
        return uri
    }

    /** Same scheme+host+port; a proxy's http -> https upgrade of the same host is accepted too. */
    private fun sameServer(base: URI, cand: URI): Boolean {
        if (!base.host.equals(cand.host, ignoreCase = true)) return false
        val baseScheme = base.scheme.lowercase()
        val candScheme = cand.scheme.lowercase()
        val basePort = effectivePort(baseScheme, base.port)
        val candPort = effectivePort(candScheme, cand.port)
        if (baseScheme == candScheme) return basePort == candPort
        // Upgrade: the proxy answers on the default https port (or keeps the same explicit port).
        return baseScheme == "http" && candScheme == "https" && (candPort == 443 || candPort == basePort)
    }

    private fun effectivePort(scheme: String, port: Int): Int = if (port != -1) port else if (scheme == "https") 443 else 80

    /** The FIRST `token` parameter must carry a value (the server takes the first one); blank counts as empty. */
    private fun hasToken(rawQuery: String?): Boolean {
        val first = rawQuery.orEmpty().split('&').firstOrNull { it.substringBefore('=') == TOKEN_PARAM } ?: return false
        val value = decode(first.substringAfter('=', "")) ?: return false
        return value.isNotBlank()
    }
}
