package com.namchamvinhcuu.signagekiosk

/**
 * Turns an app event into one remote-log post, or into nothing: not mapped to a server event, not paired (no
 * [endpoint]), over the client rate limit, or a body that cannot be built. Runs on the caller's thread up to the
 * hand-over to [RemoteLogSender], which does the network I/O elsewhere.
 */
class RemoteLogger(
    private val endpoint: () -> RemoteLogPolicy.Endpoint?,
    private val sender: RemoteLogSender,
    private val limiter: RemoteLogRateLimiter,
    private val clock: () -> Long,
) {
    fun report(appEvent: String, fields: Map<String, Any?>) {
        val serverEvent = RemoteLogPolicy.serverEvent(appEvent) ?: return
        val ep = endpoint() ?: return
        if (!limiter.tryAcquire(clock())) return
        val body = RemoteLogPolicy.body(serverEvent, mapOf("kind" to appEvent) + fields, ep.token) ?: return
        sender.post(ep, body)
    }
}
