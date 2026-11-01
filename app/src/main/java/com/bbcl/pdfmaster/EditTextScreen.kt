// Edit PDF: tap a line of text to change or delete it, or tap an empty spot to add text. The
// changes show on the page right away and are written into a new PDF when saved.
package com.bbcl.pdfmaster

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.min
import kotlin.math.roundToInt

private val textColors = listOf(Color(0xFF000000), Color(0xFF1A3DB8), Color(0xFFC62828), Color(0xFF2E7D32), Color(0xFF616161))

private fun FontFamily.compose() = when (this) {
    FontFamily.SANS -> androidx.compose.ui.text.font.FontFamily.SansSerif
    FontFamily.SERIF -> androidx.compose.ui.text.font.FontFamily.Serif
    FontFamily.MONO -> androidx.compose.ui.text.font.FontFamily.Monospace
}

/** The most common colour around `run` in the page picture: the background to cover it with. */
private fun backgroundAround(bitmap: Bitmap, run: TextRun, pageWidth: Float): Int {
    val scale = bitmap.width / pageWidth
    val pad = run.size * 0.15f
    val xs = listOf(run.x - pad, run.x + run.width / 2, run.x + run.width + pad)
    val ys = listOf(run.top - pad, run.top + run.height + pad)
    val samples = buildList {
        for (x in xs) for (y in ys) add(x to y)
        add(run.x - pad to run.top + run.height / 2)
        add(run.x + run.width + pad to run.top + run.height / 2)
    }.mapNotNull { (x, y) ->
        val px = (x * scale).roundToInt()
        val py = (y * scale).roundToInt()
        if (px in 0 until bitmap.width && py in 0 until bitmap.height) bitmap.getPixel(px, py) or 0xFF000000.toInt() else null
    }
    return samples.groupingBy { it and 0xFFF0F0F0.toInt() }.eachCount().maxByOrNull { it.value }
        ?.let { (key, _) -> samples.first { (it and 0xFFF0F0F0.toInt()) == key } } ?: 0xFFFFFFFF.toInt()
}

@Composable
fun EditTextScreen(model: AppModel, src: PdfSource) {
    val file by produceState<File?>(null, src) {
        value = withContext(Dispatchers.IO) { runCatching { Raster.renderable(src).also { Raster.pageCount(it) } }.getOrNull() }
    }
    val pages = file?.let { rememberPageCount(it) }
    var page by remember { mutableIntStateOf(0) }
    val edits = remember { mutableStateListOf<TextEdit>() }
    // The dialog: an edit being made, and the edit it replaces (if it changes an earlier one).
    var editing by remember { mutableStateOf<Pair<TextEdit, TextEdit?>?>(null) }
    val name = remember { titleOf(src.file) + "_edited" }

    Scaffold(
        topBar = {
            ToolBar("Edit PDF", onBack = model::back, subtitle = "Tap text to change it, or tap an empty spot to add text") {
                IconButton(onClick = { edits.removeAt(edits.lastIndex) }, enabled = edits.isNotEmpty()) { Icon(Icons.AutoMirrored.Filled.Undo, "Undo") }
            }
        },
        bottomBar = {
            val n = edits.size
            BottomAction(if (n == 0) "Save PDF" else "Save PDF ($n ${if (n == 1) "change" else "changes"})", enabled = n > 0, icon = Icons.Filled.Save) {
                val all = edits.toList()
                model.run("Saving changes") { progress ->
                    val out = Store.fileFor(name)
                    PdfText.apply(src, all, out, progress)
                    val pagesChanged = all.map { it.page }.distinct().size
                    Screen.Result(listOf(out), "PDF edited", "${all.size} ${if (all.size == 1) "change" else "changes"} on $pagesChanged ${if (pagesChanged == 1) "page" else "pages"}.")
                }
            }
        },
    ) { padding ->
        val f = file
        if (f == null || pages == null) return@Scaffold Spinner(Modifier.padding(padding))
        Column(Modifier.padding(padding).fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { page-- }, enabled = page > 0) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous page") }
                Text("Page ${page + 1} of $pages", modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                IconButton(onClick = { page++ }, enabled = page < pages - 1) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next page") }
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHighest).padding(12.dp), contentAlignment = Alignment.Center) {
                val maxW = maxWidth
                val maxH = maxHeight
                val density = LocalDensity.current
                val text by produceState<PageText?>(null, src, page) {
                    value = null
                    // If the text cannot be read (a scanned page, a broken font), text can still be added.
                    value = withContext(Dispatchers.IO) {
                        runCatching { PdfText.runs(src, page) }.getOrElse {
                            model.say("Could not read the text on this page; you can still add text")
                            runCatching { Raster.pageSizes(f)[page].let { (w, h) -> PageText(w.toFloat(), h.toFloat(), emptyList()) } }.getOrNull()
                        }
                    }
                }
                val pt = text ?: return@BoxWithConstraints Spinner()
                val aspect = pt.height / pt.width
                val boxW = min(maxW.value, maxH.value / aspect)
                val widthPx = with(density) { boxW.dp.roundToPx() }
                val bitmap by produceState<Bitmap?>(null, f, page, widthPx) {
                    value = withContext(Dispatchers.IO) { runCatching { Raster.page(f, page, widthPx) }.getOrNull() }
                }
                val scale = widthPx / pt.width // pixels per point
                fun Float.ptDp() = with(density) { (this@ptDp * scale).toDp() }
                fun Float.ptSp() = with(density) { (this@ptSp * scale).toSp() }
                val pageEdits = edits.filter { it.page == page }
                val replaced = pageEdits.mapNotNull { it.original }.toSet()

                Box(
                    Modifier.width(boxW.dp).height((boxW * aspect).dp).background(Color.White).pointerInput(pt, page, scale) {
                        detectTapGestures { (x, y) ->
                            val size = (pt.runs.map { it.size }.sortedBy { it }.getOrNull(pt.runs.size / 2) ?: 11f).coerceIn(8f, 24f)
                            editing = TextEdit(
                                page, null, "", x / scale, y / scale + size * 0.35f, size, 0xFF000000.toInt(),
                                bold = false, italic = false, family = FontFamily.SANS, keepOriginalFont = false,
                            ) to null
                        }
                    },
                ) {
                    bitmap?.let { Image(it.asImageBitmap(), "Page ${page + 1}", contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize()) }
                    for (run in pt.runs) {
                        if (run in replaced) continue
                        Box(
                            Modifier.offset { IntOffset((run.x * scale).roundToInt(), (run.top * scale).roundToInt()) }
                                .size(run.width.ptDp(), run.height.ptDp())
                                .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))
                                .clickable {
                                    val cover = bitmap?.let { backgroundAround(it, run, pt.width) } ?: 0xFFFFFFFF.toInt()
                                    editing = TextEdit(
                                        page, run, run.text, run.x, run.baseline, run.size, run.colorArgb,
                                        run.bold, run.italic, run.family, keepOriginalFont = true, coverArgb = cover,
                                    ) to null
                                },
                        )
                    }
                    for (e in pageEdits) {
                        val o = e.original
                        if (o != null) {
                            Box(
                                Modifier.offset { IntOffset((o.x * scale).roundToInt(), (o.top * scale).roundToInt()) }
                                    .size(o.width.ptDp(), o.height.ptDp()).background(Color(e.coverArgb)),
                            )
                        }
                        if (e.text.isBlank() && o == null) continue
                        Text(
                            e.text.ifBlank { " " },
                            color = Color(e.colorArgb),
                            fontSize = e.size.ptSp(),
                            fontWeight = if (e.bold) FontWeight.Bold else FontWeight.Normal,
                            fontStyle = if (e.italic) FontStyle.Italic else FontStyle.Normal,
                            fontFamily = e.family.compose(),
                            softWrap = false,
                            style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false), lineHeight = e.size.ptSp()),
                            modifier = Modifier
                                .offset { IntOffset((e.x * scale).roundToInt(), ((e.baseline - e.size * 0.9f) * scale).roundToInt()) }
                                .wrapContentSize(unbounded = true, align = Alignment.TopStart)
                                .border(1.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.5f))
                                .clickable { editing = e to e },
                        )
                    }
                }
            }
        }
    }

    editing?.let { (draft, old) ->
        EditTextDialog(
            draft,
            canDelete = draft.original != null || old != null,
            onDone = { result ->
                editing = null
                if (result == null) return@EditTextDialog
                val o = result.original
                val unchanged = o != null && result.text == o.text && result.size == o.size && result.colorArgb == o.colorArgb &&
                    result.bold == o.bold && result.italic == o.italic && result.family == o.family
                val i = if (old != null) edits.indexOf(old) else -1
                when {
                    unchanged || (o == null && result.text.isBlank()) -> if (i >= 0) edits.removeAt(i)
                    i >= 0 -> edits[i] = result
                    else -> edits.add(result)
                }
            },
        )
    }
}

@Composable
private fun EditTextDialog(draft: TextEdit, canDelete: Boolean, onDone: (TextEdit?) -> Unit) {
    val o = draft.original
    var text by remember { mutableStateOf(draft.text) }
    var size by remember { mutableStateOf(draft.size) }
    var bold by remember { mutableStateOf(draft.bold) }
    var italic by remember { mutableStateOf(draft.italic) }
    var family by remember { mutableStateOf(draft.family) }
    var color by remember { mutableStateOf(draft.colorArgb) }
    val colors = remember { (listOf(draft.colorArgb) + textColors.map { it.toArgb() }).distinct() }

    fun result(t: String): TextEdit {
        val sameStyle = o != null && bold == o.bold && italic == o.italic && family == o.family
        return draft.copy(text = t, size = size, bold = bold, italic = italic, family = family, colorArgb = color, keepOriginalFont = sameStyle)
    }

    AlertDialog(
        onDismissRequest = { onDone(null) },
        title = { Text(if (o == null) "Add text" else "Edit text") },
        text = {
            Column {
                OutlinedTextField(text, { text = it.replace('\n', ' ') }, label = { Text("Text") }, modifier = Modifier.fillMaxWidth())
                Text("Size: ${"%.1f".format(size)}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp))
                Slider(size, { size = (it * 2).roundToInt() / 2f }, valueRange = 5f..48f)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(bold, { bold = !bold }, label = { Text("Bold", fontWeight = FontWeight.Bold) })
                    FilterChip(italic, { italic = !italic }, label = { Text("Italic", fontStyle = FontStyle.Italic) })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (fam in FontFamily.entries) FilterChip(family == fam, { family = fam }, label = { Text(fam.label, fontFamily = fam.compose()) })
                }
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (c in colors) {
                        Box(
                            Modifier.size(30.dp).background(Color(c), CircleShape)
                                .border(3.dp, if (c == color) MaterialTheme.colorScheme.primary else Color.Transparent, CircleShape)
                                .clickable { color = c },
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onDone(result(text)) }, enabled = text.isNotBlank()) { Text("Done") } },
        dismissButton = {
            Row {
                if (canDelete) TextButton(onClick = { onDone(result("")) }) { Text("Delete") }
                TextButton(onClick = { onDone(null) }) { Text("Cancel") }
            }
        },
    )
}
