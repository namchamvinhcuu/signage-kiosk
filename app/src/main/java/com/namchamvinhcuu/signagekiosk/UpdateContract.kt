package com.namchamvinhcuu.signagekiosk

import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.net.URI
import java.net.URISyntaxException
import java.security.MessageDigest
import java.util.Properties

// Pure Kotlin (no android.* imports) so it runs in JVM unit tests.
// Design ported from the Youngmin seed's UpdateContract (read-only there); plan kiosk-selfupdate step 8.1.

/** Which release line a TV follows. [OFF] never checks. */
enum class UpdateChannel(val wire: String) {
    STABLE("stable"),
    CANARY("canary"),
    OFF("off");

    companion object {
        fun fromWire(raw: String?): UpdateChannel? {
            val s = raw?.trim()?.lowercase() ?: return null
            return entries.firstOrNull { it.wire == s }
        }
    }
}

/** `version.properties` published next to the APK; [apk] is a bare file name, resolved by the source. */
data class UpdateManifest(
    val versionCode: Int,
    val versionName: String,
    val apk: String,
    val sha256: String,
    val size: Long,
    /** The lowest installed versionCode that may update directly; null = no lower bound. */
    val minVersionCode: Int? = null,
) {
    companion object {
        const val MAX_APK_BYTES = 20L * 1024 * 1024
        private val SHA256 = Regex("^[0-9a-f]{64}$")
        // A bare file name of at most 100 characters in total, no `..`: the APK is always fetched from the manifest's own release.
        private val APK_NAME = Regex("^(?!.*\\.\\.)[A-Za-z0-9][A-Za-z0-9._-]{0,95}\\.apk$")
        private const val MAX_VERSION_NAME = 32

        /** Null when any field is missing or invalid; values are trimmed, sha256 must be lowercase hex. */
        fun parse(p: Properties): UpdateManifest? {
            val code = p.getProperty("versionCode")?.trim()?.toIntOrNull() ?: return null
            if (code <= 0) return null
            val name = p.getProperty("versionName")?.trim().orEmpty()
            if (name.isEmpty() || name.length > MAX_VERSION_NAME) return null
            val apk = p.getProperty("apk")?.trim().orEmpty()
            if (!APK_NAME.matches(apk)) return null
            val sha = p.getProperty("sha256")?.trim().orEmpty()
            if (!SHA256.matches(sha)) return null
            val size = p.getProperty("size")?.trim()?.toLongOrNull() ?: return null
            if (size <= 0 || size > MAX_APK_BYTES) return null
            val minRaw = p.getProperty("minVersionCode")?.trim()
            val min = if (minRaw.isNullOrEmpty()) null else (minRaw.toIntOrNull()?.takeIf { it > 0 } ?: return null)
            return UpdateManifest(code, name, apk, sha, size, min)
        }
    }
}

/** What every [UpdateSource] hands to the policy: a manifest whose APK name has been resolved to a full URL. */
data class UpdateOffer(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
    val size: Long,
    val minVersionCode: Int? = null,
) {
    companion object {
        /** [apkBase] ends with `/`; the manifest's [UpdateManifest.apk] is a validated bare file name. */
        fun of(m: UpdateManifest, apkBase: String): UpdateOffer =
            UpdateOffer(m.versionCode, m.versionName, apkBase + m.apk, m.sha256, m.size, m.minVersionCode)
    }
}

/** What to do with one redirect answer (`Location` header) of a source's request. */
sealed class HopResult {
    data class Follow(val url: String) : HopResult()
    data class Reject(val reason: String) : HopResult()
}

/**
 * Where updates come from. The rules a source owns are pure (URLs, which redirects may be followed, where the APK
 * lives); the network fetch is done by `Updater`, which asks the source before every request and every hop.
 * An `OdooSource` (later) brings its own rules and does not inherit [GithubUpdateSource]'s allow-list.
 */
interface UpdateSource {
    /** The manifest URL for [channel]; null for [UpdateChannel.OFF] or a channel this source does not serve. */
    fun manifestUrl(channel: UpdateChannel): String?

    /** [requestedUrl] answered with a redirect to [location] (possibly relative); [hopNumber] counts from 1. */
    fun validateHop(requestedUrl: String, location: String?, hopNumber: Int): HopResult

    /** Directory (ending with `/`) the manifest's APK name is resolved against, or null when it cannot be told safely. */
    fun apkBase(requestedUrl: String, firstHop: String?): String?
}

/**
 * GitHub Releases of one repository. `stable` = the newest non-prerelease (`releases/latest`), `canary` = the assets
 * of a pre-release under the fixed tag `canary`. Only https and only the hosts below; at most [MAX_HOPS] redirects.
 */
class GithubUpdateSource(repo: String) : UpdateSource {
    private val repo: String = repo.trim()

    init {
        require(REPO.matches(this.repo)) { "repo must be <owner>/<name>" }
    }

    private val tagPrefix = "https://github.com/${this.repo}/releases/download/"
    private val latestPrefix = "https://github.com/${this.repo}/releases/latest/download/"

    override fun manifestUrl(channel: UpdateChannel): String? = when (channel) {
        UpdateChannel.STABLE -> latestPrefix + MANIFEST_FILE
        UpdateChannel.CANARY -> "${tagPrefix}canary/$MANIFEST_FILE"
        UpdateChannel.OFF -> null
    }

    override fun validateHop(requestedUrl: String, location: String?, hopNumber: Int): HopResult {
        if (hopNumber > MAX_HOPS) return HopResult.Reject("too_many_hops")
        if (location.isNullOrBlank()) return HopResult.Reject("no_location")
        val base = try {
            URI(requestedUrl)
        } catch (e: URISyntaxException) {
            return HopResult.Reject("bad_request_url")
        }
        // A relative Location is resolved against the URL that was requested BEFORE it is judged.
        val target = try {
            base.resolve(URI(location.trim()))
        } catch (e: URISyntaxException) {
            return HopResult.Reject("bad_location")
        } catch (e: IllegalArgumentException) {
            return HopResult.Reject("bad_location")
        }
        if (target.scheme?.lowercase() != "https") return HopResult.Reject("not_https")
        if (target.rawUserInfo != null) return HopResult.Reject("userinfo")
        if (target.host?.lowercase() !in ALLOWED_HOSTS) return HopResult.Reject("host_not_allowed")
        if (target.port != -1 && target.port != 443) return HopResult.Reject("port_not_allowed")
        return HopResult.Follow(target.toString())
    }

    /**
     * stable (requested under `releases/latest/`): hop 1 must be `github.com/<repo>/releases/download/<tag>/version.properties`,
     * and that tag directory is the base; a hop straight to the CDN, a missing hop or another repo is refused because the
     * tag cannot be learned. A request already addressed to a tag (the canary manifest, every APK download) uses its own
     * directory; its redirects only have to pass [validateHop].
     */
    override fun apkBase(requestedUrl: String, firstHop: String?): String? {
        if (requestedUrl.startsWith(latestPrefix, ignoreCase = true)) {
            val hop = firstHop ?: return null
            val tag = tagOf(hop, expectedFile = MANIFEST_FILE) ?: return null
            return tagPrefix + tag + "/"
        }
        val tag = tagOf(requestedUrl, expectedFile = null) ?: return null
        return tagPrefix + tag + "/"
    }

    /** The tag of `https://github.com/<repo>/releases/download/<tag>/<file>` (exact shape), else null. */
    private fun tagOf(url: String, expectedFile: String?): String? {
        if (!url.startsWith(tagPrefix, ignoreCase = true)) return null
        val rest = url.substring(tagPrefix.length)
        if (rest.contains('?') || rest.contains('#')) return null
        val parts = rest.split('/')
        if (parts.size != 2) return null
        val (tag, file) = parts
        if (!TAG.matches(tag) || tag == "." || tag == "..") return null
        if (file.isEmpty() || (expectedFile != null && file != expectedFile)) return null
        return tag
    }

    companion object {
        const val MANIFEST_FILE = "version.properties"
        const val MAX_HOPS = 3
        val ALLOWED_HOSTS = setOf("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")
        private val REPO = Regex("^[A-Za-z0-9._-]+/[A-Za-z0-9._-]+$")
        private val TAG = Regex("^[A-Za-z0-9._+-]{1,100}$")
    }
}

/** Persisted between checks and processes (ConfigStore). 0 = never. */
data class UpdateState(
    /** Install failure / pending user action: no install before this wall-clock time. */
    val backoffUntilMs: Long = 0L,
    /** Same-code guard: the last verify failure, for this offer code only. */
    val verifyFailedCode: Int = 0,
    val verifyFailedAtMs: Long = 0L,
)

/** Conditions read from the Android layer at decision time ([channelOff] = switched to `off` meanwhile). */
data class UpdateGates(
    val canDrawOverlays: Boolean,
    val canRequestInstalls: Boolean,
    val settingsOpen: Boolean,
    val restarting: Boolean,
    val channelOff: Boolean = false,
    /** An update install is already running (its own flag, see plan D6): never start a second one. */
    val installing: Boolean = false,
)

sealed class UpdateDecision {
    object Install : UpdateDecision()
    data class Skip(val reason: String) : UpdateDecision()
}

object UpdatePolicy {
    const val CHECK_START_DELAY_MS = 2 * 60_000L
    const val CHECK_INTERVAL_MS = 30 * 60_000L
    const val INSTALL_FAILURE_BACKOFF_MS = 6 * 3_600_000L
    const val PENDING_USER_ACTION_BACKOFF_MS = 24 * 3_600_000L
    const val SAME_CODE_GUARD_MS = 24 * 3_600_000L
    /** A deadline further ahead than this was written under a wrong clock and is ignored. */
    const val MAX_DEADLINE_AHEAD_MS = 24 * 3_600_000L
    /**
     * After a committed install session the activity keeps its restart block at most this long: a refused install (an
     * OEM that blocks third-party sessions) may never send a result, and an unattended TV must not stay blocked.
     */
    const val INSTALL_FAILSAFE_MS = 10 * 60_000L

    // Skip reasons, also logged as `app_update_skip reason=...`.
    const val UP_TO_DATE = "up_to_date"
    const val TOO_OLD = "too_old"
    const val CHANNEL_OFF = "channel_off"
    const val NO_OVERLAY = "no_overlay"
    const val NO_INSTALL_PERMISSION = "no_install_permission"
    const val SETTINGS_OPEN = "settings_open"
    const val RESTARTING = "restarting"
    const val INSTALLING = "installing"
    const val BACKOFF = "backoff"
    const val SAME_CODE_GUARD = "same_code_guard"

    /**
     * Whether to download + install [o]. Order: not newer → up to date; installed code below `minVersionCode` → too old;
     * gates; install-failure / pending-user-action backoff; same-code guard. Fetch errors never reach here, so they never
     * back off.
     */
    fun decide(installedCode: Long, o: UpdateOffer, nowMs: Long, st: UpdateState, g: UpdateGates): UpdateDecision {
        if (o.versionCode.toLong() <= installedCode) return UpdateDecision.Skip(UP_TO_DATE)
        if (o.minVersionCode != null && installedCode < o.minVersionCode.toLong()) return UpdateDecision.Skip(TOO_OLD)
        if (g.channelOff) return UpdateDecision.Skip(CHANNEL_OFF)
        if (!g.canDrawOverlays) return UpdateDecision.Skip(NO_OVERLAY)
        if (!g.canRequestInstalls) return UpdateDecision.Skip(NO_INSTALL_PERMISSION)
        if (g.settingsOpen) return UpdateDecision.Skip(SETTINGS_OPEN)
        if (g.restarting) return UpdateDecision.Skip(RESTARTING)
        if (g.installing) return UpdateDecision.Skip(INSTALLING)
        if (deadlineActive(st.backoffUntilMs, nowMs)) return UpdateDecision.Skip(BACKOFF)
        if (st.verifyFailedCode == o.versionCode && deadlineActive(st.verifyFailedAtMs + SAME_CODE_GUARD_MS, nowMs)) {
            return UpdateDecision.Skip(SAME_CODE_GUARD)
        }
        return UpdateDecision.Install
    }

    /** True while [deadlineMs] is ahead of [nowMs], unless it is implausibly far ahead (wrong clock). */
    fun deadlineActive(deadlineMs: Long, nowMs: Long): Boolean {
        val ahead = deadlineMs - nowMs
        return ahead > 0 && ahead <= MAX_DEADLINE_AHEAD_MS
    }

    /** Never shortens a longer backoff that is still active (abandoning a pending session reports ABORTED too). */
    fun afterInstallFailure(st: UpdateState, nowMs: Long): UpdateState {
        val sixHours = nowMs + INSTALL_FAILURE_BACKOFF_MS
        val keep = deadlineActive(st.backoffUntilMs, nowMs) && st.backoffUntilMs > sixHours
        return st.copy(backoffUntilMs = if (keep) st.backoffUntilMs else sixHours)
    }

    fun afterPendingUserAction(st: UpdateState, nowMs: Long) =
        st.copy(backoffUntilMs = nowMs + PENDING_USER_ACTION_BACKOFF_MS)

    fun afterVerifyFailure(st: UpdateState, code: Int, nowMs: Long) =
        st.copy(verifyFailedCode = code, verifyFailedAtMs = nowMs)

    /**
     * Same signing certificates (SHA-256 hex of each signer, any case). Key rotation is not supported in v1: a lineage
     * change reads as a mismatch, which is a verify failure here instead of a failed install later. Empty on either side
     * is never "same".
     */
    fun sameSigner(ownHashes: Set<String>, archiveHashes: Set<String>): Boolean {
        if (ownHashes.isEmpty() || archiveHashes.isEmpty()) return false
        return ownHashes.map { it.lowercase() }.toSet() == archiveHashes.map { it.lowercase() }.toSet()
    }

    /**
     * Checks the downloaded file against the offer before any install session is opened. Returns the failure reason
     * (logged as `verify_failed reason=...`) or null when it may be installed. A null archive package/code means the APK
     * could not be parsed. [signerMatches] = null means the signer pre-check is not available (API < 28): the OS then
     * enforces it at install time.
     */
    fun verifyDownload(
        o: UpdateOffer,
        actualSize: Long,
        actualSha256: String,
        archivePackage: String?,
        archiveVersionCode: Long?,
        ownPackage: String,
        signerMatches: Boolean? = null,
    ): String? = when {
        actualSize != o.size -> "size"
        !actualSha256.equals(o.sha256, ignoreCase = true) -> "sha256"
        archivePackage == null || archiveVersionCode == null -> "unparsable"
        archivePackage != ownPackage -> "package"
        archiveVersionCode != o.versionCode.toLong() -> "version_code"
        signerMatches == false -> "signer"
        else -> null
    }
}

/** Install-session status handling (UpdateResultReceiver), kept free of android.* so it is JVM-tested. */
object UpdateResult {
    enum class Outcome { SUCCESS, PENDING_USER_ACTION, ABORTED, FAILURE }

    const val PENDING_USER_ACTION_NAME = "pending_user_action"

    fun nextState(o: Outcome, st: UpdateState, nowMs: Long): UpdateState = when (o) {
        Outcome.SUCCESS -> st
        Outcome.PENDING_USER_ACTION -> UpdatePolicy.afterPendingUserAction(st, nowMs)
        Outcome.ABORTED, Outcome.FAILURE -> UpdatePolicy.afterInstallFailure(st, nowMs)
    }

    /** Every outcome but success means the new version is not coming: forget the pending code. */
    fun clearsPending(o: Outcome): Boolean = o != Outcome.SUCCESS

    /**
     * Whether to overwrite the stored, not yet reported result. The ABORTED that follows our own abandon of a
     * pending session must not hide the `pending_user_action` record of the same version.
     */
    fun shouldRecord(o: Outcome, storedStatus: String?, storedCode: Int, code: Int): Boolean =
        !(o == Outcome.ABORTED && storedStatus == PENDING_USER_ACTION_NAME && storedCode == code)
}

/** Sends identical consecutive outcomes (e.g. `skip up_to_date` every 30 min) only once. */
class OutcomeDedupe {
    private var last: String? = null

    /** Non-deduped events (download, commit) always go out and reset the memory. */
    fun shouldSend(stage: String, fields: Map<String, Any?>, dedupe: Boolean): Boolean {
        val key = "$stage $fields"
        if (dedupe && key == last) return false
        last = key
        return true
    }
}

object UpdateIo {
    /**
     * Copies at most [max] bytes from [input] to [out], hashing exactly the bytes written. Returns the number of bytes
     * the source offered up to the point it went over [max] (so a longer source returns > [max] and fails the size
     * check). Throws [java.io.InterruptedIOException] when the thread is interrupted (Updater closed).
     */
    fun copyCapped(input: InputStream, out: OutputStream, max: Long, digest: MessageDigest): Long {
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            if (Thread.currentThread().isInterrupted) throw InterruptedIOException("update download interrupted")
            val n = input.read(buf)
            if (n < 0) break
            total += n
            // Never write more than announced: a wrong or hostile source cannot fill the disk.
            if (total > max) break
            digest.update(buf, 0, n)
            out.write(buf, 0, n)
        }
        return total
    }

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    /** SHA-256 of [bytes] as lowercase hex (used for signing certificates). */
    fun sha256Hex(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
}
