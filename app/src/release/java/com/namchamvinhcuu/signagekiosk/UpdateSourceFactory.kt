package com.namchamvinhcuu.signagekiosk

/** Release variant: only the GitHub source; the debug test origin does not exist in this build. */
object UpdateSourceFactory {
    fun create(): UpdateSource = GithubUpdateSource(BuildConfig.UPDATE_GITHUB_REPO)
}
