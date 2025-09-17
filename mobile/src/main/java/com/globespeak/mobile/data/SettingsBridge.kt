package com.globespeak.mobile.data

import android.content.Context
import com.globespeak.shared.Bridge
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.NodeClient
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.tasks.await
import com.globespeak.mobile.logging.LogBus
import com.globespeak.mobile.logging.LogLine

class SettingsBridge(private val app: Context, private val targetLangFlow: Flow<String>) {
  private val messages: MessageClient by lazy { Wearable.getMessageClient(app) }
  private val nodes: NodeClient by lazy { Wearable.getNodeClient(app) }

  suspend fun start() {
    // Push current value to all connected nodes on each change
    targetLangFlow.collectLatest { lang ->
      val connected = runCatching { nodes.connectedNodes.await() }.getOrDefault(emptyList())
      connected.forEach { node ->
        runCatching {
          messages.sendMessage(node.id, Bridge.PATH_SETTINGS_TARGET_LANG, lang.toByteArray()).await()
          LogBus.log("SettingsBridge", "Settings sent to watch: $lang", LogLine.Kind.DATALAYER)
        }
      }
    }
  }

  suspend fun replyIfRequested(requestFromNodeId: String?) {
    val lang = try {
      // Best-effort latest: we don't have replay here; you can feed a StateFlow for immediate value.
      // Fallback to default if not available.
      Bridge.DEFAULT_TARGET_LANG
    } catch (_: Throwable) { Bridge.DEFAULT_TARGET_LANG }

    val targets = if (requestFromNodeId != null) listOf(requestFromNodeId) else {
      runCatching { nodes.connectedNodes.await().map { it.id } }.getOrDefault(emptyList())
    }
    targets.forEach { id ->
      runCatching {
        messages.sendMessage(id, Bridge.PATH_SETTINGS_TARGET_LANG, lang.toByteArray()).await()
        LogBus.log("SettingsBridge", "Settings replied to $id: $lang", LogLine.Kind.DATALAYER)
      }
    }
  }
}

