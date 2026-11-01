// Building blocks shared by the screens: page pictures, dialogs, the "choose a PDF" sheet,
// file rows, option pickers and a drag-to-reorder list.
package com.bbcl.pdfmaster

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

// ============== Page pictures ==============

/**
 * Page `page` of `file` drawn `widthPx` pixels wide. While it is drawn (or if the PDF is
 * locked) a white or lock placeholder of the same shape is shown.
 */
@Composable
fun PdfPage(file: File, page: Int, widthPx: Int, modifier: Modifier = Modifier, crop: Boolean = false, aspect: Float = 1 / 1.414f) {
    val state by produceState<Result<Bitmap>?>(null, file, file.lastModified(), page, widthPx) {
        value = withContext(Dispatchers.IO) { runCatching { Raster.page(file, page, widthPx) } }
    }
    val bitmap = state?.getOrNull()
    if (bitmap != null) {
        Image(
            bitmap.asImageBitmap(),
            contentDescription = "Page ${page + 1}",
            contentScale = if (crop) ContentScale.Crop else ContentScale.FillWidth,
            alignment = Alignment.TopCenter,
            modifier = if (crop) modifier else modifier.aspectRatio(bitmap.width.toFloat() / bitmap.height),
        )
    } else {
        Box(
            (if (crop) modifier else modifier.aspectRatio(aspect)).background(Color.White),
            contentAlignment = Alignment.Center,
        ) {
            if (state?.isFailure == true) Icon(Icons.Filled.Lock, "Locked", tint = Color.Gray)
        }
    }
}

/** Page count of a file PdfRenderer can open, or null while counting / if unreadable. */
@Composable
fun rememberPageCount(file: File): Int? {
    val count by produceState<Int?>(null, file, file.lastModified()) {
        value = withContext(Dispatchers.IO) { runCatching { Raster.pageCount(file) }.getOrNull() }
    }
    return count
}

// ============== Scaffolding ==============

/**
 * Top bar of every tool screen. Unlike TopAppBar it grows with its text, so long titles and
 * file names wrap onto more lines instead of being cut off.
 */
@Composable
fun ToolBar(title: String, onBack: () -> Unit, subtitle: String? = null, actions: @Composable () -> Unit = {}) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).heightIn(min = 64.dp).padding(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Column(Modifier.weight(1f).padding(start = 4.dp, end = 4.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge.copy(lineHeight = 26.sp))
                if (subtitle != null) {
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            actions()
        }
    }
}

/** A file-name field that wraps, so the whole name is always visible. Enter is ignored. */
@Composable
fun NameField(value: String, onChange: (String) -> Unit, label: String, suffix: String = ".pdf", modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.filter { c -> c.code >= 32 }) },
        label = { Text(label) },
        supportingText = { Text("Saved as: ${value.trim().ifEmpty { "…" }}$suffix") },
        keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
        modifier = modifier.fillMaxWidth(),
    )
}

/** Big primary action pinned to the bottom of a tool screen. */
@Composable
fun BottomAction(text: String, enabled: Boolean = true, icon: androidx.compose.ui.graphics.vector.ImageVector? = null, onClick: () -> Unit) {
    Surface(tonalElevation = 3.dp) {
        androidx.compose.material3.Button(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime)).padding(16.dp, 10.dp).height(52.dp),
            shape = RoundedCornerShape(14.dp),
        ) {
            if (icon != null) {
                Icon(icon, null)
                Spacer(Modifier.width(8.dp))
            }
            Text(text, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = modifier.padding(top = 16.dp, bottom = 8.dp))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> Choice(title: String, options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(bottom = 6.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { i, o ->
                SegmentedButton(
                    selected = o == selected,
                    onClick = { onSelect(o) },
                    shape = SegmentedButtonDefaults.itemShape(i, options.size),
                    icon = {},
                ) { Text(label(o), fontSize = 13.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center, lineHeight = 15.sp) }
            }
        }
    }
}

// ============== Dialogs ==============

@Composable
fun JobDialog(job: Job) {
    AlertDialog(
        onDismissRequest = {},
        confirmButton = {},
        title = { Text(job.title) },
        text = {
            Column {
                if (job.total > 0) {
                    LinearProgressIndicator(progress = { job.done.toFloat() / job.total }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(12.dp))
                    Text("${job.done} of ${job.total}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Spacer(Modifier.height(12.dp))
                    Text("Working on your phone…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
    )
}

@Composable
fun PasswordField(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier, error: String? = null, onDone: (() -> Unit)? = null) {
    var show by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { onDone?.invoke() }),
        trailingIcon = {
            IconButton(onClick = { show = !show }) {
                Icon(if (show) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, if (show) "Hide" else "Show")
            }
        },
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
fun PasswordDialog(prompt: PasswordPrompt) {
    var pw by remember(prompt) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { prompt.onResult(null) },
        icon = { Icon(Icons.Filled.Lock, null) },
        title = { Text("Password needed") },
        text = {
            Column {
                Text("\"${titleOf(prompt.file)}\" is protected. Enter its password to open it.")
                Spacer(Modifier.height(12.dp))
                PasswordField(pw, { pw = it }, "Password", error = if (prompt.wrong) "Wrong password, try again" else null, onDone = { prompt.onResult(pw) })
            }
        },
        confirmButton = { TextButton(onClick = { prompt.onResult(pw) }, enabled = pw.isNotEmpty()) { Text("Open") } },
        dismissButton = { TextButton(onClick = { prompt.onResult(null) }) { Text("Cancel") } },
    )
}

@Composable
fun RenameDialog(file: File, onDone: (String?) -> Unit) {
    var name by remember { mutableStateOf(titleOf(file)) }
    AlertDialog(
        onDismissRequest = { onDone(null) },
        title = { Text("Rename PDF") },
        text = { NameField(name, { name = it }, "Name") },
        confirmButton = { TextButton(onClick = { onDone(name) }, enabled = name.isNotBlank()) { Text("Rename") } },
        dismissButton = { TextButton(onClick = { onDone(null) }) { Text("Cancel") } },
    )
}

@Composable
fun DeleteDialog(file: File, onDone: (Boolean) -> Unit) {
    AlertDialog(
        onDismissRequest = { onDone(false) },
        title = { Text("Delete PDF?") },
        text = { Text("\"${titleOf(file)}\" will be deleted from this app. Copies you saved or shared are kept.") },
        confirmButton = { TextButton(onClick = { onDone(true) }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { onDone(false) }) { Text("Cancel") } },
    )
}

// ============== Choosing a PDF ==============

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChoosePdfSheet(title: String, files: List<File>, onBrowse: () -> Unit, onPick: (File) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 24.dp, bottom = 8.dp))
        FilledTonalButton(onClick = onBrowse, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp).height(52.dp)) {
            Icon(Icons.Filled.FolderOpen, null)
            Spacer(Modifier.width(8.dp))
            Text("Browse files on this phone")
        }
        if (files.isNotEmpty()) {
            Text("My PDFs", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 24.dp, top = 12.dp))
            LazyColumn(Modifier.heightIn(max = 420.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(files, key = { it.path }) { f ->
                    ListItem(
                        headlineContent = { Text(titleOf(f)) },
                        supportingContent = { Text("${formatSize(f.length())} • ${formatDate(f.lastModified())}") },
                        leadingContent = {
                            PdfPage(f, 0, 120, Modifier.size(36.dp, 48.dp).clip(RoundedCornerShape(4.dp)), crop = true)
                        },
                        modifier = Modifier.clickable { onPick(f) },
                    )
                }
            }
        } else {
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ============== File rows ==============

enum class FileAction(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    SHARE("Share", Icons.Filled.Share),
    SAVE("Save to phone", Icons.Filled.Download),
    RENAME("Rename", Icons.Filled.DriveFileRenameOutline),
    DELETE("Delete", Icons.Filled.Delete),
}

@Composable
fun PdfFileCard(file: File, onClick: () -> Unit, onAction: (FileAction) -> Unit) {
    val pages = rememberPageCount(file)
    var menu by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            PdfPage(file, 0, 180, Modifier.size(52.dp, 68.dp).shadow(1.dp, RoundedCornerShape(6.dp)).clip(RoundedCornerShape(6.dp)), crop = true)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(titleOf(file), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(2.dp))
                val pageText = when (pages) {
                    null -> ""
                    1 -> "1 page • "
                    else -> "$pages pages • "
                }
                Text("$pageText${formatSize(file.length())}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(formatDate(file.lastModified()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                DropdownMenu(menu, { menu = false }) {
                    for (a in FileAction.entries) {
                        DropdownMenuItem(text = { Text(a.label) }, leadingIcon = { Icon(a.icon, null) }, onClick = { menu = false; onAction(a) })
                    }
                }
            }
        }
    }
}

@Composable
fun EmptyState(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, text: String, modifier: Modifier = Modifier, content: @Composable () -> Unit = {}) {
    Column(modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(72.dp))
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(6.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(Modifier.height(20.dp))
        content()
    }
}

// ============== Drag to reorder ==============

/** Drag-handle reordering for a LazyColumn whose items are all reorderable. */
class ReorderState(val list: LazyListState, private val onMove: (from: Int, to: Int) -> Unit) {
    var dragging by mutableStateOf<Int?>(null)
        private set
    var offset by mutableFloatStateOf(0f)
        private set

    fun start(index: Int) {
        dragging = index
        offset = 0f
    }

    fun drag(dy: Float) {
        val from = dragging ?: return
        offset += dy
        val items = list.layoutInfo.visibleItemsInfo
        val current = items.firstOrNull { it.index == from } ?: return
        val center = current.offset + offset + current.size / 2
        val target = items.firstOrNull { it.index != from && center > it.offset && center < it.offset + it.size } ?: return
        onMove(from, target.index)
        offset += current.offset - target.offset
        dragging = target.index
    }

    fun end() {
        dragging = null
        offset = 0f
    }
}

/** Applies the drag offset to the item being moved and makes `handle` start drags. */
fun Modifier.reorderItem(state: ReorderState, index: Int): Modifier =
    zIndex(if (state.dragging == index) 1f else 0f).graphicsLayer {
        translationY = if (state.dragging == index) state.offset else 0f
        shadowElevation = if (state.dragging == index) 12f else 0f
    }

@Composable
fun Modifier.dragHandle(state: ReorderState, index: Int): Modifier {
    val currentIndex by rememberUpdatedState(index)
    return pointerInput(state) {
        detectDragGestures(
            onDragStart = { state.start(currentIndex) },
            onDrag = { change, amount -> change.consume(); state.drag(amount.y) },
            onDragEnd = { state.end() },
            onDragCancel = { state.end() },
        )
    }
}

@Composable
fun Spinner(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}
