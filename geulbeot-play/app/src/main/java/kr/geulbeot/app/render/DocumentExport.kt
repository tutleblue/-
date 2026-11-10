package kr.geulbeot.app.render

import android.content.Context
import android.graphics.pdf.PdfDocument
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import kr.geulbeot.hwp.model.HwpDocument
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

/**
 * PDF export and printing.
 *
 * Both go through [DocumentPainter], so what is printed is what is exported. Rendering happens on
 * the device with no service involved, which keeps the app's promise that documents do not leave it.
 */
object DocumentExport {

    fun writePdf(document: HwpDocument, out: OutputStream) {
        val painter = DocumentPainter(document)
        val pages = painter.paginate()
        val pdf = PdfDocument()
        try {
            for ((index, page) in pages.withIndex()) {
                val info = PdfDocument.PageInfo.Builder(page.widthPoints, page.heightPoints, index + 1).create()
                val pdfPage = pdf.startPage(info)
                painter.draw(pdfPage.canvas, page)
                pdf.finishPage(pdfPage)
            }
            pdf.writeTo(out)
        } finally {
            pdf.close()
        }
    }

    fun writePdf(document: HwpDocument, file: File) {
        FileOutputStream(file).use { writePdf(document, it) }
    }

    fun pageCount(document: HwpDocument): Int = DocumentPainter(document).paginate().size

    /**
     * Hands the document to the system print dialog.
     *
     * The printed output is regenerated rather than reusing an exported file, so printing after an
     * edit prints the edit.
     */
    fun print(context: Context, document: HwpDocument, jobName: String) {
        val printManager = context.getSystemService(Context.PRINT_SERVICE) as? PrintManager ?: return
        printManager.print(jobName, DocumentPrintAdapter(document, jobName), null)
    }
}

private class DocumentPrintAdapter(
    private val document: HwpDocument,
    private val jobName: String,
) : PrintDocumentAdapter() {

    private var pages: List<DocumentPainter.Page> = emptyList()
    private lateinit var painter: DocumentPainter

    override fun onLayout(
        oldAttributes: PrintAttributes?,
        newAttributes: PrintAttributes?,
        cancellationSignal: CancellationSignal?,
        callback: PrintDocumentAdapter.LayoutResultCallback,
        extras: Bundle?,
    ) {
        if (cancellationSignal?.isCanceled == true) {
            callback.onLayoutCancelled()
            return
        }
        painter = DocumentPainter(document)
        pages = painter.paginate()
        val info = PrintDocumentInfo.Builder(jobName)
            .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
            .setPageCount(pages.size)
            .build()
        // The layout always changes as far as the framework is concerned: the document may have
        // been edited between print attempts, and re-rendering is cheap next to getting it wrong.
        callback.onLayoutFinished(info, true)
    }

    override fun onWrite(
        requestedPages: Array<out PageRange>?,
        destination: ParcelFileDescriptor,
        cancellationSignal: CancellationSignal?,
        callback: PrintDocumentAdapter.WriteResultCallback,
    ) {
        val pdf = PdfDocument()
        try {
            val wanted = requestedPages.orEmpty()
            val written = ArrayList<PageRange>()
            for ((index, page) in pages.withIndex()) {
                if (cancellationSignal?.isCanceled == true) {
                    callback.onWriteCancelled()
                    return
                }
                if (!wanted.any { index >= it.start && index <= it.end } && wanted.isNotEmpty()) continue
                val info = PdfDocument.PageInfo.Builder(page.widthPoints, page.heightPoints, index + 1).create()
                val pdfPage = pdf.startPage(info)
                painter.draw(pdfPage.canvas, page)
                pdf.finishPage(pdfPage)
                written.add(PageRange(index, index))
            }
            FileOutputStream(destination.fileDescriptor).use { pdf.writeTo(it) }
            callback.onWriteFinished(
                if (written.isEmpty()) arrayOf(PageRange.ALL_PAGES) else written.toTypedArray(),
            )
        } catch (e: Exception) {
            callback.onWriteFailed(e.message ?: "인쇄 데이터를 만들지 못했습니다.")
        } finally {
            pdf.close()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                runCatching { destination.close() }
            }
        }
    }
}
