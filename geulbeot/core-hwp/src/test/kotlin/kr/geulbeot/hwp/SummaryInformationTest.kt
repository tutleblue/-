package kr.geulbeot.hwp

import kr.geulbeot.hwp.hwp5.SummaryInformation
import kr.geulbeot.hwp.model.DocumentSummary
import kotlin.test.Test
import kotlin.test.assertEquals

class SummaryInformationTest {

    @Test
    fun `an OLE property set round trips every string field`() {
        val summary = DocumentSummary(
            title = "측정 결과와 오차 분석",
            subject = "재료역학 실험",
            author = "김진영",
            keywords = "인장강도, 표준편차",
            comments = "3장 초고",
            lastSavedBy = "김진영",
        )
        val bytes = SummaryInformation.write(summary)
        val restored = SummaryInformation.read(bytes)
        assertEquals(summary.title, restored.title)
        assertEquals(summary.subject, restored.subject)
        assertEquals(summary.author, restored.author)
        assertEquals(summary.keywords, restored.keywords)
        assertEquals(summary.comments, restored.comments)
        assertEquals(summary.lastSavedBy, restored.lastSavedBy)
    }

    @Test
    fun `an empty summary produces a property set with no properties`() {
        val restored = SummaryInformation.read(SummaryInformation.write(DocumentSummary()))
        assertEquals(DocumentSummary(), restored)
    }
}
