package app.parity.core.transfer

/** Raw DEFLATE (RFC 1951). Platform zlib on each target. */
expect fun deflate(data: ByteArray): ByteArray

expect fun inflate(data: ByteArray): ByteArray

/** Builds a ZIP archive from path → content. */
expect fun zip(entries: Map<String, ByteArray>): ByteArray

/** Reads every file of a ZIP archive into path → content. */
expect fun unzip(archive: ByteArray): Map<String, ByteArray>

/** Cryptographically secure random bytes. */
expect fun secureRandomBytes(size: Int): ByteArray
