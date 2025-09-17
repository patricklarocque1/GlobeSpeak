package com.globespeak.engine.whisper

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.nio.LongBuffer

internal object WhisperTensorUtils {
    @Throws(OrtException::class)
    fun int64Tensor(env: OrtEnvironment, data: IntArray, shape: LongArray): OnnxTensor {
        val longData = LongArray(data.size) { idx -> data[idx].toLong() }
        return OnnxTensor.createTensor(env, LongBuffer.wrap(longData), shape)
    }

    @Throws(OrtException::class)
    fun int32Tensor(env: OrtEnvironment, data: IntArray, shape: LongArray): OnnxTensor {
        return OnnxTensor.createTensor(env, IntBuffer.wrap(data), shape)
    }

    @Throws(OrtException::class)
    fun floatTensor(env: OrtEnvironment, data: FloatArray, shape: LongArray): OnnxTensor {
        return OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape)
    }

    @Throws(OrtException::class)
    fun zeroFloatTensor(env: OrtEnvironment, shape: LongArray): OnnxTensor {
        val total = shape.fold(1L) { acc, dim -> acc * dim } // will be zero if any dim is zero
        val buffer = if (total == 0L) {
            ByteBuffer.allocateDirect(0).asFloatBuffer()
        } else {
            FloatBuffer.wrap(FloatArray(total.toInt()))
        }
        return OnnxTensor.createTensor(env, buffer, shape)
    }
}
