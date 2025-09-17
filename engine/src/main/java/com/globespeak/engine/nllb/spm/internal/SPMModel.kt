/*
 * Minimal SentencePiece model reader (decode only)
 *
 * This implementation parses the binary .model (Protocol Buffer) to extract:
 * - Ordered list of pieces (strings)
 * - Optional BOS/EOS/UNK ids if present; otherwise inferred by special tokens
 *
 * It is a lightweight, dependency-free reader sufficient for on-device inference
 * and adheres to Apache-2.0 as original SentencePiece is Apache-2.0.
 */
package com.globespeak.engine.nllb.spm.internal

import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class SPMModel(
    val pieces: List<Piece>,
    val bosId: Int? = null,
    val eosId: Int? = null,
    val unkId: Int? = null
) {
    data class Piece(val text: String, val score: Float, val type: Type) {
        enum class Type { NORMAL, UNKNOWN, CONTROL, USER_DEFINED, BYTE, UNUSED }
    }

    fun indexOf(piece: String): Int? = pieces.indexOfFirst { it.text == piece }.takeIf { it >= 0 }

    companion object {
        fun load(file: File): SPMModel {
            FileInputStream(file).use { return parse(it) }
        }

        private fun parse(inp: InputStream): SPMModel {
            val data = inp.readBytes()
            val reader = ProtoReader(data)
            val pieces = ArrayList<Piece>(32_000)
            var bos: Int? = null
            var eos: Int? = null
            var unk: Int? = null

            while (reader.hasRemaining()) {
                val tag = reader.readTag() ?: break
                when (tag.fieldNumber) {
                    1 -> { // repeated SentencePiece
                        val slice = reader.readBytes()
                        val piece = parsePiece(ProtoReader(slice))
                        val id = pieces.size
                        pieces += piece
                        when (piece.type) {
                            Piece.Type.UNKNOWN -> if (unk == null) unk = id
                            Piece.Type.CONTROL -> {
                                if (piece.text == "<s>" && bos == null) bos = id
                                if (piece.text == "</s>" && eos == null) eos = id
                            }
                            else -> {}
                        }
                    }
                    2 -> { // TrainerSpec
                        val slice = reader.readBytes()
                        val spec = ProtoReader(slice)
                        while (spec.hasRemaining()) {
                            val inner = spec.readTag() ?: break
                            when (inner.fieldNumber) {
                                40 -> unk = spec.readVarint().toInt()
                                41 -> bos = spec.readVarint().toInt()
                                42 -> eos = spec.readVarint().toInt()
                                else -> spec.skipUnknown(inner)
                            }
                        }
                    }
                    else -> reader.skipUnknown(tag)
                }
            }

            if (pieces.isEmpty()) {
                throw IllegalStateException("No pieces found in tokenizer.model; unsupported format?")
            }

            if (unk == null) {
                val idx = pieces.indexOfFirst { it.text == "<unk>" }
                unk = if (idx >= 0) idx else 0
            }
            if (bos == null) bos = pieces.indexOfFirst { it.text == "<s>" }.takeIf { it >= 0 }
            if (eos == null) eos = pieces.indexOfFirst { it.text == "</s>" }.takeIf { it >= 0 }

            return SPMModel(pieces, bos, eos, unk)
        }

        private fun parsePiece(reader: ProtoReader): Piece {
            var text: String? = null
            var score = 0f
            var type = Piece.Type.NORMAL
            while (reader.hasRemaining()) {
                val tag = reader.readTag() ?: break
                when (tag.fieldNumber) {
                    1 -> text = reader.readString()
                    2 -> score = reader.readFloat()
                    3 -> {
                        val id = reader.readVarint().toInt()
                        type = when (id) {
                            2 -> Piece.Type.UNKNOWN
                            3 -> Piece.Type.CONTROL
                            4 -> Piece.Type.USER_DEFINED
                            5 -> Piece.Type.UNUSED
                            6 -> Piece.Type.BYTE
                            else -> Piece.Type.NORMAL
                        }
                    }
                    else -> reader.skipUnknown(tag)
                }
            }
            if (text == null) throw IllegalStateException("SentencePiece missing piece text")
            return Piece(text, score, type)
        }
    }
}

// ---- Minimal protobuf reader ----

private enum class WireType { VARINT, I64, LEN_DELIM, I32 }

private data class Tag(val fieldNumber: Int, val wireType: WireType)

private class ProtoReader(bytes: ByteArray) {
    private val buf: ByteBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

    fun hasRemaining(): Boolean = buf.hasRemaining()

    fun readTag(): Tag? {
        if (!hasRemaining()) return null
        val key = readVarint()
        val field = (key ushr 3).toInt()
        val wt = when ((key and 0x7).toInt()) {
            0 -> WireType.VARINT
            1 -> WireType.I64
            2 -> WireType.LEN_DELIM
            5 -> WireType.I32
            else -> WireType.LEN_DELIM // best effort
        }
        return Tag(field, wt)
    }

    fun readVarint(): Long {
        var shift = 0
        var result = 0L
        while (true) {
            val b = buf.get().toInt()
            result = result or (((b and 0x7F).toLong()) shl shift)
            if ((b and 0x80) == 0) break
            shift += 7
        }
        return result
    }

    fun skipVarint() { readVarint() }
    fun skip64() { buf.position(buf.position() + 8) }
    fun skip32() { buf.position(buf.position() + 4) }

    fun readBytes(): ByteArray {
        val len = readVarint().toInt()
        val out = ByteArray(len)
        buf.get(out)
        return out
    }

    fun readString(): String = String(readBytes(), Charsets.UTF_8)
    fun readFloat(): Float = Float.fromBits(buf.int)

    fun skipUnknown(tag: Tag) {
        when (tag.wireType) {
            WireType.VARINT -> skipVarint()
            WireType.I64 -> skip64()
            WireType.LEN_DELIM -> { readBytes() /* discard */ }
            WireType.I32 -> skip32()
        }
    }
}
