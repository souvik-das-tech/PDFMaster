package com.bbcl.pdfmaster

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.CallMerge
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun MergeScreen(model: AppModel, initial: List<PdfSource>) {
    val items = remember { mutableStateListOf<PdfSource>().apply { addAll(initial) } }
    var name by rememberSaveable { mutableStateOf(Store.defaultName("Merged")) }
    var addMenu by remember { mutableStateOf(false) }
    var pickMine by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun add(f: File) = model.unlockThen(f) { src -> if (items.none { it.file == src.file }) items.add(src) }

    val browse = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        scope.launch {
            val files = withContext(Dispatchers.IO) { uris.mapNotNull { runCatching { Store.importUri(it) }.getOrNull() } }
            files.forEach(::add)
        }
    }
    LaunchedEffect(model.addToMerge) {
        model.addToMerge?.let { src -> if (items.none { it.file == src.file }) items.add(src) }
        model.addToMerge = null
    }

    val listState = rememberLazyListState()
    val reorder = remember { ReorderState(listState) { from, to -> items.add(to, items.removeAt(from)) } }

    Scaffold(
        topBar = { ToolBar("Merge PDFs", onBack = model::back, subtitle = "${items.size} file${if (items.size == 1) "" else "s"}") },
        bottomBar = {
            BottomAction("Merge ${items.size} PDFs", enabled = items.size >= 2, icon = Icons.AutoMirrored.Filled.CallMerge) {
                val sources = items.toList()
                model.run("Merging PDFs") { progress ->
                    val out = Store.fileFor(name)
                    PdfOps.merge(sources, out, progress)
                    val pages = Raster.pageCount(out)
                    Screen.Result(listOf(out), "PDFs merged", "${sources.size} files • $pages pages • ${formatSize(out.length())}")
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            NameField(name, { name = it }, "New PDF name", modifier = Modifier.padding(16.dp, 8.dp))
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { pickMine = true }) { Icon(Icons.Filled.Add, null); Spacer(Modifier.width(6.dp)); Text("From My PDFs") }
                FilledTonalButton(onClick = { browse.launch(arrayOf("application/pdf")) }) { Icon(Icons.Filled.FolderOpen, null); Spacer(Modifier.width(6.dp)); Text("Browse phone") }
            }
            if (items.isEmpty()) {
                EmptyState(Icons.AutoMirrored.Filled.CallMerge, "Add PDFs to merge", "Pick two or more PDFs. Drag them into the order you want; they are joined top to bottom.")
            } else {
                LazyColumn(state = listState, contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(items, key = { _, s -> s.file.path }) { i, src ->
                        Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.reorderItem(reorder, i)) {
                            Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.DragIndicator, "Drag to reorder", Modifier.dragHandle(reorder, i).padding(6.dp))
                                if (src.password == null) {
                                    PdfPage(src.file, 0, 160, Modifier.size(44.dp, 58.dp).clip(RoundedCornerShape(4.dp)), crop = true)
                                } else {
                                    Icon(Icons.Filled.Lock, "Protected", Modifier.size(44.dp, 58.dp).padding(10.dp))
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("${i + 1}. ${titleOf(src.file)}", style = MaterialTheme.typography.titleSmall)
                                    Text(formatSize(src.file.length()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                IconButton(onClick = { items.removeAt(i) }) { Icon(Icons.Filled.Close, "Remove", tint = MaterialTheme.colorScheme.error) }
                            }
                        }
                    }
                }
            }
        }
    }

    if (pickMine) {
        val chosen = remember { mutableStateListOf<File>() }
        val mine = model.pdfs.orEmpty().filter { f -> items.none { it.file == f } }
        AlertDialog(
            onDismissRequest = { pickMine = false },
            title = { Text("Choose from My PDFs") },
            text = {
                if (mine.isEmpty()) {
                    Text("No more PDFs in My PDFs.")
                } else {
                    LazyColumn(Modifier.heightIn(max = 420.dp)) {
                        items(mine, key = { it.path }) { f ->
                            ListItem(
                                headlineContent = { Text(titleOf(f)) },
                                supportingContent = { Text(formatSize(f.length())) },
                                leadingContent = { Checkbox(f in chosen, { c -> if (c) chosen.add(f) else chosen.remove(f) }) },
                                modifier = Modifier.clickable { if (f in chosen) chosen.remove(f) else chosen.add(f) },
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { pickMine = false; chosen.forEach(::add) }, enabled = chosen.isNotEmpty()) { Text("Add ${chosen.size}") } },
            dismissButton = { TextButton(onClick = { pickMine = false }) { Text("Cancel") } },
        )
    }
}
