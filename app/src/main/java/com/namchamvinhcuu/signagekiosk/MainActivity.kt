package com.namchamvinhcuu.signagekiosk

import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.ComponentCallbacks2
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.time.Instant

/**
 * Full-screen kiosk WebView on the signage player page: recovers from renderer death and hangs,
 * restarts its own process periodically / on low memory / after repeated recoveries, logs locally and, once paired, to the server.
 * All timers run on the main looper and only while the activity is started (foreground).
 */
class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private val recovery = RecoveryPolicy()
    private val hang = HangDetector()
    private val backTaps = TapCounter()

    private lateinit var store: ConfigStore
    private lateinit var config: KioskConfig
    private lateinit var events: EventLogger
    private lateinit var secrets: SecretStore
    private lateinit var root: FrameLayout

    /** The paired player URL (carries the token: NEVER log it, only [UrlRedactor.redactUrl]); null = not paired. */
    private var pairedUrl: String? = null

    /** A paired URL whose storing failed (in memory only, same secrecy as [pairedUrl]). */
    private var unstorableUrl: String? = null

    /** The player URL the server just refused with 403 (in memory only, same secrecy as [pairedUrl]). */
    private var forbiddenUrl: String? = null
    private val authFailures = AuthFailurePolicy()

    /** Cached gate of the page bridge (see [BridgePolicy]); the actions re-check on the main thread. */
    @Volatile private var bridgeOpen = false
    private var lastRecheckMs: Long? = null
    private val bridge = KioskBridge(
        allowed = { bridgeOpen },
        memory = ::bridgeMemoryJson,
        info = ::bridgeInfoJson,
        onRestart = { handler.post { onBridgeRestart() } },
        onAuthRevoked = { handler.post { onBridgeAuthRevoked() } },
        log = { msg, e -> KLog.e(msg, e) },
    )

    private var webView: WebView? = null
    private var settingsDialog: AlertDialog? = null
    private var blankPoster: Bitmap? = null
    private var restarting = false
    /** Between onStart and onStop. No WebView is built and no restart is started while stopped. */
    private var started = false
    /** Exit reason of the previous process (EscalationGuard, RefrontPolicy). */
    private var prevExitReason: String? = null
    private var createdAtMs = 0L
    private var refrontTried = false
    /** elapsedRealtime of the self-start, 0 = none pending. */
    private var refrontAtMs = 0L

    private val watchdogTick = object : Runnable {
        override fun run() {
            onWatchdogTick()
            if (!restarting) handler.postDelayed(this, WATCHDOG_MS)
        }
    }
    private val statusTick = object : Runnable {
        override fun run() {
            logStatus()
            handler.postDelayed(this, STATUS_MS)
        }
    }
    private lateinit var updater: Updater
    private val updateCheck = Runnable { runUpdateCheck() }
    private val updateFailsafe = Runnable { onUpdateInstallStalled() }
    private val updateResultListener: () -> Unit = { handler.post { onUpdateResultStored() } }
    private val recreateWebView = Runnable { createWebView() }
    private val dismissSettings = Runnable {
        KLog.i("settings dialog closed after $SETTINGS_IDLE_MS ms without input")
        settingsDialog?.dismiss()
    }
    private val retryLoad = Runnable {
        KLog.i("retrying main-frame load")
        webView?.loadUrl(startUrl())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createdAtMs = SystemClock.elapsedRealtime()
        KLog.init(this)
        store = ConfigStore(this)
        secrets = SecretStore(this)
        installCrashLogger(store)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        config = store.load()
        events = EventLogger(
            RemoteLogger({ RemoteLogPolicy.endpoint(config.baseUrl, pairedUrl) }, remoteSender, remoteLimiter, remoteClock),
        )
        pairedUrl = loadPairedUrl()
        updater = Updater(this, store, UpdateSourceFactory.create(), UpdateHost(), deleteStaleApk = !updateInstalling)
        UpdateResultBus.listener = updateResultListener
        // An earlier Activity of this process left an install running: keep the block, but never forever.
        if (updateInstalling) handler.postDelayed(updateFailsafe, UpdatePolicy.INSTALL_FAILSAFE_MS)

        root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)
        setContentView(root)

        logAppStart()
        reportStoredUpdateResult()
        // A system re-creation (savedInstanceState != null) replays the ORIGINAL launch intent, whose
        // extras may be older than a later settings-dialog change.
        if (savedInstanceState == null) {
            applyIntentConfig(intent, reload = false)
        }
        // The WebView itself is built in onStart.
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyIntentConfig(intent, reload = true)
    }

    override fun onStart() {
        super.onStart()
        started = true
        webView?.onResume()
        // First start, or the renderer died / a recreate was pending while stopped.
        if (webView == null) createWebView()
        hang.reset()
        handler.postDelayed(watchdogTick, WATCHDOG_MS)
        handler.postDelayed(statusTick, STATUS_MS)
        scheduleUpdateCheck(UpdatePolicy.CHECK_START_DELAY_MS)
        if (refrontAtMs != 0L) {
            // A blocked start returns normally, so this may also be a much later manual start: since_attempt_ms tells.
            events.event("app_refront", mapOf("stage" to "back", "since_attempt_ms" to SystemClock.elapsedRealtime() - refrontAtMs))
            refrontAtMs = 0L
        }
    }

    override fun onStop() {
        started = false
        handler.removeCallbacks(watchdogTick)
        handler.removeCallbacks(statusTick)
        handler.removeCallbacks(updateCheck)
        webView?.onPause()
        super.onStop()
        refrontAfterRestart()
    }

    /** See [RefrontPolicy]: the launcher covered us right after a restart this app started itself. */
    private fun refrontAfterRestart() {
        val sinceCreateMs = SystemClock.elapsedRealtime() - createdAtMs
        if (!RefrontPolicy.shouldRefront(prevExitReason, sinceCreateMs, refrontTried, restarting)) return
        refrontTried = true
        events.event(
            "app_refront",
            mapOf("stage" to "attempt", "since_create_ms" to sinceCreateMs, "prev_reason" to prevExitReason, "finishing" to isFinishing),
        )
        try {
            // No extras: onNewIntent applies nothing. A start blocked by the platform is dropped silently.
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            refrontAtMs = SystemClock.elapsedRealtime()
        } catch (e: RuntimeException) {
            KLog.e("refront start failed", e)
            events.event("app_error", mapOf("where" to "refront", "error" to e.javaClass.name, "msg" to e.message))
        }
    }

    override fun onPause() {
        // R14: keep the TV logged in across restarts.
        flushCookies()
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        // Only our own listener: a newer Activity may already have registered its own.
        if (UpdateResultBus.listener === updateResultListener) UpdateResultBus.listener = null
        // An install already committed keeps running: closing now would cut its background thread off.
        if (!updateInstalling) updater.close()
        settingsDialog?.dismiss()
        settingsDialog = null
        webView?.let { discardWebView(it) }
        super.onDestroy()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // TRIM_MEMORY_RUNNING_* are not delivered since API 34 (verified in SDK sources); log what does arrive.
        if (level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            KLog.i("onTrimMemory UI_HIDDEN")
        } else {
            events.event("app_trim", mapOf("level" to level))
        }
    }

    // ---- WebView lifecycle (R1, R2, R13) -------------------------------------------------------------

    private fun createWebView() {
        handler.removeCallbacks(recreateWebView)
        // While stopped, onStart builds it (no heavy page loading behind other apps).
        if (webView != null || restarting || !started) return
        val wv = try {
            WebView(this)
        } catch (e: RuntimeException) {
            // e.g. the WebView package is being updated
            KLog.e("WebView creation failed", e)
            events.event("app_error", mapOf("where" to "webview_create", "error" to e.javaClass.name, "msg" to e.message))
            handler.postDelayed(recreateWebView, LOAD_RETRY_MS)
            return
        }
        wv.setBackgroundColor(Color.BLACK)
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.settings.mediaPlaybackRequiresUserGesture = false
        wv.settings.userAgentString = "${wv.settings.userAgentString} SignageKiosk/${BuildConfig.VERSION_NAME}"
        wv.webViewClient = KioskWebViewClient()
        wv.webChromeClient = KioskChromeClient()
        // Injected into every frame on the next page load; usable only while [bridgeOpen] (BridgePolicy).
        wv.addJavascriptInterface(bridge, "SignageKiosk")
        root.addView(wv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        webView = wv
        hang.reset()
        wv.requestFocus()
        loadConfiguredUrl()
    }

    private fun loadConfiguredUrl() {
        handler.removeCallbacks(retryLoad)
        webView?.loadUrl(startUrl())
    }

    /** The stored player URL when paired, else the pairing page: a paired TV never goes back to `/signage/pair`. */
    private fun startUrl(): String = pairedUrl ?: config.pairUrl

    /** The stored URL, only while it still belongs to the configured server; anything else is dropped. */
    private fun loadPairedUrl(): String? {
        val stored = secrets.load() ?: return null
        if (PairingPolicy.isPairedUrl(config.baseUrl, stored)) return stored
        KLog.w("stored paired url does not belong to the configured server, dropped")
        secrets.clear()
        return null
    }

    /** Main-frame URL seen by the WebView: the first one that is the player URL of this server becomes the paired session. */
    private fun onMainFrameUrl(view: WebView, url: String?) {
        if (view !== webView || url == null || url == pairedUrl || url == unstorableUrl || url == forbiddenUrl) return
        if (!PairingPolicy.isPairedUrl(config.baseUrl, url)) return
        if (secrets.save(url)) {
            pairedUrl = url
            forbiddenUrl = null
            events.event("app_paired", mapOf("url" to UrlRedactor.redactUrl(url)))
        } else {
            // Do not retry the Keystore on every page callback for the same URL.
            unstorableUrl = url
            KLog.w("paired url could not be stored; the TV will pair again after a restart")
        }
    }

    /**
     * Main-frame 403 on a player URL of this server. Only a repeated one counts (see [AuthFailurePolicy]): retry the
     * stored URL after [LOAD_RETRY_MS], and on the last one drop the session and go back to `/signage/pair`.
     * The URL that was refused is remembered so the finishing error page cannot store it again.
     */
    private fun onPlayerForbidden(url: String?) {
        if (url == null || !PairingPolicy.isPairedUrl(config.baseUrl, url)) return
        when (authFailures.onMainFrame403(SystemClock.elapsedRealtime())) {
            AuthFailurePolicy.Decision.RETRY -> {
                events.event("app_auth_403", mapOf("url" to UrlRedactor.redactUrl(url)))
                handler.removeCallbacks(retryLoad)
                handler.postDelayed(retryLoad, LOAD_RETRY_MS)
            }
            AuthFailurePolicy.Decision.CLEAR -> {
                forbiddenUrl = url
                secrets.clear()
                pairedUrl = null
                unstorableUrl = null
                bridgeOpen = false
                events.event("app_auth_revoked", mapOf("url" to UrlRedactor.redactUrl(url)))
                loadConfiguredUrl()
            }
        }
    }

    // ---- Page bridge window.SignageKiosk (see KioskBridge, BridgePolicy) ------------------------------

    /** Called after [onMainFrameUrl] so a freshly stored session already counts. */
    private fun updateBridgeGate(view: WebView, url: String?) {
        if (view === webView) bridgeOpen = BridgePolicy.isAllowed(config.baseUrl, pairedUrl, url)
    }

    /** Main-thread re-check for the two actions: the live main-frame URL, not the cached flag. */
    private fun bridgeAllowedNow(): Boolean = BridgePolicy.isAllowed(config.baseUrl, pairedUrl, webView?.url)

    private fun onBridgeRestart() {
        // Like every other restart: none while stopped (a background activity start is blocked on Android 10+).
        if (!started || !bridgeAllowedNow()) return
        if (updateInstalling) {
            // Checked before the cooldown is stored: a request lost to the install block must not burn the hourly slot.
            KLog.w("bridge restart ignored: an update install is running")
            return
        }
        if (!BridgePolicy.restartAllowed(uptimeMs())) {
            KLog.w("bridge restart ignored: process is younger than ${BridgePolicy.MIN_UPTIME_FOR_RESTART_MS / 60_000L} min")
            return
        }
        val nowWall = System.currentTimeMillis()
        if (!BridgePolicy.restartCooldownPassed(nowWall, store.lastBridgeRestartAtWallMs())) {
            KLog.w("bridge restart ignored: the last one was less than ${BridgePolicy.MIN_RESTART_GAP_MS / 60_000L} min ago")
            return
        }
        // Saved before the restart: the process exits right after, and a looping caller must still be held back.
        if (!store.saveBridgeRestartAt(nowWall)) {
            KLog.w("bridge restart ignored: cooldown could not be stored")
            return
        }
        restartProcess(BridgePolicy.RESTART_REASON)
    }

    /**
     * The player saw repeated 403s on its own requests. Not trusted as proof: the stored URL is reloaded and the real
     * main-frame answer goes through [AuthFailurePolicy] (a revoked TV is back at pairing after about a minute).
     */
    private fun onBridgeAuthRevoked() {
        if (!bridgeAllowedNow()) return
        val now = SystemClock.elapsedRealtime()
        if (!BridgePolicy.recheckAllowed(now, lastRecheckMs)) return
        lastRecheckMs = now
        events.event("app_bridge_auth_revoked", mapOf("url" to UrlRedactor.redactUrl(pairedUrl)))
        loadConfiguredUrl()
    }

    // ---- Self-update host (see Updater, UpdatePolicy; plan kiosk-selfupdate 8.4b) ------------------------

    private inner class UpdateHost : Updater.Host {
        override fun updateGates() = UpdateGates(
            canDrawOverlays = Settings.canDrawOverlays(this@MainActivity),
            canRequestInstalls = packageManager.canRequestPackageInstalls(),
            settingsOpen = settingsDialog?.isShowing == true,
            restarting = restarting,
            channelOff = config.updateChannel == UpdateChannel.OFF,
            installing = updateInstalling,
        )

        override fun logUpdate(stage: String, fields: Map<String, Any?>) = events.event("app_update_$stage", fields)

        override fun beforeInstall(code: Int) {
            updateInstalling = true
            flushCookies()
            // A refused install may never send a result: the block must not outlive this deadline.
            handler.removeCallbacks(updateFailsafe)
            handler.postDelayed(updateFailsafe, UpdatePolicy.INSTALL_FAILSAFE_MS)
        }

        override fun installFailed() = endUpdateInstall()
    }

    /** Lifts the install block. The watchdog chain keeps ticking while blocked (it only skips its restart decisions). */
    private fun endUpdateInstall() {
        updateInstalling = false
        handler.removeCallbacks(updateFailsafe)
    }

    /** No install result arrived within [UpdatePolicy.INSTALL_FAILSAFE_MS] and this process is still alive: it failed. */
    private fun onUpdateInstallStalled() {
        if (!updateInstalling) return
        events.event("app_update_install_stalled", mapOf("code" to store.peekPendingUpdateCode()))
        store.takePendingUpdateCode()
        store.saveUpdateState(UpdatePolicy.afterInstallFailure(store.loadUpdateState(), System.currentTimeMillis()))
        endUpdateInstall()
    }

    /** [UpdateResultReceiver] stored a result in this process: report it and lift the block (a success kills us anyway). */
    private fun onUpdateResultStored() {
        reportStoredUpdateResult()
        endUpdateInstall()
    }

    /**
     * The stored install result is reported once, by whichever process reads it first; a result that is already there
     * means the install is over, so the block is lifted too. (After a pending_user_action the receiver's own abandon
     * can store a second `failure_aborted` that is then reported as a second event: noise, not a failure.)
     */
    private fun reportStoredUpdateResult() {
        val r = store.takeUpdateResult() ?: return
        events.event("app_update_result", mapOf("status" to r.status, "code" to r.code, "message" to r.message))
        endUpdateInstall()
    }

    private fun scheduleUpdateCheck(delayMs: Long) {
        handler.removeCallbacks(updateCheck)
        if (started && config.updateChannel != UpdateChannel.OFF) handler.postDelayed(updateCheck, delayMs)
    }

    private fun runUpdateCheck() {
        if (!started || restarting) return
        val channel = config.updateChannel
        if (channel == UpdateChannel.OFF) return
        updater.check(channel)
        scheduleUpdateCheck(UpdatePolicy.CHECK_INTERVAL_MS)
    }

    private fun bridgeMemoryJson(): String {
        val mem = memoryInfo()
        return BridgePolicy.memoryJson(mem.availMem / MB, mem.totalMem / MB, mem.lowMemory, Debug.getPss() / 1024, uptimeMs() / 60_000L)
    }

    private fun bridgeInfoJson(): String {
        val metrics = resources.displayMetrics
        val view = webView
        @Suppress("DEPRECATION")
        val rotation = windowManager.defaultDisplay.rotation
        return BridgePolicy.infoJson(
            BuildConfig.VERSION_NAME,
            BuildConfig.VERSION_CODE,
            view?.width?.takeIf { it > 0 } ?: metrics.widthPixels,
            view?.height?.takeIf { it > 0 } ?: metrics.heightPixels,
            metrics.densityDpi,
            metrics.density.toDouble(),
            rotation,
            WebView.getCurrentWebViewPackage()?.versionName,
        )
    }

    /** R2: a dead WebView only gets removed and destroyed — nothing else is called on it. */
    private fun discardWebView(view: WebView) {
        if (view === webView) {
            webView = null
            bridgeOpen = false
            handler.removeCallbacks(retryLoad)
        }
        root.removeView(view)
        try {
            view.destroy()
        } catch (e: RuntimeException) {
            KLog.e("WebView.destroy failed", e)
        }
    }

    /** One recovery (R2 renderer death / R7 hang): recreate after 3 s, or escalate to a restart (R3). */
    private fun recover(reason: String) {
        webView?.let { discardWebView(it) }
        hang.reset()
        val action = recovery.onRecovery(SystemClock.elapsedRealtime())
        // No process restart while an update install is running; the WebView is recreated instead.
        val escalate = action == RecoveryPolicy.Action.RESTART && !updateInstalling &&
            EscalationGuard.restartAllowed(prevExitReason, uptimeMs())
        if (escalate) {
            restartProcess(EscalationGuard.ESCALATION_PREFIX + reason)
            return
        }
        val suppressed = action == RecoveryPolicy.Action.RESTART
        events.event(
            "app_recreate",
            mapOf(
                "reason" to reason,
                "recoveries" to recovery.total,
                "restart_suppressed" to suppressed,
            ),
        )
        handler.postDelayed(recreateWebView, if (suppressed) SUPPRESSED_RECREATE_DELAY_MS else RECREATE_DELAY_MS)
    }

    private inner class KioskWebViewClient : WebViewClient() {
        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            val current = view === webView
            events.event(
                "app_render_gone",
                mapOf(
                    "did_crash" to detail.didCrash(),
                    "priority" to detail.rendererPriorityAtExit(),
                    "current" to current,
                    "started" to started,
                ),
            )
            // While stopped (LMK kills background renderers) it is not a page fault: no recovery is counted
            // and onStart builds a new WebView, so no restart or background activity start happens here.
            if (current && started) recover("render_gone") else discardWebView(view)
            return true
        }

        // Whether a server redirect fires onPageStarted again is not documented: look at both callbacks.
        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            onMainFrameUrl(view, url)
            updateBridgeGate(view, url)
        }

        override fun onPageFinished(view: WebView, url: String?) {
            onMainFrameUrl(view, url)
            updateBridgeGate(view, url)
        }

        // Revocation: the server answers 403 for a regenerated token. A 403 is not a load error (onReceivedError).
        // The count uses only this callback (no ordering against onPageStarted/onPageFinished is assumed).
        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
            if (!request.isForMainFrame || view !== webView || errorResponse.statusCode != 403) return
            onPlayerForbidden(request.url?.toString())
        }

        // R8: one pending retry at a time.
        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (!request.isForMainFrame || view !== webView) return
            events.event(
                "app_load_error",
                mapOf(
                    "code" to error.errorCode,
                    "desc" to error.description?.toString(),
                    "url" to UrlRedactor.redactUrl(request.url?.toString()),
                ),
            )
            handler.removeCallbacks(retryLoad)
            handler.postDelayed(retryLoad, LOAD_RETRY_MS)
        }
    }

    private inner class KioskChromeClient : WebChromeClient() {
        // R1: no grey default poster before a video starts.
        override fun getDefaultVideoPoster(): Bitmap =
            blankPoster ?: Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).also { blankPoster = it }
    }

    // ---- Watchdog: hang probe (R7), periodic + low-memory restart (R5, R6) ---------------------------

    private fun onWatchdogTick() {
        if (restarting) return
        val mem = memoryInfo()
        val availMb = mem.availMem / MB
        // While an update install runs only the RESTART decision waits (the next tick retries it); hang probing goes on.
        val reason = if (updateInstalling) null else RestartPolicy.decide(uptimeMs(), config.restartMin, config.lowMemMb, mem.lowMemory, availMb)
        if (reason != null) {
            restartProcess(reason.wire, mapOf("avail_mb" to availMb, "low_mem" to mem.lowMemory))
            return
        }
        val wv = webView ?: return
        when (val t = hang.tick()) {
            is HangDetector.Tick.Probe -> try {
                wv.evaluateJavascript("1") { hang.onAnswer(t.token) }
            } catch (e: RuntimeException) {
                KLog.e("hang probe failed", e)
            }
            HangDetector.Tick.Hung -> {
                events.event("app_hang", mapOf("recoveries" to recovery.total))
                recover("hang")
            }
        }
    }

    // ---- Process restart (R4) ------------------------------------------------------------------------

    private fun restartProcess(reason: String, extra: Map<String, Any?> = emptyMap()) {
        if (restarting || updateInstalling) return
        restarting = true
        // The POST below dies with the process most of the time; the saved reason is logged on the next start.
        events.event("app_restart", mapOf("reason" to reason, "up_min" to uptimeMs() / 60_000L, "recoveries" to recovery.total) + extra)
        store.saveExitReason(reason, System.currentTimeMillis())
        flushCookies()
        handler.removeCallbacksAndMessages(null)
        RestartActivity.trigger(this)
    }

    private fun exitApp() {
        KLog.i("exit requested from settings")
        store.saveExitReason("exit_by_user", System.currentTimeMillis())
        flushCookies()
        handler.removeCallbacksAndMessages(null)
        finishAndRemoveTask()
        Runtime.getRuntime().exit(0)
    }

    /** CookieManager.getInstance throws while the WebView package is missing or being updated. */
    private fun flushCookies() {
        try {
            CookieManager.getInstance().flush()
        } catch (e: RuntimeException) {
            KLog.w("cookie flush failed", e)
        }
    }

    // ---- Configuration (R10, R14) --------------------------------------------------------------------

    private fun applyIntentConfig(intent: Intent?, reload: Boolean) {
        if (intent == null) return
        val keys = listOf(EXTRA_BASE_URL, EXTRA_RESTART_MIN, EXTRA_LOW_MEM_MB, EXTRA_UPDATE_CHANNEL).filter { intent.hasExtra(it) }
        if (keys.isEmpty()) return
        var next = config
        val rejected = mutableListOf<String>()
        if (intent.hasExtra(EXTRA_BASE_URL)) {
            val url = ConfigValidator.validateBaseUrl(intent.getStringExtra(EXTRA_BASE_URL))
            // A configured TV is changed only from the settings dialog (or by clearing the app data over adb).
            if (url == null || config.isConfigured) rejected += EXTRA_BASE_URL else next = next.copy(baseUrl = url)
        }
        if (intent.hasExtra(EXTRA_RESTART_MIN)) {
            val n = intExtra(intent, EXTRA_RESTART_MIN)?.let(ConfigValidator::normalizeRestartMin)
            if (n == null) rejected += EXTRA_RESTART_MIN else next = next.copy(restartMin = n)
        }
        if (intent.hasExtra(EXTRA_LOW_MEM_MB)) {
            val n = intExtra(intent, EXTRA_LOW_MEM_MB)?.let(ConfigValidator::normalizeLowMemMb)
            if (n == null) rejected += EXTRA_LOW_MEM_MB else next = next.copy(lowMemMb = n)
        }
        if (intent.hasExtra(EXTRA_UPDATE_CHANNEL)) {
            // Judged against the config BEFORE this intent: a `base_url` + `update_channel` provisioning pair both apply.
            val channel = ConfigValidator.acceptIntentUpdateChannel(config, intent.getStringExtra(EXTRA_UPDATE_CHANNEL))
            if (channel == null) rejected += EXTRA_UPDATE_CHANNEL else next = next.copy(updateChannel = channel)
        }
        // Consume the extras so a later re-delivery of this intent does not re-apply them.
        keys.forEach { intent.removeExtra(it) }
        applyConfig(next, "intent", rejected, forceReload = false, reloadAllowed = reload)
    }

    /** Accepts `--ei` (Int) and `--es` (String) for numbers. */
    private fun intExtra(intent: Intent, key: String): Int? {
        @Suppress("DEPRECATION")
        return when (val v = intent.extras?.get(key)) {
            is Int -> v
            is String -> v.trim().toIntOrNull()
            else -> null
        }
    }

    private fun applyConfig(
        next: KioskConfig,
        source: String,
        rejected: List<String>,
        forceReload: Boolean,
        reloadAllowed: Boolean = true,
    ) {
        val urlChanged = next.baseUrl != config.baseUrl
        val channelChanged = next.updateChannel != config.updateChannel
        if (urlChanged) {
            // The token belongs to the old server: never send the TV to the new one with it.
            secrets.clear()
            pairedUrl = null
            unstorableUrl = null
            forbiddenUrl = null
            bridgeOpen = false
            authFailures.reset()
        }
        if (next != config) {
            if (!store.save(next)) KLog.w("config save did not commit")
            config = next
        }
        // Switched on: first check after the usual delay; switched off: the pending check is cancelled.
        if (channelChanged) scheduleUpdateCheck(UpdatePolicy.CHECK_START_DELAY_MS)
        events.event(
            "app_config",
            mapOf(
                "source" to source,
                "base_url" to UrlRedactor.redactUrl(next.baseUrl),
                "restart_min" to next.restartMin,
                "low_mem_mb" to next.lowMemMb,
                "update_channel" to next.updateChannel.wire,
                "url_changed" to urlChanged,
                "rejected" to rejected.joinToString(",").ifEmpty { null },
            ),
        )
        flushCookies()
        if (reloadAllowed && (urlChanged || forceReload)) {
            if (webView == null) createWebView() else loadConfiguredUrl()
        }
    }

    // ---- Remote keys + settings dialog (R11) ---------------------------------------------------------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        when (event.keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                // Never navigate back: the player page is a single-page app.
                if (event.action == KeyEvent.ACTION_UP && !event.isCanceled && backTaps.onPress(event.eventTime)) {
                    showSettings()
                }
                return true
            }
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS -> {
                if (event.action == KeyEvent.ACTION_UP) showSettings()
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun showSettings() {
        if (settingsDialog?.isShowing == true || isFinishing || restarting) return
        val urlInput = EditText(this).apply {
            setText(config.baseUrl.takeIf { it != KioskConfig.DEFAULT_BASE_URL }.orEmpty())
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        val restartInput = EditText(this).apply {
            setText(config.restartMin.toString())
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        val lowMemInput = EditText(this).apply {
            setText(config.lowMemMb.toString())
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        val channelInput = EditText(this).apply {
            setText(config.updateChannel.wire)
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val pad = (16 * resources.displayMetrics.density).toInt()
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
            addView(label(R.string.settings_url))
            addView(urlInput)
            addView(label(R.string.settings_restart_min))
            addView(restartInput)
            addView(label(R.string.settings_low_mem_mb))
            addView(lowMemInput)
            addView(label(R.string.settings_update_channel))
            addView(channelInput)
        }
        val dialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(R.string.settings_title)
            // Scrollable: the fields may not fit a 540 dp-high TV screen.
            .setView(ScrollView(this).apply { addView(form) })
            .setPositiveButton(R.string.settings_save, null)
            .setNeutralButton(R.string.settings_exit) { _, _ -> exitApp() }
            .setNegativeButton(R.string.settings_cancel, null)
            .create()
        dialog.setOnShowListener {
            // Set here so an invalid value keeps the dialog open.
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (saveSettings(urlInput.text.toString(), restartInput.text.toString(), lowMemInput.text.toString(), channelInput.text.toString())) {
                    dialog.dismiss()
                }
            }
        }
        // An accidental triple-BACK must not leave the dialog over the signage: close it when idle.
        // Keys consumed by the on-screen IME never reach the dialog, so text changes re-arm the timer too.
        val rearm: () -> Unit = {
            handler.removeCallbacks(dismissSettings)
            handler.postDelayed(dismissSettings, SETTINGS_IDLE_MS)
        }
        dialog.setOnKeyListener { _, _, _ ->
            rearm()
            false
        }
        val typing = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = rearm()
        }
        listOf(urlInput, restartInput, lowMemInput, channelInput).forEach { it.addTextChangedListener(typing) }
        dialog.setOnDismissListener {
            handler.removeCallbacks(dismissSettings)
            settingsDialog = null
            webView?.requestFocus()
        }
        settingsDialog = dialog
        KLog.i("settings dialog opened")
        dialog.show()
        handler.postDelayed(dismissSettings, SETTINGS_IDLE_MS)
    }

    private fun label(res: Int) = TextView(this).apply { setText(res) }

    private fun saveSettings(urlText: String, restartText: String, lowMemText: String, channelText: String): Boolean {
        val url = ConfigValidator.validateBaseUrl(urlText)
        if (url == null) {
            Toast.makeText(this, R.string.settings_invalid_url, Toast.LENGTH_LONG).show()
            return false
        }
        val restartMin = restartText.trim().toIntOrNull()?.let(ConfigValidator::normalizeRestartMin)
        val lowMemMb = lowMemText.trim().toIntOrNull()?.let(ConfigValidator::normalizeLowMemMb)
        if (restartMin == null || lowMemMb == null) {
            Toast.makeText(this, R.string.settings_invalid_number, Toast.LENGTH_LONG).show()
            return false
        }
        val channel = ConfigValidator.normalizeUpdateChannel(channelText)
        if (channel == null) {
            Toast.makeText(this, R.string.settings_invalid_update_channel, Toast.LENGTH_LONG).show()
            return false
        }
        applyConfig(KioskConfig(url, restartMin, lowMemMb, channel), "dialog", emptyList(), forceReload = true)
        return true
    }

    // ---- Logs (R12, §3) ------------------------------------------------------------------------------

    private fun logAppStart() {
        val prev = store.takeExitReason()
        prevExitReason = prev?.reason
        // The pending update code belongs to the previous PROCESS: a same-process re-create must not consume it.
        val pendingUpdate = if (processStartLogged) 0 else store.takePendingUpdateCode()
        processStartLogged = true
        val info = AppStart.derive(intent?.getStringExtra(EXTRA_START), prev?.reason, pendingUpdate, BuildConfig.VERSION_CODE)
        events.event(
            "app_start",
            mapOf(
                "start" to info.start,
                "prev_reason" to info.prevReason,
                "update_not_installed" to info.pendingNotInstalled.takeIf { it },
                "prev_at" to prev?.let { Instant.ofEpochMilli(it.atWallMs).toString() },
                "webview" to WebView.getCurrentWebViewPackage()?.versionName,
                "sdk" to Build.VERSION.SDK_INT,
                "model" to Build.MODEL,
                "overlay" to Settings.canDrawOverlays(this),
                "base_url" to UrlRedactor.redactUrl(config.baseUrl),
                "restart_min" to config.restartMin,
                "low_mem_mb" to config.lowMemMb,
                "version_code" to BuildConfig.VERSION_CODE,
            ),
        )
    }

    private fun logStatus() {
        val mem = memoryInfo()
        events.event(
            "app_status",
            mapOf(
                "avail_mb" to mem.availMem / MB,
                "total_mb" to mem.totalMem / MB,
                "low_mem" to mem.lowMemory,
                "threshold_mb" to mem.threshold / MB,
                // Own process only — the sandboxed renderer is a separate process.
                "pss_mb" to Debug.getPss() / 1024,
                "up_min" to uptimeMs() / 60_000L,
                "recoveries" to recovery.total,
                "restart_min" to config.restartMin,
                "low_mem_mb" to config.lowMemMb,
                "webview" to (webView != null),
            ),
        )
    }

    private fun memoryInfo(): ActivityManager.MemoryInfo {
        val info = ActivityManager.MemoryInfo()
        (getSystemService(ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(info)
        return info
    }

    private fun uptimeMs(): Long = SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()

    companion object {
        const val EXTRA_BASE_URL = "base_url"
        const val EXTRA_RESTART_MIN = "restart_min"
        const val EXTRA_LOW_MEM_MB = "low_mem_mb"
        const val EXTRA_UPDATE_CHANNEL = "update_channel"
        /** Why the activity was started ("boot" from BootReceiver); otherwise derived. */
        const val EXTRA_START = "start"

        private const val WATCHDOG_MS = 60_000L
        private const val STATUS_MS = 5 * 60_000L
        private const val RECREATE_DELAY_MS = 3_000L
        private const val LOAD_RETRY_MS = 30_000L
        private const val SUPPRESSED_RECREATE_DELAY_MS = 60_000L
        private const val SETTINGS_IDLE_MS = 120_000L
        private const val MB = 1024L * 1024L

        private var crashLoggerInstalled = false

        /** True once this process logged its `app_start`; later Activity re-creations are not process starts. */
        private var processStartLogged = false

        /** An update install session is committed and its result is pending: no process restart, see UpdateHost. */
        private var updateInstalling = false

        // Process-wide: an Activity re-creation must not reset the 20/min window or leave a second sender thread.
        private val remoteClock = { SystemClock.elapsedRealtime() }
        private val remoteLimiter = RemoteLogRateLimiter()
        private val remoteSender = RemoteLogSender({ KLog.w(it) }, remoteLimiter, remoteClock)

        /** Logs an uncaught exception locally and saves it as the exit reason, then lets the process die. */
        private fun installCrashLogger(store: ConfigStore) {
            if (crashLoggerInstalled) return
            crashLoggerInstalled = true
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, e ->
                try {
                    KLog.e("uncaught exception on ${thread.name}", e)
                    store.saveExitReason("crash_${e.javaClass.simpleName}", System.currentTimeMillis())
                } catch (t: Throwable) {
                    android.util.Log.e("SignageKiosk", "crash logger failed", t)
                }
                previous?.uncaughtException(thread, e)
            }
        }
    }
}
