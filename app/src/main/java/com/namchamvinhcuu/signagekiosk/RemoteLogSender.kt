package com.namchamvinhcuu.signagekiosk

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Posts one prepared body to the server's log endpoint on a single background thread (never the main thread).
 * Best effort: a full queue, a network error or a refusal only produces a local log line, never an exception, and
 * never a retry. Redirects are NOT followed: the URL carries the token and must not be replayed elsewhere.
 * [log] receives only text without the token (the exception message goes through [RemoteLogPolicy.scrub]).
 */
class RemoteLogSender(
    private val log: (String) -> Unit,
    private val limiter: RemoteLogRateLimiter,
    private val clock: () -> Long,
    private val timeoutMs: Int = 5_000,
) {
    private val executor = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(MAX_QUEUE),
    ) { r -> Thread(r, "remote-log").apply { isDaemon = true } }

    /** Queues the post; drops it (and says so locally) when [MAX_QUEUE] posts are already waiting. */
    fun post(endpoint: RemoteLogPolicy.Endpoint, body: ByteArray) {
        try {
            executor.execute { send(endpoint, body) }
        } catch (e: RejectedExecutionException) {
            log("remote log queue full, event dropped")
        }
    }

    /** Synchronous post. Returns the HTTP status, or -1 when the request failed before one came back. */
    internal fun send(endpoint: RemoteLogPolicy.Endpoint, body: ByteArray): Int {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(endpoint.url).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.instanceFollowRedirects = false
            conn.doOutput = true
            conn.setFixedLengthStreamingMode(body.size)
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.outputStream.use { it.write(body) }
            val status = conn.responseCode
            (if (status >= 400) conn.errorStream else conn.inputStream)?.close()
            when {
                status == 429 -> {
                    limiter.onRateLimited(clock())
                    log("remote log rate limited by the server, backing off")
                }
                status !in 200..299 -> log("remote log refused: status $status")
            }
            status
        } catch (e: IOException) {
            log("remote log failed: ${e.javaClass.simpleName} ${RemoteLogPolicy.scrub(e.message.orEmpty(), endpoint.token)}")
            -1
        } catch (e: RuntimeException) {
            log("remote log failed: ${e.javaClass.simpleName} ${RemoteLogPolicy.scrub(e.message.orEmpty(), endpoint.token)}")
            -1
        } finally {
            conn?.disconnect()
        }
    }

    private companion object {
        const val MAX_QUEUE = 20
    }
}
