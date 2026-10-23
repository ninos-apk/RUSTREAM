@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package de.iptv.tv

import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy

/** A few local retries can use the existing reserve before Rust rebuilds the session. */
class PlaybackLoadErrorPolicy(private val live: Boolean) : DefaultLoadErrorHandlingPolicy(2) {
    override fun getRetryDelayMsFor(info: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        val http = generateSequence(info.exception as Throwable) { it.cause }
            .filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()?.responseCode
        if (info.errorCount > 2 || http in setOf(401, 403) || (!live && http in setOf(404, 410))) return C.TIME_UNSET
        return super.getRetryDelayMsFor(info)
    }
}

object PlaybackRecoveryPolicy {
    fun terminal(live: Boolean, httpCode: Int?, errorCode: Int): Boolean =
        httpCode in setOf(401, 403) || (!live && httpCode in setOf(404, 410)) ||
            errorCode in setOf(PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
                PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
                PlaybackException.ERROR_CODE_DECODER_INIT_FAILED)
}
