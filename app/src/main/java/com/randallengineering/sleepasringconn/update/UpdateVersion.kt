package com.randallengineering.sleepasringconn.update

/**
 * Version logic for GitHub release builds (pure, unit-tested).
 *
 * release.yml tags every main build `build-<run>` and names the APK
 * `SleepAsRingConn-v<name>-build<run>.apk`, and builds it with
 * versionCode = 100000 + run. So the run number gives the release's versionCode.
 */
object UpdateVersion {
    const val RELEASE_CODE_BASE = 100_000L

    private val tagRegex = Regex("""^build-(\d+)$""")
    private val assetRegex = Regex("""-build(\d+)\.apk$""")

    /** The versionCode a release was built with, or null if it can't be told from its names. */
    fun remoteVersionCode(tagName: String, assetName: String?): Long? {
        val run = tagRegex.find(tagName.trim())?.groupValues?.get(1)
            ?: assetName?.let { assetRegex.find(it)?.groupValues?.get(1) }
        return run?.toLongOrNull()?.let { RELEASE_CODE_BASE + it }
    }

    fun isNewer(remoteVersionCode: Long?, installedVersionCode: Long): Boolean =
        remoteVersionCode != null && remoteVersionCode > installedVersionCode
}
