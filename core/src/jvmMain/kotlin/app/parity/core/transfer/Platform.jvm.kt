package app.parity.core.transfer

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.zip.Deflater
import java.util.zip.Inflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

actual fun deflate(data: ByteArray): ByteArray {
    val deflater = Deflater(Deflater.BEST_COMPRESSION, true)
    try {
        deflater.setInput(data)
        deflater.finish()
        val out = ByteArrayOutputStream(data.size / 2 + 64)
        val buffer = ByteArray(8192)
        while (!deflater.finished()) {
            val n = deflater.deflate(buffer)
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    } finally {
        deflater.end()
    }
}

actual fun inflate(data: ByteArray): ByteArray {
    val inflater = Inflater(true)
    try {
        inflater.setInput(data)
        val out = ByteArrayOutputStream(data.size * 3)
        val buffer = ByteArray(8192)
        while (!inflater.finished()) {
            val n = inflater.inflate(buffer)
            if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    } finally {
        inflater.end()
    }
}

actual fun zip(entries: Map<String, ByteArray>): ByteArray {
    val out = ByteArrayOutputStream()
    ZipOutputStream(out).use { zip ->
        entries.forEach { (path, content) ->
            zip.putNextEntry(ZipEntry(path))
            zip.write(content)
            zip.closeEntry()
        }
    }
    return out.toByteArray()
}

actual fun unzip(archive: ByteArray): Map<String, ByteArray> {
    val result = linkedMapOf<String, ByteArray>()
    ZipInputStream(ByteArrayInputStream(archive)).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            if (!entry.isDirectory) result[entry.name] = zip.readBytes()
            zip.closeEntry()
        }
    }
    return result
}

private val secureRandom = SecureRandom()

actual fun secureRandomBytes(size: Int): ByteArray = ByteArray(size).also(secureRandom::nextBytes)
