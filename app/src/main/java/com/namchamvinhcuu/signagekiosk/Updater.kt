package com.namchamvinhcuu.signagekiosk

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.Properties
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Self-update (plan kiosk-selfupdate 8.4a), ported from the Youngmin seed: asks an [UpdateSource] for a manifest,
 * downloads a newer APK through [UpdateFetcher] (every redirect judged by the source), verifies it (size, SHA-256,
 * package, versionCode, signing certificate) and installs it with a PackageInstaller session that needs no user action
 * (API 31+, the app updates itself). Network and file work run on one background thread; every decision and every
 * [Host] call happens on the main thread. The install result arrives in [UpdateResultReceiver].
 * No URL is logged: stage fields carry codes, sizes and short reasons only.
 */
class Updater(
    context: Context,
    private val store: ConfigStore,
    private val source: UpdateSource,
    private val host: Host,
    /** False while an earlier Updater of this process is still installing: its APK file must not be deleted. */
    deleteStaleApk: Boolean = true,
) {

    /** Implemented by MainActivity; always called on the main thread. */
    interface Host {
        fun updateGates(): UpdateGates
        fun logUpdate(stage: String, fields: Map<String, Any?>)

        /** Last moment before the session commits: flush cookies, block process restarts. */
        fun beforeInstall(code: Int)

        /** The session could not be committed: the process is not going to be killed, lift the restart block. */
        fun installFailed()
    }

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "updater").apply { isDaemon = true } }
    private val fetcher = UpdateFetcher(source)
    private val apkFile = File(app.cacheDir, APK_NAME)
    private var busy = false
    private var closed = false
    private val dedupe = OutcomeDedupe()

    init {
        // A previous process may have died mid-download or right after commit.
        if (deleteStaleApk) executor.execute { deleteApk() }
    }

    /** Main thread. One check at a time. */
    fun check(channel: UpdateChannel) {
        if (busy || closed) {
            KLog.i("update check skipped: ${if (closed) "closed" else "a check is already running"}")
            return
        }
        val manifestUrl = source.manifestUrl(channel) ?: run {
            KLog.i("update check skipped: channel ${channel.wire} has no update location")
            return
        }
        busy = true
        executor.execute {
            val outcome = try {
                fetchOffer(manifestUrl)
            } catch (e: FetchFailure) {
                OfferOutcome.Error(e.reason)
            } catch (e: Exception) {
                OfferOutcome.Error(e.javaClass.simpleName)
            }
            onMain { onOffer(channel, outcome) }
        }
    }

    /** Main thread, from onDestroy. Pending results are dropped. */
    fun close() {
        closed = true
        executor.shutdownNow()
    }

    private fun onOffer(channel: UpdateChannel, outcome: OfferOutcome) {
        val offer = when (outcome) {
            is OfferOutcome.Error -> return finish("fetch_failed", mapOf("reason" to outcome.reason, "channel" to channel.wire))
            OfferOutcome.Invalid -> return finish("fetch_failed", mapOf("reason" to "invalid_manifest", "channel" to channel.wire))
            is OfferOutcome.Ok -> outcome.offer
        }
        val decision = UpdatePolicy.decide(installedCode(), offer, System.currentTimeMillis(), store.loadUpdateState(), host.updateGates())
        if (decision is UpdateDecision.Skip) {
            return finish("skip", mapOf("reason" to decision.reason, "code" to offer.versionCode, "channel" to channel.wire))
        }
        log("download", mapOf("code" to offer.versionCode, "channel" to channel.wire, "size" to offer.size), dedupe = false)
        executor.execute {
            val failure = try {
                download(offer)
            } catch (e: FetchFailure) {
                deleteApk()
                "download:" + e.reason
            } catch (e: Exception) {
                deleteApk()
                "download:" + e.javaClass.simpleName
            }
            onMain { onDownloaded(offer, failure) }
        }
    }

    private fun onDownloaded(offer: UpdateOffer, failure: String?) {
        if (failure != null) {
            if (failure.startsWith("download:")) {
                // A network error is retried at the next check, without backoff.
                return finish("download_failed", mapOf("reason" to failure.removePrefix("download:"), "code" to offer.versionCode))
            }
            store.saveUpdateState(UpdatePolicy.afterVerifyFailure(store.loadUpdateState(), offer.versionCode, System.currentTimeMillis()))
            return finish("verify_failed", mapOf("reason" to failure, "code" to offer.versionCode))
        }
        // Gates may have changed during the download (settings opened, restart started, appop revoked).
        val decision = UpdatePolicy.decide(installedCode(), offer, System.currentTimeMillis(), store.loadUpdateState(), host.updateGates())
        if (decision is UpdateDecision.Skip) {
            executor.execute { deleteApk() }
            return finish("skip", mapOf("reason" to decision.reason, "code" to offer.versionCode))
        }
        if (!store.savePendingUpdateCode(offer.versionCode)) KLog.w("pending update code not committed")
        host.beforeInstall(offer.versionCode)
        log("commit", mapOf("code" to offer.versionCode), dedupe = false)
        executor.execute {
            val error = try {
                install(offer)
                null
            } catch (e: Exception) {
                e.javaClass.simpleName
            } finally {
                deleteApk()
            }
            onMain {
                if (error != null) {
                    host.installFailed()
                    store.takePendingUpdateCode()
                    store.saveUpdateState(UpdatePolicy.afterInstallFailure(store.loadUpdateState(), System.currentTimeMillis()))
                    finish("install_failed", mapOf("reason" to error, "code" to offer.versionCode))
                } else {
                    // On success the system kills this process; UpdateResultReceiver records the outcome.
                    busy = false
                }
            }
        }
    }

    private fun finish(stage: String, fields: Map<String, Any?>) {
        busy = false
        log(stage, fields, dedupe = true)
    }

    /** Repeated identical outcomes (e.g. `skip up_to_date` every 30 min) are logged remotely only once. */
    private fun log(stage: String, fields: Map<String, Any?>, dedupe: Boolean) {
        if (!this.dedupe.shouldSend(stage, fields, dedupe)) {
            KLog.i("app_update_$stage $fields (repeat)")
            return
        }
        host.logUpdate(stage, fields)
    }

    private fun onMain(block: () -> Unit) {
        main.post { if (!closed) block() }
    }

    private fun installedCode(): Long {
        val info = app.packageManager.getPackageInfo(app.packageName, 0)
        @Suppress("DEPRECATION")
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
    }

    // ---- Background thread -------------------------------------------------------------------------

    private sealed class OfferOutcome {
        data class Ok(val offer: UpdateOffer) : OfferOutcome()
        object Invalid : OfferOutcome()
        data class Error(val reason: String) : OfferOutcome()
    }

    private fun fetchOffer(manifestUrl: String): OfferOutcome {
        fetcher.open(manifestUrl).use { opened ->
            val bytes = opened.input.readCapped(MAX_MANIFEST_BYTES + 1)
            if (bytes.size > MAX_MANIFEST_BYTES) return OfferOutcome.Error("manifest_too_large")
            val manifest = UpdateManifest.parse(Properties().apply { load(bytes.inputStream()) }) ?: return OfferOutcome.Invalid
            val base = source.apkBase(manifestUrl, opened.firstHop) ?: return OfferOutcome.Error("no_apk_base")
            return OfferOutcome.Ok(UpdateOffer.of(manifest, base))
        }
    }

    /** Null when the file is ready to install, else a verify-failure reason (see [UpdatePolicy.verifyDownload]). */
    private fun download(offer: UpdateOffer): String? {
        deleteApk()
        val digest = MessageDigest.getInstance("SHA-256")
        val total = fetcher.open(offer.apkUrl).use { opened ->
            opened.input.use { input -> apkFile.outputStream().use { out -> UpdateIo.copyCapped(input, out, offer.size, digest) } }
        }
        val sha = UpdateIo.hex(digest.digest())
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else 0
        val archive = if (total == offer.size) app.packageManager.getPackageArchiveInfo(apkFile.path, flags) else null
        @Suppress("DEPRECATION")
        val archiveCode = archive?.let { if (Build.VERSION.SDK_INT >= 28) it.longVersionCode else it.versionCode.toLong() }
        val failure = UpdatePolicy.verifyDownload(
            offer, total, sha, archive?.packageName, archiveCode, app.packageName,
            signerMatches = archive?.let { signerMatches(it) },
        )
        if (failure != null) deleteApk()
        return failure
    }

    /** Null = cannot be told here (API < 28 or no signing info on the archive): the OS enforces the signer at install. */
    private fun signerMatches(archive: android.content.pm.PackageInfo): Boolean? {
        if (Build.VERSION.SDK_INT < 28) return null
        val archiveCerts = archive.signingInfo?.apkContentsSigners ?: return null
        val own = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo?.apkContentsSigners
            ?: return null
        return UpdatePolicy.sameSigner(
            own.map { UpdateIo.sha256Hex(it.toByteArray()) }.toSet(),
            archiveCerts.map { UpdateIo.sha256Hex(it.toByteArray()) }.toSet(),
        )
    }

    private fun install(offer: UpdateOffer) {
        val installer = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(app.packageName)
            setSize(apkFile.length())
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                session.openWrite("base.apk", 0, apkFile.length()).use { out ->
                    apkFile.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                val callback = Intent(app, UpdateResultReceiver::class.java)
                    .setPackage(app.packageName)
                    .putExtra(UpdateResultReceiver.EXTRA_CODE, offer.versionCode)
                // The installer fills in the status extras, so the PendingIntent must be mutable (explicit target).
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                val pending = PendingIntent.getBroadcast(app, sessionId, callback, flags)
                session.commit(pending.intentSender)
            }
        } catch (e: Exception) {
            try {
                installer.abandonSession(sessionId)
            } catch (abandon: Exception) {
                KLog.w("abandon session $sessionId failed", abandon)
            }
            throw e
        }
    }

    private fun deleteApk() {
        if (apkFile.exists() && !apkFile.delete()) KLog.w("could not delete ${apkFile.name}")
    }

    /** InputStream.readNBytes is API 33+. */
    private fun InputStream.readCapped(limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(1024)
        while (out.size() < limit) {
            val n = read(buf, 0, minOf(buf.size, limit - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    companion object {
        private const val APK_NAME = "update.apk"
        private const val MAX_MANIFEST_BYTES = 4 * 1024
    }
}
