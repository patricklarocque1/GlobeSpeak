package com.globespeak.engine.nllb.spm

import android.content.Context
import com.globespeak.engine.backend.ModelLocator
import com.globespeak.engine.nllb.spm.internal.SPMModel
import java.io.File

/**
 * SentencePiece loader/encoder/decoder for NLLB models.
 *
 * Public API remains: load(context[, file]), encode(text), decode(ids).
 * Internals use a minimal vendored SPM reader and greedy longest-match segmentation.
 */
open class SentencePiece(
    private val pieces: List<SPMModel.Piece>,
    private val bosId: Int,
    private val eosId: Int,
    private val unkId: Int
) {
    private val _tokenToId: Map<String, Int> = pieces.withIndex().associate { it.value.text to it.index }
    private val idToPiece: Array<SPMModel.Piece?> = Array(pieces.size) { pieces[it] }
    private val trie = PrefixTrie.from(pieces)
    private val unkSafeId: Int = if (unkId >= 0 && unkId < pieces.size) unkId else 0

    fun encode(text: String): IntArray {
        val norm = TextNormalizer.normalize(text)
        val prepared = prepareInput(norm)
        val out = ArrayList<Int>(prepared.length + 4)
        if (bosId >= 0) out += bosId
        if (prepared.isNotEmpty()) {
            val scores = DoubleArray(prepared.length + 1) { Double.NEGATIVE_INFINITY }
            val backPtr = IntArray(prepared.length + 1) { -1 }
            val backPiece = IntArray(prepared.length + 1) { -1 }
            scores[0] = 0.0
            for (i in 0 until prepared.length) {
                if (scores[i] == Double.NEGATIVE_INFINITY) continue
                var matched = false
                trie.collect(prepared, i) { len, id ->
                    matched = true
                    val pieceScore = scores[i] + pieces[id].score
                    val nextIdx = i + len
                    if (pieceScore > scores[nextIdx]) {
                        scores[nextIdx] = pieceScore
                        backPtr[nextIdx] = i
                        backPiece[nextIdx] = id
                    }
                }
                if (!matched) {
                    val next = i + 1
                    val pieceScore = scores[i] + pieces[unkSafeId].score
                    if (pieceScore > scores[next]) {
                        scores[next] = pieceScore
                        backPtr[next] = i
                        backPiece[next] = unkSafeId
                    }
                }
            }

            var idx = prepared.length
            if (scores[idx] == Double.NEGATIVE_INFINITY) {
                // total fallback: treat as all UNK tokens
                repeat(prepared.length) { out += unkSafeId }
            } else {
                val collected = ArrayList<Int>()
                while (idx > 0) {
                    val pieceId = backPiece[idx]
                    val prev = backPtr[idx]
                    if (pieceId < 0 || prev < 0) break
                    collected += pieceId
                    idx = prev
                }
                collected.reverse()
                out.addAll(collected)
            }
        }
        if (eosId >= 0) out += eosId
        return out.toIntArray()
    }

    fun decode(ids: IntArray): String {
        if (ids.isEmpty()) return ""
        val sb = StringBuilder()
        for (id in ids) {
            if (id == eosId || id == bosId) continue
            val piece = idToPiece.getOrNull(id)?.text ?: continue
            if (piece == "<unk>") continue
            val type = idToPiece[id]?.type
            if (type == SPMModel.Piece.Type.CONTROL || type == SPMModel.Piece.Type.UNUSED) continue
            if (piece.startsWith('▁')) {
                if (sb.isNotEmpty()) sb.append(' ')
                sb.append(piece.substring(1))
            } else {
                sb.append(piece)
            }
        }
        return TextNormalizer.postprocessDecoded(sb.toString())
    }

    /**
     * Expose mapping to allow prompt builders to place language tag token.
     */
    fun tokenToId(token: String): Int? = _tokenToId[token]

    fun bosId(): Int = bosId
    fun eosId(): Int = eosId
    fun unkId(): Int = unkId

    companion object {
        fun load(context: Context, file: File = ModelLocator(context).tokenizerFile()): SentencePiece {
            val model = SPMModel.load(file)
            val bosId = model.bosId ?: (model.indexOf("<s>") ?: -1)
            val eosId = model.eosId ?: (model.indexOf("</s>") ?: -1)
            val unkId = model.unkId ?: (model.indexOf("<unk>") ?: 0)
            return SentencePiece(model.pieces, bosId, eosId, unkId)
        }
    }
}

// Lightweight static trie for greedy longest-match segmentation
private class PrefixTrie private constructor(private val root: Node) {
    data class Node(
        val children: MutableMap<Char, Node> = HashMap(),
        var id: Int? = null
    )

    fun collect(s: String, start: Int, fn: (len: Int, id: Int) -> Unit) {
        var node = root
        var i = start
        while (i < s.length) {
            val next = node.children[s[i]] ?: break
            node = next
            if (node.id != null) {
                fn(i - start + 1, node.id!!)
            }
            i++
        }
    }

    companion object {
        fun from(pieces: List<SPMModel.Piece>): PrefixTrie {
            val root = Node()
            pieces.withIndex().forEach { (id, piece) ->
                var node = root
                for (ch in piece.text) {
                    node = node.children.getOrPut(ch) { Node() }
                }
                if (node.id == null) node.id = id
            }
            return PrefixTrie(root)
        }
    }
}

private fun prepareInput(norm: String): String {
    if (norm.isEmpty()) return ""
    val sb = StringBuilder(norm.length + 4)
    sb.append('▁')
    for (ch in norm) {
        if (ch == ' ') sb.append('▁') else sb.append(ch)
    }
    return sb.toString()
}
