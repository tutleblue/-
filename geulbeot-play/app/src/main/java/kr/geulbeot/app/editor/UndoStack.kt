package kr.geulbeot.app.editor

import kr.geulbeot.hwp.model.HwpDocument
import kr.geulbeot.hwp.model.PageDef
import kr.geulbeot.hwp.model.Paragraph

/**
 * One restorable state of a section.
 *
 * Undo works per section rather than per document: an edit only ever touches one, and copying a
 * section's paragraphs is cheap enough to do on every step while copying an entire book is not.
 */
private class UndoEntry(
    val label: String,
    val coalesceKey: String?,
    val timestampNanos: Long,
    val sectionIndex: Int,
    val paragraphs: List<Paragraph>,
    val pageDef: PageDef,
    val summaryTitle: String,
    val summaryAuthor: String,
)

/**
 * Undo and redo.
 *
 * Snapshots rather than inverse commands. Inverse commands are smaller but have to be written and
 * kept correct for every operation, and a single wrong inverse silently corrupts a document;
 * snapshots cannot get that wrong. The depth limit keeps the memory cost bounded.
 *
 * Consecutive keystrokes in the same paragraph collapse into one step through [coalesceKey], so
 * undo moves in units a person recognises instead of one character at a time.
 */
class UndoStack(private val maxDepth: Int = 60) {

    private val undo = ArrayDeque<UndoEntry>()
    private val redo = ArrayDeque<UndoEntry>()

    val canUndo: Boolean get() = undo.isNotEmpty()
    val canRedo: Boolean get() = redo.isNotEmpty()

    val undoLabel: String? get() = undo.lastOrNull()?.label
    val redoLabel: String? get() = redo.lastOrNull()?.label

    /**
     * Records the state of a section before an edit.
     *
     * When [coalesceKey] matches the previous entry and it was pushed within [COALESCE_WINDOW_NANOS],
     * nothing is recorded - the two edits become one undo step.
     */
    fun record(document: HwpDocument, sectionIndex: Int, label: String, coalesceKey: String? = null) {
        val section = document.sections.getOrNull(sectionIndex) ?: return
        val now = System.nanoTime()
        val previous = undo.lastOrNull()
        if (
            coalesceKey != null &&
            previous != null &&
            previous.coalesceKey == coalesceKey &&
            previous.sectionIndex == sectionIndex &&
            now - previous.timestampNanos < COALESCE_WINDOW_NANOS
        ) {
            return
        }

        undo.addLast(
            UndoEntry(
                label = label,
                coalesceKey = coalesceKey,
                timestampNanos = now,
                sectionIndex = sectionIndex,
                paragraphs = section.snapshotParagraphs(),
                pageDef = section.pageDef.copy(),
                summaryTitle = document.summary.title,
                summaryAuthor = document.summary.author,
            ),
        )
        while (undo.size > maxDepth) undo.removeFirst()
        redo.clear()
    }

    /** Returns the label of the step that was undone, or null when there was nothing to undo. */
    fun undo(document: HwpDocument): String? {
        val entry = undo.removeLastOrNull() ?: return null
        redo.addLast(capture(document, entry))
        apply(document, entry)
        return entry.label
    }

    fun redo(document: HwpDocument): String? {
        val entry = redo.removeLastOrNull() ?: return null
        undo.addLast(capture(document, entry))
        apply(document, entry)
        return entry.label
    }

    fun clear() {
        undo.clear()
        redo.clear()
    }

    private fun capture(document: HwpDocument, like: UndoEntry): UndoEntry {
        val section = document.sections[like.sectionIndex]
        return UndoEntry(
            label = like.label,
            coalesceKey = null,
            timestampNanos = System.nanoTime(),
            sectionIndex = like.sectionIndex,
            paragraphs = section.snapshotParagraphs(),
            pageDef = section.pageDef.copy(),
            summaryTitle = document.summary.title,
            summaryAuthor = document.summary.author,
        )
    }

    private fun apply(document: HwpDocument, entry: UndoEntry) {
        val section = document.sections.getOrNull(entry.sectionIndex) ?: return
        section.restoreParagraphs(entry.paragraphs)
        section.pageDef = entry.pageDef.copy()
        section.dirty = true
        document.summary.title = entry.summaryTitle
        document.summary.author = entry.summaryAuthor
    }

    companion object {
        /** Typing pauses longer than this start a new undo step. */
        private const val COALESCE_WINDOW_NANOS = 1_500_000_000L
    }
}
