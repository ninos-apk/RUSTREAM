@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package de.iptv.tv

import androidx.media3.exoplayer.DefaultLoadControl
import org.junit.Assert.*
import org.junit.Test

class PlaybackBufferProfileTest {
    @Test fun liveProfilesBuildWithReachableStartAndResumeThresholds() {
        for (profile in PlaybackBufferProfile.entries) {
            val b = profile.buffers(true)
            assertTrue(b.startMs <= b.rebufferMs)
            assertTrue(b.rebufferMs <= b.minMs)
            assertEquals(b.minMs, b.maxMs)
            DefaultLoadControl.Builder().setBufferDurationsMs(b.minMs, b.maxMs, b.startMs, b.rebufferMs)
                .setTargetBufferBytes(b.bytes).setPrioritizeTimeOverSizeThresholds(false).build()
        }
    }
    @Test fun savedProfilesRestoreAndVodRetainsItsDownloadWindow() {
        assertEquals(PlaybackBufferProfile.BALANCED, PlaybackBufferProfile.fromKey(null))
        assertEquals(PlaybackBufferProfile.BALANCED, PlaybackBufferProfile.fromKey("obsolete"))
        for (profile in PlaybackBufferProfile.entries) {
            assertEquals(profile, PlaybackBufferProfile.fromKey(profile.key))
            assertEquals(PlaybackBuffers(10_000, 30_000, 1_500, 3_000, 32 * 1024 * 1024), profile.buffers(false))
        }
    }
}
