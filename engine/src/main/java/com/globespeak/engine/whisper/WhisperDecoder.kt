package com.globespeak.engine.whisper

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.extensions.OrtxPackage
import java.nio.FloatBuffer
import kotlin.math.ln

internal class WhisperDecoder(
    private val locator: WhisperModelLocator,
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
) {
    private val initSession: OrtSession
    private val encoderSession: OrtSession
    private val cacheInitSession: OrtSession
    private val decoderSession: OrtSession
    private val detokenizerSession: OrtSession

    init {
        require(locator.hasModel()) {
            "Whisper model files are missing. Expected: ${locator.requiredFiles().joinToString { it.name }}"
        }
        initSession = env.createSession(locator.initializer().absolutePath, newSessionOptions())
        encoderSession = env.createSession(locator.encoder().absolutePath, newSessionOptions())
        cacheInitSession = env.createSession(locator.cacheInitializer().absolutePath, newSessionOptions())
        decoderSession = env.createSession(locator.decoder().absolutePath, newSessionOptions())
        detokenizerSession = env.createSession(locator.detokenizer().absolutePath, newSessionOptions())
    }

    fun close() {
        detokenizerSession.close()
        decoderSession.close()
        cacheInitSession.close()
        encoderSession.close()
        initSession.close()
    }

    fun transcribe(audio: FloatArray, language: String): String {
        if (audio.isEmpty()) return ""
        val audioTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(audio), longArrayOf(1L, audio.size.toLong()))
        return audioTensor.use {
            initSession.run(mapOf("audio_pcm" to audioTensor)).use { initResult ->
                val features = (initResult.iterator().next().value as OnnxTensor)
                encoderSession.run(mapOf("input_features" to features)).use { encoderResult ->
                    val encoderHidden = (encoderResult.iterator().next().value as OnnxTensor)
                    cacheInitSession.run(mapOf("encoder_hidden_states" to encoderHidden)).use { cacheResult ->
                        val tokens = decode(cacheResult, language)
                        detokenize(tokens)
                    }
                }
            }
        }
    }

    private fun decode(cacheResult: OrtSession.Result, language: String): IntArray {
        val tokens = ArrayList<Int>()
        val languageToken = WhisperLanguages.tokenFor(language)

        val encoderKeys = Array(NUM_LAYERS) { layer ->
            cacheResult.get("present.$layer.encoder.key").get() as OnnxTensor
        }
        val encoderValues = Array(NUM_LAYERS) { layer ->
            cacheResult.get("present.$layer.encoder.value").get() as OnnxTensor
        }

        var decoderResult: OrtSession.Result? = null
        val prompt = intArrayOf(START_TOKEN_ID, languageToken, TRANSCRIBE_TOKEN_ID, NO_TIMESTAMPS_TOKEN_ID)
        val decoderKeyZero = createEmptyDecoderState()
        val decoderValueZero = createEmptyDecoderState()

        try {
            var step = 0
            var lastToken = prompt.last()
            while (tokens.size < MAX_TOKENS) {
                val token = if (step < prompt.size) prompt[step] else lastToken
                var endLoop = false
                WhisperTensorUtils.int64Tensor(env, intArrayOf(token), longArrayOf(1, 1)).use { inputIds ->
                    val inputs = HashMap<String, OnnxTensor>()
                    inputs["input_ids"] = inputIds
                    for (layer in 0 until NUM_LAYERS) {
                        inputs["past_key_values.$layer.encoder.key"] = encoderKeys[layer]
                        inputs["past_key_values.$layer.encoder.value"] = encoderValues[layer]
                        val decoderKey = if (decoderResult == null) {
                            decoderKeyZero[layer]
                        } else {
                            decoderResult!!.get("present.$layer.decoder.key").get() as OnnxTensor
                        }
                        val decoderVal = if (decoderResult == null) {
                            decoderValueZero[layer]
                        } else {
                            decoderResult!!.get("present.$layer.decoder.value").get() as OnnxTensor
                        }
                        inputs["past_key_values.$layer.decoder.key"] = decoderKey
                        inputs["past_key_values.$layer.decoder.value"] = decoderVal
                    }

                    val result = decoderSession.run(inputs)
                    decoderResult?.close()
                    decoderResult = result

                    val logitsTensor = result.get("logits").get() as OnnxTensor
                    val logits = (logitsTensor.value as Array<Array<FloatArray>>)[0][0]
                    val nextToken = argmax(logits)
                    if (nextToken == EOS_TOKEN_ID) {
                        endLoop = true
                    } else {
                        tokens += nextToken
                        lastToken = nextToken
                    }
                }
                step += 1
                if (tokens.size >= MAX_TOKENS || endLoop) {
                    break
                }
            }
        } finally {
            decoderResult?.close()
            decoderKeyZero.forEach { it.close() }
            decoderValueZero.forEach { it.close() }
        }

        return tokens.toIntArray()
    }

    private fun detokenize(tokens: IntArray): String {
        if (tokens.isEmpty()) return ""
        val tensor = WhisperTensorUtils.int32Tensor(env, tokens, longArrayOf(1, 1, tokens.size.toLong()))
        tensor.use {
            detokenizerSession.run(mapOf("sequences" to tensor)).use { result ->
                val raw = ((result.iterator().next().value as Array<Array<String>>)[0][0])
                return cleanText(raw)
            }
        }
    }

    private fun createEmptyDecoderState(): Array<OnnxTensor> {
        val shape = longArrayOf(1, NUM_ATTENTION_HEADS.toLong(), 0, HEAD_SIZE.toLong())
        return Array(NUM_LAYERS) { WhisperTensorUtils.zeroFloatTensor(env, shape) }
    }

    private fun cleanText(text: String): String {
        var result = text
        if (result.isBlank()) return ""
        result = result.replace(Regex("<\\|[^>]*\\|>"), " ")
        result = result.replace("...", " ")
        result = result.replace(Regex("\\s+"), " ").trim()
        if (result.isEmpty()) return result
        val first = result.first()
        if (first.isLowerCase()) {
            result = first.uppercaseChar() + result.substring(1)
        }
        return result
    }

    private fun argmax(values: FloatArray): Int {
        var bestIdx = 0
        var bestValue = Float.NEGATIVE_INFINITY
        for (i in values.indices) {
            val v = values[i]
            if (v > bestValue) {
                bestValue = v
                bestIdx = i
            }
        }
        return bestIdx
    }

    private fun newSessionOptions(): OrtSession.SessionOptions = OrtSession.SessionOptions().apply {
        registerCustomOpLibrary(OrtxPackage.getLibraryPath())
        setCPUArenaAllocator(false)
        setMemoryPatternOptimization(false)
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.NO_OPT)
    }

    companion object {
        private const val START_TOKEN_ID = 50258
        private const val TRANSCRIBE_TOKEN_ID = 50359
        private const val NO_TIMESTAMPS_TOKEN_ID = 50363
        private const val EOS_TOKEN_ID = 50257
        private const val NUM_LAYERS = 12
        private const val NUM_ATTENTION_HEADS = 12
        private const val HEAD_SIZE = 64
        private const val MAX_TOKENS = 448
    }
}

private inline fun <T : AutoCloseable, R> T.use(block: (T) -> R): R {
    var closed = false
    try {
        return block(this)
    } finally {
        if (!closed) {
            try {
                this.close()
            } catch (_: Exception) {
            }
        }
    }
}
