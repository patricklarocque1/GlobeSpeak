package com.globespeak.mobile.ui.bench

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.globespeak.engine.TranslatorEngine
import com.globespeak.engine.mlkit.MLKitTranslationBackend
import com.globespeak.engine.backend.NllbOnnxTranslationBackend
import com.globespeak.engine.backend.ModelLocator
import kotlinx.coroutines.launch
import kotlin.system.measureTimeMillis

@Composable
fun BenchScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var text by remember { mutableStateOf("Hello, how are you?") }
    var target by remember { mutableStateOf("fr") }
    var languages by remember { mutableStateOf(listOf("en", "fr", "es")) }

    var resultStd by remember { mutableStateOf("") }
    var resultAdv by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        // Load once from ML Kit
        runCatching { MLKitTranslationBackend().supportedLanguageTags().toList().sorted() }
            .onSuccess { languages = it }
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextField(value = text, onValueChange = { text = it }, label = { Text("Input text") }, modifier = Modifier.fillMaxWidth())
                // Minimal target picker (simple text field) to avoid duplicating dropdowns
                TextField(value = target, onValueChange = { target = it }, label = { Text("Target language (bcp47)") }, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = {
                        scope.launch { resultStd = runBench(ctx, text, target, useAdvanced = false) }
                    }) { Text("Translate (Standard)") }
                    Button(onClick = {
                        scope.launch { resultAdv = runBench(ctx, text, target, useAdvanced = true) }
                    }) { Text("Translate (Advanced)") }
                }
            }
        }
        if (resultStd.isNotBlank()) Card(Modifier.fillMaxWidth()) { Text(resultStd, modifier = Modifier.padding(16.dp)) }
        if (resultAdv.isNotBlank()) Card(Modifier.fillMaxWidth()) { Text(resultAdv, modifier = Modifier.padding(16.dp)) }
    }
}

private suspend fun runBench(ctx: Context, input: String, target: String, useAdvanced: Boolean): String {
    val backend = if (useAdvanced) NllbOnnxTranslationBackend(ctx, ModelLocator(ctx)) else MLKitTranslationBackend()
    val engine = TranslatorEngine(backend)
    var out: String? = null
    var err: String? = null
    val ms = try {
        measureTimeMillis { out = engine.translate(input, source = "auto", target = target) }
    } catch (t: Throwable) {
        err = t.message ?: t::class.java.simpleName
        -1
    }
    return formatResultRow(if (useAdvanced) "Advanced" else "Standard", ms, out, err)
}

@VisibleForTesting
internal fun formatResultRow(name: String, ms: Long, text: String?, error: String?): String {
    val meta = if (ms >= 0) "${ms}ms" else "error"
    val body = error ?: text ?: ""
    return "$name: $meta\n$body".trim()
}

