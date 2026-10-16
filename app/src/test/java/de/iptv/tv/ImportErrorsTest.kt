package de.iptv.tv

import org.junit.Assert.*
import org.junit.Test
import java.net.UnknownHostException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLHandshakeException

class ImportErrorsTest {
    @Test fun httpFailuresHaveDistinctActionableMessages() {
        val descriptions=listOf(401,403,404,429,500,503).map { ImportErrors.describe(ImportFailure("http_$it","download",it)) }
        assertEquals(6,descriptions.map { it.message }.distinct().size)
        assertEquals(401,descriptions.first().http)
        assertFalse(descriptions.first().retryable)
        assertTrue(descriptions[3].retryable)
    }
    @Test fun networkEvidenceDoesNotClaimStorageFailure() {
        for(error in listOf(UnknownHostException("https://user:secret@provider/"),SocketTimeoutException("secret"),SSLHandshakeException("secret"))) {
            val result=ImportErrors.describe(error,"download")
            assertEquals("connection",result.domain)
            assertFalse(result.message.contains("secret"))
            assertFalse(result.json().toString().contains("secret"))
        }
    }
    @Test fun storageMemoryAndFormatRemainDifferent() {
        assertEquals("storage",ImportErrors.describe(CoreException("disk_full")).domain)
        assertEquals("memory",ImportErrors.describe(CoreException("memory_exhausted")).domain)
        assertEquals("source",ImportErrors.describe(CoreException("invalid_playlist_format")).domain)
        assertTrue(ImportErrors.describe(CoreException("invalid_playlist_format")).message.contains("Webseite"))
    }
}
