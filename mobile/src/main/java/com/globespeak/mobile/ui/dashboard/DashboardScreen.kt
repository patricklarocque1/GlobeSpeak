package com.globespeak.mobile.ui.dashboard

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.android.gms.wearable.Node
import java.util.Locale

@Composable
fun DashboardScreen(vm: DashboardViewModel = viewModel()) {
    val running by vm.serviceRunning.collectAsState()
    val nodes by vm.nodes.collectAsState()
    val input by vm.input.collectAsState()
    val target by vm.target.collectAsState()
    val result by vm.result.collectAsState()
    val languages by vm.languages.collectAsState()
    val statusText by vm.engineStatusText.collectAsState()

    val notifLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted -> if (granted) vm.startService() }
    )

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        ServiceStatusCard(running = running, statusText = statusText, onStart = {
            if (Build.VERSION.SDK_INT >= 33) {
                notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else vm.startService()
        }, onStop = vm::stopService)

        WearConnectionCard(nodes)

        QuickTestCard(
            languages = languages,
            input = input,
            onInputChange = vm::setInput,
            target = target,
            onTargetChange = vm::setTarget,
            onRun = vm::runQuickTranslate,
            result = result
        )
    }
}

@Composable
private fun ServiceStatusCard(
    running: Boolean,
    statusText: String,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Service", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text(if (running) "Running" else "Stopped", fontWeight = FontWeight.SemiBold)
                Button(onClick = if (running) onStop else onStart) {
                    Text(if (running) "Stop" else "Start")
                }
            }
            if (statusText.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(statusText, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun WearConnectionCard(nodes: List<Node>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Wear connection", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            if (nodes.isEmpty()) {
                Text("No watch connected")
            } else {
                nodes.forEach { n ->
                    Text("Connected: ${n.displayName} (${n.id})")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuickTestCard(
    languages: List<String>,
    input: String,
    onInputChange: (String) -> Unit,
    target: String,
    onTargetChange: (String) -> Unit,
    onRun: () -> Unit,
    result: String
) {
    var expanded by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Quick Test", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = input,
                onValueChange = onInputChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Enter phrase to translate") }
            )

            ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                TextField(
                    value = languageDisplay(target),
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Target language") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                    modifier = Modifier.menuAnchor().fillMaxWidth()
                )
                androidx.compose.material3.DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    languages.forEach { code ->
                        androidx.compose.material3.DropdownMenuItem(text = { Text(languageDisplay(code)) }, onClick = {
                            onTargetChange(code)
                            expanded = false
                        })
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onRun) { Text("Translate on Phone") }
            }
            if (result.isNotEmpty()) {
                Text("Result: $result")
            }
        }
    }
}

private fun languageDisplay(code: String): String {
    val locale = Locale(code)
    val name = locale.displayName.replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
    return "$name ($code)"
}
