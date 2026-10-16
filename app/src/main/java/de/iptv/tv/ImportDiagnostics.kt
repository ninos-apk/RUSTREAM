package de.iptv.tv

import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

class ImportFailure(val code: String, val stage: String, val httpStatus: Int? = null) : IOException(code)

data class FailureDetails(val code: String, val domain: String, val stage: String, val message: String, val retryable: Boolean = false, val http: Int? = null) {
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
            val message = when (http) {
                401 -> "Der Anbieter lehnt die Anmeldung ab (HTTP 401). Zugangsdaten prüfen."
                403 -> "Der Anbieter verweigert den Zugriff (HTTP 403). Konto oder Freigabe prüfen."
                404,410 -> "Die Adresse wurde nicht gefunden oder ist nicht mehr verfügbar (HTTP $http). Adresse prüfen oder eine andere Quelle wählen."
                429 -> "Zu viele Anfragen beim Anbieter (HTTP 429). Bitte später erneut versuchen."
                in 500..599 -> "Der Anbieter meldet einen Serverfehler (HTTP $http). Bitte später erneut versuchen."
                else -> "Der Anbieter meldet HTTP $http. Quelle prüfen oder später erneut versuchen."
            }
            return FailureDetails(code,"provider",actualStage,message,http == 429 || http >= 500,http)
        }
        val message = when (code) {
            "dns_failed" -> "Der Servername konnte nicht aufgelöst werden. Netzwerk und Anbieteradresse prüfen."
            "connect_failed" -> "Die Verbindung zum Server konnte nicht aufgebaut werden. Netzwerk und Anbieter prüfen."
            "network_timeout" -> "Der Server hat nicht rechtzeitig geantwortet. Verbindung prüfen und erneut versuchen."
            "tls_failed" -> "Die sichere Verbindung ist fehlgeschlagen. Zertifikat und Gerätezeit prüfen."
            "file_access_denied" -> "Der Zugriff auf die Datei wurde verweigert. Bitte die Datei erneut auswählen."
            "file_not_found","import_open_failed" -> "Die Datei kann nicht mehr geöffnet werden. Bitte erneut auswählen."
            "disk_full" -> "Der Gerätespeicher reicht für den Import nicht aus. Bitte Speicherplatz freigeben."
            "memory_exhausted" -> "Der Arbeitsspeicher reicht für diesen Vorgang nicht aus. Andere Apps schließen oder eine kleinere Liste verwenden."
            "import_too_large" -> "Die Importgrenze von 512 MiB wurde überschritten. Beim Anbieter eine kleinere Liste anfordern."
            "too_many_channels" -> "Die Importgrenze von 2.000.000 Einträgen wurde überschritten. Die Auswahl beim Anbieter eingrenzen."
            "too_many_categories" -> "Die Importgrenze von 20.000 Kategorien wurde überschritten. Die Auswahl beim Anbieter eingrenzen."
            "invalid_playlist_format","invalid_playlist" -> "Die Quelle liefert keine gültige Playlist, möglicherweise eine Webseite. Den direkten M3U-Link oder eine andere Datei auswählen."
            "empty_playlist" -> "Die Liste enthält keine unterstützten Einträge. Eine andere Playlist auswählen."
            "invalid_xtream","invalid_streams","invalid_categories","invalid_series_response" -> "Der Anbieter liefert ungültige Katalogdaten. Später erneut versuchen oder den Anbieter kontaktieren."
            "xtream_auth_failed" -> "Die Xtream-Anmeldung wurde abgelehnt. Zugangsdaten prüfen."
            "xtream_account_inactive" -> "Das Xtream-Konto ist abgelaufen, gesperrt oder nicht aktiv. Den Kontostatus beim Anbieter prüfen."
            "empty_series" -> "Der Anbieter liefert keine abspielbaren Episoden für diese Serie. Eine andere Serie wählen."
            "series_too_large","categories_too_large","xtream_entry_too_large" -> "Die Anbieterantwort überschreitet die zulässige Größe. Die Auswahl beim Anbieter eingrenzen."
            "format_unsupported" -> "Dieses Gerät unterstützt das Stream-Format nicht. Eine andere Variante oder einen anderen Eintrag wählen."
            "decoder_init_failed" -> "Der Video-Decoder konnte nicht gestartet werden. Andere Video-Apps schließen oder eine andere Stream-Variante wählen. Details stehen im Diagnosebericht."
            "unsupported_protocol" -> "Dieses Stream-Protokoll wird auf Android noch nicht unterstützt. Eine HTTP(S)- oder RTSP-Quelle wählen."
            "stale_revision" -> "Die Playlist wurde inzwischen gewechselt. Bitte erneut auswählen."
            "import_read_failed","io_failed" -> "Die Daten konnten nicht vollständig gelesen oder gespeichert werden. Details enthalten den betroffenen Schritt."
            "catalog_io_failed","catalog_invalid","catalog_incompatible","catalog_key_failed" -> "Der gespeicherte Katalog kann nicht verwendet werden. Bitte die Quelle neu laden."
            "invalid_source","invalid_base","invalid_server","use_query_credentials" -> "Die Quellenadresse ist ungültig. Bitte Adresse und Eingaben prüfen."
            "source_save_failed" -> "Die Liste wurde geladen, die Quelle konnte aber nicht gespeichert werden. Speicherplatz prüfen und die Quelle erneut hinzufügen."
            else -> "Der Vorgang ist fehlgeschlagen. Der Diagnosebericht enthält den Fehlercode und den betroffenen Schritt."
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
