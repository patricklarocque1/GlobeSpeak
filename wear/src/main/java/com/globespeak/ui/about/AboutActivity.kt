package com.globespeak.ui.about

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.globespeak.R

class AboutActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme { AboutContent() }
        }
    }
}

@Composable
private fun AboutContent() {
    val ctx = LocalContext.current
    val text = runCatching {
        ctx.resources.openRawResource(R.raw.notice).bufferedReader().use { it.readText() }
    }.getOrDefault("NOTICE missing")
    Text(
        text = text,
        style = MaterialTheme.typography.body2,
        modifier = Modifier
            .padding(12.dp)
            .verticalScroll(rememberScrollState())
    )
}
