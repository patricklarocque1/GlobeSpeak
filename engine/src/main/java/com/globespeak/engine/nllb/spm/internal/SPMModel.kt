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
    val pieces: List<String>,
    val bosId: Int? = null,
    val eosId: Int? = null,
    val unkId: Int? = null
) {
    fun indexOf(piece: String): Int? = pieces.indexOf(piece).let { if (it >= 0) it else null }

    companion object {
        fun load(file: File): SPMModel {
            FileInputStream(file).use { return parse(it) }
        }

        private fun parse(inp: InputStream): SPMModel {
            val data = inp.readBytes()
            val pieces = ArrayList<String>(32000)
            var bos: Int? = null
            var eos: Int? = null
            var unk: Int? = null

            // Walk all length-delimited submessages and collect any message that looks like SentencePiece
            fun parseMessage(buf: ProtoReader) {
                while (buf.hasRemaining()) {
                    val tag = buf.readTag() ?: break
                    when (tag.wireType) {
                        WireType.VARINT -> buf.skipVarint()
                        WireType.I64 -> buf.skip64()
                        WireType.LEN_DELIM -> {
                            val slice = buf.readBytes()
                            // Heuristic: try to parse a SentencePiece submessage
                            val maybe = ProtoReader(slice)
                            var piece: String? = null
                            var typeId: Int? = null // 0=normal,1=unk,2=control,3=user_defined,4=bos,5=eos
                            while (maybe.hasRemaining()) {
                                val inner = maybe.readTag() ?: break
                                when (inner.fieldNumber) {
                                    1 -> if (inner.wireType == WireType.LEN_DELIM) piece = maybe.readString()
                                    2 -> if (inner.wireType == WireType.I32) maybe.skip32() else maybe.skipUnknown(inner)
                                    3 -> if (inner.wireType == WireType.VARINT) typeId = maybe.readVarint().toInt() else maybe.skipUnknown(inner)
                                    else -> maybe.skipUnknown(inner)
                                }
                            }
                            if (piece != null) {
                                val id = pieces.size
                                pieces += piece
                                when (typeId) {
                                    1 -> if (unk == null) unk = id
                                    4 -> if (bos == null) bos = id
                                    5 -> if (eos == null) eos = id
                                }
                            } else {
                                // Not a piece; still recurse into nested structures to find pieces
                                parseMessage(ProtoReader(slice))
                            }
                        }
                        WireType.I32 -> buf.skip32()
                    }
                }
            }

            parseMessage(ProtoReader(data))

            if (pieces.isEmpty()) {
                throw IllegalStateException("No pieces found in tokenizer.model; unsupported format?")
            }

            // Fallback inference for special ids if types were not annotated
            if (unk == null) unk = pieces.indexOf("<unk>").takeIf { it >= 0 } ?: 0
            if (bos == null) bos = pieces.indexOf("<s>").takeIf { it >= 0 }
            if (eos == null) eos = pieces.indexOf("</s>").takeIf { it >= 0 }

            return SPMModel(pieces, bos, eos, unk)
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

    fun skipUnknown(tag: Tag) {
        when (tag.wireType) {
            WireType.VARINT -> skipVarint()
            WireType.I64 -> skip64()
            WireType.LEN_DELIM -> { readBytes() /* discard */ }
            WireType.I32 -> skip32()
        }
    }
}
