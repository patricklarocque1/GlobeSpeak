package com.globespeak.mobile.data

import android.content.Context
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.tasks.await

class NodeRepository(private val context: Context) {
    fun connectedNodes(pollMs: Long = 4_000L): Flow<List<Node>> = flow {
        val client = Wearable.getNodeClient(context)
        while (true) {
            val nodes = client.connectedNodes.await()
            emit(nodes)
            delay(pollMs)
        }
    }.flowOn(Dispatchers.IO)
}

