package com.bbcl.pdfmaster

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/** Runs every PDF operation on the device and checks the results. */
@RunWith(AndroidJUnit4::class)
class PdfOpsTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var dir: File
    private val noProgress: Progress = { _, _ -> }

    @Before
    fun setUp() {
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(context)
        Store.init(context)
        dir = File(context.cacheDir, "test").apply { deleteRecursively(); mkdirs() }
    }

    private fun out(name: String) = File(dir, "$name.pdf")

    private fun textPdf(name: String, text: String): File = out(name).also {
        TextToPdf.make(text, "Heading $name", PageSize.A4, TextToPdf.Font.SANS, 12f, it)
    }

    private fun photoJpeg(): ByteArray {
        val bmp = Bitmap.createBitmap(2400, 1600, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint()
        for (i in 0 until 60) {
            p.color = Color.rgb((i * 37) % 255, (i * 91) % 255, (i * 53) % 255)
            c.drawCircle((i * 97 % 2400).toFloat(), (i * 61 % 1600).toFloat(), 150f, p)
        }
        val file = File(dir, "photo.jpg")
        FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        return file.readBytes()
    }

    @Test
    fun everyOperationWorks() {
        // Text to PDF: long text runs over several pages; non-Latin text is laid out by Android.
        val long = (1..400).joinToString("\n") { "Line $it — the quick brown fox. আমার সোনার বাংলা। नमस्ते" }
        val a = textPdf("a", long)
        val pagesA = Raster.pageCount(a)
        assertTrue("text spans pages", pagesA >= 5)
        assertTrue(PdfOps.extractText(PdfSource(a)).contains("quick brown fox"))

        // Images to PDF.
        val jpeg = photoJpeg()
        val img = out("img")
        PdfOps.imagesToPdf(listOf(PdfOps.ImagePage(jpeg, 595f, 842f, 18f), PdfOps.ImagePage(jpeg, 842f, 595f, 0f)), "img", img)
        assertEquals(2, Raster.pageCount(img))

        // Merge.
        val merged = out("merged")
        PdfOps.merge(listOf(PdfSource(a), PdfSource(img)), merged, noProgress)
        assertEquals(pagesA + 2, Raster.pageCount(merged))

        // Split into single pages and by ranges.
        val parts = PdfOps.split(PdfSource(merged), listOf(0..1, 2..2), { i, _ -> out("part$i") }, noProgress)
        assertEquals(listOf(2, 1), parts.map { Raster.pageCount(it) })

        // Extract / organize: reverse, rotate and drop pages.
        val organized = out("organized")
        PdfOps.selectPages(PdfSource(merged), listOf(PdfOps.PageRef(pagesA + 1, 90), PdfOps.PageRef(0)), organized)
        assertEquals(2, Raster.pageCount(organized))
        val sizes = Raster.pageSizes(organized)
        assertTrue("rotated landscape page becomes portrait", sizes[0].first < sizes[0].second)

        // Smart compression shrinks the big photo pages.
        val big = out("big")
        PdfOps.imagesToPdf(List(3) { PdfOps.ImagePage(jpeg, 595f, 842f, 0f) }, "big", big)
        val small = out("small")
        PdfOps.compress(PdfSource(big), PdfOps.CompressLevel.HIGH, small, noProgress)
        assertTrue("compressed ${small.length()} < ${big.length()}", small.length() < big.length())
        assertEquals(3, Raster.pageCount(small))

        // Maximum compression / black & white: pages as pictures.
        val rendered = Raster.renderAll(big, 72, 50, true, File(dir, "r").apply { mkdirs() }, noProgress)
        val raster = out("raster")
        PdfOps.pagesFromImages(rendered, "raster", raster)
        assertEquals(3, Raster.pageCount(raster))

        // Passwords.
        val locked = out("locked")
        PdfOps.protect(PdfSource(a), "s3cret", allowPrint = true, allowCopy = false, allowEdit = false, out = locked)
        assertTrue(PdfOps.needsPassword(locked))
        assertFalse(PdfOps.checkPassword(locked, "wrong"))
        assertTrue(PdfOps.checkPassword(locked, "s3cret"))
        val unlocked = out("unlocked")
        PdfOps.unlock(PdfSource(locked, "s3cret"), unlocked)
        assertFalse(PdfOps.needsPassword(unlocked))
        assertEquals(pagesA, Raster.pageCount(unlocked))
        // Locked files can still be previewed through an unlocked copy.
        assertEquals(pagesA, Raster.pageCount(Raster.renderable(PdfSource(locked, "s3cret"))))

        // Watermark (Latin and non-Latin text) and page numbers.
        for ((i, style) in PdfOps.WatermarkStyle.entries.withIndex()) {
            val w = out("wm$i")
            PdfOps.watermark(PdfSource(organized), "CONFIDENTIAL", Color.RED, 0.3f, 0.6f, style, w, noProgress)
            assertTrue(PdfOps.extractText(PdfSource(w)).contains("CONFIDENTIAL"))
        }
        val numbered = out("numbered")
        PdfOps.pageNumbers(PdfSource(organized), PdfOps.NumberPosition.BOTTOM_RIGHT, PdfOps.NumberFormat.PAGE_OF, 1, 11f, numbered, noProgress)
        assertTrue(PdfOps.extractText(PdfSource(numbered)).contains("Page 2 of 2"))

        // Signature stamp.
        val sig = Bitmap.createBitmap(300, 100, Bitmap.Config.ARGB_8888).also { Canvas(it).drawLine(0f, 50f, 300f, 50f, Paint().apply { strokeWidth = 8f }) }
        val signed = out("signed")
        PdfOps.stamp(PdfSource(organized), 0, sig, 0.5f, 0.7f, 0.3f, signed)
        assertEquals(2, Raster.pageCount(signed))

        // Properties.
        val props = out("props")
        PdfOps.setInfo(PdfSource(a), "My title", "Me", "Subj", "k1, k2", props)
        val info = PdfOps.info(PdfSource(props))
        assertEquals("My title", info.title)
        assertEquals("Me", info.author)

        // Page rendering for previews.
        val bmp = Raster.page(organized, 1, 300)
        assertEquals(300, bmp.width)

        // Photo preparation (the Image to PDF path) from a content-less file Uri.
        val uri = android.net.Uri.fromFile(File(dir, "photo.jpg"))
        val (prepared, pw, ph) = ImageToPdf.prepare(context, uri, 1400, 70, 90, true)
        assertEquals(1400, ph) // rotated 90°: the long side is now the height
        assertTrue(pw < ph && prepared.isNotEmpty())
    }
}
