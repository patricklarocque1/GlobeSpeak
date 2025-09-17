package com.globespeak.mobile.data

import android.content.Context
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.tasks.await
import com.globespeak.mobile.logging.LogBus
import com.globespeak.mobile.logging.LogLine

class NodeRepository(private val context: Context) {
    fun connectedNodes(pollMs: Long = 4_000L): Flow<List<Node>> = flow {
        val client = Wearable.getNodeClient(context)
        while (true) {
            try {
                val nodes = client.connectedNodes.await()
                emit(nodes)
            } catch (e: ApiException) {
                if (e.statusCode == ConnectionResult.API_UNAVAILABLE) {
                    // Device doesn't support Wearable API (e.g., emulator without companion). Degrade gracefully.
                    LogBus.log("NodeRepo", "Wearable API unavailable; showing no nodes", LogLine.Kind.DATALAYER)
                    emit(emptyList())
                } else {
                    LogBus.log("NodeRepo", "Wearable error: ${e.statusCode}", LogLine.Kind.DATALAYER)
                    emit(emptyList())
                }
            } catch (t: Throwable) {
                LogBus.log("NodeRepo", "Error listing nodes: ${t.message}", LogLine.Kind.DATALAYER)
                emit(emptyList())
            }
            delay(pollMs)
        }
    }.flowOn(Dispatchers.IO)
}
