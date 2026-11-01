// The app's own PDF folder ("My PDFs"), file naming, and moving files in and out of the app:
// importing picked files, sharing, printing and saving copies to the phone.
package com.bbcl.pdfmaster

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Store {
    private lateinit var app: Context

    fun init(context: Context) {
        app = context.applicationContext
    }

    /** Folder holding the PDFs made by the app. */
    val pdfDir: File get() = File(app.filesDir, "pdfs").apply { mkdirs() }

    /** Scratch space for picked files, camera photos and work in progress. */
    fun tempDir(name: String): File = File(app.cacheDir, name).apply { mkdirs() }

    /** The app's PDFs, newest first. */
    fun list(): List<File> =
        pdfDir.listFiles { f -> f.isFile && f.name.endsWith(".pdf", ignoreCase = true) }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()

    fun isMine(f: File) = f.parentFile?.canonicalPath == pdfDir.canonicalPath

    fun defaultName(prefix: String = "PDF"): String =
        "${prefix}_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    private val illegal = Regex("[\\\\/:*?\"<>|\\x00-\\x1F]")

    /** A file in My PDFs named after `title`, not yet taken. `except` may keep its own name. */
    fun fileFor(title: String, except: File? = null): File {
        val clean = title.replace(illegal, "_").trim().removeSuffix(".pdf").removeSuffix(".PDF").trim()
        val base = clean.ifEmpty { defaultName() }
        var file = File(pdfDir, "$base.pdf")
        var n = 2
        while (file.exists() && file.path != except?.path) file = File(pdfDir, "$base (${n++}).pdf")
        return file
    }

    fun rename(f: File, title: String): File? {
        if (title.isBlank() || title.trim() == titleOf(f)) return null
        val target = fileFor(title, except = f)
        return if (f.renameTo(target)) target else null
    }

    fun displayName(uri: Uri): String? = runCatching {
        app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')

    /** Copies a picked or shared file into the app's scratch space. */
    fun importUri(uri: Uri, folder: String = "inputs", fallbackExt: String = ".pdf"): File {
        val dir = tempDir("$folder/${System.nanoTime()}")
        val name = (displayName(uri) ?: "file$fallbackExt").replace(illegal, "_")
        val out = File(dir, if ('.' in name) name else name + fallbackExt)
        app.contentResolver.openInputStream(uri)!!.use { input -> FileOutputStream(out).use { input.copyTo(it) } }
        return out
    }

    /** Copies `f` into My PDFs (for PDFs opened from elsewhere). */
    fun keep(f: File, title: String = titleOf(f)): File {
        val target = fileFor(title)
        f.copyTo(target)
        return target
    }

    fun uriFor(f: File): Uri = FileProvider.getUriForFile(app, "${app.packageName}.files", f)

    fun shareIntent(files: List<File>, mime: String = "application/pdf"): Intent {
        val uris = ArrayList(files.map(::uriFor))
        val send = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        }
        send.type = mime
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(send, null)
    }

    fun shareText(text: String, subject: String): Intent = Intent.createChooser(
        Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text).putExtra(Intent.EXTRA_SUBJECT, subject),
        null,
    )

    /** Writes `f` to a place the user picked with the system file picker. */
    fun copyTo(f: File, uri: Uri) {
        app.contentResolver.openOutputStream(uri, "w")!!.use { out -> f.inputStream().use { it.copyTo(out) } }
    }

    /** Sends the PDF to Android's print service (printers, or "Save as PDF"). */
    fun print(context: Context, f: File) {
        val manager = context.getSystemService(Context.PRINT_SERVICE) as PrintManager
        manager.print(titleOf(f), object : PrintDocumentAdapter() {
            override fun onLayout(
                old: PrintAttributes?, new: PrintAttributes, cancel: CancellationSignal, callback: LayoutResultCallback, extras: Bundle?,
            ) {
                if (cancel.isCanceled) return callback.onLayoutCancelled()
                val info = PrintDocumentInfo.Builder(f.name).setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).build()
                callback.onLayoutFinished(info, true)
            }

            override fun onWrite(
                pages: Array<out PageRange>, destination: ParcelFileDescriptor, cancel: CancellationSignal, callback: WriteResultCallback,
            ) {
                runCatching {
                    f.inputStream().use { input -> FileOutputStream(destination.fileDescriptor).use { input.copyTo(it) } }
                }.onSuccess { callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES)) }
                    .onFailure { callback.onWriteFailed(it.message) }
            }
        }, null)
    }
}

fun titleOf(f: File): String = f.name.removeSuffix(".pdf").removeSuffix(".PDF")

fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${(bytes + 1023) / 1024} KB"
    else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
}

fun formatDate(millis: Long): String = SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(Date(millis))
