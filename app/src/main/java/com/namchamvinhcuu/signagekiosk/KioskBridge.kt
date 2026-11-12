package com.namchamvinhcuu.signagekiosk

import android.webkit.JavascriptInterface

/**
 * `window.SignageKiosk` as seen by the player page (feature-detect every method). Its methods run on a private
 * WebView thread: an exception there would kill the process, so each one is guarded and answers null / does nothing
 * when [allowed] is false or something fails. [allowed] is a cached flag (see [BridgePolicy]); the two actions re-check
 * on the main thread before they do anything.
 *
 * - `getMemory()` / `info()`: JSON text with coarse device figures, or null.
 * - `restart()`: ask the app to restart its own process (ignored in the first minutes).
 * - `onAuthRevoked()`: the player got repeated 403s on its own requests; the app re-checks by reloading the page.
 */
class KioskBridge(
    private val allowed: () -> Boolean,
    private val memory: () -> String,
    private val info: () -> String,
    private val onRestart: () -> Unit,
    private val onAuthRevoked: () -> Unit,
    private val log: (String, Throwable) -> Unit,
) {
    @JavascriptInterface
    fun getMemory(): String? = answer("getMemory", memory)

    @JavascriptInterface
    fun info(): String? = answer("info", info)

    @JavascriptInterface
    fun restart() = act("restart", onRestart)

    @JavascriptInterface
    fun onAuthRevoked() = act("onAuthRevoked", onAuthRevoked)

    private fun answer(name: String, build: () -> String): String? = try {
        if (allowed()) build() else null
    } catch (e: RuntimeException) {
        log("bridge $name failed", e)
        null
    }

    private fun act(name: String, action: () -> Unit) {
        try {
            if (allowed()) action()
        } catch (e: RuntimeException) {
            log("bridge $name failed", e)
        }
    }
}
