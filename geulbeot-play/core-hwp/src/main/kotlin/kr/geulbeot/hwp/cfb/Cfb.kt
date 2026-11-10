package kr.geulbeot.hwp.cfb

/**
 * Shared constants and name ordering for the Compound File Binary container that a `.hwp` file is.
 *
 * Reference: [MS-CFB]. HWP 5.0 always uses the version 3 layout (512-byte sectors).
 */
internal object Cfb {
    val SIGNATURE = byteArrayOf(
        0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(),
        0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte(),
    )

    const val SECTOR_SIZE = 512
    const val MINI_SECTOR_SIZE = 64
    const val MINI_STREAM_CUTOFF = 4096
    const val DIRECTORY_ENTRY_SIZE = 128
    const val ENTRIES_PER_SECTOR = SECTOR_SIZE / DIRECTORY_ENTRY_SIZE // 4
    const val FAT_ENTRIES_PER_SECTOR = SECTOR_SIZE / 4 // 128
    const val DIFAT_ENTRIES_IN_HEADER = 109
    const val DIFAT_ENTRIES_PER_SECTOR = FAT_ENTRIES_PER_SECTOR - 1 // 127, last slot chains onward

    const val MAX_REG_SECT = 0xFFFFFFFA.toInt()
    const val DIFSECT = 0xFFFFFFFC.toInt()
    const val FATSECT = 0xFFFFFFFD.toInt()
    const val END_OF_CHAIN = 0xFFFFFFFE.toInt()
    const val FREE_SECT = 0xFFFFFFFF.toInt()
    const val NO_STREAM = 0xFFFFFFFF.toInt()

    const val TYPE_UNALLOCATED = 0
    const val TYPE_STORAGE = 1
    const val TYPE_STREAM = 2
    const val TYPE_ROOT = 5

    const val COLOR_RED = 0
    const val COLOR_BLACK = 1

    /**
     * Directory entries are ordered by name: shorter names first, then a case-insensitive
     * comparison of the UTF-16 code units. This exact ordering is what makes the red-black tree
     * navigable by other readers, so it is not interchangeable with ordinary string sorting.
     */
    val NAME_ORDER: Comparator<String> = Comparator { a, b ->
        if (a.length != b.length) {
            a.length - b.length
        } else {
            var result = 0
            for (i in a.indices) {
                val ca = a[i].uppercaseChar().code
                val cb = b[i].uppercaseChar().code
                if (ca != cb) {
                    result = ca - cb
                    break
                }
            }
            result
        }
    }
}

/** One entry in the compound file directory: the root, a storage (folder) or a stream (file). */
data class CfbEntry(
    val name: String,
    val type: Int,
    val path: String,
)
