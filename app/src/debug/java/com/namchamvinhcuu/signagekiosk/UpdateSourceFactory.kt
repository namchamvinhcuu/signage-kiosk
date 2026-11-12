package com.namchamvinhcuu.signagekiosk

/** Debug variant: the cleartext test origin when one was baked in at build time, else the real GitHub source. */
object UpdateSourceFactory {
    fun create(): UpdateSource =
        BuildConfig.UPDATE_TEST_ORIGIN.takeIf { it.isNotEmpty() }?.let { DebugOriginSource(it) }
            ?: GithubUpdateSource(BuildConfig.UPDATE_GITHUB_REPO)
}
