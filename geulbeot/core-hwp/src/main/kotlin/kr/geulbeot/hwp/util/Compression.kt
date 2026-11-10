package kr.geulbeot.hwp.util

import java.io.ByteArrayOutputStream
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * HWP 5.0 compresses DocInfo, each BodyText section and each BinData entry with *raw* deflate -
 * no zlib header, no adler checksum. That is `nowrap = true` on both sides.
 */
object Compression {

    /**
     * Ceiling on how much a single stream may expand to.
     *
     * Deflate can turn a few kilobytes into gigabytes, so a document arriving from anywhere - a
     * messaging app, a download - could otherwise take the process down just by being opened. No
     * real HWP stream comes close to this.
     */
    const val MAX_INFLATED_BYTES: Int = 256 * 1024 * 1024

    fun inflateRaw(data: ByteArray, limit: Int = MAX_INFLATED_BYTES): ByteArray {
        val inflater = Inflater(true)
        try {
            inflater.setInput(data)
            val out = ByteArrayOutputStream(data.size * 4)
            val chunk = ByteArray(16 * 1024)
            while (!inflater.finished()) {
                val n = try {
                    inflater.inflate(chunk)
                } catch (e: DataFormatException) {
                    throw HwpFormatException("압축 해제에 실패했습니다. 손상된 문서이거나 지원하지 않는 형식입니다.", e)
                }
                if (n == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) break
                } else {
                    if (out.size() + n > limit) {
                        throw HwpFormatException(
                            "문서의 압축을 푸는 중 크기가 비정상적으로 커졌습니다. 손상되었거나 안전하지 않은 파일일 수 있습니다.",
                        )
                    }
                    out.write(chunk, 0, n)
                }
            }
            return out.toByteArray()
        } finally {
            inflater.end()
        }
    }

    fun deflateRaw(data: ByteArray, level: Int = Deflater.DEFAULT_COMPRESSION): ByteArray {
        val deflater = Deflater(level, true)
        try {
            deflater.setInput(data)
            deflater.finish()
            val out = ByteArrayOutputStream(data.size / 2 + 64)
            val chunk = ByteArray(16 * 1024)
            while (!deflater.finished()) {
                val n = deflater.deflate(chunk)
                if (n == 0 && deflater.needsInput()) break
                out.write(chunk, 0, n)
            }
            return out.toByteArray()
        } finally {
            deflater.end()
        }
    }

    /**
     * Some producers store BinData entries with a 4-byte uncompressed-size prefix ahead of the raw
     * deflate payload. Try the plain form first and fall back, so both shapes load.
     */
    fun inflateBinData(data: ByteArray): ByteArray {
        if (data.isEmpty()) return data
        return try {
            inflateRaw(data)
        } catch (_: HwpFormatException) {
            if (data.size > 4) inflateRaw(data.copyOfRange(4, data.size)) else data
        }
    }
}
