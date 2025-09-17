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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.globespeak.engine.TranslatorEngine
import com.globespeak.engine.backend.ModelLocator
import com.globespeak.engine.backend.NllbOnnxTranslationBackend
import com.globespeak.engine.mlkit.MLKitTranslationBackend
import com.globespeak.mobile.logging.LogBus
import com.globespeak.mobile.logging.LogLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToLong
import kotlin.system.measureTimeMillis

@Composable
fun BenchScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var text by remember { mutableStateOf("Hello, how are you?") }
    var target by remember { mutableStateOf("fr") }
    var languages by remember { mutableStateOf(listOf("en", "fr", "es")) }
    var running by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<BenchResult>>(emptyList()) }

    LaunchedEffect(Unit) {
        runCatching { MLKitTranslationBackend().supportedLanguageTags().toList().sorted() }
            .onSuccess { languages = it }
        // If the current target is not in the supported list, keep user entry but suggest otherwise
        if (!languages.contains(target)) {
            target = target.lowercase()
        }
    }

    fun updateResult(res: BenchResult) {
        results = results.filterNot { it.name == res.name } + res
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Input text") },
                    modifier = Modifier.fillMaxWidth()
                )
                TextField(
                    value = target,
                    onValueChange = { target = it },
                    label = { Text("Target language (BCP-47)") },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = {
                            scope.launch {
                                running = true
                                updateResult(runBench(ctx, text, target, useAdvanced = false, runs = 1))
                                running = false
                            }
                        },
                        enabled = !running
                    ) { Text("Run Standard") }
                    Button(
                        onClick = {
                            scope.launch {
                                running = true
                                updateResult(runBench(ctx, text, target, useAdvanced = true, runs = 1))
                                running = false
                            }
                        },
                        enabled = !running
                    ) { Text("Run Advanced") }
                    Button(
                        onClick = {
                            scope.launch {
                                running = true
                                updateResult(runBench(ctx, text, target, useAdvanced = false, runs = 3))
                                updateResult(runBench(ctx, text, target, useAdvanced = true, runs = 3))
                                running = false
                            }
                        },
                        enabled = !running
                    ) { Text("Run Both ×3") }
                }
                if (running) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator()
                        Text("Benchmark running…")
                    }
                }
            }
        }

        results.sortedBy { it.name }.forEach { res ->
            Card(Modifier.fillMaxWidth()) {
                Text(formatResultRow(res), modifier = Modifier.padding(16.dp))
            }
        }
    }
}

private suspend fun runBench(
    ctx: Context,
    input: String,
    target: String,
    useAdvanced: Boolean,
    runs: Int
): BenchResult = withContext(Dispatchers.Default) {
    val name = if (useAdvanced) "Advanced" else "Standard"
    val backend = if (useAdvanced) {
        NllbOnnxTranslationBackend(ctx, ModelLocator(ctx))
    } else {
        MLKitTranslationBackend()
    }
    val engine = TranslatorEngine(backend)
    val durations = ArrayList<Long>(runs)
    var output: String? = null
    var error: String? = null

    try {
        runCatching { engine.ensureModel(target, requireWifi = false) }
        repeat(runs) {
            val ms = measureTimeMillis {
                output = engine.translate(input, source = "auto", target = target)
            }
            durations += ms
        }
    } catch (t: Throwable) {
        error = t.message ?: t::class.java.simpleName
    }

    val result = BenchResult(name, durations, output, error)
    val avg = result.average()
    LogBus.log(
        "Bench",
        "${result.name} runs=${durations.size} avg=${avg?.toString() ?: "n/a"} error=${error ?: "none"}",
        LogLine.Kind.ENGINE
    )
    result
}

data class BenchResult(
    val name: String,
    val durations: List<Long>,
    val sample: String?,
    val error: String?
) {
    fun average(): Long? = if (durations.isEmpty()) null else durations.average().roundToLong()
    fun best(): Long? = durations.minOrNull()
    fun worst(): Long? = durations.maxOrNull()
}

@VisibleForTesting
internal fun formatResultRow(result: BenchResult): String {
    val stats = when {
        result.error != null -> "error: ${result.error}"
        result.durations.isEmpty() -> "no runs"
        else -> {
            val avg = result.average()
            val best = result.best()
            val worst = result.worst()
            "avg=${avg}ms (runs=${result.durations.size}, best=${best}ms, worst=${worst}ms)"
        }
    }
    val sample = result.error ?: (result.sample?.take(160) ?: "")
    return buildString {
        append(result.name)
        append(": ")
        append(stats)
        if (sample.isNotBlank()) {
            append('\n')
            append(sample)
        }
    }
}
