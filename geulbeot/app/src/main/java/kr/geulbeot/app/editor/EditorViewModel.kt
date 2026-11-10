package kr.geulbeot.app.editor

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kr.geulbeot.app.data.DocumentStore
import kr.geulbeot.app.data.RecentDocument
import kr.geulbeot.app.data.RecentDocuments
import kr.geulbeot.app.data.Settings
import kr.geulbeot.app.render.DocumentExport
import kr.geulbeot.hwp.api.DocumentEditor
import kr.geulbeot.hwp.api.DocumentSearch
import kr.geulbeot.hwp.api.GeulbeotDocuments
import kr.geulbeot.hwp.api.SearchHit
import kr.geulbeot.hwp.api.SearchOptions
import kr.geulbeot.hwp.api.charShapeAtDisplay
import kr.geulbeot.hwp.model.CharShape
import kr.geulbeot.hwp.model.DocumentFormat
import kr.geulbeot.hwp.model.DocumentStatistics
import kr.geulbeot.hwp.model.HwpDocument
import kr.geulbeot.hwp.model.PageDef
import kr.geulbeot.hwp.model.ParaAlign
import kr.geulbeot.hwp.model.ParaShape
import kr.geulbeot.hwp.model.Paragraph
import kr.geulbeot.hwp.model.RestrictedDocumentException
import kr.geulbeot.hwp.model.Section
import kr.geulbeot.hwp.model.TableControl
import kr.geulbeot.hwp.util.HwpFormatException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Which paragraph the caret is in, including when it is inside a table cell. */
data class ParagraphAddress(
    val sectionIndex: Int,
    val paragraphIndex: Int,
    val tableIndex: Int = -1,
    val row: Int = -1,
    val column: Int = -1,
    val cellParagraphIndex: Int = -1,
) {
    val isInTable: Boolean get() = tableIndex >= 0
}

enum class SaveState { SAVED, MODIFIED, SAVING }

/** A message for the user: something finished, or something went wrong and here is what to do. */
data class UserMessage(val text: String, val actionLabel: String? = null, val action: (() -> Unit)? = null)

/**
 * State and behaviour of the editor.
 *
 * The document model is plain Kotlin and does not notify anyone when it changes, so [revision] is
 * bumped after every mutation and the UI reads it. That is one deliberate line of bookkeeping in
 * exchange for keeping the format engine free of any UI framework.
 */
class EditorViewModel(application: Application) : AndroidViewModel(application) {

    private val store = DocumentStore(application)
    private val recents = RecentDocuments(application)
    val settings = Settings(application)

    private val undoStack = UndoStack()

    var document by mutableStateOf<HwpDocument?>(null)
        private set
    var documentUri by mutableStateOf<Uri?>(null)
        private set
    var displayName by mutableStateOf("제목 없음")
        private set
    var saveState by mutableStateOf(SaveState.SAVED)
        private set
    var busy by mutableStateOf(false)
        private set
    var message by mutableStateOf<UserMessage?>(null)

    /** Bumped whenever the document changes, so composables that read it recompose. */
    var revision by mutableIntStateOf(0)
        private set

    var caret by mutableStateOf(ParagraphAddress(0, 0))
        private set
    var selectionStart by mutableIntStateOf(0)
        private set
    var selectionEnd by mutableIntStateOf(0)
        private set

    // ---- find and replace --------------------------------------------------------------------
    var findVisible by mutableStateOf(false)
    var findQuery by mutableStateOf("")
    var replaceText by mutableStateOf("")
    var findOptions by mutableStateOf(SearchOptions())
    var findHits by mutableStateOf<List<SearchHit>>(emptyList())
        private set
    var currentHit by mutableIntStateOf(-1)
        private set

    val canUndo: Boolean get() = revision.let { undoStack.canUndo }
    val canRedo: Boolean get() = revision.let { undoStack.canRedo }

    val isDirty: Boolean get() = saveState == SaveState.MODIFIED

    private val autoSaveKey: String get() = documentUri?.toString() ?: "새 문서"

    // ---- document lifecycle ------------------------------------------------------------------

    fun newDocument() {
        document = GeulbeotDocuments.newDocument().also {
            // The preference decides what an unsaved document will be written as, which is also
            // what the save sheet offers first.
            it.format = settings.defaultNewFormat
        }
        documentUri = null
        displayName = "제목 없음"
        saveState = SaveState.SAVED
        undoStack.clear()
        caret = ParagraphAddress(0, 0)
        selectionStart = 0
        selectionEnd = 0
        bump()
    }

    fun open(uri: Uri) {
        viewModelScope.launch {
            busy = true
            try {
                val opened = withContext(Dispatchers.IO) {
                    store.persistPermission(uri, writable = true)
                    store.open(uri)
                }
                document = opened.document
                documentUri = opened.uri
                displayName = opened.displayName
                saveState = SaveState.SAVED
                undoStack.clear()
                caret = ParagraphAddress(0, 0)
                selectionStart = 0
                selectionEnd = 0
                withContext(Dispatchers.IO) {
                    recents.record(
                        RecentDocument(
                            uri = uri.toString(),
                            displayName = opened.displayName,
                            format = opened.document.format.name,
                            lastOpenedMillis = System.currentTimeMillis(),
                            sizeBytes = opened.sizeBytes,
                            snippet = opened.document.plainText().take(120).replace('\n', ' ').trim(),
                        ),
                    )
                }
                bump()
                if (store.hasAutoSave(autoSaveKey)) {
                    message = UserMessage(
                        "저장하지 않은 편집 내용이 남아 있습니다.",
                        actionLabel = "복구",
                        action = { restoreAutoSave() },
                    )
                }
            } catch (e: RestrictedDocumentException) {
                message = UserMessage(e.restriction.message)
            } catch (e: HwpFormatException) {
                message = UserMessage(e.message ?: "문서를 열 수 없습니다.")
            } catch (e: Exception) {
                message = UserMessage("문서를 열지 못했습니다: ${e.message ?: "알 수 없는 오류"}")
            } finally {
                busy = false
            }
        }
    }

    /** Opens bytes handed over by another app, which may not be a file we can write back to. */
    fun openBytes(bytes: ByteArray, name: String, uri: Uri?) {
        try {
            document = GeulbeotDocuments.open(bytes, name)
            documentUri = uri
            displayName = name
            saveState = SaveState.SAVED
            undoStack.clear()
            bump()
        } catch (e: RestrictedDocumentException) {
            message = UserMessage(e.restriction.message)
        } catch (e: Exception) {
            message = UserMessage(e.message ?: "문서를 열 수 없습니다.")
        }
    }

    fun close() {
        document = null
        documentUri = null
        displayName = "제목 없음"
        saveState = SaveState.SAVED
        undoStack.clear()
        bump()
    }

    /**
     * Saves back to the file the document came from.
     *
     * Returns false when there is nowhere to save to yet, which is the caller's cue to ask for a
     * location instead.
     */
    fun save(onNeedsLocation: () -> Unit) {
        val doc = document ?: return
        val uri = documentUri
        if (uri == null) {
            onNeedsLocation()
            return
        }
        viewModelScope.launch {
            saveState = SaveState.SAVING
            try {
                val format = GeulbeotDocuments.defaultSaveFormat(doc)
                val size = withContext(Dispatchers.IO) { store.save(doc, uri, format) }
                saveState = SaveState.SAVED
                withContext(Dispatchers.IO) {
                    store.clearAutoSave(autoSaveKey)
                    recents.record(
                        RecentDocument(
                            uri = uri.toString(),
                            displayName = displayName,
                            format = format.name,
                            lastOpenedMillis = System.currentTimeMillis(),
                            sizeBytes = size.toLong(),
                            snippet = doc.plainText().take(120).replace('\n', ' ').trim(),
                        ),
                    )
                }
                message = UserMessage("저장했습니다.")
            } catch (e: Exception) {
                saveState = SaveState.MODIFIED
                message = UserMessage("저장하지 못했습니다: ${e.message ?: "알 수 없는 오류"}")
            }
            bump()
        }
    }

    fun saveAs(uri: Uri, format: DocumentFormat) {
        val doc = document ?: return
        viewModelScope.launch {
            saveState = SaveState.SAVING
            try {
                val size = withContext(Dispatchers.IO) {
                    store.persistPermission(uri, writable = true)
                    store.save(doc, uri, format)
                }
                documentUri = uri
                displayName = withContext(Dispatchers.IO) { store.displayName(uri) }
                saveState = SaveState.SAVED
                withContext(Dispatchers.IO) {
                    store.clearAutoSave(autoSaveKey)
                    recents.record(
                        RecentDocument(
                            uri = uri.toString(),
                            displayName = displayName,
                            format = format.name,
                            lastOpenedMillis = System.currentTimeMillis(),
                            sizeBytes = size.toLong(),
                            snippet = doc.plainText().take(120).replace('\n', ' ').trim(),
                        ),
                    )
                }
                message = UserMessage("${format.displayName} 형식으로 저장했습니다.")
            } catch (e: Exception) {
                saveState = SaveState.MODIFIED
                message = UserMessage("저장하지 못했습니다: ${e.message ?: "알 수 없는 오류"}")
            }
            bump()
        }
    }

    private fun restoreAutoSave() {
        viewModelScope.launch {
            val recovered = withContext(Dispatchers.IO) { store.readAutoSave(autoSaveKey) }
            if (recovered == null) {
                message = UserMessage("복구할 내용을 찾지 못했습니다.")
                return@launch
            }
            document = recovered
            saveState = SaveState.MODIFIED
            undoStack.clear()
            bump()
            message = UserMessage("편집 내용을 복구했습니다.")
        }
    }

    private fun scheduleAutoSave() {
        if (!settings.autoSaveEnabled) return
        val doc = document ?: return
        viewModelScope.launch(Dispatchers.IO) { store.writeAutoSave(autoSaveKey, doc) }
    }

    // ---- addressing ----------------------------------------------------------------------------

    fun section(index: Int = caret.sectionIndex): Section? = document?.sections?.getOrNull(index)

    fun paragraphAt(address: ParagraphAddress): Paragraph? {
        val section = document?.sections?.getOrNull(address.sectionIndex) ?: return null
        val outer = section.paragraphs.getOrNull(address.paragraphIndex) ?: return null
        if (!address.isInTable) return outer
        val table = outer.controls().filterIsInstance<TableControl>().getOrNull(address.tableIndex) ?: return null
        val cell = table.rows.getOrNull(address.row)?.cells?.getOrNull(address.column) ?: return null
        return cell.paragraphs.getOrNull(address.cellParagraphIndex)
    }

    val focusedParagraph: Paragraph? get() = paragraphAt(caret)

    fun setCaret(address: ParagraphAddress, start: Int, end: Int) {
        caret = address
        selectionStart = start
        selectionEnd = end
    }

    fun setSelection(start: Int, end: Int) {
        selectionStart = start
        selectionEnd = end
    }

    /** The range formatting applies to: the selection, or the whole paragraph when nothing is selected. */
    private fun formattingRange(paragraph: Paragraph): Pair<Int, Int> =
        if (selectionEnd > selectionStart) {
            selectionStart to selectionEnd
        } else {
            0 to paragraph.displayLength
        }

    // ---- editing --------------------------------------------------------------------------------

    private fun mutate(label: String, coalesceKey: String? = null, block: (DocumentEditor) -> Unit) {
        val doc = document ?: return
        val editor = DocumentEditor(doc)
        undoStack.record(doc, caret.sectionIndex, label, coalesceKey)
        block(editor)
        saveState = SaveState.MODIFIED
        scheduleAutoSave()
        bump()
    }

    fun onParagraphTextChanged(address: ParagraphAddress, newText: String) {
        val paragraph = paragraphAt(address) ?: return
        if (paragraph.text == newText) return
        caret = address
        mutate("입력", coalesceKey = "text:${address.hashCode()}") { it.replaceParagraphText(paragraph, newText) }
    }

    fun splitParagraphAtCaret() {
        val section = section() ?: return
        if (caret.isInTable) {
            // Inside a cell, Enter adds a paragraph to that cell rather than to the body.
            val paragraph = focusedParagraph ?: return
            mutate("문단 나누기") {
                val cellParagraphs = cellParagraphsFor(caret) ?: return@mutate
                val index = caret.cellParagraphIndex
                val tail = paragraph.text.substring(selectionStart.coerceIn(0, paragraph.text.length))
                it.replaceParagraphText(paragraph, paragraph.text.take(selectionStart))
                val next = Paragraph(paragraph.paraShapeId, paragraph.styleId)
                next.appendText(tail, paragraph.charShapeAtDisplay(selectionStart))
                cellParagraphs.add(index + 1, next)
            }
            caret = caret.copy(cellParagraphIndex = caret.cellParagraphIndex + 1)
            setSelection(0, 0)
            return
        }
        mutate("문단 나누기") { it.splitParagraph(section, caret.paragraphIndex, selectionStart) }
        caret = caret.copy(paragraphIndex = caret.paragraphIndex + 1)
        setSelection(0, 0)
    }

    /** Backspace at the very start of a paragraph joins it with the one above. */
    fun mergeWithPrevious(): Boolean {
        val section = section() ?: return false
        if (caret.isInTable || caret.paragraphIndex == 0) return false
        var joinOffset = 0
        mutate("문단 합치기") { joinOffset = it.mergeWithPrevious(section, caret.paragraphIndex) }
        caret = caret.copy(paragraphIndex = caret.paragraphIndex - 1)
        setSelection(joinOffset, joinOffset)
        return true
    }

    fun insertParagraphAfterCaret() {
        val section = section() ?: return
        mutate("문단 추가") { it.insertParagraphAfter(section, caret.paragraphIndex) }
        caret = caret.copy(paragraphIndex = caret.paragraphIndex + 1)
        setSelection(0, 0)
    }

    fun deleteFocusedParagraph() {
        val section = section() ?: return
        if (caret.isInTable) return
        mutate("문단 삭제") {
            if (!it.deleteParagraph(section, caret.paragraphIndex)) {
                message = UserMessage("마지막 문단은 지울 수 없습니다.")
            }
        }
        caret = caret.copy(paragraphIndex = caret.paragraphIndex.coerceAtMost(section.paragraphs.size - 1))
    }

    private fun cellParagraphsFor(address: ParagraphAddress): MutableList<Paragraph>? {
        val section = document?.sections?.getOrNull(address.sectionIndex) ?: return null
        val outer = section.paragraphs.getOrNull(address.paragraphIndex) ?: return null
        val table = outer.controls().filterIsInstance<TableControl>().getOrNull(address.tableIndex) ?: return null
        return table.rows.getOrNull(address.row)?.cells?.getOrNull(address.column)?.paragraphs
    }

    // ---- character formatting -----------------------------------------------------------------

    private fun withRange(label: String, block: (DocumentEditor, Paragraph, Int, Int) -> Unit) {
        val paragraph = focusedParagraph ?: return
        val (start, end) = formattingRange(paragraph)
        if (end <= start) return
        mutate(label) { block(it, paragraph, start, end) }
    }

    fun toggleBold() = withRange("진하게") { e, p, s, t -> e.toggleBold(p, s, t) }
    fun toggleItalic() = withRange("기울임") { e, p, s, t -> e.toggleItalic(p, s, t) }
    fun toggleUnderline() = withRange("밑줄") { e, p, s, t -> e.toggleUnderline(p, s, t) }
    fun toggleStrikeOut() = withRange("취소선") { e, p, s, t -> e.toggleStrikeOut(p, s, t) }
    fun setFontSize(points: Double) = withRange("글자 크기") { e, p, s, t -> e.setFontSize(p, s, t, points) }
    fun setFont(name: String) = withRange("글꼴") { e, p, s, t -> e.setFont(p, s, t, name) }
    fun setTextColor(colorRef: Int) = withRange("글자 색") { e, p, s, t -> e.setTextColor(p, s, t, colorRef) }
    fun setHighlight(colorRef: Int) = withRange("음영 색") { e, p, s, t -> e.setHighlight(p, s, t, colorRef) }
    fun setSuperscript(on: Boolean) = withRange("위 첨자") { e, p, s, t -> e.setSuperscript(p, s, t, on) }
    fun setSubscript(on: Boolean) = withRange("아래 첨자") { e, p, s, t -> e.setSubscript(p, s, t, on) }

    /** The character shape shown in the toolbar: what is selected, or what typing would produce. */
    fun currentCharShape(): CharShape {
        val doc = document ?: return CharShape()
        val paragraph = focusedParagraph ?: return CharShape()
        val offset = if (selectionEnd > selectionStart) selectionStart else (selectionStart - 1).coerceAtLeast(0)
        return doc.charShapeOrDefault(paragraph.charShapeAtDisplay(offset))
    }

    fun currentParaShape(): ParaShape {
        val doc = document ?: return ParaShape()
        val paragraph = focusedParagraph ?: return ParaShape()
        return doc.paraShapeOrDefault(paragraph.paraShapeId)
    }

    fun currentFontName(): String {
        val doc = document ?: return HwpDocument.DEFAULT_BODY_FONT
        return doc.fontName(currentCharShape().fontIds.getOrElse(0) { 0 })
    }

    // ---- paragraph formatting ------------------------------------------------------------------

    fun setAlign(align: ParaAlign) {
        val paragraph = focusedParagraph ?: return
        mutate("문단 정렬") { it.setAlign(paragraph, align) }
    }

    fun setLineSpacing(percent: Int) {
        val paragraph = focusedParagraph ?: return
        mutate("줄 간격") { it.setLineSpacing(paragraph, percent) }
    }

    fun setParagraphSpacing(beforePt: Double, afterPt: Double) {
        val paragraph = focusedParagraph ?: return
        mutate("문단 간격") { it.setParagraphSpacing(paragraph, beforePt, afterPt) }
    }

    fun indent(steps: Int) {
        val paragraph = focusedParagraph ?: return
        mutate(if (steps > 0) "들여쓰기" else "내어쓰기") { it.indent(paragraph, steps) }
    }

    fun setFirstLineIndent(points: Double) {
        val paragraph = focusedParagraph ?: return
        mutate("첫 줄 들여쓰기") { it.setFirstLineIndent(paragraph, points) }
    }

    fun applyStyle(styleId: Int) {
        val doc = document ?: return
        val paragraph = focusedParagraph ?: return
        val style = doc.styles.getOrNull(styleId) ?: return
        mutate("스타일 적용") {
            paragraph.paraShapeId = style.paraShapeId
            paragraph.styleId = styleId
            it.applyCharShape(paragraph, 0, paragraph.displayLength) {
                doc.charShapeOrDefault(style.charShapeId).copyShape()
            }
            paragraph.dirty = true
        }
    }

    // ---- objects --------------------------------------------------------------------------------

    fun insertTable(rows: Int, columns: Int) {
        val section = section() ?: return
        val paragraph = focusedParagraph ?: return
        if (caret.isInTable) {
            message = UserMessage("표 안에는 표를 넣을 수 없습니다.")
            return
        }
        mutate("표 넣기") { it.insertTable(section, paragraph, selectionStart, rows, columns) }
    }

    fun insertPicture(bytes: ByteArray, extension: String, widthHwpUnit: Int, heightHwpUnit: Int) {
        val section = section() ?: return
        val paragraph = focusedParagraph ?: return
        mutate("그림 넣기") {
            it.insertPicture(section, paragraph, selectionStart, bytes, extension, widthHwpUnit, heightHwpUnit)
        }
    }

    fun focusedTable(): TableControl? {
        if (!caret.isInTable) return null
        val section = document?.sections?.getOrNull(caret.sectionIndex) ?: return null
        val outer = section.paragraphs.getOrNull(caret.paragraphIndex) ?: return null
        return outer.controls().filterIsInstance<TableControl>().getOrNull(caret.tableIndex)
    }

    fun addTableRow() {
        val table = focusedTable() ?: return
        mutate("줄 추가") { it.addTableRow(table, caret.row) }
    }

    fun addTableColumn() {
        val table = focusedTable() ?: return
        mutate("칸 추가") { it.addTableColumn(table, caret.column) }
    }

    fun removeTableRow() {
        val table = focusedTable() ?: return
        mutate("줄 삭제") { it.removeTableRow(table, caret.row) }
        caret = caret.copy(row = caret.row.coerceAtMost((table.rows.size - 1).coerceAtLeast(0)))
    }

    fun removeTableColumn() {
        val table = focusedTable() ?: return
        mutate("칸 삭제") { it.removeTableColumn(table, caret.column) }
        caret = caret.copy(column = caret.column.coerceAtMost((table.columnCount - 1).coerceAtLeast(0)))
    }

    // ---- page and metadata -----------------------------------------------------------------------

    fun applyPageSetup(pageDef: PageDef) {
        val doc = document ?: return
        val section = section() ?: return
        undoStack.record(doc, caret.sectionIndex, "쪽 설정")
        section.pageDef = pageDef
        section.dirty = true
        saveState = SaveState.MODIFIED
        scheduleAutoSave()
        bump()
    }

    fun applySummary(title: String, author: String, subject: String, keywords: String, comments: String) {
        val doc = document ?: return
        undoStack.record(doc, caret.sectionIndex, "문서 정보")
        doc.summary.title = title
        doc.summary.author = author
        doc.summary.subject = subject
        doc.summary.keywords = keywords
        doc.summary.comments = comments
        doc.docInfoDirty = true
        saveState = SaveState.MODIFIED
        bump()
    }

    fun statistics(): DocumentStatistics? = document?.statistics()

    // ---- export, share and print -------------------------------------------------------------

    /** Renders the document to PDF and writes it to a location the user chose. */
    fun exportPdfTo(uri: Uri) {
        val doc = document ?: return
        viewModelScope.launch {
            busy = true
            try {
                withContext(Dispatchers.IO) {
                    val bytes = java.io.ByteArrayOutputStream().also { DocumentExport.writePdf(doc, it) }.toByteArray()
                    store.writeBytes(uri, bytes)
                }
                message = UserMessage("PDF로 내보냈습니다.")
            } catch (e: Exception) {
                message = UserMessage("PDF로 내보내지 못했습니다: ${e.message ?: "알 수 없는 오류"}")
            } finally {
                busy = false
            }
        }
    }

    /**
     * Writes a copy into the app's cache and hands it to another app.
     *
     * The copy lives in a directory the FileProvider exposes, and the grant is per-URI and
     * temporary, so sharing a document does not open the app's storage to anything else.
     */
    fun shareAs(context: android.content.Context, format: DocumentFormat?) {
        val doc = document ?: return
        viewModelScope.launch {
            busy = true
            try {
                val base = displayName.substringBeforeLast('.', displayName)
                val file = withContext(Dispatchers.IO) {
                    if (format == null) {
                        val target = store.createExportFile("$base.pdf")
                        DocumentExport.writePdf(doc, target)
                        target
                    } else {
                        val target = store.createExportFile("$base.${format.extension}")
                        target.writeBytes(GeulbeotDocuments.save(doc, format))
                        target
                    }
                }
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file,
                )
                val mime = if (format == null) "application/pdf" else format.mimeType
                val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = mime
                    putExtra(android.content.Intent.EXTRA_STREAM, uri)
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(android.content.Intent.createChooser(intent, "문서 보내기"))
            } catch (e: Exception) {
                message = UserMessage("공유하지 못했습니다: ${e.message ?: "알 수 없는 오류"}")
            } finally {
                busy = false
            }
        }
    }

    fun print(context: android.content.Context) {
        val doc = document ?: return
        runCatching { DocumentExport.print(context, doc, displayName) }
            .onFailure { message = UserMessage("인쇄를 시작하지 못했습니다: ${it.message ?: "알 수 없는 오류"}") }
    }

    /** Reads an image the user picked and places it at the caret. */
    fun insertPictureFrom(uri: Uri) {
        viewModelScope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) { store.readBytes(uri) }
                if (bytes.size > MAX_IMAGE_BYTES) {
                    message = UserMessage("그림이 너무 큽니다. 20MB 이하 파일을 넣어 주세요.")
                    return@launch
                }
                val name = withContext(Dispatchers.IO) { store.displayName(uri) }
                val extension = name.substringAfterLast('.', "png").lowercase()
                val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                val pixelWidth = options.outWidth.coerceAtLeast(1)
                val pixelHeight = options.outHeight.coerceAtLeast(1)

                // Fit the picture to the text column, keeping its proportions. Dropping a phone
                // photo in at its own pixel size would be metres wide on the page.
                val columnWidth = section()?.pageDef?.contentWidth ?: kr.geulbeot.hwp.util.HwpUnit.A4_WIDTH
                val maxWidth = (columnWidth * 0.9).toInt()
                val scale = minOf(1.0, maxWidth.toDouble() / (pixelWidth * PIXELS_TO_HWPUNIT))
                val widthUnits = (pixelWidth * PIXELS_TO_HWPUNIT * scale).toInt().coerceAtLeast(1000)
                val heightUnits = (pixelHeight * PIXELS_TO_HWPUNIT * scale).toInt().coerceAtLeast(1000)

                insertPicture(bytes, extension, widthUnits, heightUnits)
            } catch (e: Exception) {
                message = UserMessage("그림을 넣지 못했습니다: ${e.message ?: "알 수 없는 오류"}")
            }
        }
    }

    private companion object {
        const val MAX_IMAGE_BYTES = 20 * 1024 * 1024

        /** Treat an image pixel as 1/96 inch, the usual screen assumption. 7200/96 = 75. */
        const val PIXELS_TO_HWPUNIT = 75.0
    }

    // ---- undo ------------------------------------------------------------------------------------

    fun undo() {
        val doc = document ?: return
        val label = undoStack.undo(doc)
        if (label == null) {
            message = UserMessage("되돌릴 작업이 없습니다.")
        } else {
            saveState = SaveState.MODIFIED
            clampCaret()
            bump()
        }
    }

    fun redo() {
        val doc = document ?: return
        val label = undoStack.redo(doc)
        if (label == null) {
            message = UserMessage("다시 실행할 작업이 없습니다.")
        } else {
            saveState = SaveState.MODIFIED
            clampCaret()
            bump()
        }
    }

    private fun clampCaret() {
        val section = section() ?: return
        val index = caret.paragraphIndex.coerceIn(0, (section.paragraphs.size - 1).coerceAtLeast(0))
        caret = caret.copy(paragraphIndex = index)
        val length = paragraphAt(caret)?.displayLength ?: 0
        selectionStart = selectionStart.coerceIn(0, length)
        selectionEnd = selectionEnd.coerceIn(selectionStart, length)
    }

    // ---- find and replace --------------------------------------------------------------------------

    fun runFind() {
        val doc = document ?: return
        findHits = DocumentSearch.find(doc, findQuery, findOptions)
        currentHit = if (findHits.isEmpty()) -1 else 0
        moveCaretToCurrentHit()
        bump()
    }

    fun findNext() {
        if (findHits.isEmpty()) return
        currentHit = (currentHit + 1).mod(findHits.size)
        moveCaretToCurrentHit()
    }

    fun findPrevious() {
        if (findHits.isEmpty()) return
        currentHit = (currentHit - 1).mod(findHits.size)
        moveCaretToCurrentHit()
    }

    private fun moveCaretToCurrentHit() {
        val hit = findHits.getOrNull(currentHit) ?: return
        caret = ParagraphAddress(
            sectionIndex = hit.sectionIndex,
            paragraphIndex = hit.paragraphIndex,
            tableIndex = hit.cellPath?.tableIndex ?: -1,
            row = hit.cellPath?.row ?: -1,
            column = hit.cellPath?.column ?: -1,
            cellParagraphIndex = hit.cellPath?.paragraphIndex ?: -1,
        )
        selectionStart = hit.start
        selectionEnd = hit.end
    }

    fun replaceCurrent() {
        val doc = document ?: return
        val hit = findHits.getOrNull(currentHit) ?: return
        undoStack.record(doc, hit.sectionIndex, "바꾸기")
        if (DocumentSearch.replace(doc, hit, replaceText)) {
            saveState = SaveState.MODIFIED
            runFind()
            message = UserMessage("한 곳을 바꿨습니다.")
        }
    }

    fun replaceAll() {
        val doc = document ?: return
        if (findQuery.isEmpty()) return
        undoStack.record(doc, caret.sectionIndex, "모두 바꾸기")
        val count = DocumentSearch.replaceAll(doc, findQuery, replaceText, findOptions)
        saveState = SaveState.MODIFIED
        runFind()
        message = UserMessage(if (count == 0) "바꿀 내용을 찾지 못했습니다." else "${count}곳을 바꿨습니다.")
    }

    fun closeFind() {
        findVisible = false
        findHits = emptyList()
        currentHit = -1
        bump()
    }

    /** Where the matches are in a given paragraph, so the editor can highlight them. */
    fun hitsIn(address: ParagraphAddress): List<IntRange> = findHits.filter {
        it.sectionIndex == address.sectionIndex &&
            it.paragraphIndex == address.paragraphIndex &&
            (it.cellPath?.tableIndex ?: -1) == address.tableIndex &&
            (it.cellPath?.row ?: -1) == address.row &&
            (it.cellPath?.column ?: -1) == address.column &&
            (it.cellPath?.paragraphIndex ?: -1) == address.cellParagraphIndex
    }.map { it.start until it.end }

    // ---- view settings ---------------------------------------------------------------------
    //
    // Preferences are not observable state, so the values the toolbar shows are mirrored here and
    // the revision counter carries the change into recomposition.

    var zoomPercent by mutableIntStateOf(settings.zoomPercent)
        private set
    var pageLayoutView by mutableStateOf(settings.pageLayoutView)
        private set
    var themeChoice by mutableStateOf(settings.theme)
        private set

    fun setTheme(choice: kr.geulbeot.app.ui.theme.ThemeChoice) {
        settings.theme = choice
        themeChoice = choice
    }

    fun setZoom(percent: Int) {
        val clamped = percent.coerceIn(50, 300)
        settings.zoomPercent = clamped
        zoomPercent = clamped
    }

    // Named setPageLayout, not setPageLayoutView: the latter is the JVM name Kotlin already gives
    // the property's own setter, and two declarations cannot share one signature.
    fun setPageLayout(enabled: Boolean) {
        settings.pageLayoutView = enabled
        pageLayoutView = enabled
    }

    private fun bump() {
        revision++
    }

    fun consumeMessage() {
        message = null
    }
}
