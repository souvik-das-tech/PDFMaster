// Drawing PDF pages as pictures with Android's own PdfRenderer: previews, thumbnails, page
// images, and the "pages as pictures" PDFs (strong compression, black & white).
package com.bbcl.pdfmaster

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.LruCache
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt

object Raster {
    private val lock = Any() // PdfRenderer allows one open page at a time

    private val cache = object : LruCache<String, Bitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    /** Unlocked copies of password-protected PDFs, so PdfRenderer can draw them. */
    private val unlocked = HashMap<String, File>()

    /** A file PdfRenderer can open: the PDF itself, or an unlocked copy of it. */
    fun renderable(src: PdfSource): File {
        if (src.password.isNullOrEmpty()) return src.file
        val key = "${src.file.path}|${src.file.lastModified()}|${src.password.hashCode()}"
        synchronized(unlocked) {
            unlocked[key]?.takeIf { it.exists() }?.let { return it }
        }
        val out = File(Store.tempDir("unlocked"), "${key.hashCode()}.pdf")
        PdfOps.unlock(src, out)
        synchronized(unlocked) { unlocked[key] = out }
        return out
    }

    private fun <T> withPdf(f: File, use: (PdfRenderer) -> T): T = synchronized(lock) {
        ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY).use { fd -> PdfRenderer(fd).use(use) }
    }

    fun pageCount(f: File): Int = withPdf(f) { it.pageCount }

    /** Page sizes in points (1/72 inch). */
    fun pageSizes(f: File): List<Pair<Int, Int>> = withPdf(f) { pdf ->
        (0 until pdf.pageCount).map { i -> pdf.openPage(i).use { it.width to it.height } }
    }

    /** Page `index` drawn `width` pixels wide on white. Cached. */
    fun page(f: File, index: Int, width: Int): Bitmap {
        val key = "${f.path}|${f.lastModified()}|$index|$width"
        cache.get(key)?.let { return it }
        val bitmap = withPdf(f) { pdf ->
            pdf.openPage(index).use { page ->
                val height = (width.toFloat() * page.height / page.width).roundToInt().coerceAtLeast(1)
                Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                    it.eraseColor(Color.WHITE)
                    page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                }
            }
        }
        cache.put(key, bitmap)
        return bitmap
    }

    class RenderedPage(val file: File, val widthPt: Float, val heightPt: Float)

    /** Every page as a JPEG at `dpi`, optionally black & white. */
    fun renderAll(f: File, dpi: Int, quality: Int, gray: Boolean, outDir: File, progress: Progress): List<RenderedPage> {
        val count = pageCount(f)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
            if (gray) colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
        }
        return (0 until count).map { i ->
            val (bitmap, wPt, hPt) = withPdf(f) { pdf ->
                pdf.openPage(i).use { page ->
                    val scale = dpi / 72f
                    val w = (page.width * scale).roundToInt().coerceIn(1, 4000)
                    val h = (page.height * scale).roundToInt().coerceIn(1, 4000)
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                    Triple(bmp, page.width.toFloat(), page.height.toFloat())
                }
            }
            val outBmp = if (gray) {
                Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888).also {
                    Canvas(it).drawBitmap(bitmap, 0f, 0f, paint)
                    bitmap.recycle()
                }
            } else {
                bitmap
            }
            val out = File(outDir, "page_${i + 1}.jpg")
            FileOutputStream(out).use { outBmp.compress(Bitmap.CompressFormat.JPEG, quality, it) }
            outBmp.recycle()
            progress(i + 1, count)
            RenderedPage(out, wPt, hPt)
        }
    }

    enum class ImageFormat(val label: String, val ext: String, val mime: String, val format: Bitmap.CompressFormat) {
        JPG("JPG", "jpg", "image/jpeg", Bitmap.CompressFormat.JPEG),
        PNG("PNG", "png", "image/png", Bitmap.CompressFormat.PNG),
    }

    /**
     * Saves the chosen pages as pictures: into the gallery (Pictures/PDF Master) on Android 10+,
     * or into `tree` (a folder the user picked) on older phones. Returns how many were saved.
     */
    fun exportImages(
        context: Context, src: File, pages: List<Int>, dpi: Int, format: ImageFormat, baseName: String, tree: Uri?, progress: Progress,
    ): Int {
        val resolver = context.contentResolver
        val folder = tree?.let { DocumentFile.fromTreeUri(context, it) }
        pages.forEachIndexed { n, i ->
            val bitmap = withPdf(src) { pdf ->
                pdf.openPage(i).use { page ->
                    val scale = dpi / 72f
                    val w = (page.width * scale).roundToInt().coerceIn(1, 5000)
                    val h = (page.height * scale).roundToInt().coerceIn(1, 5000)
                    Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
                        it.eraseColor(Color.WHITE)
                        page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                    }
                }
            }
            val name = "${baseName}_page${i + 1}.${format.ext}"
            if (folder != null) {
                val doc = folder.createFile(format.mime, name) ?: throw IllegalStateException("Could not create $name")
                resolver.openOutputStream(doc.uri)!!.use { bitmap.compress(format.format, 92, it) }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, format.mime)
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/PDF Master")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("Could not save to the gallery")
                resolver.openOutputStream(uri)!!.use { bitmap.compress(format.format, 92, it) }
                resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            } else {
                throw IllegalStateException("Choose a folder to save the pictures in")
            }
            bitmap.recycle()
            progress(n + 1, pages.size)
        }
        return pages.size
    }
}
