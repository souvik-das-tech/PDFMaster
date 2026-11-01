package com.bbcl.pdfmaster

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Edit PDF: finding text runs, replacing, deleting and adding text. */
@RunWith(AndroidJUnit4::class)
class TextEditTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var dir: File
    private val noProgress: Progress = { _, _ -> }

    @Before
    fun setUp() {
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(context)
        Store.init(context)
        dir = File(context.cacheDir, "textedit").apply { deleteRecursively(); mkdirs() }
    }

    /** A CV-like page: plain lines, a two-column row, and a line drawn with TJ kerning. */
    private fun cv(): File = File(dir, "cv.pdf").also { f ->
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle.A4)
            doc.addPage(page)
            PDPageContentStream(doc, page).use { cs ->
                fun line(x: Float, y: Float, text: String, font: PDType1Font = PDType1Font.HELVETICA) {
                    cs.beginText(); cs.setFont(font, 12f); cs.newLineAtOffset(x, y); cs.showText(text); cs.endText()
                }
                line(50f, 780f, "Rahim Uddin", PDType1Font.HELVETICA_BOLD)
                line(50f, 750f, "Experience: 2 years")
                line(50f, 720f, "Skills")
                line(300f, 720f, "Kotlin, Android")
                cs.beginText(); cs.setFont(PDType1Font.TIMES_ROMAN, 12f); cs.newLineAtOffset(50f, 690f)
                cs.showTextWithPositioning(arrayOf<Any>("Dha", -20f, "ka, Bangladesh"))
                cs.endText()
            }
            doc.save(f)
        }
    }

    private fun edit(run: TextRun, text: String) = TextEdit(
        0, run, text, run.x, run.baseline, run.size, run.colorArgb, run.bold, run.italic, run.family, keepOriginalFont = true,
    )

    @Test
    fun replacesDeletesAndAddsText() {
        val src = PdfSource(cv())
        val page = PdfText.runs(src, 0)
        val byText = page.runs.associateBy { it.text }
        assertTrue(page.runs.map { it.text }.toString(), byText.keys.containsAll(listOf("Rahim Uddin", "Experience: 2 years", "Skills", "Kotlin, Android")))
        val name = byText.getValue("Rahim Uddin")
        assertTrue(name.bold)
        assertEquals(12f, name.size, 0.5f)
        assertEquals(50f, name.x, 1f)
        assertEquals(842f - 780f, name.baseline, 1f)
        val city = page.runs.first { it.text.startsWith("Dha") }
        assertEquals(FontFamily.SERIF, city.family)

        val out = File(dir, "edited.pdf")
        PdfText.apply(
            src,
            listOf(
                edit(byText.getValue("Experience: 2 years"), "Experience: 5 years"),
                edit(byText.getValue("Kotlin, Android"), ""),
                edit(city, "Khulna, Bangladesh"),
                TextEdit(0, null, "New line", 50f, 400f, 14f, 0xFF000000.toInt(), false, false, FontFamily.SANS, false),
            ),
            out,
            noProgress,
        )
        val text = PdfOps.extractText(PdfSource(out))
        assertTrue(text, text.contains("Experience: 5 years"))
        assertFalse("old text is gone, not just covered: $text", text.contains("2 years"))
        assertFalse(text, text.contains("Kotlin"))
        assertFalse(text, text.contains("Dhaka"))
        assertTrue(text, text.contains("Khulna, Bangladesh"))
        assertTrue(text, text.contains("New line"))
        assertTrue(text, text.contains("Rahim Uddin") && text.contains("Skills"))

        // The replacement sits where the old text was.
        val after = PdfText.runs(PdfSource(out), 0).runs.first { it.text == "Experience: 5 years" }
        assertEquals(50f, after.x, 1f)
        assertEquals(842f - 750f, after.baseline, 1f)
        assertEquals(1, Raster.pageCount(out))
    }

    @Test
    fun editsTextToPdfOutput() {
        val f = File(dir, "notes.pdf")
        TextToPdf.make("First line here\nSecond line আমার বাংলা\nThird line", "Notes", PageSize.A4, TextToPdf.Font.SANS, 12f, f)
        val src = PdfSource(f)
        val runs = PdfText.runs(src, 0).runs
        val second = runs.first { it.text.startsWith("First") }
        val out = File(dir, "notes_edited.pdf")
        PdfText.apply(src, listOf(edit(second, "Changed line")), out, noProgress)
        val text = PdfOps.extractText(PdfSource(out))
        assertTrue(text, text.contains("Changed line"))
        assertFalse(text, text.contains("First line"))
        assertTrue(text, text.contains("Third line"))
    }
}
