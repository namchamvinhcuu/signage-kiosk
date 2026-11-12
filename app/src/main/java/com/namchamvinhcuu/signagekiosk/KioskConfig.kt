package com.namchamvinhcuu.signagekiosk

import java.net.URI
import java.net.URISyntaxException

// Pure Kotlin (no android.* imports) so it runs in JVM unit tests.

/** Runtime configuration. [baseUrl] is the server base; the app loads [pairUrl] derived from it. */
data class KioskConfig(
    val baseUrl: String,
    val restartMin: Int,
    val lowMemMb: Int,
) {
    /** Page to load: `<base>/signage/pair`, or a blank page while no server is configured. */
    val pairUrl: String
        get() = if (baseUrl == DEFAULT_BASE_URL) DEFAULT_BASE_URL else baseUrl + PAIR_PATH

    companion object {
        /** No server is baked in: the page is blank until a base URL is configured. */
        const val DEFAULT_BASE_URL = "about:blank"
        const val PAIR_PATH = "/signage/pair"
        const val DEFAULT_RESTART_MIN = 240
        const val DEFAULT_LOW_MEM_MB = 0
        val DEFAULT = KioskConfig(DEFAULT_BASE_URL, DEFAULT_RESTART_MIN, DEFAULT_LOW_MEM_MB)
    }
}

/** Validation + clamping for every config source (SharedPreferences, intent extras, settings dialog). */
object ConfigValidator {
    const val RESTART_MIN_LOW = 15
    const val RESTART_MIN_HIGH = 1440
    const val LOW_MEM_MB_LOW = 50
    const val LOW_MEM_MB_HIGH = 4096

    /** Returns the trimmed URL when it is http/https with a host, else null (rejected). */
    fun validateUrl(raw: String?): String? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return null
        val uri = try {
            URI(s)
        } catch (e: URISyntaxException) {
            return null
        }
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        if (uri.host.isNullOrEmpty()) return null
        return s
    }

    /**
     * Base URL of the server: an http/https URL with a host and no userinfo, query or fragment; trailing
     * slashes are dropped (a path prefix for a reverse proxy is allowed), and so is a pasted `/signage/pair`
     * suffix (else the pair URL would double it). Otherwise null (rejected).
     */
    fun validateBaseUrl(raw: String?): String? {
        val s = validateUrl(raw) ?: return null
        val uri = URI(s)
        if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) return null
        val trimmed = s.trimEnd('/')
        // Judge the suffix on the URI path, not the whole string: `https://signage/pair` is host `signage`.
        return if (uri.rawPath.orEmpty().trimEnd('/').endsWith(KioskConfig.PAIR_PATH)) {
            trimmed.removeSuffix(KioskConfig.PAIR_PATH).trimEnd('/')
        } else {
            trimmed
        }
    }

    /** 0 = off; negative = rejected (null); otherwise clamped to 15..1440. */
    fun normalizeRestartMin(n: Int): Int? = normalize(n, RESTART_MIN_LOW, RESTART_MIN_HIGH)

    /** 0 = off; negative = rejected (null); otherwise clamped to 50..4096. */
    fun normalizeLowMemMb(n: Int): Int? = normalize(n, LOW_MEM_MB_LOW, LOW_MEM_MB_HIGH)

    private fun normalize(n: Int, low: Int, high: Int): Int? = when {
        n == 0 -> 0
        n < 0 -> null
        else -> n.coerceIn(low, high)
    }
}
