package kr.geulbeot.hwp.api

import kr.geulbeot.hwp.model.HwpDocument
import kr.geulbeot.hwp.model.Paragraph
import kr.geulbeot.hwp.model.TableControl

/** Where a match was found. Table cells are addressed through [cellPath] so they can be shown. */
data class SearchHit(
    val sectionIndex: Int,
    val paragraphIndex: Int,
    val start: Int,
    val end: Int,
    val cellPath: CellPath? = null,
) {
    /** The paragraph this hit lives in, resolved against a document. */
    fun paragraphIn(document: HwpDocument): Paragraph? {
        val section = document.sections.getOrNull(sectionIndex) ?: return null
        val outer = section.paragraphs.getOrNull(paragraphIndex) ?: return null
        val path = cellPath ?: return outer
        val table = outer.controls().filterIsInstance<TableControl>().getOrNull(path.tableIndex) ?: return null
        val cell = table.rows.getOrNull(path.row)?.cells?.getOrNull(path.column) ?: return null
        return cell.paragraphs.getOrNull(path.paragraphIndex)
    }
}

data class CellPath(val tableIndex: Int, val row: Int, val column: Int, val paragraphIndex: Int)

data class SearchOptions(
    val caseSensitive: Boolean = false,
    val wholeWord: Boolean = false,
    val useRegex: Boolean = false,
    val searchTables: Boolean = true,
)

/**
 * Find and replace across a document.
 *
 * Searching walks table cells as well as body paragraphs - a table's text is not part of the
 * paragraph it is anchored in, so a naive search over paragraph text alone silently misses it,
 * which in a document full of tables is most of the content.
 */
object DocumentSearch {

    fun find(document: HwpDocument, query: String, options: SearchOptions = SearchOptions()): List<SearchHit> {
        if (query.isEmpty()) return emptyList()
        val regex = buildRegex(query, options) ?: return emptyList()
        val hits = ArrayList<SearchHit>()

        for ((sectionIndex, section) in document.sections.withIndex()) {
            for ((paragraphIndex, paragraph) in section.paragraphs.withIndex()) {
                addMatches(hits, regex, paragraph.text, sectionIndex, paragraphIndex, null)
                if (!options.searchTables) continue
                val tables = paragraph.controls().filterIsInstance<TableControl>()
                for ((tableIndex, table) in tables.withIndex()) {
                    for ((rowIndex, row) in table.rows.withIndex()) {
                        for ((columnIndex, cell) in row.cells.withIndex()) {
                            for ((cellParagraphIndex, cellParagraph) in cell.paragraphs.withIndex()) {
                                addMatches(
                                    hits,
                                    regex,
                                    cellParagraph.text,
                                    sectionIndex,
                                    paragraphIndex,
                                    CellPath(tableIndex, rowIndex, columnIndex, cellParagraphIndex),
                                )
                            }
                        }
                    }
                }
            }
        }
        return hits
    }

    /** Replaces one match, keeping the formatting of the text around it. Returns true if applied. */
    fun replace(document: HwpDocument, hit: SearchHit, replacement: String): Boolean {
        val paragraph = hit.paragraphIn(document) ?: return false
        val text = paragraph.text
        if (hit.start < 0 || hit.end > text.length || hit.start > hit.end) return false
        val updated = text.substring(0, hit.start) + replacement + text.substring(hit.end)
        DocumentEditor(document).replaceParagraphText(paragraph, updated)
        document.sections.getOrNull(hit.sectionIndex)?.dirty = true
        return true
    }

    /**
     * Replaces every match. Hits are applied from the end of each paragraph backwards so that
     * earlier offsets stay valid as the text changes length.
     */
    fun replaceAll(
        document: HwpDocument,
        query: String,
        replacement: String,
        options: SearchOptions = SearchOptions(),
    ): Int {
        val hits = find(document, query, options)
        var applied = 0
        for (hit in hits.sortedWith(compareByDescending<SearchHit> { it.paragraphIndex }.thenByDescending { it.start })) {
            if (replace(document, hit, replacement)) applied++
        }
        return applied
    }

    private fun addMatches(
        into: MutableList<SearchHit>,
        regex: Regex,
        text: String,
        sectionIndex: Int,
        paragraphIndex: Int,
        cellPath: CellPath?,
    ) {
        if (text.isEmpty()) return
        for (match in regex.findAll(text)) {
            if (match.value.isEmpty()) continue
            into.add(SearchHit(sectionIndex, paragraphIndex, match.range.first, match.range.last + 1, cellPath))
        }
    }

    private fun buildRegex(query: String, options: SearchOptions): Regex? {
        val flags = if (options.caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
        val pattern = when {
            options.useRegex -> query
            options.wholeWord -> "\\b" + Regex.escape(query) + "\\b"
            else -> Regex.escape(query)
        }
        return try {
            Regex(pattern, flags)
        } catch (_: Exception) {
            // An unfinished regular expression while the user is still typing is not an error.
            null
        }
    }
}
