// Tools that take a few options for one PDF: compress, black & white, add / remove password,
// watermark, page numbers, properties and PDF to text.
package com.bbcl.pdfmaster

import android.content.ClipData
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.automirrored.filled.TextSnippet
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.atan2
import kotlin.math.roundToInt

private enum class CompressMethod(val label: String) { SMART("Smart"), MAX("Maximum") }

private val watermarkColors = listOf(Color(0xFF9E9E9E), Color(0xFFE53935), Color(0xFF1E88E5), Color(0xFF212121), Color(0xFF43A047))

@Composable
fun FormScreen(model: AppModel, tool: Tool, src: PdfSource) {
    val base = titleOf(src.file)
    var name by rememberSaveable {
        mutableStateOf(
            base + when (tool) {
                Tool.COMPRESS -> "_compressed"
                Tool.GRAYSCALE -> "_bw"
                Tool.PROTECT -> "_protected"
                Tool.UNLOCK -> "_unlocked"
                Tool.WATERMARK -> "_watermarked"
                Tool.PAGE_NUMBERS -> "_numbered"
                else -> ""
            },
        )
    }
    if (tool == Tool.TO_TEXT) return TextScreen(model, src)
    if (tool == Tool.PROPERTIES) return PropertiesScreen(model, src)

    // Options of every form tool (only the current tool's are shown).
    var method by remember { mutableStateOf(CompressMethod.SMART) }
    var level by remember { mutableStateOf(PdfOps.CompressLevel.MEDIUM) }
    var rasterQuality by remember { mutableFloatStateOf(60f) }
    var pw by remember { mutableStateOf("") }
    var pw2 by remember { mutableStateOf("") }
    var allowPrint by remember { mutableStateOf(true) }
    var allowCopy by remember { mutableStateOf(false) }
    var allowEdit by remember { mutableStateOf(false) }
    var wmText by rememberSaveable { mutableStateOf("CONFIDENTIAL") }
    var wmStyle by remember { mutableStateOf(PdfOps.WatermarkStyle.DIAGONAL) }
    var wmOpacity by remember { mutableFloatStateOf(0.25f) }
    var wmSize by remember { mutableFloatStateOf(0.6f) }
    var wmColor by remember { mutableStateOf(watermarkColors[0]) }
    var numPos by remember { mutableStateOf(PdfOps.NumberPosition.BOTTOM_CENTER) }
    var numFormat by remember { mutableStateOf(PdfOps.NumberFormat.PAGE_OF) }
    var numStart by remember { mutableStateOf("1") }
    var numSize by remember { mutableFloatStateOf(11f) }

    val pwError = when {
        pw.isNotEmpty() && pw.length < 4 -> "Use at least 4 characters"
        pw2.isNotEmpty() && pw != pw2 -> "The passwords do not match"
        else -> null
    }
    val (actionText, enabled) = when (tool) {
        Tool.COMPRESS -> "Compress PDF" to true
        Tool.GRAYSCALE -> "Make black & white" to true
        Tool.PROTECT -> "Add password" to (pw.length >= 4 && pw == pw2)
        Tool.UNLOCK -> "Remove password" to (src.password != null)
        Tool.WATERMARK -> "Add watermark" to wmText.isNotBlank()
        Tool.PAGE_NUMBERS -> "Add page numbers" to ((numStart.toIntOrNull() ?: 0) >= 0)
        else -> "Save" to true
    }

    Scaffold(
        topBar = { ToolBar(tool.label, onBack = model::back) },
        bottomBar = {
            BottomAction(actionText, enabled, tool.icon) {
                val before = src.file.length()
                when (tool) {
                    Tool.COMPRESS -> {
                        val m = method
                        val lv = level
                        val q = rasterQuality.roundToInt()
                        model.run("Compressing") { progress ->
                            val out = Store.fileFor(name)
                            if (m == CompressMethod.SMART) {
                                PdfOps.compress(src, lv, out, progress)
                            } else {
                                val tmp = Store.tempDir("raster/${System.nanoTime()}")
                                val pages = Raster.renderAll(Raster.renderable(src), 110, q, false, tmp) { d, t -> progress(d, t + 1) }
                                PdfOps.pagesFromImages(pages, titleOf(out), out)
                                tmp.deleteRecursively()
                            }
                            compressResult(out, before)
                        }
                    }
                    Tool.GRAYSCALE -> model.run("Converting to black & white") { progress ->
                        val out = Store.fileFor(name)
                        val tmp = Store.tempDir("raster/${System.nanoTime()}")
                        val pages = Raster.renderAll(Raster.renderable(src), 150, 80, true, tmp, progress)
                        PdfOps.pagesFromImages(pages, titleOf(out), out)
                        tmp.deleteRecursively()
                        Screen.Result(listOf(out), "Black & white PDF ready", "${pages.size} pages • ${formatSize(out.length())}")
                    }
                    Tool.PROTECT -> {
                        val password = pw
                        val (p, c, e) = Triple(allowPrint, allowCopy, allowEdit)
                        model.run("Adding password") { _ ->
                            val out = Store.fileFor(name)
                            PdfOps.protect(src, password, p, c, e, out)
                            Screen.Result(listOf(out), "Password added", "Anyone opening \"${out.name}\" will need the password. Keep it safe: it cannot be recovered.")
                        }
                    }
                    Tool.UNLOCK -> model.run("Removing password") { _ ->
                        val out = Store.fileFor(name)
                        PdfOps.unlock(src, out)
                        Screen.Result(listOf(out), "Password removed", "\"${out.name}\" now opens without a password.")
                    }
                    Tool.WATERMARK -> {
                        val text = wmText.trim()
                        val color = wmColor.toArgb()
                        val opacity = wmOpacity
                        val size = wmSize
                        val style = wmStyle
                        model.run("Adding watermark") { progress ->
                            val out = Store.fileFor(name)
                            PdfOps.watermark(src, text, color, opacity, size, style, out, progress)
                            Screen.Result(listOf(out), "Watermark added", "\"$text\" on every page • ${formatSize(out.length())}")
                        }
                    }
                    Tool.PAGE_NUMBERS -> {
                        val pos = numPos
                        val fmt = numFormat
                        val start = numStart.toIntOrNull() ?: 1
                        val size = numSize
                        model.run("Numbering pages") { progress ->
                            val out = Store.fileFor(name)
                            PdfOps.pageNumbers(src, pos, fmt, start, size, out, progress)
                            Screen.Result(listOf(out), "Page numbers added", formatSize(out.length()))
                        }
                    }
                    else -> {}
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            SourceCard(src)
            NameField(name, { name = it }, "New PDF name", modifier = Modifier.padding(top = 12.dp))
            when (tool) {
                Tool.COMPRESS -> {
                    SectionTitle("How to compress")
                    OptionRow(method == CompressMethod.SMART, "Smart compression", "Shrinks the pictures inside. Text stays sharp and selectable.") { method = CompressMethod.SMART }
                    OptionRow(method == CompressMethod.MAX, "Maximum compression", "Turns every page into a picture. Smallest for scans; text can no longer be selected.") { method = CompressMethod.MAX }
                    if (method == CompressMethod.SMART) {
                        SectionTitle("Strength")
                        for (l in PdfOps.CompressLevel.entries) OptionRow(level == l, l.label, l.detail) { level = l }
                    } else {
                        SectionTitle("Picture quality: ${rasterQuality.roundToInt()}%")
                        Slider(rasterQuality, { rasterQuality = it }, valueRange = 30f..90f)
                    }
                }
                Tool.GRAYSCALE -> Note("Every page is redrawn in shades of grey. Good for printing and for scans. Text becomes part of the page picture and can no longer be selected.")
                Tool.PROTECT -> {
                    if (src.password != null) Note("This PDF already has a password. The new password replaces it.")
                    SectionTitle("Password")
                    PasswordField(pw, { pw = it }, "Password")
                    Spacer(Modifier.height(8.dp))
                    PasswordField(pw2, { pw2 = it }, "Repeat password", error = pwError)
                    SectionTitle("People with the password may")
                    ToggleRow("Print", allowPrint) { allowPrint = it }
                    ToggleRow("Copy text and pictures", allowCopy) { allowCopy = it }
                    ToggleRow("Edit, fill forms and comment", allowEdit) { allowEdit = it }
                    Note("Uses 256-bit AES encryption. If you forget the password, the PDF cannot be opened.")
                }
                Tool.UNLOCK -> Note(
                    if (src.password == null) "This PDF has no password to remove." else "The password you entered is removed. The new copy opens on any device without a password; the original stays protected.",
                )
                Tool.WATERMARK -> {
                    SectionTitle("Text")
                    OutlinedTextField(wmText, { wmText = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    SectionTitle("Preview")
                    WatermarkPreview(src, wmText, wmColor, wmOpacity, wmSize, wmStyle)
                    Spacer(Modifier.height(12.dp))
                    Choice("Placement", PdfOps.WatermarkStyle.entries, wmStyle, { it.label }) { wmStyle = it }
                    Text("Colour", style = MaterialTheme.typography.labelLarge)
                    Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        for (c in watermarkColors) {
                            Box(
                                Modifier.size(36.dp).clip(CircleShape).background(c).border(3.dp, if (c == wmColor) MaterialTheme.colorScheme.primary else Color.Transparent, CircleShape)
                                    .then(Modifier.padding(0.dp)),
                                contentAlignment = Alignment.Center,
                            ) {
                                androidx.compose.material3.IconButton(onClick = { wmColor = c }) {
                                    if (c == wmColor) Icon(Icons.Filled.Check, null, tint = Color.White)
                                }
                            }
                        }
                    }
                    Text("Opacity: ${(wmOpacity * 100).roundToInt()}%", style = MaterialTheme.typography.labelLarge)
                    Slider(wmOpacity, { wmOpacity = it }, valueRange = 0.05f..0.9f)
                    Text("Size", style = MaterialTheme.typography.labelLarge)
                    Slider(wmSize, { wmSize = it }, valueRange = 0.2f..0.9f)
                }
                Tool.PAGE_NUMBERS -> {
                    SectionTitle("Position")
                    for (p in PdfOps.NumberPosition.entries) OptionRow(numPos == p, p.label, null) { numPos = p }
                    SectionTitle("Style")
                    Choice("Format", PdfOps.NumberFormat.entries, numFormat, { it.label }) { numFormat = it }
                    OutlinedTextField(numStart, { numStart = it.filter(Char::isDigit).take(5) }, label = { Text("First number") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                    Text("Text size: ${numSize.roundToInt()} pt", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp))
                    Slider(numSize, { numSize = it }, valueRange = 8f..20f)
                }
                else -> {}
            }
        }
    }
}

private fun compressResult(out: File, before: Long): Screen.Result {
    val after = out.length()
    if (after >= before) {
        out.delete()
        return Screen.Result(emptyList(), "Already compact", "This PDF could not be made smaller (${formatSize(before)}). Try \"Maximum compression\" for scanned documents.")
    }
    val saved = ((before - after) * 100.0 / before).roundToInt()
    return Screen.Result(listOf(out), "Compressed by $saved%", "${formatSize(before)} → ${formatSize(after)}")
}

@Composable
fun SourceCard(src: PdfSource) {
    val render by produceState<File?>(null, src) { value = withContext(Dispatchers.IO) { runCatching { Raster.renderable(src) }.getOrNull() } }
    val pages = render?.let { rememberPageCount(it) }
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            val f = render
            if (f != null) PdfPage(f, 0, 160, Modifier.size(44.dp, 58.dp).clip(RoundedCornerShape(4.dp)), crop = true)
            else Box(Modifier.size(44.dp, 58.dp).background(Color.White))
            Spacer(Modifier.width(12.dp))
            Column {
                Text(titleOf(src.file), style = MaterialTheme.typography.titleSmall)
                Text(
                    listOfNotNull(pages?.let { "$it page${if (it == 1) "" else "s"}" }, formatSize(src.file.length()), if (src.password != null) "Protected" else null).joinToString(" • "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun OptionRow(selected: Boolean, title: String, detail: String?, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else Color.Transparent,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    ) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected, onClick)
            Column(Modifier.padding(start = 4.dp)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ToggleRow(title: String, value: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(headlineContent = { Text(title) }, trailingContent = { Switch(value, onChange) }, modifier = Modifier.padding(0.dp))
}

@Composable
fun Note(text: String) {
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f), modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(12.dp))
    }
}

/** The first page with the watermark drawn over it roughly as it will appear. */
@Composable
private fun WatermarkPreview(src: PdfSource, text: String, color: Color, opacity: Float, size: Float, style: PdfOps.WatermarkStyle) {
    val render by produceState<File?>(null, src) { value = withContext(Dispatchers.IO) { runCatching { Raster.renderable(src) }.getOrNull() } }
    val f = render ?: return
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.width(220.dp).border(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            PdfPage(f, 0, 500, Modifier.fillMaxWidth())
            val w = maxWidth.value
            val h = w * 1.414f
            val diag = kotlin.math.sqrt(w * w + h * h)
            val chars = text.length.coerceAtLeast(1)
            var fs = diag * size / (chars * 0.6f)
            if (style != PdfOps.WatermarkStyle.DIAGONAL) fs *= 0.8f
            val angle = when (style) {
                PdfOps.WatermarkStyle.DIAGONAL -> -Math.toDegrees(atan2(h.toDouble(), w.toDouble())).toFloat()
                PdfOps.WatermarkStyle.CENTER -> 0f
                PdfOps.WatermarkStyle.TILED -> -30f
            }
            if (style == PdfOps.WatermarkStyle.TILED) {
                Column(Modifier.matchParentSize(), verticalArrangement = Arrangement.SpaceEvenly, horizontalAlignment = Alignment.CenterHorizontally) {
                    repeat(4) {
                        Text(text, color = color.copy(alpha = opacity), fontSize = (fs * 0.35f).sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false, modifier = Modifier.wrapContentWidth(unbounded = true).rotate(angle))
                    }
                }
            } else {
                Text(text, color = color.copy(alpha = opacity), fontSize = fs.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false, modifier = Modifier.align(Alignment.Center).wrapContentWidth(unbounded = true).rotate(angle))
            }
        }
    }
}

// ============== PDF to text ==============

@Composable
private fun TextScreen(model: AppModel, src: PdfSource) {
    val text by produceState<Result<String>?>(null, src) { value = withContext(Dispatchers.IO) { runCatching { PdfOps.extractText(src) } } }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val content = text?.getOrNull()
    val saveTxt = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val t = content ?: return@rememberLauncherForActivityResult
        if (uri != null) {
            scope.launch {
                runCatching { withContext(Dispatchers.IO) { model.getApplication<android.app.Application>().contentResolver.openOutputStream(uri, "w")!!.use { it.write(t.toByteArray()) } } }
                    .onSuccess { model.say("Text saved") }.onFailure { model.say("Could not save: ${it.message}") }
            }
        }
    }
    Scaffold(topBar = { ToolBar("PDF to text", onBack = model::back, subtitle = titleOf(src.file)) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(16.dp)) {
            when {
                text == null -> Spinner()
                content == null -> EmptyState(Icons.AutoMirrored.Filled.TextSnippet, "Could not read the text", text?.exceptionOrNull()?.message ?: "")
                content.lines().none { it.isNotBlank() && !it.startsWith("--- Page") } -> EmptyState(
                    Icons.AutoMirrored.Filled.TextSnippet,
                    "No text found",
                    "This PDF has no selectable text — it is probably a scan or photos. Text recognition (OCR) would be needed.",
                )
                else -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = { scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("PDF text", content))); model.say("Text copied") } }) {
                            Icon(Icons.Filled.ContentCopy, null); Spacer(Modifier.width(6.dp)); Text("Copy")
                        }
                        FilledTonalButton(onClick = { model.intents.tryEmit(Store.shareText(content, titleOf(src.file))) }) {
                            Icon(Icons.Filled.Share, null); Spacer(Modifier.width(6.dp)); Text("Share")
                        }
                        FilledTonalButton(onClick = { saveTxt.launch(titleOf(src.file) + ".txt") }) {
                            Icon(Icons.Filled.Download, null); Spacer(Modifier.width(6.dp)); Text(".txt")
                        }
                    }
                    Text("${content.length} characters • select text to copy part of it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
                    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxSize()) {
                        SelectionContainer {
                            Text(content, fontFamily = FontFamily.Default, fontSize = 14.sp, modifier = Modifier.verticalScroll(rememberScrollState()).padding(12.dp))
                        }
                    }
                }
            }
        }
    }
}

// ============== Properties ==============

@Composable
private fun PropertiesScreen(model: AppModel, src: PdfSource) {
    val info by produceState<Result<PdfOps.Info>?>(null, src) { value = withContext(Dispatchers.IO) { runCatching { PdfOps.info(src) } } }
    var title by remember { mutableStateOf("") }
    var author by remember { mutableStateOf("") }
    var subject by remember { mutableStateOf("") }
    var keywords by remember { mutableStateOf("") }
    LaunchedEffect(info) {
        info?.getOrNull()?.let { title = it.title.orEmpty(); author = it.author.orEmpty(); subject = it.subject.orEmpty(); keywords = it.keywords.orEmpty() }
    }
    val i = info?.getOrNull()
    Scaffold(
        topBar = { ToolBar("Properties", onBack = model::back) },
        bottomBar = {
            BottomAction("Save properties", enabled = i != null, icon = Icons.Filled.Check) {
                val t = title
                val a = author
                val s = subject
                val k = keywords
                model.run("Saving") { _ ->
                    val tmp = File(Store.tempDir("props"), "${System.nanoTime()}.pdf")
                    PdfOps.setInfo(src, t, a, s, k, tmp)
                    // My PDFs are updated in place; files from elsewhere get a new copy.
                    val out = if (Store.isMine(src.file) && src.password == null) src.file.also { tmp.copyTo(it, overwrite = true) } else Store.fileFor(titleOf(src.file))
                    if (out != src.file) tmp.copyTo(out)
                    tmp.delete()
                    Screen.Result(listOf(out), "Properties saved", if (out == src.file) "\"${out.name}\" was updated." else "Saved as \"${out.name}\".")
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            SourceCard(src)
            if (i == null) {
                if (info?.isFailure == true) Note("Could not read this PDF: ${info?.exceptionOrNull()?.message}") else Spinner(Modifier.height(120.dp))
                return@Column
            }
            SectionTitle("Details")
            InfoLine("Pages", "${i.pages}")
            InfoLine("File size", formatSize(src.file.length()))
            InfoLine("PDF version", "${i.version}")
            InfoLine("Password", if (i.encrypted) "Yes" else "No")
            i.creator?.let { InfoLine("Made with", it) }
            i.producer?.let { InfoLine("Produced by", it) }
            InfoLine("Modified", formatDate(src.file.lastModified()))
            SectionTitle("Edit")
            OutlinedTextField(title, { title = it }, label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(author, { author = it }, label = { Text("Author") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            OutlinedTextField(subject, { subject = it }, label = { Text("Subject") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            OutlinedTextField(keywords, { keywords = it }, label = { Text("Keywords") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(120.dp))
        Text(value, modifier = Modifier.weight(1f))
    }
}
