package de.iptv.tv

import android.content.Context
import androidx.annotation.StringRes
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

class ImportFailure(val code: String, val stage: String, val httpStatus: Int? = null) : IOException(code)

/** [messageRes] is resolved in the UI language; [messageArgs] only ever holds the HTTP status. */
data class FailureDetails(val code: String, val domain: String, val stage: String, @param:StringRes val messageRes: Int, val retryable: Boolean = false, val http: Int? = null,
                          val messageArgs: List<Any> = emptyList()) {
    fun message(context: Context): String =
        if (messageArgs.isEmpty()) context.getString(messageRes) else context.getString(messageRes, *messageArgs.toTypedArray())
    fun json() = JSONObject().put("code",code).put("domain",domain).put("stage",stage)
        .put("retryable",retryable).put("http_status",http ?: JSONObject.NULL)
}

/** Only fixed codes and type checks leave this boundary; exception text may contain credentials. */
object ImportErrors {
    fun describe(error: Throwable, stage: String = "operation"): FailureDetails {
        val chain = generateSequence(error) { it.cause }.take(16).toList()
        val code = when {
            error is ImportFailure -> error.code
            error is CoreException -> error.code
            chain.any { it is UnknownHostException } -> "dns_failed"
            chain.any { it is SSLException } -> "tls_failed"
            chain.any { it is SocketTimeoutException } -> "network_timeout"
            chain.any { it is ConnectException } -> "connect_failed"
            chain.any { it is SecurityException } -> "file_access_denied"
            chain.any { it is android.system.ErrnoException && it.errno == android.system.OsConstants.ENOSPC } -> "disk_full"
            error.message in setOf("import_too_large","too_many_channels","use_query_credentials") -> error.message!!
            error.message?.matches(Regex("http_[0-9]{3}")) == true -> error.message!!
            error is java.io.FileNotFoundException -> "file_not_found"
            error is org.json.JSONException -> "invalid_xtream"
            error is IllegalArgumentException -> "invalid_source"
            error is IOException -> "io_failed"
            else -> "unknown_error"
        }
        val actualStage = (error as? ImportFailure)?.stage ?: stage
        val http = (error as? ImportFailure)?.httpStatus ?: code.removePrefix("http_").toIntOrNull()?.takeIf { code.startsWith("http_") }
        if (http != null) {
            val (message, args) = when (http) {
                401 -> R.string.error_http_401 to emptyList<Int>()
                403 -> R.string.error_http_403 to emptyList<Int>()
                404,410 -> R.string.error_http_not_found to listOf(http)
                429 -> R.string.error_http_429 to emptyList<Int>()
                in 500..599 -> R.string.error_http_server to listOf(http)
                else -> R.string.error_http_other to listOf(http)
            }
            return FailureDetails(code,"provider",actualStage,message,http == 429 || http >= 500,http,args)
        }
        val message = when (code) {
            "dns_failed" -> R.string.error_dns
            "connect_failed" -> R.string.error_connect
            "network_timeout" -> R.string.error_timeout
            "tls_failed" -> R.string.error_tls
            "file_access_denied" -> R.string.error_file_denied
            "file_not_found","import_open_failed" -> R.string.error_file_missing
            "disk_full" -> R.string.error_disk_full
            "memory_exhausted" -> R.string.error_memory
            "import_too_large" -> R.string.error_import_too_large
            "too_many_channels" -> R.string.error_too_many_channels
            "too_many_categories" -> R.string.error_too_many_categories
            "invalid_playlist_format","invalid_playlist" -> R.string.error_invalid_playlist
            "empty_playlist" -> R.string.error_empty_playlist
            "invalid_xtream","invalid_streams","invalid_categories","invalid_series_response" -> R.string.error_invalid_catalog
            "xtream_auth_failed" -> R.string.error_xtream_auth
            "xtream_account_inactive" -> R.string.error_xtream_inactive
            "empty_series" -> R.string.error_empty_series
            "series_too_large","categories_too_large","xtream_entry_too_large" -> R.string.error_response_too_large
            "format_unsupported" -> R.string.error_format_unsupported
            "decoder_init_failed" -> R.string.error_decoder
            "unsupported_protocol" -> R.string.error_protocol
            "stale_revision" -> R.string.error_stale_revision
            "import_read_failed","io_failed" -> R.string.error_io
            "catalog_io_failed","catalog_invalid","catalog_incompatible","catalog_key_failed" -> R.string.error_catalog
            "invalid_source","invalid_base","invalid_server","use_query_credentials" -> R.string.error_invalid_source
            "source_save_failed" -> R.string.error_source_save
            else -> R.string.error_unknown
        }
        val domain = when {
            code in setOf("dns_failed","connect_failed","network_timeout","tls_failed") -> "connection"
            code.startsWith("catalog_") || code in setOf("disk_full","file_not_found","file_access_denied","source_save_failed") -> "storage"
            code == "memory_exhausted" -> "memory"
            code == "unknown_error" -> "unknown"
            else -> "source"
        }
        return FailureDetails(code,domain,actualStage,message,domain == "connection")
    }
}
