// Image to PDF: the chosen photos as pages (add more from the gallery, camera or files, drag
// to reorder, rotate, remove), the PDF's name and settings, and the button that makes it.
package com.bbcl.pdfmaster

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** A small, upright-enough preview of a photo (EXIF rotation is applied when the PDF is made). */
@Composable
fun PhotoThumb(uri: Uri, quarterTurns: Int, modifier: Modifier) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(null, uri) {
        value = withContext(Dispatchers.IO) { runCatching { decodeThumb(context, uri, 240) }.getOrNull() }
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
        val b = bitmap
        if (b != null) {
            Image(b.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().rotate(quarterTurns * 90f))
        } else {
            Icon(Icons.Filled.BrokenImage, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun decodeThumb(context: Context, uri: Uri, size: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= size) sample *= 2
    return context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImagesScreen(model: AppModel, initial: List<Uri>, camera: Boolean) {
    val context = LocalContext.current
    val pages = remember { mutableStateListOf<PageImage>().apply { addAll(initial.map { PageImage(it) }) } }
    var name by rememberSaveable { mutableStateOf(Store.defaultName()) }
    var settings by remember { mutableStateOf(PdfSettings.load(context)) }
    var showSettings by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    var addMenu by remember { mutableStateOf(false) }

    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        pages.addAll(uris.map { PageImage(it) })
    }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        pages.addAll(uris.map { PageImage(it) })
    }
    var pendingPhoto by remember { mutableStateOf<Uri?>(null) }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = pendingPhoto
        if (ok && uri != null) pages.add(PageImage(uri))
    }
    fun openCamera() {
        val f = File(Store.tempDir("camera"), "photo_${System.currentTimeMillis()}.jpg")
        val uri = Store.uriFor(f)
        pendingPhoto = uri
        runCatching { takePhoto.launch(uri) }.onFailure { model.say("No camera app found") }
    }
    fun openGallery() = gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))

    var started by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!started && initial.isEmpty()) {
            started = true
            if (camera) openCamera() else openGallery()
        }
    }

    BackHandler { if (pages.isEmpty()) model.back() else confirmLeave = true }

    val listState = rememberLazyListState()
    val reorder = remember { ReorderState(listState) { from, to -> pages.add(to, pages.removeAt(from)) } }

    Scaffold(
        topBar = {
            ToolBar(if (camera) "Camera Scan" else "Image to PDF", onBack = { if (pages.isEmpty()) model.back() else confirmLeave = true }) {
                IconButton(onClick = ::openCamera) { Icon(Icons.Filled.AddAPhoto, "Take a photo") }
                IconButton(onClick = ::openGallery) { Icon(Icons.Filled.AddPhotoAlternate, "Add photos") }
            }
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Row(Modifier.windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime)).padding(16.dp, 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { showSettings = true }) {
                        Icon(Icons.Filled.Tune, null)
                        Spacer(Modifier.width(6.dp))
                        Text(settings.size.label)
                    }
                    Spacer(Modifier.width(12.dp))
                    androidx.compose.material3.Button(
                        onClick = {
                            val list = pages.toList()
                            val s = settings.copy()
                            model.run("Making your PDF") { progress ->
                                val out = ImageToPdf.make(context, list, s, name, progress)
                                Screen.Result(listOf(out), "PDF created", "${list.size} page${if (list.size == 1) "" else "s"} • ${formatSize(out.length())}")
                            }
                        },
                        enabled = pages.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Filled.PictureAsPdf, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Create PDF (${pages.size})")
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            NameField(name, { name = it }, "PDF name", modifier = Modifier.padding(16.dp, 8.dp))
            if (pages.isEmpty()) {
                EmptyState(Icons.Filled.AddPhotoAlternate, "No pages yet", "Add photos from your gallery, take pictures with the camera, or pick image files.") {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = ::openGallery) { Icon(Icons.Filled.AddPhotoAlternate, null); Spacer(Modifier.width(6.dp)); Text("Choose photos") }
                        FilledTonalButton(onClick = ::openCamera) { Icon(Icons.Filled.AddAPhoto, null); Spacer(Modifier.width(6.dp)); Text("Take a photo") }
                        TextButton(onClick = { files.launch(arrayOf("image/*")) }) { Icon(Icons.Filled.FolderOpen, null); Spacer(Modifier.width(6.dp)); Text("Pick image files") }
                    }
                }
            } else {
                Text("Drag ⠿ to reorder pages", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp))
                LazyColumn(state = listState, contentPadding = PaddingValues(12.dp, 8.dp, 12.dp, 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(pages, key = { _, p -> p.id }) { i, page ->
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            modifier = Modifier.reorderItem(reorder, i),
                        ) {
                            Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.DragIndicator, "Drag to reorder", Modifier.dragHandle(reorder, i).padding(6.dp))
                                PhotoThumb(page.uri, page.quarterTurns, Modifier.size(72.dp).clip(RoundedCornerShape(8.dp)))
                                Spacer(Modifier.width(12.dp))
                                Text("Page ${i + 1}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                                IconButton(onClick = { page.quarterTurns = (page.quarterTurns + 1) % 4 }) {
                                    Icon(Icons.AutoMirrored.Filled.RotateRight, "Rotate")
                                }
                                IconButton(onClick = { pages.removeAt(i) }) { Icon(Icons.Filled.Close, "Remove", tint = MaterialTheme.colorScheme.error) }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showSettings) {
        ModalBottomSheet(onDismissRequest = { showSettings = false }) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp, 0.dp, 20.dp, 24.dp)) {
                Text("PDF settings", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 16.dp))
                fun update(change: PdfSettings.() -> Unit) {
                    settings = settings.copy().apply(change)
                    settings.save(context)
                }
                Choice("Page size", PageSize.entries, settings.size, { it.label }) { update { size = it } }
                if (settings.size != PageSize.FIT) Choice("Orientation", PageOrientation.entries, settings.orientation, { it.label }) { update { orientation = it } }
                Choice("Margins", PageMargin.entries, settings.margin, { it.label }) { update { margin = it } }
                Choice("Image quality", ImageQuality.entries, settings.quality, { it.label }) { update { quality = it } }
                ListItem(
                    headlineContent = { Text("Black & white") },
                    supportingContent = { Text("Good for documents, and makes the file smaller") },
                    trailingContent = { Switch(settings.grayscale, { v -> update { grayscale = v } }) },
                )
            }
        }
    }

    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text("Discard this PDF?") },
            text = { Text("The photos you chose will not be turned into a PDF.") },
            confirmButton = { TextButton(onClick = { confirmLeave = false; model.back() }) { Text("Discard") } },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("Keep editing") } },
        )
    }
}
