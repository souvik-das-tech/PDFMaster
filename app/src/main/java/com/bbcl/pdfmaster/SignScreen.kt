// Sign PDF: draw a signature with a finger (or pick a picture of one, or a stamp), then drag
// and size it on any page. The last signature is remembered for next time.
package com.bbcl.pdfmaster

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.Path
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private val inkColors = listOf(Color(0xFF111111), Color(0xFF1A3DB8), Color(0xFFC62828))

private fun savedSignature(context: Context) = File(context.filesDir, "signature.png")

/** Draws the strokes (in pad pixels) into a tightly cropped, transparent picture. */
private fun strokesToBitmap(strokes: List<List<Offset>>, color: Color, width: Float): Bitmap? {
    val points = strokes.flatten()
    if (points.isEmpty()) return null
    val pad = width * 2
    val minX = points.minOf { it.x } - pad
    val minY = points.minOf { it.y } - pad
    val maxX = points.maxOf { it.x } + pad
    val maxY = points.maxOf { it.y } + pad
    val scale = 2f // sharper than the screen
    val bmp = Bitmap.createBitmap(((maxX - minX) * scale).roundToInt().coerceAtLeast(1), ((maxY - minY) * scale).roundToInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color.toArgb()
        style = Paint.Style.STROKE
        strokeWidth = width * scale
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    for (s in strokes) {
        if (s.size == 1) {
            paint.style = Paint.Style.FILL
            canvas.drawCircle((s[0].x - minX) * scale, (s[0].y - minY) * scale, width * scale / 2, paint)
            paint.style = Paint.Style.STROKE
            continue
        }
        val path = Path()
        s.forEachIndexed { i, p ->
            val x = (p.x - minX) * scale
            val y = (p.y - minY) * scale
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, paint)
    }
    return bmp
}

@Composable
fun SignScreen(model: AppModel, src: PdfSource) {
    val context = LocalContext.current
    var signature by remember { mutableStateOf<Bitmap?>(null) }
    if (signature == null) {
        SignaturePad(model, onDone = { signature = it })
    } else {
        PlaceSignature(model, src, signature!!, onRedo = { signature = null })
    }
}

@Composable
private fun SignaturePad(model: AppModel, onDone: (Bitmap) -> Unit) {
    val context = LocalContext.current
    val strokes = remember { mutableStateListOf<List<Offset>>() }
    var current by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var ink by remember { mutableStateOf(inkColors[0]) }
    val strokeWidth = with(LocalDensity.current) { 3.dp.toPx() }
    val saved = savedSignature(context)
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it) }
            }.getOrNull()?.let { b ->
                val scale = min(1f, 1200f / max(b.width, b.height))
                onDone(if (scale < 1f) Bitmap.createScaledBitmap(b, (b.width * scale).roundToInt(), (b.height * scale).roundToInt(), true) else b)
            } ?: model.say("Could not open that picture")
        }
    }

    Scaffold(
        topBar = { ToolBar("Sign PDF", onBack = model::back, subtitle = "Step 1 of 2: your signature") },
        bottomBar = {
            BottomAction("Next: place it on the page", enabled = strokes.isNotEmpty(), icon = Icons.Filled.Check) {
                val bmp = strokesToBitmap(strokes, ink, strokeWidth) ?: return@BottomAction
                runCatching { FileOutputStream(saved).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } }
                onDone(bmp)
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(16.dp)) {
            Text("Sign with your finger in the box", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(10.dp))
            Box(
                Modifier.fillMaxWidth().height(240.dp).clip(RoundedCornerShape(16.dp)).background(Color.White)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp)),
            ) {
                Canvas(
                    Modifier.fillMaxSize().pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { current = listOf(it) },
                            onDrag = { change, _ -> change.consume(); current = current + change.position },
                            onDragEnd = { strokes.add(current); current = emptyList() },
                            onDragCancel = { strokes.add(current); current = emptyList() },
                        )
                    },
                ) {
                    val style = Stroke(strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
                    for (s in strokes + listOf(current)) {
                        if (s.size < 2) continue
                        val path = androidx.compose.ui.graphics.Path().apply {
                            moveTo(s[0].x, s[0].y)
                            for (p in s.drop(1)) lineTo(p.x, p.y)
                        }
                        drawPath(path, ink, style = style)
                    }
                    // Signature line.
                    drawLine(Color.LightGray, Offset(24f, size.height * 0.78f), Offset(size.width - 24f, size.height * 0.78f), 2f)
                }
                if (strokes.isEmpty() && current.isEmpty()) {
                    Text("Sign here", color = Color.LightGray, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.align(Alignment.Center))
                }
            }
            Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                for (c in inkColors) {
                    Box(
                        Modifier.padding(end = 10.dp).size(34.dp).clip(CircleShape).background(c)
                            .border(3.dp, if (c == ink) MaterialTheme.colorScheme.primary else Color.Transparent, CircleShape),
                    ) { IconButton(onClick = { ink = c }) {} }
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { if (strokes.isNotEmpty()) strokes.removeAt(strokes.lastIndex) }, enabled = strokes.isNotEmpty()) { Icon(Icons.AutoMirrored.Filled.Undo, "Undo") }
                IconButton(onClick = { strokes.clear() }, enabled = strokes.isNotEmpty()) { Icon(Icons.Filled.Delete, "Clear") }
            }
            Spacer(Modifier.height(16.dp))
            Text("Or use", style = MaterialTheme.typography.labelLarge)
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (saved.exists()) {
                    FilledTonalButton(onClick = { BitmapFactory.decodeFile(saved.path)?.let(onDone) }) {
                        Icon(Icons.Filled.History, null); Spacer(Modifier.width(6.dp)); Text("Last signature")
                    }
                }
                FilledTonalButton(onClick = { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                    Icon(Icons.Filled.Image, null); Spacer(Modifier.width(6.dp)); Text("A picture / stamp")
                }
            }
        }
    }
}

@Composable
private fun PlaceSignature(model: AppModel, src: PdfSource, signature: Bitmap, onRedo: () -> Unit) {
    val info by produceState<Pair<File, List<Pair<Int, Int>>>?>(null, src) {
        value = withContext(Dispatchers.IO) { runCatching { Raster.renderable(src).let { it to Raster.pageSizes(it) } }.getOrNull() }
    }
    var page by remember { mutableIntStateOf(0) }
    var x by remember { mutableFloatStateOf(0.55f) }
    var y by remember { mutableFloatStateOf(0.78f) }
    var w by remember { mutableFloatStateOf(0.3f) }
    var name by remember { mutableStateOf(titleOf(src.file) + "_signed") }
    val image = remember(signature) { signature.asImageBitmap() }
    val sigAspect = signature.height.toFloat() / signature.width

    Scaffold(
        topBar = { ToolBar("Sign PDF", onBack = onRedo, subtitle = "Step 2 of 2: drag the signature into place") },
        bottomBar = {
            BottomAction("Sign page ${page + 1}", enabled = info != null, icon = Icons.Filled.Draw) {
                val p = page
                val px = x
                val py = y
                val pw = w
                model.run("Signing") { _ ->
                    val out = Store.fileFor(name)
                    PdfOps.stamp(src, p, signature, px, py, pw, out)
                    Screen.Result(listOf(out), "PDF signed", "Signature added on page ${p + 1}.")
                }
            }
        },
    ) { padding ->
        val loaded = info
        if (loaded == null) return@Scaffold Spinner(Modifier.padding(padding))
        val (file, sizes) = loaded
        Column(Modifier.padding(padding).fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { page-- }, enabled = page > 0) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous page") }
                Text("Page ${page + 1} of ${sizes.size}", modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                IconButton(onClick = { page++ }, enabled = page < sizes.lastIndex) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next page") }
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHighest).padding(12.dp), contentAlignment = Alignment.Center) {
                val (pw, ph) = sizes[page]
                val aspect = ph.toFloat() / pw
                val boxW = min(maxWidth.value, maxHeight.value / aspect)
                val density = LocalDensity.current
                Box(Modifier.width(boxW.dp).height((boxW * aspect).dp).background(Color.White)) {
                    PdfPage(file, page, with(density) { boxW.dp.roundToPx() }, Modifier.fillMaxSize(), crop = true)
                    val pxW = with(density) { boxW.dp.toPx() }
                    val pxH = pxW * aspect
                    Image(
                        image,
                        "Signature",
                        modifier = Modifier
                            .offset { IntOffset((x * pxW).roundToInt(), (y * pxH).roundToInt()) }
                            .width((boxW * w).dp)
                            .height((boxW * w * sigAspect).dp)
                            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f))
                            .pointerInput(page) {
                                detectDragGestures { change, amount ->
                                    change.consume()
                                    x = (x + amount.x / pxW).coerceIn(0f, 1f - w)
                                    y = (y + amount.y / pxH).coerceIn(0f, 1f - w * sigAspect * pxW / pxH)
                                }
                            },
                    )
                }
            }
            Surface(tonalElevation = 1.dp) {
                Column(Modifier.padding(16.dp, 8.dp)) {
                    Text("Size", style = MaterialTheme.typography.labelLarge)
                    Slider(w, { v -> w = v; x = x.coerceAtMost(1f - v) }, valueRange = 0.08f..0.8f)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onRedo) { Text("Change signature") }
                    }
                }
            }
        }
    }
}
