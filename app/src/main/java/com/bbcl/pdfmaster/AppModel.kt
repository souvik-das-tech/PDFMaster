// App state: the screen stack, My PDFs, running jobs with progress, password prompts and
// messages. Screens call into here; heavy work runs on background threads.
package com.bbcl.pdfmaster

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.AutoAwesomeMosaic
import androidx.compose.material.icons.automirrored.filled.BrandingWatermark
import androidx.compose.material.icons.automirrored.filled.CallMerge
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.FilterBAndW
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.automirrored.filled.TextSnippet
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class ToolGroup(val label: String) { CREATE("Create"), ORGANIZE("Organize"), OPTIMIZE("Optimize"), SECURE("Security"), EDIT("Edit"), CONVERT("Convert") }

enum class Tool(val label: String, val detail: String, val icon: ImageVector, val group: ToolGroup, val needsPdf: Boolean = true) {
    IMAGES("Image to PDF", "Photos into one PDF", Icons.Filled.AddPhotoAlternate, ToolGroup.CREATE, needsPdf = false),
    SCAN("Camera Scan", "Take photos of pages to make a PDF", Icons.Filled.PhotoCamera, ToolGroup.CREATE, needsPdf = false),
    TEXT_TO_PDF("Text to PDF", "Type or paste text", Icons.Filled.TextFields, ToolGroup.CREATE, needsPdf = false),
    MERGE("Merge PDFs", "Join several PDFs", Icons.AutoMirrored.Filled.CallMerge, ToolGroup.ORGANIZE, needsPdf = false),
    SPLIT("Split PDF", "Into parts or single pages", Icons.AutoMirrored.Filled.CallSplit, ToolGroup.ORGANIZE),
    EXTRACT("Extract pages", "Keep the pages you pick", Icons.Filled.ContentCut, ToolGroup.ORGANIZE),
    ORGANIZE("Organize pages", "Reorder, rotate, delete", Icons.Filled.AutoAwesomeMosaic, ToolGroup.ORGANIZE),
    COMPRESS("Compress PDF", "Make the file smaller", Icons.Filled.Compress, ToolGroup.OPTIMIZE),
    GRAYSCALE("Black & white", "Remove colour", Icons.Filled.FilterBAndW, ToolGroup.OPTIMIZE),
    PROTECT("Add password", "Lock with a password", Icons.Filled.Lock, ToolGroup.SECURE),
    UNLOCK("Remove password", "Unlock a protected PDF", Icons.Filled.LockOpen, ToolGroup.SECURE),
    EDIT_TEXT("Edit PDF", "Change, delete or add text", Icons.Filled.EditNote, ToolGroup.EDIT),
    WATERMARK("Watermark", "Stamp text on pages", Icons.AutoMirrored.Filled.BrandingWatermark, ToolGroup.EDIT),
    PAGE_NUMBERS("Page numbers", "Number every page", Icons.Filled.FormatListNumbered, ToolGroup.EDIT),
    SIGN("Sign PDF", "Draw and place a signature", Icons.Filled.Draw, ToolGroup.EDIT),
    PROPERTIES("Properties", "Title, author, details", Icons.Filled.Info, ToolGroup.EDIT),
    TO_IMAGES("PDF to images", "Save pages as JPG/PNG", Icons.Filled.Image, ToolGroup.CONVERT),
    TO_TEXT("PDF to text", "Copy out the text", Icons.AutoMirrored.Filled.TextSnippet, ToolGroup.CONVERT),
    VIEW("Open a PDF", "View any PDF", Icons.Filled.PictureAsPdf, ToolGroup.CONVERT),
    ;

    val color: Color
        get() = when (group) {
            ToolGroup.CREATE -> Brand.create
            ToolGroup.ORGANIZE -> Brand.organize
            ToolGroup.OPTIMIZE -> Brand.optimize
            ToolGroup.SECURE -> Brand.secure
            ToolGroup.EDIT -> Brand.edit
            ToolGroup.CONVERT -> Brand.convert
        }
}

sealed interface Screen {
    data object Home : Screen
    data class Viewer(val src: PdfSource) : Screen
    data class Images(val initial: List<Uri>, val camera: Boolean = false) : Screen
    data object TextToPdf : Screen
    data class Merge(val initial: List<PdfSource>) : Screen
    /** Page-grid tools: split, extract, organize, PDF to images. */
    data class Pages(val tool: Tool, val src: PdfSource) : Screen
    /** Form tools: compress, black & white, passwords, watermark, numbers, properties, text. */
    data class Form(val tool: Tool, val src: PdfSource) : Screen
    data class Sign(val src: PdfSource) : Screen
    data class EditText(val src: PdfSource) : Screen
    data class Result(val files: List<File>, val headline: String, val detail: String, val text: String? = null) : Screen
}

class Job(val title: String) {
    var done by mutableStateOf(0)
    var total by mutableStateOf(0)
}

class PasswordPrompt(val file: File, val wrong: Boolean, val onResult: (String?) -> Unit)

class AppModel(app: Application) : AndroidViewModel(app) {
    private val context get() = getApplication<Application>()

    val stack = mutableStateListOf<Screen>(Screen.Home)
    val screen get() = stack.last()

    var pdfs by mutableStateOf<List<File>?>(null)
        private set
    var job by mutableStateOf<Job?>(null)
        private set
    var passwordPrompt by mutableStateOf<PasswordPrompt?>(null)
        private set

    /** The tool waiting for the user to choose its PDF. */
    var choosingFor by mutableStateOf<Tool?>(null)

    val messages = MutableSharedFlow<String>(extraBufferCapacity = 4)

    /** Asks the activity to start an intent (share, open settings, ...). */
    val intents = MutableSharedFlow<Intent>(extraBufferCapacity = 4)

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            pdfs = withContext(Dispatchers.IO) { Store.list() }
        }
    }

    fun say(text: String) {
        messages.tryEmit(text)
    }

    // ============== Navigation ==============

    fun open(s: Screen) {
        stack.add(s)
    }

    fun replace(s: Screen) {
        stack[stack.lastIndex] = s
    }

    fun back() {
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
        refresh()
    }

    fun home() {
        while (stack.size > 1) stack.removeAt(stack.lastIndex)
        refresh()
    }

    // ============== Choosing PDFs ==============

    fun startTool(tool: Tool) {
        when (tool) {
            Tool.IMAGES -> open(Screen.Images(emptyList()))
            Tool.SCAN -> open(Screen.Images(emptyList(), camera = true))
            Tool.TEXT_TO_PDF -> open(Screen.TextToPdf)
            Tool.MERGE -> open(Screen.Merge(emptyList()))
            else -> choosingFor = tool
        }
    }

    /** Runs `tool` on `file` (after asking for its password if it has one). */
    fun useFile(tool: Tool, file: File, replaceCurrent: Boolean = false) {
        unlockThen(file) { src ->
            val s = screenFor(tool, src)
            if (replaceCurrent) replace(s) else open(s)
        }
    }

    fun screenFor(tool: Tool, src: PdfSource): Screen = when (tool) {
        Tool.SPLIT, Tool.EXTRACT, Tool.ORGANIZE, Tool.TO_IMAGES -> Screen.Pages(tool, src)
        Tool.SIGN -> Screen.Sign(src)
        Tool.EDIT_TEXT -> Screen.EditText(src)
        Tool.MERGE -> Screen.Merge(listOf(src))
        Tool.VIEW -> Screen.Viewer(src)
        else -> Screen.Form(tool, src)
    }

    fun useUri(tool: Tool, uri: Uri) {
        viewModelScope.launch {
            val file = runCatching { withContext(Dispatchers.IO) { Store.importUri(uri) } }
                .getOrElse { say("Could not open that file: ${it.message}"); return@launch }
            useFile(tool, file)
        }
    }

    /** Checks whether `file` needs a password; asks for it until it is right or cancelled. */
    fun unlockThen(file: File, then: (PdfSource) -> Unit) {
        viewModelScope.launch {
            val locked = runCatching { withContext(Dispatchers.IO) { PdfOps.needsPassword(file) } }
                .getOrElse { say("This file is not a PDF that can be opened."); return@launch }
            if (!locked) return@launch then(PdfSource(file))
            fun ask(wrong: Boolean) {
                passwordPrompt = PasswordPrompt(file, wrong) { pw ->
                    passwordPrompt = null
                    if (pw == null) return@PasswordPrompt
                    viewModelScope.launch {
                        val ok = withContext(Dispatchers.IO) { PdfOps.checkPassword(file, pw) }
                        if (ok) then(PdfSource(file, pw)) else ask(true)
                    }
                }
            }
            ask(false)
        }
    }

    // ============== Incoming intents ==============

    fun handleIntent(intent: Intent?) {
        intent ?: return
        val uris = mutableListOf<Uri>()
        when (intent.action) {
            Intent.ACTION_VIEW -> intent.data?.let(uris::add)
            Intent.ACTION_SEND -> intent.streamExtra()?.let(uris::add)
            Intent.ACTION_SEND_MULTIPLE -> uris.addAll(intent.streamListExtra())
            else -> return
        }
        if (uris.isEmpty()) return
        val type = intent.type ?: context.contentResolver.getType(uris[0]) ?: ""
        if (type.startsWith("image/")) {
            home()
            open(Screen.Images(uris))
            return
        }
        viewModelScope.launch {
            val files = runCatching { withContext(Dispatchers.IO) { uris.map { Store.importUri(it) } } }
                .getOrElse { say("Could not open that file: ${it.message}"); return@launch }
            home()
            if (files.size == 1) {
                useFile(Tool.VIEW, files[0])
            } else {
                open(Screen.Merge(emptyList()))
                files.forEach { f -> unlockThen(f) { addToMerge = it } }
            }
        }
    }

    /** Files added to the merge list by the intent flow, picked up by the merge screen. */
    var addToMerge by mutableStateOf<PdfSource?>(null)

    @Suppress("DEPRECATION")
    private fun Intent.streamExtra(): Uri? =
        if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else getParcelableExtra(Intent.EXTRA_STREAM)

    @Suppress("DEPRECATION")
    private fun Intent.streamListExtra(): List<Uri> =
        (if (Build.VERSION.SDK_INT >= 33) getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) else getParcelableArrayListExtra(Intent.EXTRA_STREAM))
            ?: emptyList()

    // ============== Jobs ==============

    /**
     * Runs `work` in the background with a progress dialog, then shows its result screen in
     * place of the current screen.
     */
    fun run(title: String, work: suspend (progress: Progress) -> Screen.Result) {
        if (job != null) return
        val j = Job(title)
        job = j
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    work { done, total ->
                        j.done = done
                        j.total = total
                    }
                }
                replace(result)
            } catch (e: CancellationException) {
                throw e
            } catch (e: PasswordNeeded) {
                say("This PDF needs its password.")
            } catch (e: OutOfMemoryError) {
                say("This PDF is too large to process on this phone.")
            } catch (e: Throwable) {
                say("Something went wrong: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                job = null
                refresh()
            }
        }
    }

    // ============== File actions ==============

    fun share(files: List<File>) {
        intents.tryEmit(Store.shareIntent(files))
    }

    fun rename(f: File, title: String): File? {
        val renamed = Store.rename(f, title)
        if (renamed != null) {
            stack.replaceAll { s -> if (s is Screen.Viewer && s.src.file == f) s.copy(src = s.src.copy(file = renamed)) else s }
            refresh()
        }
        return renamed
    }

    fun delete(f: File) {
        f.delete()
        refresh()
    }

    /** Saves a PDF opened from elsewhere into My PDFs. */
    fun keep(src: PdfSource) {
        viewModelScope.launch {
            val kept = withContext(Dispatchers.IO) { Store.keep(src.file) }
            stack.replaceAll { s -> if (s is Screen.Viewer && s.src.file == src.file) s.copy(src = src.copy(file = kept)) else s }
            say("Added to My PDFs")
            refresh()
        }
    }

    fun saveCopy(f: File, uri: Uri) {
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { Store.copyTo(f, uri) } }
                .onSuccess { say("Saved ${f.name}") }
                .onFailure { say("Could not save: ${it.message}") }
        }
    }
}
