// Making new PDFs from photos and from typed text.
package com.bbcl.pdfmaster

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// ============== Settings ==============

enum class PageSize(val label: String, val widthPt: Float, val heightPt: Float) {
    A4("A4", 595.28f, 841.89f),
    LETTER("Letter", 612f, 792f),
    LEGAL("Legal", 612f, 1008f),
    A5("A5", 419.53f, 595.28f),
    FIT("Fit photo", 0f, 0f),
}

enum class PageOrientation(val label: String) { AUTO("Auto"), PORTRAIT("Portrait"), LANDSCAPE("Landscape") }

enum class PageMargin(val label: String, val points: Float) { NONE("None", 0f), SMALL("Small", 18f), LARGE("Large", 40f) }

enum class ImageQuality(val label: String, val maxSide: Int, val jpeg: Int) {
    HIGH("High", 3000, 90),
    MEDIUM("Medium", 2000, 80),
    SMALL("Small file", 1400, 65),
}

class PdfSettings(
    var size: PageSize = PageSize.A4,
    var orientation: PageOrientation = PageOrientation.AUTO,
    var margin: PageMargin = PageMargin.SMALL,
    var quality: ImageQuality = ImageQuality.MEDIUM,
    var grayscale: Boolean = false,
) {
    fun copy() = PdfSettings(size, orientation, margin, quality, grayscale)

    fun save(context: Context) {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
            .putString("size", size.name).putString("orientation", orientation.name).putString("margin", margin.name)
            .putString("quality", quality.name).putBoolean("grayscale", grayscale).apply()
    }

    companion object {
        fun load(context: Context): PdfSettings {
            val p = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            fun <T : Enum<T>> pick(values: Array<T>, key: String, fallback: T) = values.firstOrNull { it.name == p.getString(key, null) } ?: fallback
            return PdfSettings(
                pick(PageSize.entries.toTypedArray(), "size", PageSize.A4),
                pick(PageOrientation.entries.toTypedArray(), "orientation", PageOrientation.AUTO),
                pick(PageMargin.entries.toTypedArray(), "margin", PageMargin.SMALL),
                pick(ImageQuality.entries.toTypedArray(), "quality", ImageQuality.MEDIUM),
                p.getBoolean("grayscale", false),
            )
        }
    }
}

/** A photo chosen for the PDF. */
class PageImage(val uri: Uri, quarterTurns: Int = 0) {
    val id = nextId++
    var quarterTurns by mutableIntStateOf(quarterTurns) // clockwise

    private companion object {
        var nextId = 0L
    }
}

// ============== Photos to PDF ==============

object ImageToPdf {
    /**
     * Decodes a photo at a reduced size, turns it upright (EXIF), rotates it, optionally makes it
     * black and white, and returns it as JPEG bytes with its size.
     */
    fun prepare(context: Context, uri: Uri, maxSide: Int, quality: Int, rotation: Int, grayscale: Boolean): Triple<ByteArray, Int, Int> {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IllegalArgumentException("Not a supported image")
        // Decode at the smallest power-of-two reduction that still covers maxSide.
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val decoded = resolver.openInputStream(uri)!!.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: throw IllegalArgumentException("Not a supported image")

        val matrix = Matrix()
        val orientation = runCatching {
            resolver.openInputStream(uri)!!.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
        }
        matrix.postRotate(rotation.toFloat())
        val scale = min(1f, maxSide.toFloat() / max(decoded.width, decoded.height))
        matrix.postScale(scale, scale)

        // Draw onto white, so transparent PNGs don't turn black in the JPEG.
        val box = RectF(0f, 0f, decoded.width.toFloat(), decoded.height.toFloat())
        matrix.mapRect(box)
        val width = box.width().roundToInt().coerceAtLeast(1)
        val height = box.height().roundToInt().coerceAtLeast(1)
        matrix.postTranslate(-box.left, -box.top)
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        if (grayscale) paint.colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
        Canvas(out).apply {
            drawColor(Color.WHITE)
            drawBitmap(decoded, matrix, paint)
        }
        decoded.recycle()
        val bytes = ByteArrayOutputStream()
        out.compress(Bitmap.CompressFormat.JPEG, quality, bytes)
        out.recycle()
        return Triple(bytes.toByteArray(), width, height)
    }

    fun make(context: Context, pages: List<PageImage>, s: PdfSettings, title: String, progress: Progress): File {
        val prepared = pages.mapIndexed { i, p ->
            val (jpeg, w, h) = prepare(context, p.uri, s.quality.maxSide, s.quality.jpeg, p.quarterTurns % 4 * 90, s.grayscale)
            progress(i + 1, pages.size + 1)
            val margin = s.margin.points
            val landscapePhoto = w > h
            val (pw, ph) = if (s.size == PageSize.FIT) {
                // The page takes the photo's shape, with its long side as long as A4's.
                val scale = (PageSize.A4.heightPt - 2 * margin) / (if (landscapePhoto) w else h)
                (w * scale + 2 * margin) to (h * scale + 2 * margin)
            } else {
                val landscape = when (s.orientation) {
                    PageOrientation.AUTO -> landscapePhoto
                    PageOrientation.PORTRAIT -> false
                    PageOrientation.LANDSCAPE -> true
                }
                if (landscape) s.size.heightPt to s.size.widthPt else s.size.widthPt to s.size.heightPt
            }
            PdfOps.ImagePage(jpeg, pw, ph, margin)
        }
        val out = Store.fileFor(title)
        PdfOps.imagesToPdf(prepared, titleOf(out), out)
        progress(pages.size + 1, pages.size + 1)
        return out
    }
}

// ============== Text to PDF ==============

object TextToPdf {
    enum class Font(val label: String, val typeface: Typeface) {
        SANS("Sans", Typeface.SANS_SERIF), SERIF("Serif", Typeface.SERIF), MONO("Mono", Typeface.MONOSPACE),
    }

    /**
     * Lays the text out with Android's own text engine (so every script and emoji the phone can
     * show comes out right) and writes it as real, selectable text.
     */
    fun make(text: String, title: String, size: PageSize, font: Font, fontSize: Float, out: File) {
        val pageSize = if (size == PageSize.FIT) PageSize.A4 else size
        val w = pageSize.widthPt.roundToInt()
        val h = pageSize.heightPt.roundToInt()
        val margin = 50
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = font.typeface
            textSize = fontSize
            color = Color.BLACK
        }
        val titlePaint = TextPaint(paint).apply {
            typeface = Typeface.create(font.typeface, Typeface.BOLD)
            textSize = fontSize * 1.6f
        }
        val contentW = w - 2 * margin
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, contentW)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0f, 1.25f).build()
        val heading = title.takeIf { it.isNotBlank() }?.let {
            StaticLayout.Builder.obtain(it, 0, it.length, titlePaint, contentW).build()
        }

        val doc = PdfDocument()
        var line = 0
        var pageNo = 1
        while (line < layout.lineCount || pageNo == 1) {
            val page = doc.startPage(PdfDocument.PageInfo.Builder(w, h, pageNo).create())
            val canvas = page.canvas
            var top = margin.toFloat()
            if (pageNo == 1 && heading != null) {
                canvas.save()
                canvas.translate(margin.toFloat(), top)
                heading.draw(canvas)
                canvas.restore()
                top += heading.height + fontSize
            }
            val available = h - margin - top
            val first = line
            val startY = layout.getLineTop(first)
            while (line < layout.lineCount && layout.getLineBottom(line) - startY <= available) line++
            if (line == first && line < layout.lineCount) line++ // a single over-tall line still advances
            if (line > first) {
                canvas.save()
                canvas.translate(margin.toFloat(), top - startY)
                canvas.clipRect(0f, startY.toFloat(), contentW.toFloat(), layout.getLineBottom(line - 1).toFloat())
                layout.draw(canvas)
                canvas.restore()
            }
            doc.finishPage(page)
            pageNo++
            if (layout.lineCount == 0) break
        }
        out.parentFile?.mkdirs()
        FileOutputStream(out).use { doc.writeTo(it) }
        doc.close()
    }
}
