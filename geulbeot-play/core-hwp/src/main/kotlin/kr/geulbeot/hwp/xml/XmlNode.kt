package kr.geulbeot.hwp.xml

import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayInputStream
import javax.xml.parsers.SAXParserFactory

/**
 * A parsed XML element.
 *
 * SAX is used rather than DOM because it is the one XML API available on both a plain JVM and on
 * Android without adding a dependency. The handler below builds a small tree from it, which is what
 * the OWPML reader actually wants to walk.
 */
class XmlNode(
    val name: String,
    val attributes: Map<String, String>,
) {
    /**
     * Content in document order: each entry is either a [String] of character data or a child
     * [XmlNode]. OWPML mixes them inside a text element - `<hp:t>a<hp:lineBreak/>b</hp:t>` - and the
     * order is the difference between "a, break, b" and "ab with a stray break", so it is kept.
     */
    val content: MutableList<Any> = ArrayList()

    val children: List<XmlNode> get() = content.filterIsInstance<XmlNode>()

    /** Character data directly inside this element, concatenated. */
    val text: String get() = content.filterIsInstance<String>().joinToString("")

    /** Element name without its namespace prefix, e.g. `p` for `hp:p`. */
    val localName: String get() = name.substringAfterLast(':')

    fun child(localName: String): XmlNode? = children.firstOrNull { it.localName == localName }

    fun childrenNamed(localName: String): List<XmlNode> = children.filter { it.localName == localName }

    fun attr(name: String): String? = attributes[name] ?: attributes.entries.firstOrNull {
        it.key.substringAfterLast(':') == name
    }?.value

    fun int(name: String, fallback: Int = 0): Int = attr(name)?.trim()?.toIntOrNull() ?: fallback

    fun long(name: String, fallback: Long = 0): Long = attr(name)?.trim()?.toLongOrNull() ?: fallback

    fun bool(name: String, fallback: Boolean = false): Boolean = when (attr(name)?.trim()?.lowercase()) {
        "1", "true" -> true
        "0", "false" -> false
        else -> fallback
    }

    /** All text in this element and everything under it, in document order. */
    fun deepText(): String = buildString { collectText(this) }

    private fun collectText(sb: StringBuilder) {
        for (item in content) {
            when (item) {
                is String -> sb.append(item)
                is XmlNode -> item.collectText(sb)
            }
        }
    }

    fun forEachDeep(action: (XmlNode) -> Unit) {
        action(this)
        for (c in children) c.forEachDeep(action)
    }

    override fun toString(): String = "<$name ${attributes.size} attrs, ${children.size} children>"

    companion object {
        fun parse(bytes: ByteArray): XmlNode {
            val factory = SAXParserFactory.newInstance()
            factory.isNamespaceAware = false
            // Never resolve external entities: an XML file that arrives as a document should not be
            // able to reach the filesystem or the network.
            runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            val parser = factory.newSAXParser()
            val handler = TreeHandler()
            parser.parse(ByteArrayInputStream(bytes), handler)
            return handler.root ?: XmlNode("empty", emptyMap())
        }
    }

    private class TreeHandler : DefaultHandler() {
        var root: XmlNode? = null
        private val stack = ArrayList<XmlNode>()

        override fun startElement(uri: String?, localName: String?, qName: String, attributes: Attributes) {
            val map = LinkedHashMap<String, String>(attributes.length)
            for (i in 0 until attributes.length) map[attributes.getQName(i)] = attributes.getValue(i)
            val node = XmlNode(qName, map)
            if (stack.isEmpty()) root = node else stack.last().content.add(node)
            stack.add(node)
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            val node = stack.lastOrNull() ?: return
            val chunk = String(ch, start, length)
            val last = node.content.lastOrNull()
            // Long text arrives in several callbacks; merging keeps one entry per text run.
            if (last is String) node.content[node.content.size - 1] = last + chunk else node.content.add(chunk)
        }

        override fun endElement(uri: String?, localName: String?, qName: String?) {
            if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
        }

        override fun resolveEntity(publicId: String?, systemId: String?) = org.xml.sax.InputSource(java.io.StringReader(""))
    }
}
