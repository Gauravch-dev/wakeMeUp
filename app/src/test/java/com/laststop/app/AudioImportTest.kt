package com.laststop.app

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test

class AudioImportTest {
    @Test fun importedAudioIsCopiedExactlyAndCanFillTheLimit() {
        val bytes = ByteArray(32768) { (it % 251).toByte() }
        val output = ByteArrayOutputStream()
        assertEquals(bytes.size.toLong(), AudioImport.copy(ByteArrayInputStream(bytes), output, bytes.size.toLong()))
        assertArrayEquals(bytes, output.toByteArray())
    }
    @Test(expected = IllegalArgumentException::class)
    fun oversizedAudioIsRejected() {
        AudioImport.copy(ByteArrayInputStream(ByteArray(100)), ByteArrayOutputStream(), 99)
    }
    @Test(expected = IllegalArgumentException::class)
    fun emptyAudioIsRejected() {
        AudioImport.copy(ByteArrayInputStream(byteArrayOf()), ByteArrayOutputStream())
    }
}
