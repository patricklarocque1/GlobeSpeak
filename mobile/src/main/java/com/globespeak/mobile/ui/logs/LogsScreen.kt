package com.globespeak.mobile.ui.logs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.globespeak.mobile.logging.LogBus
import com.globespeak.mobile.logging.LogLine

@Composable
fun LogsScreen() {
    var filter by remember { mutableStateOf(LogLine.Kind.ALL) }
    val logs by LogBus.events.collectAsState(initial = emptyList())
    val filtered = logs.filter { filter == LogLine.Kind.ALL || it.kind == filter }

    Column(Modifier.fillMaxSize()) {
        // Simple filter chips row
        androidx.compose.foundation.layout.Row(modifier = androidx.compose.ui.Modifier.padding(8.dp)) {
            listOf(LogLine.Kind.ALL, LogLine.Kind.DATALAYER, LogLine.Kind.ENGINE).forEach { kind ->
                FilterChip(
                    selected = filter == kind,
                    onClick = { filter = kind },
                    label = { Text(kind.name) },
                    modifier = Modifier
                )
            }
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp)) {
            items(filtered) { line ->
                Text("${line.time} ${line.tag}: ${line.msg}")
            }
        }
    }
}
