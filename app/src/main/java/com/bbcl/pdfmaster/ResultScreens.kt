// Text to PDF, and the screen shown after a tool has finished.
package com.bbcl.pdfmaster

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.io.File
import kotlin.math.roundToInt

@Composable
fun TextToPdfScreen(model: AppModel) {
    var title by rememberSaveable { mutableStateOf("") }
    var body by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf(Store.defaultName("Text")) }
    var font by remember { mutableStateOf(TextToPdf.Font.SANS) }
    var size by remember { mutableStateOf(PageSize.A4) }
    var fontSize by remember { mutableFloatStateOf(12f) }
    Scaffold(
        topBar = { ToolBar("Text to PDF", onBack = model::back) },
        bottomBar = {
            BottomAction("Create PDF", enabled = body.isNotBlank() || title.isNotBlank(), icon = Icons.Filled.PictureAsPdf) {
                val heading = title
                val text = body
                val f = font
                val s = size
                val fs = fontSize
                model.run("Making your PDF") { _ ->
                    val out = Store.fileFor(name)
                    TextToPdf.make(text, heading, s, f, fs, out)
                    Raster.pageCount(out).let { n -> Screen.Result(listOf(out), "PDF created", "$n page${if (n == 1) "" else "s"} • ${formatSize(out.length())}") }
                }
            }
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
            item {
                NameField(name, { name = it }, "PDF name")
                OutlinedTextField(title, { title = it }, label = { Text("Heading (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                OutlinedTextField(body, { body = it }, label = { Text("Text") }, minLines = 10, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                Text("Any language works, including Bangla and Hindi.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                SectionTitle("Layout")
                Choice("Font", TextToPdf.Font.entries, font, { it.label }) { font = it }
                Choice("Page size", PageSize.entries.filter { it != PageSize.FIT }, size, { it.label }) { size = it }
                Text("Text size: ${fontSize.roundToInt()} pt", style = MaterialTheme.typography.labelLarge)
                Slider(fontSize, { fontSize = it }, valueRange = 8f..24f)
            }
        }
    }
}

@Composable
fun ResultScreen(model: AppModel, result: Screen.Result, onFileAction: (File, FileAction) -> Unit) {
    val files = result.files.filter { it.exists() }
    Scaffold(topBar = { ToolBar("Done", onBack = model::back) }) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    val ok = files.isNotEmpty() || result.files.isEmpty() && !result.headline.startsWith("Already")
                    Box(Modifier.size(84.dp).background((if (ok) Brand.optimize else MaterialTheme.colorScheme.secondary).copy(alpha = 0.15f), CircleShape), contentAlignment = Alignment.Center) {
                        Icon(if (ok) Icons.Filled.CheckCircle else Icons.Filled.Info, null, tint = if (ok) Brand.optimize else MaterialTheme.colorScheme.secondary, modifier = Modifier.size(52.dp))
                    }
                    Spacer(Modifier.height(16.dp))
                    Text(result.headline, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(6.dp))
                    Text(result.detail, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                }
            }
            if (files.size == 1) {
                val f = files[0]
                item {
                    PdfFileCard(f, onClick = { model.useFile(Tool.VIEW, f, replaceCurrent = true) }, onAction = { onFileAction(f, it) })
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { model.useFile(Tool.VIEW, f, replaceCurrent = true) }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                        Icon(Icons.Filled.Visibility, null); Spacer(Modifier.width(8.dp)); Text("Open PDF")
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = { model.share(listOf(f)) }, modifier = Modifier.weight(1f)) { Icon(Icons.Filled.Share, null); Spacer(Modifier.width(6.dp)); Text("Share") }
                        FilledTonalButton(onClick = { onFileAction(f, FileAction.SAVE) }, modifier = Modifier.weight(1f)) { Icon(FileAction.SAVE.icon, null); Spacer(Modifier.width(6.dp)); Text("Save to phone") }
                    }
                }
            } else if (files.size > 1) {
                item {
                    FilledTonalButton(onClick = { model.share(files) }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Filled.Share, null); Spacer(Modifier.width(6.dp)); Text("Share all ${files.size}") }
                }
                items(files, key = { it.path }) { f ->
                    PdfFileCard(f, onClick = { model.useFile(Tool.VIEW, f) }, onAction = { onFileAction(f, it) })
                }
            }
            item {
                OutlinedButton(onClick = model::home, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Icon(Icons.Filled.Home, null); Spacer(Modifier.width(6.dp)); Text("Back to tools")
                }
            }
        }
    }
}
