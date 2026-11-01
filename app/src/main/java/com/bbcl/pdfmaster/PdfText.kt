// Edit PDF: finds the lines of text on a page, then replaces, deletes or adds text. Replaced
// text is taken out of the page itself (so it no longer shows up when copying or searching);
// when that is not possible a box in the background colour covers it. New text is written in
// the original font when it has every letter, else in the closest standard font.
package com.bbcl.pdfmaster

import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSFloat
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdfparser.PDFStreamParser
import com.tom_roush.pdfbox.pdfwriter.ContentStreamWriter
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.font.PDVectorFont
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDTransparencyGroup
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.IdentityHashMap
import kotlin.math.abs
import kotlin.math.max

enum class FontFamily(val label: String) { SANS("Sans"), SERIF("Serif"), MONO("Mono") }

/**
 * A run of text on a page: words on one line close enough to read as one piece. Positions are
 * in points on the page as the reader sees it, from the top-left corner; `baseline` is where
 * the letters sit.
 */
data class TextRun(
    val text: String, val x: Float, val top: Float, val width: Float, val height: Float, val baseline: Float,
    val size: Float, val colorArgb: Int, val bold: Boolean, val italic: Boolean, val family: FontFamily,
)

/** Width, height (points, as the reader sees the page) and text runs of one page. */
class PageText(val width: Float, val height: Float, val runs: List<TextRun>)

/**
 * One change: `original` is the run being replaced (null for new text). An empty `text`
 * deletes the original. `coverArgb` is the page colour behind the original, used when the old
 * text cannot be taken out of the page.
 */
data class TextEdit(
    val page: Int, val original: TextRun?, val text: String, val x: Float, val baseline: Float, val size: Float,
    val colorArgb: Int, val bold: Boolean, val italic: Boolean, val family: FontFamily,
    val keepOriginalFont: Boolean, val coverArgb: Int = 0xFFFFFFFF.toInt(),
)

object PdfText {
    private const val ASCENT = 0.86f
    private const val DESCENT = 0.26f

    /** Whether a glyph runs left to right the way the reader sees the page. */
    private fun upright(tp: TextPosition, page: PDPage) = tp.dir == 0f || tp.dir == page.rotation.toFloat()

    private fun styleOf(font: PDFont?): Triple<Boolean, Boolean, FontFamily> {
        val name = (font?.name ?: "").substringAfter('+').lowercase()
        val bold = listOf("bold", "black", "heavy", "semibold", "demi").any { it in name } ||
            runCatching { font?.fontDescriptor?.isForceBold == true || (font?.fontDescriptor?.fontWeight ?: 0f) >= 600f }.getOrDefault(false)
        val italic = "italic" in name || "oblique" in name || runCatching { font?.fontDescriptor?.isItalic == true }.getOrDefault(false)
        val family = when {
            listOf("courier", "mono", "consol", "typewriter").any { it in name } -> FontFamily.MONO
            listOf("times", "serif", "georgia", "cambria", "garamond", "book", "minion", "palatino").any { it in name && "sans" !in name } -> FontFamily.SERIF
            else -> FontFamily.SANS
        }
        return Triple(bold, italic, family)
    }

    /** The lines of text on page `pageIndex`, merged into runs. */
    fun runs(src: PdfSource, pageIndex: Int): PageText = PdfOps.load(src).use { doc ->
        val page = doc.getPage(pageIndex)
        val (vw, vh) = PdfOps.viewedSize(page)
        val colors = IdentityHashMap<TextPosition, Int>()
        val glyphs = mutableListOf<TextPosition>()
        val stripper = object : PDFTextStripper() {
            override fun processTextPosition(text: TextPosition) {
                colors[text] = runCatching { graphicsState.nonStrokingColor.toRGB() }.getOrDefault(0)
                super.processTextPosition(text)
            }

            override fun writeString(text: String, textPositions: List<TextPosition>) {
                textPositions.filterTo(glyphs) { upright(it, page) && it.unicode != null }
            }
        }
        stripper.sortByPosition = true
        stripper.startPage = pageIndex + 1
        stripper.endPage = pageIndex + 1
        stripper.getText(doc)

        // The stripper gives the letters line by line, left to right. Letters on the same
        // baseline with a small gap form one run; wide gaps (table columns, tab stops) start a
        // new run. Spaces join the run they are in.
        val runs = mutableListOf<MutableList<TextPosition>>()
        for (g in glyphs) {
            val last = runs.lastOrNull()
            if (g.unicode.isBlank()) { last?.add(g); continue }
            val prev = last?.lastOrNull { !it.unicode.isBlank() }
            val size = max(1f, g.yScale)
            if (prev != null && abs(prev.yDirAdj - g.yDirAdj) < size * 0.35f &&
                g.xDirAdj - (prev.xDirAdj + prev.widthDirAdj) in -size * 0.2f..size * 0.9f
            ) {
                last.add(g)
            } else {
                runs.add(mutableListOf(g))
            }
        }
        PageText(vw, vh, runs.map { run -> toRun(run, colors) })
    }

    private fun toRun(glyphs: List<TextPosition>, colors: Map<TextPosition, Int>): TextRun {
        val all = glyphs.filter { !it.unicode.isBlank() }
        val size = all.groupingBy { (it.yScale * 2).toInt() }.eachCount().maxBy { it.value }.key / 2f
        val sb = StringBuilder()
        var space = false
        var prev: TextPosition? = null
        for (g in glyphs) {
            if (g.unicode.isBlank()) { space = true; continue }
            if (prev != null && (space || g.xDirAdj - (prev.xDirAdj + prev.widthDirAdj) > size * 0.15f)) sb.append(' ')
            sb.append(g.unicode)
            space = false
            prev = g
        }
        val left = all.minOf { it.xDirAdj }
        val right = all.maxOf { it.xDirAdj + it.widthDirAdj }
        val baseline = all.map { it.yDirAdj }.sorted()[all.size / 2]
        val top = all.minOf { it.yDirAdj - it.yScale * ASCENT }
        val bottom = all.maxOf { it.yDirAdj + it.yScale * DESCENT }
        val (bold, italic, family) = styleOf(all[0].font)
        val color = (colors[all[0]] ?: 0) or 0xFF000000.toInt()
        return TextRun(sb.toString(), left, top, right - left, bottom - top, baseline, size, color, bold, italic, family)
    }

    // ============== Saving ==============

    fun apply(src: PdfSource, edits: List<TextEdit>, out: File, progress: Progress) {
        PdfOps.load(src).use { doc ->
            val byPage = edits.groupBy { it.page }
            byPage.entries.forEachIndexed { n, (pageIndex, pageEdits) ->
                applyToPage(doc, pageIndex, pageEdits)
                progress(n + 1, byPage.size)
            }
            doc.isAllSecurityToBeRemoved = true
            doc.documentInformation.producer = PdfOps.CREATOR
            doc.documentInformation.title = titleOf(out)
            out.parentFile?.mkdirs()
            doc.save(out)
        }
    }

    /** A string shown by the page: the `element`-th string of top-level operator `op`. */
    private data class Piece(val op: Int, val element: Int)

    private class PieceInfo(var glyphs: Int = 0, var inside: Int = 0, var advance: Float = 0f, var scale: Float = 0f)

    private fun applyToPage(doc: PDDocument, pageIndex: Int, edits: List<TextEdit>) {
        val page = doc.getPage(pageIndex)
        val boxes = edits.mapNotNull { e -> e.original?.let { it to e } }
        // For each box: whether some of its text could not be taken out, and its first font.
        val leftover = BooleanArray(boxes.size)
        val fonts = arrayOfNulls<PDFont>(boxes.size)
        if (boxes.isNotEmpty()) {
            val removed = removeText(doc, pageIndex, boxes.map { it.first }, leftover, fonts)
            if (!removed) leftover.fill(true)
        }
        val (_, vh) = PdfOps.viewedSize(page)
        PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
            boxes.forEachIndexed { i, (run, e) ->
                if (!leftover[i]) return@forEachIndexed
                cs.saveGraphicsState()
                cs.setNonStrokingColor(((e.coverArgb shr 16) and 0xFF) / 255f, ((e.coverArgb shr 8) and 0xFF) / 255f, (e.coverArgb and 0xFF) / 255f)
                val pad = run.size * 0.05f
                cs.transform(PdfOps.placeOnRotatedPage(page, page.cropBox, run.x - pad, vh - run.top - run.height - pad))
                cs.addRect(0f, 0f, run.width + 2 * pad, run.height + 2 * pad)
                cs.fill()
                cs.restoreGraphicsState()
            }
            for (e in edits) {
                if (e.text.isBlank()) continue
                val original = boxes.indexOfFirst { it.second === e }.takeIf { it >= 0 }?.let { fonts[it] }
                val font = fontFor(doc, e, original)
                cs.beginText()
                cs.setFont(font, e.size)
                cs.setNonStrokingColor(((e.colorArgb shr 16) and 0xFF) / 255f, ((e.colorArgb shr 8) and 0xFF) / 255f, (e.colorArgb and 0xFF) / 255f)
                cs.setTextMatrix(PdfOps.placeOnRotatedPage(page, page.cropBox, e.x, vh - e.baseline))
                cs.showText(e.text)
                cs.endText()
            }
        }
    }

    /**
     * Takes the text inside `boxes` out of the page's own content. Strings that lie wholly in a
     * box are replaced by an equal blank space, so the rest of the line does not move. Text the
     * page shows through forms, or strings only partly in a box, stays: those boxes are marked
     * in `leftover`. Returns false if the page could not be rewritten at all.
     */
    private fun removeText(doc: PDDocument, pageIndex: Int, boxes: List<TextRun>, leftover: BooleanArray, fonts: Array<PDFont?>): Boolean {
        val page = doc.getPage(pageIndex)
        fun boxOf(tp: TextPosition): Int {
            val cx = tp.xDirAdj + tp.widthDirAdj / 2
            val cy = tp.yDirAdj - tp.yScale * 0.3f
            return boxes.indexOfFirst { b -> cx >= b.x - 1 && cx <= b.x + b.width + 1 && cy >= b.top - 1 && cy <= b.top + b.height + 1 }
        }

        val pieces = HashMap<Piece, PieceInfo>()
        val glyphBoxes = mutableListOf<Pair<Piece?, Int>>()
        val stripper = object : PDFTextStripper() {
            var op = -1
            var element = -1
            var inOperator = false
            var nested = 0

            override fun processOperator(operator: Operator, operands: List<COSBase>) {
                if (inOperator || nested > 0) return super.processOperator(operator, operands)
                op++
                element = -1
                inOperator = true
                try {
                    super.processOperator(operator, operands)
                } finally {
                    inOperator = false
                }
            }

            override fun showForm(form: PDFormXObject) {
                nested++
                try { super.showForm(form) } finally { nested-- }
            }

            override fun showTransparencyGroup(form: PDTransparencyGroup) {
                nested++
                try { super.showTransparencyGroup(form) } finally { nested-- }
            }

            override fun showText(string: ByteArray) {
                if (nested > 0) return super.showText(string)
                element++
                val before = textMatrix.clone()
                super.showText(string)
                val after = textMatrix
                val a = before.getValue(0, 0)
                val b = before.getValue(0, 1)
                val advance = if (abs(a) > 1e-6f) (after.getValue(2, 0) - before.getValue(2, 0)) / a
                else if (abs(b) > 1e-6f) (after.getValue(2, 1) - before.getValue(2, 1)) / b else 0f
                val ts = graphicsState.textState
                pieces.getOrPut(Piece(op, element)) { PieceInfo() }.apply {
                    this.advance += advance
                    scale = ts.fontSize * ts.horizontalScaling / 100f
                }
            }

            override fun processTextPosition(text: TextPosition) {
                val box = if (upright(text, page)) boxOf(text) else -1
                val piece = if (nested == 0 && element >= 0) Piece(op, element) else null
                if (piece != null) {
                    val info = pieces.getOrPut(piece) { PieceInfo() }
                    info.glyphs++
                    if (box >= 0) info.inside++
                }
                if (box >= 0) {
                    if (fonts[box] == null) fonts[box] = text.font
                    glyphBoxes += piece to box
                }
                super.processTextPosition(text)
            }
        }
        stripper.startPage = pageIndex + 1
        stripper.endPage = pageIndex + 1
        stripper.getText(doc)

        val gone = pieces.filter { (_, i) -> i.glyphs > 0 && i.inside == i.glyphs && i.scale != 0f }.keys
        for ((piece, box) in glyphBoxes) if (piece == null || piece !in gone) leftover[box] = true
        if (gone.isEmpty()) return true

        return runCatching {
            val parser = PDFStreamParser(page)
            parser.parse()
            val tokens = parser.tokens
            val result = ArrayList<Any>(tokens.size + 16)
            val operands = mutableListOf<Any>()
            var op = -1
            fun blank(piece: Piece): COSFloat {
                val info = pieces.getValue(piece)
                return COSFloat(-info.advance * 1000f / info.scale)
            }
            for (t in tokens) {
                if (t !is Operator) { operands += t; continue }
                op++
                val touched = gone.any { it.op == op }
                if (!touched) { result.addAll(operands); result.add(t); operands.clear(); continue }
                when (t.name) {
                    "Tj", "'", "\"" -> {
                        if (t.name == "\"") {
                            result.add(operands[0]); result.add(Operator.getOperator("Tw"))
                            result.add(operands[1]); result.add(Operator.getOperator("Tc"))
                        }
                        if (t.name != "Tj") result.add(Operator.getOperator("T*"))
                        result.add(COSArray().apply { add(blank(Piece(op, 0))) })
                        result.add(Operator.getOperator("TJ"))
                    }
                    "TJ" -> {
                        val array = operands.firstOrNull() as? COSArray
                        if (array == null) { result.addAll(operands); result.add(t) } else {
                            val rewritten = COSArray()
                            var element = -1
                            for (item in array) {
                                if (item is COSString) {
                                    element++
                                    val piece = Piece(op, element)
                                    rewritten.add(if (piece in gone) blank(piece) else item)
                                } else if (item is COSNumber) {
                                    rewritten.add(item)
                                }
                            }
                            result.add(rewritten)
                            result.add(t)
                        }
                    }
                    else -> { result.addAll(operands); result.add(t) }
                }
                operands.clear()
            }
            result.addAll(operands)
            val bytes = ByteArrayOutputStream().also { ContentStreamWriter(it).writeTokens(result) }.toByteArray()
            page.setContents(PDStream(doc, ByteArrayInputStream(bytes), COSName.FLATE_DECODE))
        }.isSuccess
    }

    // ============== Fonts ==============

    /** Whether `font` can write every letter of `text`. */
    private fun canWrite(font: PDFont, text: String): Boolean = runCatching {
        val bytes = font.encode(text)
        if (font !is PDVectorFont) return@runCatching true
        val input = ByteArrayInputStream(bytes)
        while (input.available() > 0) if (!font.hasGlyph(font.readCode(input))) return@runCatching false
        true
    }.getOrDefault(false)

    private fun standardFont(family: FontFamily, bold: Boolean, italic: Boolean): PDFont = when (family) {
        FontFamily.SANS -> when {
            bold && italic -> PDType1Font.HELVETICA_BOLD_OBLIQUE
            bold -> PDType1Font.HELVETICA_BOLD
            italic -> PDType1Font.HELVETICA_OBLIQUE
            else -> PDType1Font.HELVETICA
        }
        FontFamily.SERIF -> when {
            bold && italic -> PDType1Font.TIMES_BOLD_ITALIC
            bold -> PDType1Font.TIMES_BOLD
            italic -> PDType1Font.TIMES_ITALIC
            else -> PDType1Font.TIMES_ROMAN
        }
        FontFamily.MONO -> when {
            bold && italic -> PDType1Font.COURIER_BOLD_OBLIQUE
            bold -> PDType1Font.COURIER_BOLD
            italic -> PDType1Font.COURIER_OBLIQUE
            else -> PDType1Font.COURIER
        }
    }

    /** The original font when it has every letter, else a standard font, else one from the phone. */
    private fun fontFor(doc: PDDocument, e: TextEdit, original: PDFont?): PDFont {
        if (e.keepOriginalFont && original != null && canWrite(original, e.text)) return original
        val standard = standardFont(e.family, e.bold, e.italic)
        if (canWrite(standard, e.text)) return standard
        val names = buildList {
            if (e.bold) addAll(listOf("NotoSansBengali-Bold.ttf", "NotoSansDevanagari-Bold.ttf", "Roboto-Bold.ttf"))
            addAll(listOf("NotoSansBengali-Regular.ttf", "NotoSansBengaliUI-Regular.ttf", "NotoSansDevanagari-Regular.ttf",
                "NotoSansDevanagariUI-Regular.ttf", "Roboto-Regular.ttf", "NotoSans-Regular.ttf", "DroidSans.ttf"))
        }
        for (f in names.map { File("/system/fonts", it) }.filter { it.exists() }) {
            val font = runCatching { PDType0Font.load(doc, f) }.getOrNull() ?: continue
            if (canWrite(font, e.text)) return font
        }
        throw IllegalArgumentException("\"${e.text}\" uses letters that cannot be written into the PDF")
    }
}
