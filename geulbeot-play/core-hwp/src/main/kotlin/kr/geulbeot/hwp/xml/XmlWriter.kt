package kr.geulbeot.hwp.xml

/**
 * A small XML emitter.
 *
 * OWPML is verbose but shallow, and its documents are built rather than transformed, so a streaming
 * builder beats a DOM here: no tree in memory, and every element is closed by construction.
 * Attribute order follows the order given, which keeps generated files diffable.
 */
class XmlWriter(private val indent: Boolean = false) {

    private val sb = StringBuilder(4096)
    private val open = ArrayList<String>()
    private var elementOpen = false

    init {
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        if (indent) sb.append('\n')
    }

    fun start(name: String, vararg attributes: Pair<String, Any?>): XmlWriter {
        closeStartTag()
        if (indent && open.isNotEmpty()) newline()
        sb.append('<').append(name)
        for ((key, value) in attributes) attribute(key, value)
        open.add(name)
        elementOpen = true
        return this
    }

    fun attribute(name: String, value: Any?): XmlWriter {
        if (value == null) return this
        sb.append(' ').append(name).append("=\"").append(escapeAttribute(value.toString())).append('"')
        return this
    }

    /** Writes element text. Call between [start] and [end]. */
    fun text(value: String): XmlWriter {
        closeStartTag()
        sb.append(escapeText(value))
        return this
    }

    fun end(): XmlWriter {
        val name = open.removeAt(open.size - 1)
        if (elementOpen) {
            sb.append("/>")
            elementOpen = false
        } else {
            sb.append("</").append(name).append('>')
        }
        return this
    }

    /** Convenience for a leaf element with attributes and no children. */
    fun element(name: String, vararg attributes: Pair<String, Any?>): XmlWriter {
        start(name, *attributes)
        return end()
    }

    /** Convenience for a leaf element containing only text. */
    fun textElement(name: String, value: String, vararg attributes: Pair<String, Any?>): XmlWriter {
        start(name, *attributes)
        text(value)
        return end()
    }

    private fun closeStartTag() {
        if (elementOpen) {
            sb.append('>')
            elementOpen = false
        }
    }

    private fun newline() {
        sb.append('\n')
        repeat(open.size) { sb.append("  ") }
    }

    fun build(): String {
        while (open.isNotEmpty()) end()
        return sb.toString()
    }

    fun toBytes(): ByteArray = build().toByteArray(Charsets.UTF_8)

    companion object {
        fun escapeText(value: String): String {
            val sb = StringBuilder(value.length + 16)
            for (c in value) {
                when (c) {
                    '&' -> sb.append("&amp;")
                    '<' -> sb.append("&lt;")
                    '>' -> sb.append("&gt;")
                    else -> if (isXmlSafe(c)) sb.append(c) else sb.append("&#${c.code};")
                }
            }
            return sb.toString()
        }

        fun escapeAttribute(value: String): String {
            val sb = StringBuilder(value.length + 16)
            for (c in value) {
                when (c) {
                    '&' -> sb.append("&amp;")
                    '<' -> sb.append("&lt;")
                    '>' -> sb.append("&gt;")
                    '"' -> sb.append("&quot;")
                    '\n' -> sb.append("&#10;")
                    '\t' -> sb.append("&#9;")
                    else -> if (isXmlSafe(c)) sb.append(c) else sb.append("&#${c.code};")
                }
            }
            return sb.toString()
        }

        /**
         * XML 1.0 forbids most control characters outright - they cannot even be escaped. HWP text
         * is full of them, so anything unrepresentable is dropped rather than written as a numeric
         * reference that no parser will accept back.
         */
        private fun isXmlSafe(c: Char): Boolean = when {
            c == '\t' || c == '\n' || c == '\r' -> true
            c.code < 0x20 -> false
            c.code in 0xD800..0xDFFF -> false
            c.code == 0xFFFE || c.code == 0xFFFF -> false
            else -> true
        }

        /** Strips characters XML cannot carry, used before putting HWP text into an element. */
        fun sanitize(value: String): String = value.filter { isXmlSafe(it) }
    }
}
