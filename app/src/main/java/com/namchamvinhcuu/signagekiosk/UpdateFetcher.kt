package com.namchamvinhcuu.signagekiosk

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** A fetch that must not be retried blindly or trusted: [reason] is a short code that is safe to log (no URL, no token). */
class FetchFailure(val reason: String) : IOException(reason)

/**
 * HTTP GET that follows redirects BY HAND: every `Location` is judged by [UpdateSource.validateHop] before it is
 * requested (https, host allow-list, hop limit for the GitHub source), so a redirect can never lead the app somewhere the
 * source did not allow. Only a final 200 is accepted. The caller reads the body and then calls [Opened.close].
 * Plain java.net, no android.* imports.
 */
class UpdateFetcher(
    private val source: UpdateSource,
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 60_000,
) {
    class Opened(private val conn: HttpURLConnection, val firstHop: String?) : AutoCloseable {
        val input get() = conn.inputStream
        val contentLength: Long get() = conn.contentLengthLong
        override fun close() = conn.disconnect()
    }

    /** @throws FetchFailure for a refused hop, a non-200 answer or too many hops; IOException for network errors. */
    fun open(url: String): Opened {
        var current = url
        var firstHop: String? = null
        var hops = 0
        while (true) {
            val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                useCaches = false
                instanceFollowRedirects = false
            }
            try {
                val code = conn.responseCode
                if (code in REDIRECTS) {
                    val location = conn.getHeaderField("Location")
                    conn.disconnect()
                    hops++
                    when (val hop = source.validateHop(current, location, hops)) {
                        is HopResult.Reject -> throw FetchFailure("hop_" + hop.reason)
                        is HopResult.Follow -> {
                            if (hops == 1) firstHop = hop.url
                            current = hop.url
                        }
                    }
                    continue
                }
                if (code != HttpURLConnection.HTTP_OK) {
                    conn.disconnect()
                    throw FetchFailure("http_$code")
                }
                return Opened(conn, firstHop)
            } catch (e: FetchFailure) {
                throw e
            } catch (e: IOException) {
                conn.disconnect()
                throw e
            }
        }
    }

    private companion object {
        val REDIRECTS = setOf(301, 302, 303, 307, 308)
    }
}
