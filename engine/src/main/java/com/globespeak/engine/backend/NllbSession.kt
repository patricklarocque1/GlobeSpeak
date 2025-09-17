package com.globespeak.engine.backend

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class NllbSession(private val env: OrtEnvironment, private val session: OrtSession) {
    private val inputName: String = session.inputNames.firstOrNull { it.contains("input", true) } ?: "input_ids"
    private val outputName: String = session.outputNames.firstOrNull { it.contains("logits", true) } ?: session.outputNames.first()

    private val pastInputNames: List<String>
    private val presentOutputNames: List<String>

    init {
        val ins = session.inputNames.toList()
        val outs = session.outputNames.toList()
        pastInputNames = ins.filter { it.contains("past_key_values") || it.contains("cache", true) || it.contains("past", true) }
        presentOutputNames = outs.filter { it.contains("present") || it.contains("past_key_values") }
    }

    fun supportsIncremental(): Boolean = pastInputNames.isNotEmpty() && presentOutputNames.isNotEmpty()

    suspend fun greedyDecode(
        inputIds: IntArray,
        maxNewTokens: Int = 128,
        eosId: Int
    ): IntArray = withContext(Dispatchers.Default) {
        if (supportsIncremental()) {
            decodeIncremental(inputIds, maxNewTokens, eosId)
        } else {
            decodeFullSequence(inputIds, maxNewTokens, eosId)
        }
    }

    private fun decodeFullSequence(input: IntArray, maxNewTokens: Int, eosId: Int): IntArray {
        val seq = input.toMutableList()
        repeat(maxNewTokens) {
            val logits = forwardFull(seq.toIntArray())
            val next = argmaxLast(logits)
            if (next == eosId) return seq.toIntArray()
            seq.add(next)
        }
        return seq.toIntArray()
    }

    private fun forwardFull(ids: IntArray): Array<FloatArray> {
        val longs = ids.map { it.toLong() }.toLongArray()
        val data = arrayOf(longs)
        OnnxTensor.createTensor(env, data).use { input ->
            val results = session.run(mapOf(inputName to input))
            results.use { res ->
                val logits = (res[0].value as Array<Array<FloatArray>>)[0] // [1, seq, vocab]
                return logits
            }
        }
    }

    private fun decodeIncremental(input: IntArray, maxNewTokens: Int, eosId: Int): IntArray {
        val seq = input.toMutableList()
        // Step 1: Feed full prompt to warm the cache
        var cache: Map<String, Any> = emptyMap()
        run {
            val longs = input.map { it.toLong() }.toLongArray()
            val data = arrayOf(longs)
            OnnxTensor.createTensor(env, data).use { idsTensor ->
                val feeds = HashMap<String, OnnxTensor>()
                feeds[inputName] = idsTensor
                val res = session.run(feeds)
                res.use { out ->
                    val logits = (out[0].value as Array<Array<FloatArray>>)[0]
                    val next = argmaxLast(logits)
                    if (next == eosId) return seq.toIntArray()
                    seq.add(next)
                    cache = extractPresentToCache(out)
                }
            }
        }
        // Subsequent steps: last token + cache
        repeat(maxNewTokens - 1) {
            val last = seq.last().toLong()
            val data = arrayOf(longArrayOf(last))
            OnnxTensor.createTensor(env, data).use { idsTensor ->
                val feedTensors = HashMap<String, OnnxTensor>()
                feedTensors[inputName] = idsTensor
                val additional = ArrayList<OnnxTensor>()
                try {
                    // Attach past key/values
                    for ((k, v) in cache) {
                        val t = OnnxTensor.createTensor(env, v)
                        additional += t
                        feedTensors[k] = t
                    }
                    val res = session.run(feedTensors)
                    res.use { out ->
                        val logits = (out[0].value as Array<Array<FloatArray>>)[0]
                        val next = argmaxLast(logits)
                        if (next == eosId) return seq.toIntArray()
                        seq.add(next)
                        cache = extractPresentToCache(out)
                    }
                } finally {
                    // idsTensor is closed by use; close additional tensors
                    additional.forEach { runCatching { it.close() } }
                }
            }
        }
        return seq.toIntArray()
    }

    private fun extractPresentToCache(res: OrtSession.Result): Map<String, Any> {
        val map = HashMap<String, Any>()
        // Map output present names to input past names by simple name normalization
        val outputs = session.outputNames.toList()
        val inputs = pastInputNames
        val normOutToIndex = outputs.withIndex().associate { (i, n) -> normalizePresentName(n) to i }
        for (inName in inputs) {
            val normalized = normalizePastNameToPresent(inName)
            val idx = normOutToIndex[normalized]
            if (idx != null) {
                val value = res[idx].value
                map[inName] = value
            }
        }
        return map
    }

    private fun normalizePresentName(n: String): String = n
        .replace(Regex("present\\."), "kv.")
        .replace(Regex("past_key_values\\."), "kv.")
        .replace(Regex("key$"), "k")
        .replace(Regex("value$"), "v")

    private fun normalizePastNameToPresent(n: String): String = n
        .replace(Regex("past_key_values\\."), "kv.")
        .replace(Regex("key$"), "k")
        .replace(Regex("value$"), "v")

    private fun argmaxLast(logits: Array<FloatArray>): Int {
        val last = logits.last()
        var maxIdx = 0
        var maxVal = Float.NEGATIVE_INFINITY
        for (i in last.indices) {
            val v = last[i]
            if (v > maxVal) { maxVal = v; maxIdx = i }
        }
        return maxIdx
    }
}
