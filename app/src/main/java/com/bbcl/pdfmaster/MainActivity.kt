package com.bbcl.pdfmaster

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.io.File

class MainActivity : ComponentActivity() {
    private val model: AppModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) model.handleIntent(intent)
        setContent { PdfMasterTheme { App(model) { startActivity(it) } } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        model.handleIntent(intent)
    }
}

@Composable
private fun App(model: AppModel, startActivity: (Intent) -> Unit) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(model) { model.messages.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(model) {
        model.intents.collect { runCatching { startActivity(it) }.onFailure { model.say("No app can do that") } }
    }

    // File actions shared by every screen (rename and delete dialogs, "save to phone").
    var renaming by remember { mutableStateOf<File?>(null) }
    var deleting by remember { mutableStateOf<File?>(null) }
    var saving by remember { mutableStateOf<File?>(null) }
    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val f = saving
        saving = null
        if (uri != null && f != null) model.saveCopy(f, uri)
    }
    val onFileAction: (File, FileAction) -> Unit = { f, action ->
        when (action) {
            FileAction.SHARE -> model.share(listOf(f))
            FileAction.SAVE -> { saving = f; saveLauncher.launch(f.name) }
            FileAction.RENAME -> renaming = f
            FileAction.DELETE -> deleting = f
        }
    }

    val browse = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val tool = model.choosingFor
        model.choosingFor = null
        if (uri != null && tool != null) model.useUri(tool, uri)
    }

    BackHandler(enabled = model.stack.size > 1) { model.back() }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize()) {
            AnimatedContent(model.screen, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "screen") { s ->
                when (s) {
                    Screen.Home -> HomeScreen(model, onFileAction)
                    is Screen.Viewer -> ViewerScreen(model, s.src, onFileAction)
                    is Screen.Images -> ImagesScreen(model, s.initial, s.camera)
                    Screen.TextToPdf -> TextToPdfScreen(model)
                    is Screen.Merge -> MergeScreen(model, s.initial)
                    is Screen.Pages -> PagesScreen(model, s.tool, s.src)
                    is Screen.Form -> FormScreen(model, s.tool, s.src)
                    is Screen.Sign -> SignScreen(model, s.src)
                    is Screen.EditText -> EditTextScreen(model, s.src)
                    is Screen.Result -> ResultScreen(model, s, onFileAction)
                }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 72.dp)) { Snackbar(it) }
        }
    }

    model.choosingFor?.let { tool ->
        ChoosePdfSheet(
            title = tool.label,
            files = model.pdfs.orEmpty(),
            onBrowse = { browse.launch(arrayOf("application/pdf")) },
            onPick = { f -> model.choosingFor = null; model.useFile(tool, f) },
            onDismiss = { model.choosingFor = null },
        )
    }
    model.job?.let { JobDialog(it) }
    model.passwordPrompt?.let { PasswordDialog(it) }
    renaming?.let { f ->
        RenameDialog(f) { title ->
            renaming = null
            if (title != null && model.rename(f, title) == null && title.trim() != titleOf(f)) model.say("Could not rename")
        }
    }
    deleting?.let { f ->
        DeleteDialog(f) { ok ->
            deleting = null
            if (ok) {
                model.delete(f)
                if ((model.screen as? Screen.Viewer)?.src?.file == f) model.back()
            }
        }
    }
}
