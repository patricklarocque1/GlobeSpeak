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
    private val vocab: List<String>,
    private val bosId: Int,
    private val eosId: Int,
    private val unkId: Int
) {
    private val _tokenToId: Map<String, Int> = vocab.withIndex().associate { it.value to it.index }
    private val idToToken: Map<Int, String> = _tokenToId.entries.associate { it.value to it.key }
    private val trie = PrefixTrie.from(vocab)

    fun encode(text: String): IntArray {
        val norm = TextNormalizer.normalize(text)
        val ids = ArrayList<Int>(norm.length + 4)
        if (bosId >= 0) ids += bosId
        val words = if (norm.isEmpty()) emptyList() else norm.split(' ')
        for (word in words) {
            var token = if (word.isEmpty()) "▁" else "▁$word"
            var pos = 0
            while (pos < token.length) {
                val (len, id) = trie.longestMatch(token, pos)
                if (id != null && len > 0) {
                    ids += id
                    pos += len
                } else {
                    // Fallback: emit UNK and advance 1 char to avoid infinite loop
                    ids += unkId
                    pos += 1
                }
            }
        }
        if (eosId >= 0) ids += eosId
        return ids.toIntArray()
    }

    fun decode(ids: IntArray): String {
        if (ids.isEmpty()) return ""
        val sb = StringBuilder()
        for (id in ids) {
            if (id == eosId || id == bosId) continue
            val piece = idToToken[id] ?: continue
            if (piece == "<unk>") continue
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
            // Infer special IDs by piece text if types not available
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

    fun longestMatch(s: String, start: Int): Pair<Int, Int?> {
        var node = root
        var bestLen = -1
        var bestId: Int? = null
        var i = start
        while (i < s.length) {
            val next = node.children[s[i]] ?: break
            node = next
            if (node.id != null) { bestLen = i - start + 1; bestId = node.id }
            i++
        }
        return if (bestLen > 0) bestLen to bestId else 0 to null
    }

    companion object {
        fun from(vocab: List<String>): PrefixTrie {
            val root = Node()
            vocab.withIndex().forEach { (id, piece) ->
                var node = root
                for (ch in piece) {
                    node = node.children.getOrPut(ch) { Node() }
                }
                if (node.id == null) node.id = id
            }
            return PrefixTrie(root)
        }
    }
}
