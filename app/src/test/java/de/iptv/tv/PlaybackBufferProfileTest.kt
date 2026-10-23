@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package de.iptv.tv

import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import androidx.media3.exoplayer.source.SinglePeriodTimeline
import androidx.media3.exoplayer.upstream.Allocation
import androidx.media3.exoplayer.upstream.DefaultAllocator
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
    @Test fun savedProfilesRestoreAndVodIsIndependentOfLiveProfile() {
        assertEquals(PlaybackBufferProfile.BALANCED, PlaybackBufferProfile.fromKey(null))
        assertEquals(PlaybackBufferProfile.BALANCED, PlaybackBufferProfile.fromKey("obsolete"))
        for (profile in PlaybackBufferProfile.entries) {
            assertEquals(profile, PlaybackBufferProfile.fromKey(profile.key))
            assertEquals(PlaybackBuffers(60_000, 120_000, 1_500, 3_000, 120_000_000), profile.buffers(false))
        }
    }
    @Test fun threeProfilesProvideIncreasingReservesAndMigrateRemovedChoices() {
        val profiles = PlaybackBufferProfile.entries
        assertEquals(listOf(5_000, 10_000, 20_000), profiles.map { it.targetMs })
        assertEquals(listOf(1_000, 1_500, 2_000), profiles.map { it.startMs })
        assertEquals(listOf(2_000, 3_000, 5_000), profiles.map { it.rebufferMs })
        assertEquals(listOf("sport", "balanced", "stable"), profiles.map { it.key })
        assertEquals(PlaybackBufferProfile.BALANCED, PlaybackBufferProfile.fromKey("custom_10"))
        assertEquals(PlaybackBufferProfile.STABLE, PlaybackBufferProfile.fromKey("custom_15"))
        assertEquals(PlaybackBufferProfile.STABLE, PlaybackBufferProfile.fromKey("custom_20"))
    }
    @Test fun vodRefillsBelowOneMinuteEvenAtTheByteTarget() {
        val buffers = PlaybackBufferProfile.BALANCED.buffers(false)
        val mediaAllocator = DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE)
        val control = DefaultLoadControl.Builder().setAllocator(mediaAllocator)
            .setBufferDurationsMs(buffers.minMs, buffers.maxMs, buffers.startMs, buffers.rebufferMs)
            .setTargetBufferBytes(buffers.bytes).setPrioritizeTimeOverSizeThresholds(true).build()
        val player = PlayerId("buffer-test")
        val timeline = SinglePeriodTimeline(300_000_000L, true, false, false, null, MediaItem.EMPTY)
        fun parameters(bufferedSeconds: Long) = LoadControl.Parameters(
            player, timeline, MediaPeriodId(timeline.getUidOfPeriod(0)), 0,
            bufferedSeconds * 1_000_000L, 1f, true, false, C.TIME_UNSET, C.TIME_UNSET,
        )
        control.onPrepared(player)
        val allocator = control.getAllocator(player)
        val allocations = mutableListOf<Allocation>()
        try {
            assertTrue(control.shouldContinueLoading(parameters(30)))
            assertFalse(control.shouldContinueLoading(parameters(120)))
            assertFalse("Keep consuming the reserve before refilling", control.shouldContinueLoading(parameters(90)))
            assertTrue("Refill below one minute", control.shouldContinueLoading(parameters(59)))
            while (mediaAllocator.totalBytesAllocated < buffers.bytes) allocations += allocator.allocate()
            assertTrue("The byte target must not block the minimum VOD reserve", control.shouldContinueLoading(parameters(30)))
            assertTrue("Keep requesting data below one minute", control.shouldContinueLoading(parameters(59)))
            assertFalse("Above one minute the byte target can stop read-ahead", control.shouldContinueLoading(parameters(60)))
            allocations.forEach { allocator.release(it) }
            allocations.clear()
            assertTrue("Resume once the byte budget has room", control.shouldContinueLoading(parameters(30)))
        } finally {
            allocations.forEach { allocator.release(it) }
            control.onReleased(player)
        }
    }
}
