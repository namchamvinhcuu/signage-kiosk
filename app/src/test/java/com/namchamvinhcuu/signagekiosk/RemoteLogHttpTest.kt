package com.namchamvinhcuu.signagekiosk

import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Sender + logger against the JDK in-process HttpServer on 127.0.0.1 (no extra library). */
class RemoteLogHttpTest {

    private class Req(val method: String, val path: String, val query: String?, val contentType: String?, val body: String)

    private class Srv(val status: Int = 200, val location: String? = null, val delayMs: Long = 0) {
        val received = CopyOnWriteArrayList<Req>()
        val latch = CountDownLatch(1)
        // The Kotlin compiler of this project cannot see com.sun.net.httpserver (jdk.httpserver is not on the unit-test
        // compile classpath), so this is a minimal in-process HTTP/1.1 server on a plain ServerSocket.
        val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        private val thread = Thread {
            while (!server.isClosed) {
                val sock = try { server.accept() } catch (e: java.io.IOException) { break }
                Thread { handle(sock) }.apply { isDaemon = true }.start()
            }
        }.apply { isDaemon = true; start() }

        private fun handle(sock: java.net.Socket) {
            sock.use {
                val input = java.io.BufferedInputStream(it.getInputStream())
                fun line(): String {
                    val sb = StringBuilder()
                    while (true) {
                        val c = input.read()
                        if (c < 0 || c == '\n'.code) break
                        if (c != '\r'.code) sb.append(c.toChar())
                    }
                    return sb.toString()
                }
                val reqLine = line().split(' ')
                if (reqLine.size < 2) return
                val headers = HashMap<String, String>()
                while (true) {
                    val h = line()
                    if (h.isEmpty()) break
                    headers[h.substringBefore(':').trim().lowercase()] = h.substringAfter(':').trim()
                }
                val len = headers["content-length"]?.toInt() ?: 0
                val body = ByteArray(len)
                var off = 0
                while (off < len) {
                    val n = input.read(body, off, len - off)
                    if (n < 0) break
                    off += n
                }
                val target = reqLine[1]
                received += Req(reqLine[0], target.substringBefore('?'), target.substringAfter('?', "").ifEmpty { null },
                    headers["content-type"], String(body, Charsets.UTF_8))
                latch.countDown()
                if (delayMs > 0) Thread.sleep(delayMs)
                val loc = if (location != null) "Location: $location\r\n" else ""
                try {
                    it.getOutputStream().write("HTTP/1.1 $status X\r\n${loc}Content-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    it.getOutputStream().flush()
                } catch (e: java.io.IOException) {
                    // client gave up (timeout test)
                }
            }
        }

        val port: Int get() = server.localPort
        val base: String get() = "http://127.0.0.1:$port"
        fun stop() = server.close()
    }

    private val servers = mutableListOf<Srv>()
    private fun srv(status: Int = 200, location: String? = null, delayMs: Long = 0) = Srv(status, location, delayMs).also { servers += it }

    @After
    fun tearDown() {
        servers.forEach { it.stop() }
    }

    private val token = "SECRETTOKEN123"
    private fun endpointFor(base: String) =
        RemoteLogPolicy.endpoint(base, "$base/signage/tv/tv-01?token=$token&junk=1")!!

    private class Rig(timeoutMs: Int = 2_000) {
        var now = 1_000_000L
        val logs = CopyOnWriteArrayList<String>()
        val limiter = RemoteLogRateLimiter()
        val sender = RemoteLogSender({ logs += it }, limiter, { now }, timeoutMs)
    }

    // ---- RemoteLogSender.send

    @Test
    fun `200 returns 200 and the server gets path query body and content type intact`() {
        val s = srv(200)
        val r = Rig()
        val body = """{"event":"boot","data":{"a":"ệ"}}""".toByteArray(Charsets.UTF_8)
        assertEquals(200, r.sender.send(endpointFor(s.base), body))
        val req = s.received.single()
        assertEquals("POST", req.method)
        assertEquals("/signage/tv/tv-01/log", req.path)
        assertEquals("token=$token", req.query)
        assertTrue(req.contentType!!.startsWith("application/json"))
        assertEquals(String(body, Charsets.UTF_8), req.body)
        assertTrue(r.limiter.tryAcquire(r.now))
        assertTrue(r.logs.isEmpty())
    }

    @Test
    fun `429 returns 429 and the limiter backs off`() {
        val s = srv(429)
        val r = Rig()
        assertEquals(429, r.sender.send(endpointFor(s.base), "{}".toByteArray()))
        assertFalse(r.limiter.tryAcquire(r.now))
        assertFalse(r.limiter.tryAcquire(r.now + 59_999))
        assertTrue(r.limiter.tryAcquire(r.now + 60_000))
        assertTrue(r.logs.any { it.contains("rate limited") })
    }

    @Test
    fun `400 403 413 return the status without backoff and log without the token`() {
        for (code in listOf(400, 403, 413, 500)) {
            val s = srv(code)
            val r = Rig()
            assertEquals(code, r.sender.send(endpointFor(s.base), "{}".toByteArray()))
            assertTrue("no backoff after $code", r.limiter.tryAcquire(r.now))
            assertTrue(r.logs.single().contains("status $code"))
            assertFalse(r.logs.any { it.contains(token) })
        }
    }

    @Test
    fun `a 302 redirect is not followed`() {
        val target = srv(200)
        val first = srv(302, location = "${target.base}/stolen")
        val r = Rig()
        assertEquals(302, r.sender.send(endpointFor(first.base), "{}".toByteArray()))
        Thread.sleep(300)
        assertEquals(1, first.received.size)
        assertEquals(0, target.received.size)
        assertFalse(r.logs.any { it.contains(token) })
    }

    @Test
    fun `connection refused returns minus one and logs no token`() {
        val port = ServerSocket(0, 0, InetAddress.getLoopbackAddress()).use { it.localPort }
        val r = Rig()
        assertEquals(-1, r.sender.send(endpointFor("http://127.0.0.1:$port"), "{}".toByteArray()))
        assertTrue(r.logs.single().startsWith("remote log failed: "))
        assertFalse(r.logs.any { it.contains(token) })
        assertTrue(r.limiter.tryAcquire(r.now))
    }

    @Test
    fun `timeout returns minus one and logs no token`() {
        val s = srv(200, delayMs = 2_500)
        val r = Rig(timeoutMs = 300)
        val t0 = System.nanoTime()
        assertEquals(-1, r.sender.send(endpointFor(s.base), "{}".toByteArray()))
        assertTrue("took too long", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0) < 2_000)
        assertTrue(r.logs.single().contains("SocketTimeoutException"))
        assertFalse(r.logs.any { it.contains(token) })
    }

    @Test
    fun `an exception message carrying the token is scrubbed in the log`() {
        // a malformed URL makes URL() throw a RuntimeException-free IOException (MalformedURLException) containing the url
        val ep = RemoteLogPolicy.endpoint("http://h", "http://h/signage/tv/c?token=$token")!!
        val r = Rig()
        // host "h" does not resolve: UnknownHostException message is the host only; still must not contain the token
        r.sender.send(ep, "{}".toByteArray())
        assertTrue(r.logs.isNotEmpty())
        assertFalse(r.logs.any { it.contains(token) })
    }

    @Test
    fun `queue full drops the extra post with a local log and never throws`() {
        val s = srv(200, delayMs = 2_000) // holds the single worker busy
        val r = Rig(timeoutMs = 10_000)
        val ep = endpointFor(s.base)
        r.sender.post(ep, "{}".toByteArray())
        assertTrue(s.latch.await(3, TimeUnit.SECONDS)) // the worker is now inside the first request
        repeat(20) { r.sender.post(ep, "{}".toByteArray()) } // fills the 20-slot queue
        assertTrue(r.logs.none { it.contains("queue full") })
        r.sender.post(ep, "{}".toByteArray()) // 22nd: rejected
        r.sender.post(ep, "{}".toByteArray())
        assertEquals(2, r.logs.count { it == "remote log queue full, event dropped" })
        assertFalse(r.logs.any { it.contains(token) })
    }

    // ---- RemoteLogger.report

    private fun logger(s: Srv, r: Rig, ep: () -> RemoteLogPolicy.Endpoint?) =
        RemoteLogger(ep, r.sender, r.limiter) { r.now }

    /** Reports a sentinel mapped event last and waits for it: the single sender thread keeps order. */
    private fun drainWith(l: RemoteLogger, s: Srv, expectedTotal: Int) {
        l.report("app_status", mapOf("sentinel" to true))
        val deadline = System.currentTimeMillis() + 3_000
        while (s.received.size < expectedTotal && System.currentTimeMillis() < deadline) Thread.sleep(20)
    }

    @Test
    fun `not paired sends nothing`() {
        val s = srv()
        val r = Rig()
        val l = logger(s, r) { null }
        l.report("app_start", mapOf("a" to "b"))
        Thread.sleep(300)
        assertEquals(0, s.received.size)
    }

    @Test
    fun `unmapped event sends nothing and does not use up rate budget`() {
        val s = srv()
        val r = Rig()
        val ep = endpointFor(s.base)
        val l = logger(s, r) { ep }
        repeat(30) { l.report("app_unmapped", mapOf("a" to "b")) }
        drainWith(l, s, 1)
        assertEquals(1, s.received.size) // only the sentinel; limiter budget untouched by unmapped events
        assertTrue(s.received.single().body.contains("sentinel"))
    }

    @Test
    fun `rate limited sends nothing`() {
        val s = srv()
        val r = Rig()
        val ep = endpointFor(s.base)
        val l = logger(s, r) { ep }
        r.limiter.onRateLimited(r.now)
        l.report("app_start", emptyMap())
        Thread.sleep(300)
        assertEquals(0, s.received.size)
    }

    @Test
    fun `mapped event posts exactly one request with event name and kind`() {
        val s = srv()
        val r = Rig()
        val ep = endpointFor(s.base)
        val l = logger(s, r) { ep }
        l.report("app_render_gone", linkedMapOf("didCrash" to true, "n" to null))
        assertTrue(s.latch.await(3, TimeUnit.SECONDS))
        Thread.sleep(200)
        assertEquals(1, s.received.size)
        val req = s.received.single()
        assertEquals("/signage/tv/tv-01/log", req.path)
        assertEquals("""{"event":"watchdog","data":{"kind":"app_render_gone","didCrash":true}}""", req.body)
    }

    @Test
    fun `token inside the fields never reaches the server`() {
        val s = srv()
        val r = Rig()
        val ep = endpointFor(s.base)
        val l = logger(s, r) { ep }
        l.report("app_load_error", mapOf("url" to "${s.base}/signage/tv/tv-01?token=$token", "msg" to "bad token=$token"))
        assertTrue(s.latch.await(3, TimeUnit.SECONDS))
        val req = s.received.single()
        assertNotNull(req)
        assertFalse(req.body, req.body.contains(token))
        assertTrue(req.body.contains("http_error"))
    }

    @Test
    fun `client budget of 20 is enforced across reports`() {
        val s = srv()
        val r = Rig()
        val ep = endpointFor(s.base)
        val l = logger(s, r) { ep }
        repeat(25) { l.report("app_status", mapOf("i" to it)) }
        val deadline = System.currentTimeMillis() + 5_000
        while (s.received.size < 20 && System.currentTimeMillis() < deadline) Thread.sleep(20)
        Thread.sleep(300)
        assertEquals(20, s.received.size)
    }
}
