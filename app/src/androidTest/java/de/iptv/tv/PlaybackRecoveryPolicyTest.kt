@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package de.iptv.tv

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class PlaybackRecoveryPolicyTest {
    private fun info(count: Int, http: Int? = null): LoadErrorInfo {
        val spec = DataSpec.Builder().setUri(Uri.EMPTY).build()
        val error = if (http == null) IOException("temporary") else
            InvalidResponseCodeException(http, "fixture", null, emptyMap(), spec, byteArrayOf())
        return LoadErrorInfo(LoadEventInfo(1, spec, 0), MediaLoadData(C.DATA_TYPE_MEDIA), error, count)
    }
    @Test fun transientLoadsGetOnlyTwoLocalRetriesBeforeSessionRecovery() {
        val policy = PlaybackLoadErrorPolicy(true)
        assertEquals(0L, policy.getRetryDelayMsFor(info(1, 503)))
        assertEquals(1_000L, policy.getRetryDelayMsFor(info(2)))
        assertEquals(C.TIME_UNSET, policy.getRetryDelayMsFor(info(3, 503)))
    }
    @Test fun liveMissingMediaRecoversWhileVodAndAccessErrorsRemainTerminal() {
        for (http in listOf(404, 410)) {
            assertFalse(PlaybackRecoveryPolicy.terminal(true, http, PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS))
            assertTrue(PlaybackRecoveryPolicy.terminal(false, http, PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS))
            assertEquals(0L, PlaybackLoadErrorPolicy(true).getRetryDelayMsFor(info(1, http)))
            assertEquals(C.TIME_UNSET, PlaybackLoadErrorPolicy(false).getRetryDelayMsFor(info(1, http)))
        }
        for (http in listOf(401, 403)) {
            assertTrue(PlaybackRecoveryPolicy.terminal(true, http, PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS))
            assertEquals(C.TIME_UNSET, PlaybackLoadErrorPolicy(true).getRetryDelayMsFor(info(1, http)))
        }
        assertTrue(PlaybackRecoveryPolicy.terminal(true, null, PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED))
    }
}
