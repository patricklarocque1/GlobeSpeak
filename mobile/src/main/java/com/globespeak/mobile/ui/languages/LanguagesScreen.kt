package com.globespeak.mobile.ui.languages

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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import java.util.Locale

@Composable
fun LanguagesScreen(vm: LanguagesViewModel = viewModel()) {
    val ui by vm.ui.collectAsState()

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
