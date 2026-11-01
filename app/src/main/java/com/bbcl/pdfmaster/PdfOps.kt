// Every PDF operation of the app, run on the phone with PdfBox-Android. All functions block,
// so callers run them on a background dispatcher. Outputs are written without passwords
// (except by `protect`), so they open anywhere.
package com.bbcl.pdfmaster

import android.graphics.Bitmap
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.util.Matrix
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A PDF to read, with its password if it has one. */
data class PdfSource(val file: File, val password: String? = null)

class PasswordNeeded(val file: File) : Exception("This PDF is protected with a password")

typealias Progress = (done: Int, total: Int) -> Unit

object PdfOps {
    const val CREATOR = "PDF Master"

    fun load(src: PdfSource): PDDocument = try {
        PDDocument.load(src.file, src.password ?: "", MemoryUsageSetting.setupTempFileOnly())
    } catch (e: InvalidPasswordException) {
        throw PasswordNeeded(src.file)
    }

    /** Whether the PDF needs a password to open. */
    fun needsPassword(f: File): Boolean = try {
        PDDocument.load(f, "", MemoryUsageSetting.setupTempFileOnly()).close()
        false
    } catch (e: InvalidPasswordException) {
        true
    }

    /** Whether `password` opens the PDF. */
    fun checkPassword(f: File, password: String): Boolean = try {
        PDDocument.load(f, password, MemoryUsageSetting.setupTempFileOnly()).close()
        true
    } catch (e: InvalidPasswordException) {
        false
    }

    fun pageCount(src: PdfSource): Int = load(src).use { it.numberOfPages }

    private fun PDDocument.saveTo(out: File, title: String? = null) {
        isAllSecurityToBeRemoved = true
        documentInformation.producer = CREATOR
        if (title != null) documentInformation.title = title
        out.parentFile?.mkdirs()
        save(out)
    }

    // ============== Create ==============

    /** One page per JPEG, the photo centred in the page's printable area. */
    class ImagePage(val jpeg: ByteArray, val pageWidth: Float, val pageHeight: Float, val margin: Float)

    fun imagesToPdf(pages: List<ImagePage>, title: String, out: File) {
        PDDocument(MemoryUsageSetting.setupTempFileOnly()).use { doc ->
            for (p in pages) {
                val page = PDPage(PDRectangle(p.pageWidth, p.pageHeight))
                doc.addPage(page)
                val image = JPEGFactory.createFromByteArray(doc, p.jpeg)
                val boxW = p.pageWidth - 2 * p.margin
                val boxH = p.pageHeight - 2 * p.margin
                val scale = min(boxW / image.width, boxH / image.height)
                val w = image.width * scale
                val h = image.height * scale
                PDPageContentStream(doc, page).use { it.drawImage(image, (p.pageWidth - w) / 2, (p.pageHeight - h) / 2, w, h) }
            }
            doc.documentInformation.creator = CREATOR
            doc.saveTo(out, title)
        }
    }

    // ============== Organize ==============

    fun merge(sources: List<PdfSource>, out: File, progress: Progress) {
        PDDocument(MemoryUsageSetting.setupTempFileOnly()).use { dest ->
            val merger = PDFMergerUtility()
            val opened = mutableListOf<PDDocument>()
            try {
                sources.forEachIndexed { i, src ->
                    val doc = load(src)
                    opened.add(doc)
                    doc.isAllSecurityToBeRemoved = true
                    merger.appendDocument(dest, doc)
                    progress(i + 1, sources.size)
                }
                dest.saveTo(out, titleOf(out))
            } finally {
                opened.forEach { it.close() }
            }
        }
    }

    /** A page of the source, turned by `rotation` extra degrees (multiple of 90). */
    data class PageRef(val index: Int, val rotation: Int = 0)

    /** A new PDF with the given pages of `src`, in that order: extract, reorder, rotate, delete. */
    fun selectPages(src: PdfSource, pages: List<PageRef>, out: File) {
        load(src).use { doc ->
            PDDocument(MemoryUsageSetting.setupTempFileOnly()).use { dest ->
                for (ref in pages) {
                    val page = dest.importPage(doc.getPage(ref.index))
                    page.rotation = ((page.rotation + ref.rotation) % 360 + 360) % 360
                }
                dest.saveTo(out, titleOf(out))
            }
        }
    }

    /** Splits `src` into one PDF per range (0-based, inclusive). */
    fun split(src: PdfSource, ranges: List<IntRange>, outFor: (Int, IntRange) -> File, progress: Progress): List<File> =
        load(src).use { doc ->
            ranges.mapIndexed { i, range ->
                val out = outFor(i, range)
                PDDocument(MemoryUsageSetting.setupTempFileOnly()).use { dest ->
                    for (p in range) dest.importPage(doc.getPage(p))
                    dest.saveTo(out, titleOf(out))
                }
                progress(i + 1, ranges.size)
                out
            }
        }

    // ============== Optimize ==============

    enum class CompressLevel(val label: String, val detail: String, val maxSide: Int, val quality: Float) {
        LOW("Light", "Best quality, smaller savings", 2400, 0.85f),
        MEDIUM("Recommended", "Good quality, good savings", 1600, 0.7f),
        HIGH("Strong", "Smallest file, lower image quality", 1100, 0.5f),
    }

    /**
     * Re-encodes the pictures inside the PDF as smaller JPEGs. Text and drawings stay sharp and
     * selectable. Pictures that would not get smaller are left alone.
     */
    fun compress(src: PdfSource, level: CompressLevel, out: File, progress: Progress) {
        load(src).use { doc ->
            val done = HashMap<COSBase, PDImageXObject?>() // shared pictures are re-encoded once
            val total = doc.numberOfPages
            doc.pages.forEachIndexed { i, page ->
                page.resources?.let { compressResources(doc, it, level, done, HashSet()) }
                progress(i + 1, total)
            }
            doc.saveTo(out, titleOf(out))
        }
    }

    private fun compressResources(
        doc: PDDocument, res: PDResources, level: CompressLevel, done: HashMap<COSBase, PDImageXObject?>, seen: HashSet<COSBase>,
    ) {
        for (name in res.xObjectNames.toList()) {
            val x = runCatching { res.getXObject(name) }.getOrNull() ?: continue
            val key = x.cosObject
            if (!seen.add(key)) continue
            when (x) {
                is PDFormXObject -> x.resources?.let { compressResources(doc, it, level, done, seen) }
                is PDImageXObject -> {
                    val replacement = done.getOrPut(key) { recompress(doc, x, level) }
                    if (replacement != null) res.put(name, replacement)
                }
            }
        }
    }

    private fun recompress(doc: PDDocument, img: PDImageXObject, level: CompressLevel): PDImageXObject? {
        // Masks and transparent pictures would lose their meaning as JPEGs.
        if (img.isStencil || img.bitsPerComponent == 1 || img.cosObject.containsKey(COSName.SMASK) ||
            img.cosObject.containsKey(COSName.MASK)
        ) {
            return null
        }
        val before = img.cosObject.length
        if (before < 20_000) return null
        val bitmap = runCatching { img.image }.getOrNull() ?: return null
        if (bitmap.hasAlpha() && !bitmap.isOpaqueEnough()) return null
        val scale = min(1f, level.maxSide.toFloat() / max(bitmap.width, bitmap.height))
        val scaled = if (scale < 1f) {
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).roundToInt().coerceAtLeast(1), (bitmap.height * scale).roundToInt().coerceAtLeast(1), true)
        } else {
            bitmap
        }
        val jpeg = runCatching { JPEGFactory.createFromImage(doc, scaled, level.quality) }.getOrNull()
        if (scaled !== bitmap) scaled.recycle()
        bitmap.recycle()
        if (jpeg == null || jpeg.cosObject.length >= before * 0.9) return null
        return jpeg
    }

    private fun Bitmap.isOpaqueEnough(): Boolean {
        // Samples a grid of pixels; any real transparency means the picture keeps its format.
        val step = max(1, max(width, height) / 64)
        for (y in 0 until height step step) for (x in 0 until width step step) if ((getPixel(x, y) ushr 24) < 250) return false
        return true
    }

    /** A new PDF whose pages are the given JPEG renderings (raster compress, black & white). */
    fun pagesFromImages(pages: List<Raster.RenderedPage>, title: String, out: File) {
        PDDocument(MemoryUsageSetting.setupTempFileOnly()).use { doc ->
            for (p in pages) {
                val page = PDPage(PDRectangle(p.widthPt, p.heightPt))
                doc.addPage(page)
                val image = JPEGFactory.createFromByteArray(doc, p.file.readBytes())
                PDPageContentStream(doc, page).use { it.drawImage(image, 0f, 0f, p.widthPt, p.heightPt) }
            }
            doc.saveTo(out, title)
        }
    }

    // ============== Security ==============

    fun protect(src: PdfSource, userPassword: String, allowPrint: Boolean, allowCopy: Boolean, allowEdit: Boolean, out: File) {
        load(src).use { doc ->
            doc.isAllSecurityToBeRemoved = true
            val permissions = AccessPermission().apply {
                setCanPrint(allowPrint)
                setCanPrintFaithful(allowPrint)
                setCanExtractContent(allowCopy)
                setCanExtractForAccessibility(true)
                setCanModify(allowEdit)
                setCanModifyAnnotations(allowEdit)
                setCanFillInForm(allowEdit)
                setCanAssembleDocument(allowEdit)
            }
            // The owner password unlocks the permissions; the user only ever needs theirs.
            val owner = userPassword + "#" + System.nanoTime()
            val policy = StandardProtectionPolicy(owner, userPassword, permissions).apply { encryptionKeyLength = 256 }
            doc.protect(policy)
            doc.documentInformation.producer = CREATOR
            doc.save(out)
        }
    }

    fun unlock(src: PdfSource, out: File) {
        load(src).use { it.saveTo(out, titleOf(out)) }
    }

    // ============== Edit ==============

    /** A font that can write `text`: Helvetica when possible, else a font from the phone. */
    private fun fontFor(doc: PDDocument, text: String, bold: Boolean): PDFont {
        val base = if (bold) PDType1Font.HELVETICA_BOLD else PDType1Font.HELVETICA
        if (runCatching { base.encode(text) }.isSuccess) return base
        val candidates = listOf(
            "NotoSansBengali-Regular.ttf", "NotoSansBengaliUI-Regular.ttf", "NotoSansDevanagari-Regular.ttf",
            "NotoSansDevanagariUI-Regular.ttf", "Roboto-Regular.ttf", "NotoSans-Regular.ttf", "DroidSans.ttf",
        ).map { File("/system/fonts", it) }.filter { it.exists() }
        for (f in candidates) {
            val font = runCatching { PDType0Font.load(doc, f) }.getOrNull() ?: continue
            if (runCatching { font.encode(text) }.isSuccess) return font
        }
        throw IllegalArgumentException("This text uses letters that cannot be written into the PDF")
    }

    enum class WatermarkStyle(val label: String) { DIAGONAL("Diagonal"), CENTER("Centre"), TILED("Tiled") }

    fun watermark(
        src: PdfSource, text: String, colorArgb: Int, opacity: Float, sizeRatio: Float, style: WatermarkStyle, out: File, progress: Progress,
    ) {
        load(src).use { doc ->
            val font = fontFor(doc, text, bold = true)
            val gs = PDExtendedGraphicsState().apply { nonStrokingAlphaConstant = opacity; strokingAlphaConstant = opacity }
            val r = ((colorArgb shr 16) and 0xFF) / 255f
            val g = ((colorArgb shr 8) and 0xFF) / 255f
            val b = (colorArgb and 0xFF) / 255f
            val total = doc.numberOfPages
            doc.pages.forEachIndexed { i, page ->
                val box = page.cropBox
                val w = box.width
                val h = box.height
                val diagonal = kotlin.math.sqrt(w * w + h * h)
                var fontSize = diagonal * sizeRatio / max(1f, font.getStringWidth(text) / 1000f)
                if (style != WatermarkStyle.DIAGONAL) fontSize *= 0.8f
                if (style == WatermarkStyle.TILED) fontSize *= 0.35f
                val textW = font.getStringWidth(text) / 1000f * fontSize
                PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                    cs.setGraphicsStateParameters(gs)
                    cs.setNonStrokingColor(r, g, b)
                    fun draw(cx: Float, cy: Float, angle: Double) {
                        cs.beginText()
                        cs.setFont(font, fontSize)
                        val m = Matrix.getRotateInstance(angle, box.lowerLeftX + cx, box.lowerLeftY + cy)
                        m.translate(-textW / 2, -fontSize / 3)
                        cs.setTextMatrix(m)
                        cs.showText(text)
                        cs.endText()
                    }
                    when (style) {
                        WatermarkStyle.DIAGONAL -> draw(w / 2, h / 2, kotlin.math.atan2(h.toDouble(), w.toDouble()))
                        WatermarkStyle.CENTER -> draw(w / 2, h / 2, 0.0)
                        WatermarkStyle.TILED -> {
                            val stepX = textW * 1.4f
                            val stepY = fontSize * 4f
                            var y = stepY / 2
                            var row = 0
                            while (y < h + stepY) {
                                var x = if (row % 2 == 0) stepX / 2 else 0f
                                while (x < w + stepX) {
                                    draw(x, y, Math.toRadians(30.0))
                                    x += stepX
                                }
                                y += stepY
                                row++
                            }
                        }
                    }
                }
                progress(i + 1, total)
            }
            doc.saveTo(out, titleOf(out))
        }
    }

    enum class NumberPosition(val label: String) {
        BOTTOM_CENTER("Bottom centre"), BOTTOM_RIGHT("Bottom right"), BOTTOM_LEFT("Bottom left"),
        TOP_CENTER("Top centre"), TOP_RIGHT("Top right"), TOP_LEFT("Top left"),
    }

    enum class NumberFormat(val label: String) { PLAIN("1"), PAGE("Page 1"), OF_TOTAL("1 / 10"), PAGE_OF("Page 1 of 10") }

    fun pageNumbers(src: PdfSource, position: NumberPosition, format: NumberFormat, startAt: Int, fontSize: Float, out: File, progress: Progress) {
        load(src).use { doc ->
            val font = PDType1Font.HELVETICA
            val total = doc.numberOfPages
            val last = startAt + total - 1
            doc.pages.forEachIndexed { i, page ->
                val n = startAt + i
                val label = when (format) {
                    NumberFormat.PLAIN -> "$n"
                    NumberFormat.PAGE -> "Page $n"
                    NumberFormat.OF_TOTAL -> "$n / $last"
                    NumberFormat.PAGE_OF -> "Page $n of $last"
                }
                val box = page.cropBox
                val textW = font.getStringWidth(label) / 1000f * fontSize
                val margin = 28f
                val x = when (position) {
                    NumberPosition.BOTTOM_LEFT, NumberPosition.TOP_LEFT -> margin
                    NumberPosition.BOTTOM_RIGHT, NumberPosition.TOP_RIGHT -> box.width - margin - textW
                    else -> (box.width - textW) / 2
                }
                val top = position.name.startsWith("TOP")
                val y = if (top) box.height - margin - fontSize * 0.7f else margin
                PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                    cs.beginText()
                    cs.setFont(font, fontSize)
                    cs.setNonStrokingColor(0.2f, 0.2f, 0.2f)
                    // Pages turned by the viewer are numbered in the turned page's own corners.
                    cs.setTextMatrix(placeOnRotatedPage(page, box, x, y))
                    cs.showText(label)
                    cs.endText()
                }
                progress(i + 1, total)
            }
            doc.saveTo(out, titleOf(out))
        }
    }

    /** Matrix that puts (x, y), measured on the page as the reader sees it, onto a rotated page. */
    internal fun placeOnRotatedPage(page: PDPage, box: PDRectangle, x: Float, y: Float): Matrix {
        val w = box.width
        val h = box.height
        val ox = box.lowerLeftX
        val oy = box.lowerLeftY
        return when (page.rotation) {
            90 -> Matrix.getRotateInstance(Math.PI / 2, ox + w - y, oy + x).also { }
            180 -> Matrix.getRotateInstance(Math.PI, ox + w - x, oy + h - y)
            270 -> Matrix.getRotateInstance(-Math.PI / 2, ox + y, oy + h - x)
            else -> Matrix.getTranslateInstance(ox + x, oy + y)
        }
    }

    /** Width and height of a page as the reader sees it (after its own rotation). */
    fun viewedSize(page: PDPage): Pair<Float, Float> {
        val b = page.cropBox
        return if (page.rotation % 180 == 0) b.width to b.height else b.height to b.width
    }

    /**
     * Stamps a picture (a signature or a stamp) onto one page. `x`, `y`, `w` are fractions of
     * the page as the reader sees it, measured from the top-left corner.
     */
    fun stamp(src: PdfSource, pageIndex: Int, picture: Bitmap, x: Float, y: Float, w: Float, out: File) {
        load(src).use { doc ->
            val page = doc.getPage(pageIndex)
            val image = LosslessFactory.createFromImage(doc, picture)
            val (vw, vh) = viewedSize(page)
            val width = w * vw
            val height = width * picture.height / picture.width
            val left = x * vw
            val bottom = vh - y * vh - height
            PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                cs.saveGraphicsState()
                cs.transform(placeOnRotatedPage(page, page.cropBox, left, bottom))
                cs.drawImage(image, 0f, 0f, width, height)
                cs.restoreGraphicsState()
            }
            doc.saveTo(out, titleOf(out))
        }
    }

    // ============== Convert ==============

    fun extractText(src: PdfSource): String = load(src).use { doc ->
        val stripper = PDFTextStripper().apply { sortByPosition = true }
        val sb = StringBuilder()
        for (p in 1..doc.numberOfPages) {
            stripper.startPage = p
            stripper.endPage = p
            val text = stripper.getText(doc).trim()
            if (doc.numberOfPages > 1) sb.append("--- Page $p ---\n")
            sb.append(text).append("\n\n")
        }
        sb.toString().trim()
    }

    class Info(val pages: Int, val title: String?, val author: String?, val subject: String?, val keywords: String?, val creator: String?, val producer: String?, val encrypted: Boolean, val version: Float)

    fun info(src: PdfSource): Info = load(src).use { doc ->
        val i = doc.documentInformation
        Info(doc.numberOfPages, i.title, i.author, i.subject, i.keywords, i.creator, i.producer, src.password != null, doc.version)
    }

    fun setInfo(src: PdfSource, title: String, author: String, subject: String, keywords: String, out: File) {
        load(src).use { doc ->
            doc.documentInformation.apply {
                this.title = title.ifBlank { null }
                this.author = author.ifBlank { null }
                this.subject = subject.ifBlank { null }
                this.keywords = keywords.ifBlank { null }
            }
            doc.saveTo(out)
        }
    }
}
