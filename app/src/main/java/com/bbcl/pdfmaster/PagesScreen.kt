// Tools that work on a grid of page thumbnails: split, extract, organize (reorder / rotate /
// delete) and PDF to images.
package com.bbcl.pdfmaster

import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Parses "1-3, 5, 8-" into 0-based ranges within `count` pages; null if it makes no sense. */
fun parseRanges(text: String, count: Int): List<IntRange>? {
    val parts = text.split(',', ';', ' ').map { it.trim() }.filter { it.isNotEmpty() }
    if (parts.isEmpty()) return null
    return parts.map { p ->
        val bits = p.split('-', '–')
        val a = bits[0].trim().toIntOrNull() ?: if (bits[0].isBlank() && bits.size == 2) 1 else return null
        val b = when {
            bits.size == 1 -> a
            bits[1].isBlank() -> count
            else -> bits[1].trim().toIntOrNull() ?: return null
        }
        if (bits.size > 2 || a < 1 || b > count || a > b) return null
        (a - 1)..(b - 1)
    }
}

private enum class SplitMode(val label: String) { EACH("Every page"), EVERY_N("Every N pages"), RANGES("Custom ranges") }

private enum class Dpi(val label: String, val dpi: Int) { LOW("Small", 96), MEDIUM("Medium", 150), HIGH("High", 300) }

@Composable
fun PagesScreen(model: AppModel, tool: Tool, src: PdfSource) {
    val context = LocalContext.current
    val renderState by produceState<Result<Pair<File, Int>>?>(null, src) {
        value = withContext(Dispatchers.IO) { runCatching { Raster.renderable(src).let { it to Raster.pageCount(it) } } }
    }
    val loaded = renderState?.getOrNull()
    if (loaded == null) {
        Scaffold(topBar = { ToolBar(tool.label, onBack = model::back) }) { p ->
            if (renderState?.isFailure == true) {
                EmptyState(tool.icon, "Can't open this PDF", renderState?.exceptionOrNull()?.message ?: "", Modifier.padding(p))
            } else {
                Spinner(Modifier.padding(p))
            }
        }
        return
    }
    val (file, count) = loaded

    // Organize: the pages in their new order, with extra rotation.
    val order = remember { mutableStateListOf<PdfOps.PageRef>().apply { addAll((0 until count).map { PdfOps.PageRef(it) }) } }
    // Extract / images: chosen pages.
    val selected = remember { mutableStateListOf<Int>().apply { if (tool == Tool.TO_IMAGES) addAll(0 until count) } }
    var rangeText by remember { mutableStateOf("") }
    var splitMode by remember { mutableStateOf(SplitMode.EACH) }
    var everyN by remember { mutableStateOf("2") }
    var name by rememberSaveable {
        mutableStateOf(
            titleOf(src.file) + when (tool) {
                Tool.EXTRACT -> "_pages"
                Tool.ORGANIZE -> "_organized"
                Tool.SPLIT -> "_part"
                else -> ""
            },
        )
    }
    var format by remember { mutableStateOf(Raster.ImageFormat.JPG) }
    var dpi by remember { mutableStateOf(Dpi.MEDIUM) }

    val splitRanges: List<IntRange>? = when (splitMode) {
        SplitMode.EACH -> (0 until count).map { it..it }
        SplitMode.EVERY_N -> everyN.toIntOrNull()?.takeIf { it in 1..count }?.let { n -> (0 until count step n).map { it..minOf(it + n - 1, count - 1) } }
        SplitMode.RANGES -> parseRanges(rangeText, count)
    }
    val partOf = IntArray(count) { -1 }.also { arr -> splitRanges?.forEachIndexed { part, r -> r.forEach { if (arr[it] == -1) arr[it] = part } } }

    fun exportImages(tree: Uri?) {
        val pages = selected.sorted()
        val base = titleOf(src.file)
        model.run("Saving pictures") { progress ->
            val n = Raster.exportImages(context, file, pages, dpi.dpi, format, base, tree, progress)
            Screen.Result(emptyList(), "$n picture${if (n == 1) "" else "s"} saved", if (tree == null) "Find them in your gallery, in Pictures › PDF Master." else "Saved in the folder you chose.")
        }
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> if (uri != null) exportImages(uri) }

    val (actionText, actionEnabled, actionIcon) = when (tool) {
        Tool.ORGANIZE -> Triple("Save ${order.size} pages", order.isNotEmpty(), Icons.Filled.Save)
        Tool.EXTRACT -> Triple("Extract ${selected.size} page${if (selected.size == 1) "" else "s"}", selected.isNotEmpty(), Icons.Filled.ContentCut)
        Tool.SPLIT -> Triple("Split into ${splitRanges?.size ?: 0} PDFs", (splitRanges?.size ?: 0) >= 1, Icons.AutoMirrored.Filled.CallSplit)
        else -> Triple("Save ${selected.size} picture${if (selected.size == 1) "" else "s"}", selected.isNotEmpty(), Icons.Filled.Image)
    }

    Scaffold(
        topBar = { ToolBar(tool.label, onBack = model::back, subtitle = "${titleOf(src.file)} • $count pages") },
        bottomBar = {
            BottomAction(actionText, actionEnabled, actionIcon) {
                when (tool) {
                    Tool.ORGANIZE -> {
                        val refs = order.toList()
                        model.run("Saving pages") { _ ->
                            val out = Store.fileFor(name)
                            PdfOps.selectPages(src, refs, out)
                            Screen.Result(listOf(out), "Pages organized", "${refs.size} pages • ${formatSize(out.length())}")
                        }
                    }
                    Tool.EXTRACT -> {
                        val refs = selected.sorted().map { PdfOps.PageRef(it) }
                        model.run("Extracting pages") { _ ->
                            val out = Store.fileFor(name)
                            PdfOps.selectPages(src, refs, out)
                            Screen.Result(listOf(out), "Pages extracted", "${refs.size} page${if (refs.size == 1) "" else "s"} • ${formatSize(out.length())}")
                        }
                    }
                    Tool.SPLIT -> {
                        val ranges = splitRanges ?: return@BottomAction
                        val base = name
                        model.run("Splitting PDF") { progress ->
                            val files = PdfOps.split(src, ranges, { i, _ -> Store.fileFor("${base}${i + 1}") }, progress)
                            Screen.Result(files, "PDF split", "${files.size} PDFs created")
                        }
                    }
                    else -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) exportImages(null) else pickFolder.launch(null)
                }
            }
        },
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(108.dp),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    if (tool != Tool.TO_IMAGES) {
                        NameField(name, { name = it }, if (tool == Tool.SPLIT) "Name of the parts" else "New PDF name", suffix = if (tool == Tool.SPLIT) "1.pdf, ${name.trim()}2.pdf, …" else ".pdf")
                    }
                    when (tool) {
                        Tool.ORGANIZE -> {
                            Text("Move pages with the arrows, turn them, or remove them.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                AssistChip(onClick = { order.replaceAll { it.copy(rotation = (it.rotation + 90) % 360) } }, label = { Text("Rotate all") }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.RotateRight, null) })
                                AssistChip(onClick = { order.reverse() }, label = { Text("Reverse") }, leadingIcon = { Icon(Icons.Filled.SwapVert, null) })
                                AssistChip(onClick = { order.clear(); order.addAll((0 until count).map { PdfOps.PageRef(it) }) }, label = { Text("Reset") }, leadingIcon = { Icon(Icons.Filled.Restore, null) })
                            }
                        }
                        Tool.SPLIT -> {
                            Spacer(Modifier.size(8.dp))
                            Choice("Split", SplitMode.entries, splitMode, { it.label }) { splitMode = it }
                            if (splitMode == SplitMode.EVERY_N) {
                                OutlinedTextField(everyN, { everyN = it.filter(Char::isDigit) }, label = { Text("Pages per PDF") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                            }
                            if (splitMode == SplitMode.RANGES) {
                                OutlinedTextField(rangeText, { rangeText = it }, label = { Text("Ranges, e.g. 1-3, 4-6, 7") }, singleLine = true, isError = rangeText.isNotBlank() && splitRanges == null, supportingText = { Text("Each range becomes its own PDF") }, modifier = Modifier.fillMaxWidth())
                            }
                        }
                        else -> {
                            if (tool == Tool.TO_IMAGES) {
                                Choice("Format", Raster.ImageFormat.entries, format, { it.label }) { format = it }
                                Choice("Picture size", Dpi.entries, dpi, { "${it.label} (${it.dpi} dpi)" }) { dpi = it }
                            }
                            OutlinedTextField(
                                rangeText,
                                { t ->
                                    rangeText = t
                                    parseRanges(t, count)?.let { r -> selected.clear(); selected.addAll(r.flatten().distinct()) }
                                },
                                label = { Text("Pages, e.g. 1-3, 5") },
                                singleLine = true,
                                isError = rangeText.isNotBlank() && parseRanges(rangeText, count) == null,
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            )
                            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                AssistChip(onClick = { selected.clear(); selected.addAll(0 until count) }, label = { Text("Select all") })
                                AssistChip(onClick = { selected.clear() }, label = { Text("Clear") })
                                AssistChip(onClick = { val inv = (0 until count).filter { it !in selected }; selected.clear(); selected.addAll(inv) }, label = { Text("Invert") })
                            }
                        }
                    }
                }
            }
            if (tool == Tool.ORGANIZE) {
                itemsIndexed(order, key = { i, ref -> "${ref.index}-$i" }) { i, ref ->
                    PageCard(file, ref.index, ref.rotation, label = "${i + 1}", sub = if (ref.index != i) "was ${ref.index + 1}" else null) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            SmallIcon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Move earlier", i > 0) { order.add(i - 1, order.removeAt(i)) }
                            SmallIcon(Icons.AutoMirrored.Filled.RotateRight, "Rotate", true) { order[i] = ref.copy(rotation = (ref.rotation + 90) % 360) }
                            SmallIcon(Icons.Filled.Close, "Remove", order.size > 1) { order.removeAt(i) }
                            SmallIcon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Move later", i < order.lastIndex) { order.add(i + 1, order.removeAt(i)) }
                        }
                    }
                }
            } else {
                itemsIndexed((0 until count).toList(), key = { _, p -> p }) { _, p ->
                    if (tool == Tool.SPLIT) {
                        val part = partOf[p]
                        PageCard(file, p, 0, label = "${p + 1}", sub = if (part >= 0) "Part ${part + 1}" else "Not included", tint = if (part >= 0) partColors[part % partColors.size] else null)
                    } else {
                        val on = p in selected
                        PageCard(file, p, 0, label = "${p + 1}", selected = on, onClick = { if (on) selected.remove(p) else selected.add(p) })
                    }
                }
            }
        }
    }
}

private val partColors = listOf(Color(0xFF1E88E5), Color(0xFF43A047), Color(0xFFFB8C00), Color(0xFF8E24AA), Color(0xFFE53935), Color(0xFF00897B))

@Composable
private fun SmallIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(28.dp)) { Icon(icon, label, Modifier.size(18.dp)) }
}

@Composable
private fun PageCard(
    file: File,
    page: Int,
    rotation: Int,
    label: String,
    sub: String? = null,
    selected: Boolean? = null,
    tint: Color? = null,
    onClick: (() -> Unit)? = null,
    actions: (@Composable () -> Unit)? = null,
) {
    val border = when {
        selected == true -> MaterialTheme.colorScheme.primary
        tint != null -> tint
        else -> Color.Transparent
    }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        Column(Modifier.border(2.dp, border, RoundedCornerShape(12.dp)).padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.fillMaxWidth().aspectRatio(0.75f), contentAlignment = Alignment.Center) {
                PdfPage(file, page, 280, Modifier.fillMaxSize().rotate(rotation.toFloat()).padding(if (rotation % 180 != 0) 14.dp else 0.dp).background(Color.White), crop = true)
                if (selected != null) {
                    Icon(
                        if (selected) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                        null,
                        tint = if (selected) MaterialTheme.colorScheme.primary else Color.Gray,
                        modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).background(Color.White, CircleShape),
                    )
                }
            }
            Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
            Text(sub ?: " ", fontSize = 11.sp, color = tint ?: MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) // always present, so all page cards are equally tall
            actions?.invoke()
        }
    }
}
