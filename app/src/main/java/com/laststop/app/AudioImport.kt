package com.laststop.app

import java.io.InputStream
import java.io.OutputStream

object AudioImport {
    const val MAX_BYTES = 32L * 1024 * 1024
    fun copy(input: InputStream, output: OutputStream, limit: Long = MAX_BYTES): Long {
        var total = 0L
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val size = input.read(buffer)
            if (size < 0) break
            total += size
            require(total <= limit) { "Choose an audio file smaller than 32 MB." }
            output.write(buffer, 0, size)
        }
        require(total > 0) { "The selected audio file is empty." }
        return total
    }
}
