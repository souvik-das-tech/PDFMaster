package com.bbcl.pdfmaster

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import kotlin.math.max
import androidx.compose.ui.unit.max
import androidx.compose.runtime.remember

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(model: AppModel, onFileAction: (File, FileAction) -> Unit) {
    var tab by rememberSaveable { mutableStateOf(0) }
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(painterResource(R.drawable.logo), null, Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)))
                        Spacer(Modifier.width(10.dp))
                        Text("PDF Master", fontWeight = FontWeight.ExtraBold)
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    icon = { Icon(if (tab == 0) Icons.Filled.Apps else Icons.Outlined.Apps, null) },
                    label = { Text("Tools") },
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    icon = { Icon(if (tab == 1) Icons.Filled.Folder else Icons.Outlined.Folder, null) },
                    label = { Text("My PDFs" + (model.pdfs?.size?.takeIf { it > 0 }?.let { " ($it)" } ?: "")) },
                )
            }
        },
        floatingActionButton = {
            if (tab == 1 && !model.pdfs.isNullOrEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = { model.startTool(Tool.IMAGES) },
                    icon = { Icon(Icons.Filled.AddPhotoAlternate, null) },
                    text = { Text("Image to PDF") },
                )
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            if (tab == 0) ToolsTab(model) else FilesTab(model, onFileAction)
        }
    }
}

@Composable
private fun ToolsTab(model: AppModel) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
    val tileHeight = rememberTallestTile(maxWidth)
    LazyVerticalGrid(
        columns = GridCells.Adaptive(160.dp),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) { Hero(model) }
        for (group in ToolGroup.entries) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(group.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp, start = 4.dp))
            }
            items(Tool.entries.filter { it.group == group }, key = { it.name }) { tool -> ToolTile(tool, tileHeight) { model.startTool(tool) } }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.WifiOff, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(6.dp))
                Text("Everything runs on your phone. Nothing is uploaded.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    }
}

// Tool tile layout, shared by the tile and the measurement below.
private val tilePadding = 12.dp
private val tileIcon = 42.dp
private val tileGap = 10.dp
private val gridPadding = 16.dp
private val gridSpacing = 10.dp
private val minCell = 160.dp

/**
 * Height of the tallest tool tile at this screen width, so every tile can take it and the
 * grid stays even. Measured from the texts up front, so tiles never change size on scroll.
 */
@Composable
private fun rememberTallestTile(width: Dp): Dp {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val labelStyle = MaterialTheme.typography.titleSmall
    val detailStyle = MaterialTheme.typography.bodySmall.copy(lineHeight = 15.sp)
    return remember(width, density, labelStyle, detailStyle) {
        with(density) {
            // Same column count as GridCells.Adaptive(minCell).
            val content = width - gridPadding * 2
            val columns = max(1, ((content + gridSpacing) / (minCell + gridSpacing)).toInt())
            val cell = (content - gridSpacing * (columns - 1)) / columns
            val textWidth = (cell - tilePadding * 2 - tileIcon - tileGap).roundToPx().coerceAtLeast(1)
            val tallest = Tool.entries.maxOf { tool ->
                val c = Constraints(maxWidth = textWidth)
                measurer.measure(tool.label, labelStyle, constraints = c).size.height +
                    measurer.measure(tool.detail, detailStyle, constraints = c).size.height
            }
            max(tallest.toDp(), tileIcon) + tilePadding * 2
        }
    }
}

@Composable
private fun Hero(model: AppModel) {
    Surface(shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
        Box(Modifier.background(Brand.gradient).padding(20.dp)) {
            Column {
                Text("All your PDF tools", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text("Create, merge, compress, protect and more — offline and private.", color = Color.White.copy(alpha = 0.9f), fontSize = 14.sp)
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    HeroButton("Image to PDF", Icons.Filled.AddPhotoAlternate) { model.startTool(Tool.IMAGES) }
                    HeroButton("Open PDF", Icons.Filled.PictureAsPdf) { model.startTool(Tool.VIEW) }
                }
            }
        }
    }
}

@Composable
private fun HeroButton(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(12.dp), color = Color.White) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = Brand.redDark, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(6.dp))
            Text(text, color = Brand.redDark, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        }
    }
}

@Composable
private fun ToolTile(tool: Tool, height: Dp, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth().heightIn(min = height)) {
        Row(Modifier.padding(tilePadding), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(tileIcon).clip(CircleShape).background(tool.color.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                Icon(tool.icon, null, tint = tool.color, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(tileGap))
            Column(Modifier.weight(1f)) {
                Text(tool.label, style = MaterialTheme.typography.titleSmall)
                Text(tool.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 15.sp)
            }
        }
    }
}

@Composable
private fun FilesTab(model: AppModel, onFileAction: (File, FileAction) -> Unit) {
    val pdfs = model.pdfs ?: return Spinner()
    if (pdfs.isEmpty()) {
        EmptyState(
            Icons.Filled.PictureAsPdf,
            "No PDFs yet",
            "PDFs you make or edit with PDF Master appear here.",
            Modifier.fillMaxSize(),
        ) {
            androidx.compose.material3.Button(onClick = { model.startTool(Tool.IMAGES) }) {
                Icon(Icons.Filled.AddPhotoAlternate, null)
                Spacer(Modifier.width(8.dp))
                Text("Image to PDF")
            }
        }
        return
    }
    var query by rememberSaveable { mutableStateOf("") }
    val shown = if (query.isBlank()) pdfs else pdfs.filter { titleOf(it).contains(query.trim(), ignoreCase = true) }
    LazyColumn(contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            OutlinedTextField(
                query,
                { query = it },
                placeholder = { Text("Search my PDFs") },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
            )
        }
        items(shown, key = { it.path }) { f ->
            PdfFileCard(f, onClick = { model.useFile(Tool.VIEW, f) }, onAction = { onFileAction(f, it) })
        }
        if (shown.isEmpty()) item { Text("No PDF matches \"$query\".", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp)) }
    }
}
