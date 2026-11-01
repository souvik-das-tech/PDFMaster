// Shows a PDF page by page (drawn by Android's own PDF renderer) with pinch / double-tap zoom,
// a page counter and "go to page", the file actions, and quick access to every tool.
package com.bbcl.pdfmaster

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.min
import kotlin.math.roundToInt

private val quickTools = listOf(
    Tool.EDIT_TEXT, Tool.COMPRESS, Tool.MERGE, Tool.SPLIT, Tool.ORGANIZE, Tool.EXTRACT, Tool.SIGN, Tool.WATERMARK, Tool.PAGE_NUMBERS,
    Tool.PROTECT, Tool.UNLOCK, Tool.GRAYSCALE, Tool.TO_IMAGES, Tool.TO_TEXT, Tool.PROPERTIES,
)

@Composable
fun ViewerScreen(model: AppModel, src: PdfSource, onFileAction: (File, FileAction) -> Unit) {
    val context = LocalContext.current
    val file = src.file
    val mine = Store.isMine(file)
    // PdfRenderer cannot open password-protected files, so those are drawn from an unlocked copy.
    val render by produceState<Result<File>?>(null, src, file.lastModified()) {
        value = withContext(Dispatchers.IO) { runCatching { Raster.renderable(src).also { Raster.pageCount(it) } } }
    }
    val pages = render?.getOrNull()?.let { rememberPageCount(it) }
    var menu by remember { mutableStateOf(false) }
    var goTo by remember { mutableStateOf(false) }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            ToolBar(
                titleOf(file),
                onBack = model::back,
                subtitle = listOfNotNull(pages?.let { if (it == 1) "1 page" else "$it pages" }, formatSize(file.length()), if (src.password != null) "Protected" else null).joinToString(" • "),
            ) {
                IconButton(onClick = { model.share(listOf(file)) }) { Icon(Icons.Filled.Share, "Share") }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text("Save to phone") }, leadingIcon = { Icon(Icons.Filled.Download, null) }, onClick = { menu = false; onFileAction(file, FileAction.SAVE) })
                        DropdownMenuItem(text = { Text("Print") }, leadingIcon = { Icon(Icons.Filled.Print, null) }, onClick = {
                            menu = false
                            scope.launch {
                                val printable = render?.getOrNull() ?: file
                                runCatching { Store.print(context, printable) }.onFailure { model.say("Printing is not available: ${it.message}") }
                            }
                        })
                        if (pages != null && pages > 1) {
                            DropdownMenuItem(text = { Text("Go to page") }, leadingIcon = { Icon(Icons.Filled.Info, null) }, onClick = { menu = false; goTo = true })
                        }
                        DropdownMenuItem(text = { Text("Properties") }, leadingIcon = { Icon(Icons.Filled.Info, null) }, onClick = { menu = false; model.open(Screen.Form(Tool.PROPERTIES, src)) })
                        if (mine) {
                            DropdownMenuItem(text = { Text("Rename") }, leadingIcon = { Icon(Icons.Filled.DriveFileRenameOutline, null) }, onClick = { menu = false; onFileAction(file, FileAction.RENAME) })
                            DropdownMenuItem(text = { Text("Delete") }, leadingIcon = { Icon(Icons.Filled.Delete, null) }, onClick = { menu = false; onFileAction(file, FileAction.DELETE) })
                        } else {
                            DropdownMenuItem(text = { Text("Add to My PDFs") }, leadingIcon = { Icon(Icons.Filled.LibraryAdd, null) }, onClick = { menu = false; model.keep(src) })
                        }
                    }
                }
            }
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                LazyRow(
                    Modifier.navigationBarsPadding().padding(vertical = 8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(quickTools) { tool ->
                        AssistChip(
                            onClick = { model.open(model.screenFor(tool, src)) },
                            label = { Text(tool.label) },
                            leadingIcon = { Icon(tool.icon, null, tint = tool.color, modifier = Modifier.size(AssistChipDefaults.IconSize)) },
                        )
                    }
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            val renderFile = render?.getOrNull()
            when {
                render == null -> Spinner()
                renderFile == null || pages == null && render?.isFailure == true ->
                    EmptyState(Icons.Filled.Info, "Can't open this PDF", render?.exceptionOrNull()?.message ?: "The file may be damaged.")
                pages == null -> Spinner()
                else -> ZoomablePages(renderFile, pages, list)
            }
            val current by remember { derivedStateOf { list.firstVisibleItemIndex + 1 } }
            if (pages != null && pages > 1) {
                Surface(
                    onClick = { goTo = true },
                    shape = RoundedCornerShape(50),
                    color = Color.Black.copy(alpha = 0.6f),
                    modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
                ) {
                    Text("$current / $pages", color = Color.White, modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
                }
            }
        }
    }

    if (goTo && pages != null) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { goTo = false },
            title = { Text("Go to page") },
            text = {
                OutlinedTextField(text, { text = it.filter(Char::isDigit) }, label = { Text("Page (1–$pages)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            },
            confirmButton = {
                TextButton(onClick = {
                    val n = text.toIntOrNull()?.coerceIn(1, pages)
                    goTo = false
                    if (n != null) scope.launch { list.scrollToItem(n - 1) }
                }) { Text("Go") }
            },
            dismissButton = { TextButton(onClick = { goTo = false }) { Text("Cancel") } },
        )
    }
}

/** Pages one under the other; pinch or double-tap to zoom, scroll sideways when zoomed. */
@Composable
private fun ZoomablePages(file: File, pages: Int, list: androidx.compose.foundation.lazy.LazyListState) {
    var zoom by remember { mutableFloatStateOf(1f) }
    val density = LocalDensity.current
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                // Two fingers zoom; one finger is left to the list for scrolling.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.changes.count { it.pressed } >= 2) {
                            zoom = (zoom * event.calculateZoom()).coerceIn(1f, 5f)
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { zoom = if (zoom > 1.2f) 1f else 2.5f }) },
    ) {
        val viewW = maxWidth
        val pageW = viewW * zoom
        // Pages are re-drawn only at a few sharpness steps, not on every pinch movement.
        val step = (zoom * 2).roundToInt() / 2f
        val px = with(density) { min(2600f, (viewW * step).toPx() - 24.dp.toPx()) }.roundToInt().coerceAtLeast(200)
        Box(Modifier.fillMaxSize().horizontalScroll(rememberScrollState(), enabled = zoom > 1f)) {
            LazyColumn(
                state = list,
                modifier = Modifier.width(pageW).fillMaxSize(),
                contentPadding = PaddingValues(12.dp, 12.dp, 12.dp, 56.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(pages) { i ->
                    PdfPage(file, i, px, Modifier.fillMaxWidth().shadow(2.dp).background(Color.White))
                }
            }
        }
    }
}
