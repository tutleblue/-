package kr.geulbeot.hwp.util

/**
 * Little-endian cursor over a byte array.
 *
 * Every number inside an HWP 5.0 file is little-endian, and almost every parser bug in this kind of
 * code comes from reading the wrong width or forgetting to advance. Centralising the cursor here
 * keeps the record parsers readable.
 */
class ByteReader(private val buf: ByteArray, var position: Int = 0, private val limit: Int = buf.size) {

    val remaining: Int get() = limit - position
    fun hasRemaining(): Boolean = position < limit

    private fun require(n: Int) {
        if (position + n > limit) {
            throw HwpFormatException("스트림이 예상보다 짧습니다: ${position + n}바이트를 읽으려 했지만 $limit 바이트뿐입니다.")
        }
    }

    fun u8(): Int {
        require(1)
        return buf[position++].toInt() and 0xFF
    }

    fun i8(): Int {
        require(1)
        return buf[position++].toInt()
    }

    fun u16(): Int {
        require(2)
        val v = (buf[position].toInt() and 0xFF) or ((buf[position + 1].toInt() and 0xFF) shl 8)
        position += 2
        return v
    }

    fun i16(): Int = u16().toShort().toInt()

    fun i32(): Int {
        require(4)
        val v = (buf[position].toInt() and 0xFF) or
            ((buf[position + 1].toInt() and 0xFF) shl 8) or
            ((buf[position + 2].toInt() and 0xFF) shl 16) or
            ((buf[position + 3].toInt() and 0xFF) shl 24)
        position += 4
        return v
    }

    fun u32(): Long = i32().toLong() and 0xFFFF_FFFFL

    fun i64(): Long {
        val lo = u32()
        val hi = u32()
        return (hi shl 32) or lo
    }

    fun bytes(n: Int): ByteArray {
        require(n)
        val out = buf.copyOfRange(position, position + n)
        position += n
        return out
    }

    fun skip(n: Int) {
        require(n)
        position += n
    }

    /** Reads the remaining bytes without consuming beyond the limit. */
    fun rest(): ByteArray = bytes(remaining)

    /**
     * HWP stores most strings as a UINT16 character count followed by that many UTF-16LE code units.
     */
    fun hwpString(): String {
        val count = u16()
        if (count == 0) return ""
        val raw = bytes(count * 2)
        return decodeUtf16Le(raw)
    }

    /** Reads [count] UTF-16LE code units (no length prefix). */
    fun utf16(count: Int): String = decodeUtf16Le(bytes(count * 2))

    /** A window over the same buffer, used to parse a record payload without copying it. */
    fun window(length: Int): ByteReader {
        require(length)
        val w = ByteReader(buf, position, position + length)
        position += length
        return w
    }
}

/** Growable little-endian byte sink. */
class ByteWriter(initialCapacity: Int = 256) {
    private var buf = ByteArray(initialCapacity.coerceAtLeast(16))
    var size: Int = 0
        private set

    private fun ensure(extra: Int) {
        if (size + extra <= buf.size) return
        var cap = buf.size
        while (cap < size + extra) cap = cap * 2 + 16
        buf = buf.copyOf(cap)
    }

    fun u8(v: Int) = apply {
        ensure(1)
        buf[size++] = (v and 0xFF).toByte()
    }

    fun u16(v: Int) = apply {
        ensure(2)
        buf[size++] = (v and 0xFF).toByte()
        buf[size++] = ((v ushr 8) and 0xFF).toByte()
    }

    fun i32(v: Int) = apply {
        ensure(4)
        buf[size++] = (v and 0xFF).toByte()
        buf[size++] = ((v ushr 8) and 0xFF).toByte()
        buf[size++] = ((v ushr 16) and 0xFF).toByte()
        buf[size++] = ((v ushr 24) and 0xFF).toByte()
    }

    fun u32(v: Long) = i32(v.toInt())

    fun bytes(src: ByteArray, from: Int = 0, len: Int = src.size - from) = apply {
        ensure(len)
        System.arraycopy(src, from, buf, size, len)
        size += len
    }

    fun zeros(n: Int) = apply {
        ensure(n)
        java.util.Arrays.fill(buf, size, size + n, 0.toByte())
        size += n
    }

    /** UINT16 code-unit count followed by UTF-16LE data - the standard HWP string encoding. */
    fun hwpString(s: String) = apply {
        val data = encodeUtf16Le(s)
        u16(data.size / 2)
        bytes(data)
    }

    fun utf16(s: String) = apply { bytes(encodeUtf16Le(s)) }

    /** Overwrites 4 bytes at [offset]; used to backfill lengths that are only known afterwards. */
    fun patchI32(offset: Int, v: Int) {
        buf[offset] = (v and 0xFF).toByte()
        buf[offset + 1] = ((v ushr 8) and 0xFF).toByte()
        buf[offset + 2] = ((v ushr 16) and 0xFF).toByte()
        buf[offset + 3] = ((v ushr 24) and 0xFF).toByte()
    }

    fun toByteArray(): ByteArray = buf.copyOf(size)
}

/**
 * Decodes UTF-16LE.
 *
 * Note this deliberately does not use `String(bytes, UTF_16LE)` semantics blindly: HWP text streams
 * contain unpaired code units in the 0x00-0x1F control range that must survive the round trip, and
 * the JDK decoder maps malformed surrogate pairs to U+FFFD. Decoding code unit by code unit keeps
 * every value intact.
 */
fun decodeUtf16Le(raw: ByteArray): String {
    val sb = StringBuilder(raw.size / 2)
    var i = 0
    while (i + 1 < raw.size) {
        val unit = (raw[i].toInt() and 0xFF) or ((raw[i + 1].toInt() and 0xFF) shl 8)
        sb.append(unit.toChar())
        i += 2
    }
    return sb.toString()
}

fun encodeUtf16Le(s: String): ByteArray {
    val out = ByteArray(s.length * 2)
    for (i in s.indices) {
        val c = s[i].code
        out[i * 2] = (c and 0xFF).toByte()
        out[i * 2 + 1] = ((c ushr 8) and 0xFF).toByte()
    }
    return out
}

/** Raised for anything structurally wrong in a document we were asked to read. */
class HwpFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)
