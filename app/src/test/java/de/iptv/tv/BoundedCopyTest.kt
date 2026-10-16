package de.iptv.tv

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

class BoundedCopyTest {
    @Test fun acceptsExactLimitAndEmptyStreams() {
        val output = ByteArrayOutputStream()
        BoundedCopy.copy(ByteArrayInputStream(byteArrayOf(1,2,3)), output, 3)
        assertArrayEquals(byteArrayOf(1,2,3), output.toByteArray())
        BoundedCopy.copy(ByteArrayInputStream(byteArrayOf()), output, 0)
    }
    @Test fun rejectsUnknownLengthOverflow() {
        assertThrows(IllegalArgumentException::class.java) { BoundedCopy.copy(ByteArrayInputStream(ByteArray(33)), ByteArrayOutputStream(),32) }
    }
    @Test fun propagatesConnectionBreakWithoutReportingSuccess() {
        assertThrows(IOException::class.java) {
            BoundedCopy.copy(object : InputStream() { override fun read(): Int = throw IOException("fixture disconnect") }, ByteArrayOutputStream())
        }
    }
}

