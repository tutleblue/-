package kr.geulbeot.hwp.hwp5

import kr.geulbeot.hwp.model.DocumentRestriction
import kr.geulbeot.hwp.util.ByteReader
import kr.geulbeot.hwp.util.ByteWriter
import kr.geulbeot.hwp.util.HwpFormatException

/**
 * The 256-byte `FileHeader` stream, which is the only part of a `.hwp` that is never compressed.
 *
 * It answers the three questions that have to be settled before anything else can be read: is this
 * really an HWP 5 document, which version, and is the rest of the file compressed or protected.
 */
class FileHeader(
    val version: Int,
    val properties: Int,
    val raw: ByteArray,
) {
    val compressed: Boolean get() = properties and BIT_COMPRESSED != 0
    val passwordProtected: Boolean get() = properties and BIT_PASSWORD != 0
    val distributionDocument: Boolean get() = properties and BIT_DISTRIBUTION != 0
    val hasScripts: Boolean get() = properties and BIT_SCRIPT != 0
    val drmProtected: Boolean get() = properties and BIT_DRM != 0
    val hasSignature: Boolean get() = properties and BIT_SIGNATURE != 0

    val majorVersion: Int get() = (version ushr 24) and 0xFF
    val minorVersion: Int get() = (version ushr 16) and 0xFF
    val buildNumber: Int get() = (version ushr 8) and 0xFF
    val revision: Int get() = version and 0xFF

    val versionText: String get() = "$majorVersion.$minorVersion.$buildNumber.$revision"

    /** Non-null when the document is protected in a way this app deliberately does not work around. */
    fun restriction(): DocumentRestriction? = when {
        passwordProtected || drmProtected -> DocumentRestriction.PASSWORD_PROTECTED
        distributionDocument -> DocumentRestriction.DISTRIBUTION
        else -> null
    }

    /** True when the version is at least the given one, used for the many "5.0.2.1 이상" fields. */
    fun atLeast(major: Int, minor: Int, build: Int, rev: Int = 0): Boolean {
        val other = (major shl 24) or (minor shl 16) or (build shl 8) or rev
        // Compare as unsigned: a version byte can exceed 0x7F.
        return (version.toLong() and 0xFFFFFFFFL) >= (other.toLong() and 0xFFFFFFFFL)
    }

    companion object {
        const val SIZE = 256
        val SIGNATURE = "HWP Document File"

        const val BIT_COMPRESSED = 1 shl 0
        const val BIT_PASSWORD = 1 shl 1
        const val BIT_DISTRIBUTION = 1 shl 2
        const val BIT_SCRIPT = 1 shl 3
        const val BIT_DRM = 1 shl 4
        const val BIT_XML_TEMPLATE = 1 shl 5
        const val BIT_HISTORY = 1 shl 6
        const val BIT_SIGNATURE = 1 shl 7
        const val BIT_CERTIFICATE_ENCRYPTION = 1 shl 8
        const val BIT_SIGNATURE_RESERVED = 1 shl 9
        const val BIT_CERTIFICATE_DRM = 1 shl 10
        const val BIT_CCL = 1 shl 11

        fun parse(data: ByteArray): FileHeader {
            if (data.size < SIZE) {
                throw HwpFormatException("FileHeader 스트림이 ${data.size}바이트뿐입니다. 손상된 문서입니다.")
            }
            val signature = String(data, 0, SIGNATURE.length, Charsets.US_ASCII)
            if (signature != SIGNATURE) {
                throw HwpFormatException("한글 5.0 문서가 아닙니다. (서명: '${signature.trim()}')")
            }
            val r = ByteReader(data, 32)
            val version = r.i32()
            val properties = r.i32()
            return FileHeader(version, properties, data.copyOf(SIZE))
        }

        /** Builds a fresh header for a document this app is writing. */
        fun create(version: Int, compressed: Boolean): FileHeader {
            val w = ByteWriter(SIZE)
            w.bytes(SIGNATURE.toByteArray(Charsets.US_ASCII))
            w.zeros(32 - SIGNATURE.length)
            w.i32(version)
            val properties = if (compressed) BIT_COMPRESSED else 0
            w.i32(properties)
            w.zeros(SIZE - w.size)
            return FileHeader(version, properties, w.toByteArray())
        }
    }

    /**
     * Re-emits the header, keeping every reserved byte from the source file and changing only the
     * compression bit. Anything in the reserved area belongs to the producer, not to us.
     */
    fun withCompression(enabled: Boolean): FileHeader {
        val copy = raw.copyOf()
        val newProperties = if (enabled) properties or BIT_COMPRESSED else properties and BIT_COMPRESSED.inv()
        val w = ByteWriter(4)
        w.i32(newProperties)
        System.arraycopy(w.toByteArray(), 0, copy, 36, 4)
        return FileHeader(version, newProperties, copy)
    }
}
