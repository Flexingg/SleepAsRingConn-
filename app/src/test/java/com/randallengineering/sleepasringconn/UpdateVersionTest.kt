package com.randallengineering.sleepasringconn

import com.randallengineering.sleepasringconn.update.UpdateVersion
import org.junit.Assert.*
import org.junit.Test

class UpdateVersionTest {

    @Test
    fun readsVersionCodeFromBuildTag() {
        assertEquals(100_019L, UpdateVersion.remoteVersionCode("build-19", "SleepAsRingConn-v1.4.1-build19.apk"))
    }

    @Test
    fun fallsBackToAssetNameForVersionTags() {
        assertEquals(100_042L, UpdateVersion.remoteVersionCode("v1.5.0", "SleepAsRingConn-v1.5.0-build42.apk"))
        assertNull(UpdateVersion.remoteVersionCode("v1.5.0", "app-release.apk"))
    }

    @Test
    fun onlyNewerReleasesAreOffered() {
        assertTrue(UpdateVersion.isNewer(100_020L, 100_019L))
        assertFalse(UpdateVersion.isNewer(100_019L, 100_019L))
        assertFalse(UpdateVersion.isNewer(null, 10L))
        // PR test builds (1000 + run) and local builds (10) are always behind a release.
        assertTrue(UpdateVersion.isNewer(100_019L, 1_006L))
    }
}
