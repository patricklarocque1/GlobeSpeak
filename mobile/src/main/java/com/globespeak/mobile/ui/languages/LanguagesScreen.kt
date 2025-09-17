package com.globespeak.mobile.ui.languages

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.globespeak.engine.backend.ModelLocator
import com.globespeak.engine.backend.NllbValidator
import com.globespeak.engine.whisper.WhisperModelLocator
import com.globespeak.mobile.logging.LogBus
import com.globespeak.mobile.logging.LogLine
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

@Composable
fun LanguagesScreen(vm: LanguagesViewModel = viewModel()) {
    val ui by vm.ui.collectAsState()

    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val ctx = LocalContext.current

    val onnxPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) scope.launch {
            copyIntoAppDir(
                ctx = ctx,
                uri = uri,
                destDir = ModelLocator(ctx).baseDir(),
                resolvedName = "nllb.onnx",
                allowedNames = setOf("nllb.onnx")
            ).onSuccess { name ->
                snackbar.showSnackbar("Imported $name")
                vm.onModelFileImported(name)
            }.onFailure { e ->
                snackbar.showSnackbar("Import failed: ${e.message}")
            }
        }
    }
    val spmPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) scope.launch {
            copyIntoAppDir(
                ctx = ctx,
                uri = uri,
                destDir = ModelLocator(ctx).baseDir(),
                resolvedName = "tokenizer.model",
                allowedNames = setOf("tokenizer.model")
            ).onSuccess { name ->
                snackbar.showSnackbar("Imported $name")
                vm.onModelFileImported(name)
            }.onFailure { e ->
                snackbar.showSnackbar("Import failed: ${e.message}")
            }
        }
    }
    val importProgress = remember { mutableStateOf<ImportProgress?>(null) }
    val whisperPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri>? ->
        if (uris.isNullOrEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val locator = WhisperModelLocator(ctx)
            val allowedNames = locator.requiredFiles().map { it.name }.toSet()
            val total = uris.size
            importProgress.value = ImportProgress(completed = 0, total = total)
            var successCount = 0
            var failureMessages = mutableListOf<String>()
            uris.forEachIndexed { index, uri ->
                val result = copyIntoAppDir(
                    ctx = ctx,
                    uri = uri,
                    destDir = locator.baseDir(),
                    resolvedName = null,
                    allowedNames = allowedNames,
                    allowedExtensions = WHISPER_ALLOWED_EXTENSIONS
                )
                result.onSuccess { name ->
                    vm.onModelFileImported(name)
                    successCount += 1
                }.onFailure { e ->
                    failureMessages += (e.message ?: "Import failed")
                }
                importProgress.value = ImportProgress(completed = index + 1, total = total)
            }
            importProgress.value = null

            if (successCount > 0) {
                snackbar.showSnackbar("Imported $successCount/${total} Whisper file${if (successCount == 1) "" else "s"}")
            }
            if (failureMessages.isNotEmpty()) {
                snackbar.showSnackbar(failureMessages.joinToString(limit = 1, truncated = " ⋯"))
            }

            val missing = locator.requiredFiles().filterNot { it.exists() }
            if (missing.isEmpty()) {
                snackbar.showSnackbar("Whisper bundle ready")
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Source language: Auto-detect")
                TargetLanguagePicker(ui.supported, ui.selectedTarget, onChange = vm::setTarget)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Wi‑Fi only downloads")
                    Switch(checked = ui.wifiOnly, onCheckedChange = vm::toggleWifiOnly)
                }
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Engine")
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { vm.setEngineMode("standard") }, enabled = ui.engineMode != "standard") { Text("Standard (ML Kit)") }
                    Button(onClick = { vm.setEngineMode("advanced") }, enabled = ui.engineMode != "advanced") { Text("Advanced (NLLB-ONNX)") }
                }
                Text("Device capability: " + (if (ui.deviceCapable) "OK" else "Not supported"))
                Text("NLLB model: " + (if (ui.nllbModelPresent) "Found" else "Missing"))
                Text("Whisper model: " + (if (ui.whisperModelPresent) "Found" else "Missing"))
                val active = if (ui.activeEngine == "advanced") "Advanced" else "Standard"
                val reason = ui.fallbackReason
                Text("Active: $active" + (if (reason != null) " [fallback: $reason]" else ""))
            }
        }
        Card(Modifier.fillMaxWidth()) {
            val path = ModelLocator(ctx).baseDir().absolutePath
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Advanced model (NLLB-ONNX)")
                Text("Model folder: $path")
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { onnxPicker.launch(arrayOf("application/octet-stream", "application/*")) }) { Text("Import ONNX") }
                    Button(onClick = { spmPicker.launch(arrayOf("application/octet-stream", "application/*")) }) { Text("Import Tokenizer") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = {
                        scope.launch {
                            val (ok, msg) = NllbValidator.validate(ctx)
                            if (ok) {
                                LogBus.log("Languages", "Validator passed", LogLine.Kind.ENGINE)
                                vm.refreshAdvancedStatus()
                            } else {
                                LogBus.log("Languages", "Validator failed: ${msg}", LogLine.Kind.ENGINE)
                            }
                            snackbar.showSnackbar(if (ok) "Model OK" else "Invalid: ${msg}")
                        }
                    }) { Text("Validate Model") }
                }
            }
        }
        Card(Modifier.fillMaxWidth()) {
            val whisperPath = WhisperModelLocator(ctx).baseDir().absolutePath
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Whisper (speech-to-text)")
                Text("Model folder: $whisperPath")
                if (ui.whisperMissing.isEmpty()) {
                    Text("All required files present")
                } else {
                    Text("Missing files: ${ui.whisperMissing.joinToString()}" )
                }
                importProgress.value?.let { progress ->
                    val fraction = if (progress.total == 0) 0f else progress.completed / progress.total.toFloat()
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                        LinearProgressIndicator(progress = { fraction })
                        Text("Importing ${progress.completed}/${progress.total}")
                    }
                }
                Button(onClick = { whisperPicker.launch(WHISPER_MIME_TYPES) }) {
                    Text("Import Whisper file")
                }
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Model status: ${modelLabel(ui.modelState)}")
                if (ui.modelState is ModelState.Downloading) {
                    CircularProgressIndicator()
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = vm::downloadModel, enabled = ui.modelState is ModelState.Idle || ui.modelState is ModelState.Error) { Text("Download") }
                    Button(onClick = vm::deleteModel, enabled = ui.modelState is ModelState.Ready) { Text("Delete") }
                    Button(onClick = { vm.checkModel() }) { Text("Check") }
                }
            }
        }
    }
    SnackbarHost(hostState = snackbar)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TargetLanguagePicker(languages: List<String>, current: String, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        TextField(
            value = languageName(current),
            onValueChange = {},
            label = { Text("Target language") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
            readOnly = true
        )
        androidx.compose.material3.DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            languages.forEach { code ->
                androidx.compose.material3.DropdownMenuItem(text = { Text(languageName(code)) }, onClick = {
                    onChange(code)
                    expanded = false
                })
            }
        }
    }
}

private fun languageName(code: String): String =
    Locale(code).displayName.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() } +
        " ($code)"

private fun modelLabel(state: ModelState): String = when (state) {
    is ModelState.Idle -> "Idle"
    is ModelState.Checking -> "Checking…"
    is ModelState.Downloading -> "Downloading…"
    is ModelState.Ready -> "Ready"
    is ModelState.Error -> "Error: ${state.message}"
}

private suspend fun copyIntoAppDir(
    ctx: Context,
    uri: Uri,
    destDir: File,
    resolvedName: String?,
    allowedNames: Set<String>?,
    allowedExtensions: Set<String>? = null
): Result<String> {
    return runCatching {
        val displayName = queryDisplayName(ctx, uri)
        val target = resolvedName ?: displayName ?: error("Unknown file name")
        val candidate = resolvedName ?: displayName
        if (allowedNames != null) {
            val nameAllowed = candidate != null && allowedNames.contains(candidate)
            val extensionAllowed = candidate != null && allowedExtensions?.any { candidate.endsWith(it, ignoreCase = true) } == true
            require(nameAllowed || extensionAllowed) { "Unsupported file (${candidate ?: "unknown"})" }
        } else if (allowedExtensions != null) {
            require(candidate != null && allowedExtensions.any { candidate.endsWith(it, ignoreCase = true) }) { "Unsupported file (${candidate ?: "unknown"})" }
        }

        destDir.mkdirs()
        val dest = File(destDir, target)
        ctx.contentResolver.openInputStream(uri)?.use { inp ->
            dest.outputStream().use { out -> inp.copyTo(out) }
        } ?: error("Cannot open input stream")
        target
    }
}

private fun queryDisplayName(ctx: Context, uri: Uri): String? {
    return ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
    }
}

private data class ImportProgress(val completed: Int, val total: Int)

private val WHISPER_ALLOWED_EXTENSIONS = setOf(".onnx", ".bin", ".json", ".gguf")
private val WHISPER_MIME_TYPES = arrayOf(
    "application/octet-stream",
    "application/x-gguf",
    "application/json",
    "application/*"
)
