package com.globespeak.engine.proto

/**
 * Shared protocol data classes used by both phone and watch.
 */
data class AudioChunk(
    val seq: Int,
    val tsEpochMs: Long,
    val bytes: ByteArray
)

data class EngineState(
    val connected: Boolean,
    val lastHeartbeatAt: Long,
    val lastError: String? = null,
    val modelsReady: Boolean? = null
)

data class TranslationPacket(
    val type: String, // "partial" | "final"
    val text: String,
    val seq: Int
)

