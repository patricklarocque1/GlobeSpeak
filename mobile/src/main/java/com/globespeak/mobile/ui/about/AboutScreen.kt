package com.globespeak.mobile.ui.about

import android.content.res.Resources
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.globespeak.R

@Composable
fun AboutScreen() {
    val context = LocalContext.current
    val text = runCatching { readRawText(context.resources, R.raw.notice) }.getOrDefault("NOTICE missing")

    Scaffold { padding ->
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        )
    }
}

private fun readRawText(res: Resources, id: Int): String {
    res.openRawResource(id).use { input ->
        return input.bufferedReader().readText()
    }
}
