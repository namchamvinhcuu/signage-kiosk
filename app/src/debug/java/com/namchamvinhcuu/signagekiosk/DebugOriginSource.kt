package com.namchamvinhcuu.signagekiosk

import java.net.URI
import java.net.URISyntaxException

/**
 * DEBUG BUILDS ONLY (lives in src/debug): an update source on one fixed cleartext origin, for the emulator end-to-end
 * test of plan kiosk-selfupdate (D1b). Layout: `<origin>/<channel>/version.properties` with the APK next to it. It
 * follows redirects only inside the same origin. [GithubUpdateSource]'s https-only allow-list is never loosened for this.
 */
class DebugOriginSource(origin: String) : UpdateSource {
    private val origin = origin.trim().trimEnd('/')

    override fun manifestUrl(channel: UpdateChannel): String? =
        if (channel == UpdateChannel.OFF) null else "$origin/${channel.wire}/${GithubUpdateSource.MANIFEST_FILE}"

    override fun validateHop(requestedUrl: String, location: String?, hopNumber: Int): HopResult {
        if (hopNumber > GithubUpdateSource.MAX_HOPS) return HopResult.Reject("too_many_hops")
        if (location.isNullOrBlank()) return HopResult.Reject("no_location")
        val target = try {
            URI(requestedUrl).resolve(URI(location.trim())).toString()
        } catch (e: URISyntaxException) {
            return HopResult.Reject("bad_location")
        } catch (e: IllegalArgumentException) {
            return HopResult.Reject("bad_location")
        }
        return if (target == origin || target.startsWith("$origin/")) HopResult.Follow(target) else HopResult.Reject("origin_not_allowed")
    }

    override fun apkBase(requestedUrl: String, firstHop: String?): String? =
        if (requestedUrl.startsWith("$origin/")) requestedUrl.substringBeforeLast('/') + "/" else null
}
