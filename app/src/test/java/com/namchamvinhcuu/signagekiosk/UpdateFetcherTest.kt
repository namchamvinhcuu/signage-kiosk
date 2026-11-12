package com.namchamvinhcuu.signagekiosk

import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** UpdateFetcher against minimal in-process HTTP/1.1 servers on 127.0.0.1 (same approach as RemoteLogHttpTest). */
class UpdateFetcherTest {

    private class Resp(val status: Int, val location: String? = null, val body: String = "")

    /** [handler] gets the request path; null = accept and stay silent until the server is closed. */
    private class Srv(val handler: (String) -> Resp?) {
        val connections = AtomicInteger(0)
        val paths = CopyOnWriteArrayList<String>()
        val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())

        init {
            Thread {
                while (!server.isClosed) {
                    val sock = try { server.accept() } catch (e: IOException) { break }
                    connections.incrementAndGet()
                    Thread { handle(sock) }.apply { isDaemon = true }.start()
                }
            }.apply { isDaemon = true; start() }
        }

        private fun handle(sock: Socket) {
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
                while (line().isNotEmpty()) { /* skip headers */ }
                paths += reqLine[1]
                val r = handler(reqLine[1])
                if (r == null) {
                    // silent server: wait until the client gives up or the server is closed
                    try { while (input.read() >= 0) { /* drain */ } } catch (e: IOException) { }
                    return
                }
                val bytes = r.body.toByteArray()
                val loc = if (r.location != null) "Location: ${r.location}\r\n" else ""
                try {
                    val out = it.getOutputStream()
                    out.write("HTTP/1.1 ${r.status} X\r\n${loc}Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    out.write(bytes)
                    out.flush()
                } catch (e: IOException) {
                    // client gave up
                }
            }
        }

        val base: String get() = "http://127.0.0.1:${server.localPort}"
        fun stop() = server.close()
    }

    private val servers = mutableListOf<Srv>()
    private fun srv(handler: (String) -> Resp?) = Srv(handler).also { servers += it }

    @After
    fun tearDown() {
        servers.forEach { it.stop() }
    }

    /** Permissive source: resolves a relative Location, follows unless [rejectAt] says otherwise. */
    private class TestSource(val rejectAt: (Int) -> String? = { null }) : UpdateSource {
        class Call(val requested: String, val location: String?, val hop: Int)

        val calls = CopyOnWriteArrayList<Call>()
        override fun manifestUrl(channel: UpdateChannel): String? = null
        override fun apkBase(requestedUrl: String, firstHop: String?): String? = null
        override fun validateHop(requestedUrl: String, location: String?, hopNumber: Int): HopResult {
            calls += Call(requestedUrl, location, hopNumber)
            rejectAt(hopNumber)?.let { return HopResult.Reject(it) }
            if (location == null) return HopResult.Reject("no_location")
            return HopResult.Follow(URI(requestedUrl).resolve(location).toString())
        }
    }

    private fun fetcher(src: UpdateSource, readMs: Int = 2_000) = UpdateFetcher(src, 2_000, readMs)

    // ---- final answers

    @Test
    fun `200 body is readable and contentLength matches and validateHop is never called`() {
        val s = srv { Resp(200, body = "hello-apk") }
        val src = TestSource()
        fetcher(src).open(s.base + "/a").use {
            assertEquals("hello-apk", String(it.input.readBytes()))
            assertEquals(9L, it.contentLength)
            assertNull(it.firstHop)
        }
        assertTrue(src.calls.isEmpty())
    }

    @Test
    fun `each redirect status 301 302 303 307 308 is followed to the 200`() {
        for (code in listOf(301, 302, 303, 307, 308)) {
            val s = srv { p -> if (p == "/start") Resp(code, location = "/final") else Resp(200, body = "ok$code") }
            val src = TestSource()
            fetcher(src).open(s.base + "/start").use {
                assertEquals("status $code", "ok$code", String(it.input.readBytes()))
                assertEquals("status $code", s.base + "/final", it.firstHop)
            }
            assertEquals("status $code", 1, src.calls.size)
            assertEquals("status $code", listOf("/start", "/final"), s.paths.toList())
        }
    }

    @Test
    fun `two redirects end in 200 with firstHop the first followed url and hops 1 then 2`() {
        val s = srv { p ->
            when (p) {
                "/a" -> Resp(302, location = "/b")
                "/b" -> Resp(302, location = "/c")
                else -> Resp(200, body = "end")
            }
        }
        val src = TestSource()
        fetcher(src).open(s.base + "/a").use {
            assertEquals("end", String(it.input.readBytes()))
            assertEquals(s.base + "/b", it.firstHop)
        }
        assertEquals(listOf(1, 2), src.calls.map { it.hop })
        assertEquals(listOf(s.base + "/a", s.base + "/b"), src.calls.map { it.requested })
        assertEquals(listOf("/b", "/c"), src.calls.map { it.location })
    }

    @Test
    fun `three redirects give hop numbers 1 2 3`() {
        val s = srv { p ->
            when (p) {
                "/a" -> Resp(301, location = "/b")
                "/b" -> Resp(307, location = "/c")
                "/c" -> Resp(308, location = "/d")
                else -> Resp(200)
            }
        }
        val src = TestSource()
        fetcher(src).open(s.base + "/a").use { assertEquals(s.base + "/b", it.firstHop) }
        assertEquals(listOf(1, 2, 3), src.calls.map { it.hop })
    }

    @Test
    fun `relative Location is resolved by the source against the requested url`() {
        val s = srv { p -> if (p == "/dir/x") Resp(302, location = "y") else Resp(200, body = p) }
        val src = TestSource()
        fetcher(src).open(s.base + "/dir/x").use {
            assertEquals("/dir/y", String(it.input.readBytes()))
            assertEquals(s.base + "/dir/y", it.firstHop)
        }
        assertEquals("y", src.calls.single().location)
        assertEquals(s.base + "/dir/x", src.calls.single().requested)
    }

    @Test
    fun `404 500 and 204 final answers are FetchFailure http_code`() {
        for (code in listOf(404, 500, 204)) {
            val s = srv { Resp(code) }
            val src = TestSource()
            try {
                fetcher(src).open(s.base + "/x")
                fail("status $code must fail")
            } catch (e: FetchFailure) {
                assertEquals("http_$code", e.reason)
            }
            assertTrue(src.calls.isEmpty())
        }
    }

    // ---- refusals

    @Test
    fun `Reject becomes FetchFailure hop_reason and the refused target sees no connection`() {
        val target = srv { Resp(200, body = "must-not-be-fetched") }
        val first = srv { Resp(302, location = target.base + "/x") }
        val src = TestSource { "evil" }
        try {
            fetcher(src).open(first.base + "/start")
            fail("must be refused")
        } catch (e: FetchFailure) {
            assertEquals("hop_evil", e.reason)
        }
        assertEquals(1, first.connections.get())
        assertEquals(0, target.connections.get())
        assertEquals(1, src.calls.size)
        assertEquals(target.base + "/x", src.calls.single().location)
    }

    @Test
    fun `redirect without Location gives validateHop null and its Reject surfaces`() {
        val s = srv { Resp(302) }
        val src = TestSource()
        try {
            fetcher(src).open(s.base + "/x")
            fail("must be refused")
        } catch (e: FetchFailure) {
            assertEquals("hop_no_location", e.reason)
        }
        assertNull(src.calls.single().location)
        assertEquals(1, src.calls.single().hop)
    }

    @Test(timeout = 10_000)
    fun `redirect loop A to B to A stops when the source rejects at hop 4`() {
        val s = srv { p -> if (p == "/a") Resp(302, location = "/b") else Resp(302, location = "/a") }
        val src = TestSource { hop -> if (hop >= 4) "too_many_hops" else null }
        try {
            fetcher(src).open(s.base + "/a")
            fail("loop must stop")
        } catch (e: FetchFailure) {
            assertEquals("hop_too_many_hops", e.reason)
        }
        assertEquals(listOf(1, 2, 3, 4), src.calls.map { it.hop })
        assertEquals(listOf("/a", "/b", "/a", "/b"), s.paths.toList())
    }

    @Test
    fun `real GithubUpdateSource refuses a redirect to a local http server as not_https and it sees no connection`() {
        val target = srv { Resp(200, body = "must-not-be-fetched") }
        val first = srv { Resp(302, location = target.base + "/x") }
        try {
            fetcher(GithubUpdateSource("owner/repo")).open(first.base + "/start")
            fail("must be refused")
        } catch (e: FetchFailure) {
            assertEquals("hop_not_https", e.reason)
        }
        assertEquals(1, first.connections.get())
        assertEquals(0, target.connections.get())
    }

    @Test
    fun `real GithubUpdateSource refuses a relative Location resolved against a local http url`() {
        val first = srv { Resp(302, location = "/elsewhere") }
        try {
            fetcher(GithubUpdateSource("owner/repo")).open(first.base + "/start")
            fail("must be refused")
        } catch (e: FetchFailure) {
            assertEquals("hop_not_https", e.reason)
        }
        assertEquals(listOf("/start"), first.paths.toList())
    }

    // ---- network errors

    @Test
    fun `connection refused is an IOException and not a FetchFailure`() {
        val dead = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val url = "http://127.0.0.1:${dead.localPort}/x"
        dead.close()
        try {
            fetcher(TestSource()).open(url)
            fail("must fail")
        } catch (e: FetchFailure) {
            fail("refused connection must not be FetchFailure: ${e.reason}")
        } catch (e: IOException) {
            // expected
        }
    }

    @Test
    fun `silent server hits the read timeout as an IOException within a short time`() {
        val s = srv { null }
        val started = System.nanoTime()
        try {
            fetcher(TestSource(), readMs = 300).open(s.base + "/x")
            fail("must time out")
        } catch (e: FetchFailure) {
            fail("timeout must not be FetchFailure: ${e.reason}")
        } catch (e: IOException) {
            // expected (SocketTimeoutException)
        }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue("took $elapsedMs ms", elapsedMs < 3_000)
    }

    @Test
    fun `close twice does not throw`() {
        val s = srv { Resp(200, body = "x") }
        val opened = fetcher(TestSource()).open(s.base + "/x")
        opened.close()
        opened.close()
    }
}
