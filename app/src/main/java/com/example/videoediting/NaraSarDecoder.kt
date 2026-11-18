package com.example.videoediting

import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.LinkedHashMap
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max

/** Kotlin port of Nara SAR's published CTC prefix beam search, character LM and Burmese gate. */
internal class NaraSarDecoder(modelDirectory: File) : NaraSarEngine.SarDecoder, Closeable {
    private val metadata = JSONObject(File(modelDirectory, "sar.json").readText(Charsets.UTF_8))
    private val idToChar = HashMap<Int, String>().apply {
        val mapping = metadata.getJSONObject("id2char")
        mapping.keys().forEach { key -> put(key.toInt(), mapping.getString(key)) }
    }
    private val languageModel = NaraLanguageModel(File(modelDirectory, "language.lm"))
    private val constraint = BurmeseSyllableGate()

    override fun decode(logits: Array<FloatArray>, modelDirectory: File): String {
        if (logits.isEmpty()) return ""
        data class Beam(var blank: Double, var nonBlank: Double, var lmScore: Double, var lmState: List<Int>, var syllables: Set<BurmeseSyllableGate.State>)
        var beams: MutableMap<String, Beam> = hashMapOf("" to Beam(0.0, Double.NEGATIVE_INFINITY, 0.0, languageModel.beginning, constraint.start()))
        for (row in logits) {
            if (row.size != VOCAB_SIZE || row.any { !it.isFinite() }) throw IllegalArgumentException("Invalid Nara SAR acoustic output row")
            val candidateIds = row.indices.sortedWith(compareByDescending<Int> { row[it] }.thenBy { it }).take(TOP_K)
            val shift = row[candidateIds.first()].toDouble()
            val following = HashMap<String, Beam>()
            fun obtain(prefix: String, from: Beam): Beam = following.getOrPut(prefix) {
                Beam(Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, from.lmScore, from.lmState, from.syllables)
            }
            for ((prefix, beam) in beams) {
                val total = logAdd(beam.blank, beam.nonBlank)
                val last = prefix.lastOrNull()
                for (token in candidateIds) {
                    val logProbability = row[token].toDouble() - shift
                    if (token == BLANK_ID) {
                        val item = obtain(prefix, beam)
                        item.blank = logAdd(item.blank, total + logProbability)
                        continue
                    }
                    val text = idToChar[token] ?: continue
                    if (text.length != 1) continue
                    val ch = text[0]
                    val nextSyllables = constraint.step(beam.syllables, ch) ?: continue
                    val (lmDelta, nextLmState) = languageModel.advance(beam.lmState, ch)
                    val nextPrefix = prefix + ch
                    val probability = if (ch == last) {
                        val samePrefix = obtain(prefix, beam)
                        samePrefix.nonBlank = logAdd(samePrefix.nonBlank, beam.nonBlank + logProbability)
                        beam.blank + logProbability
                    } else total + logProbability
                    val item = obtain(nextPrefix, beam)
                    item.nonBlank = logAdd(item.nonBlank, probability)
                    item.lmScore = beam.lmScore + lmDelta
                    item.lmState = nextLmState
                    item.syllables = nextSyllables
                }
            }
            if (following.isEmpty()) throw IllegalStateException("Nara SAR decoder has no valid Burmese beam")
            beams = following.entries.sortedWith(
                compareByDescending<Map.Entry<String, Beam>> { logAdd(it.value.blank, it.value.nonBlank) + ALPHA * it.value.lmScore }
                    .thenBy { it.key }
            ).take(BEAM_SIZE).associate { it.key to it.value }.toMutableMap()
        }
        val legal = beams.filterValues { constraint.canFinish(it.syllables) }.ifEmpty { beams }
        return legal.maxWithOrNull(compareBy<Map.Entry<String, Beam>> { logAdd(it.value.blank, it.value.nonBlank) + ALPHA * it.value.lmScore }.thenBy { it.key })?.key.orEmpty()
    }

    override fun close() = languageModel.close()

    private class NaraLanguageModel(file: File) : Closeable {
        private val channel: FileChannel
        private val mapped: ByteBuffer
        private val dataStart: Int
        private val keyWidth: Int
        private val blockOffsets = IntArray(6)
        private val blockCounts = IntArray(6)
        private val vocabulary = HashMap<String, Int>()
        private val unknown: Int
        val beginning: List<Int>
        private val lookupCache = object : LinkedHashMap<String, Pair<Float, Float>?>(4096, .75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Float, Float>?>?) = size > 8192
        }
        private val advanceCache = object : LinkedHashMap<String, Pair<Float, List<Int>>>(8192, .75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Float, List<Int>>?>?) = size > 32768
        }

        init {
            RandomAccessFile(file, "r").use { raf ->
                val prefix = ByteArray(12)
                raf.readFully(prefix)
                if (String(prefix, 0, 8, Charsets.US_ASCII) != "NARALM01") throw IllegalArgumentException("Invalid Nara SAR language model header")
                val headerLength = ByteBuffer.wrap(prefix, 8, 4).order(ByteOrder.LITTLE_ENDIAN).int
                if (headerLength <= 0 || headerLength > 16 * 1024 * 1024) throw IllegalArgumentException("Invalid Nara SAR language model metadata")
                val headerBytes = ByteArray(headerLength)
                raf.readFully(headerBytes)
                val header = JSONObject(String(headerBytes, Charsets.UTF_8))
                dataStart = 12 + headerLength
                keyWidth = header.getInt("keyWidth")
                if (keyWidth !in 1..4) throw IllegalArgumentException("Unsupported Nara SAR language model key width")
                val vocab = header.getJSONArray("vocab")
                for (i in 0 until vocab.length()) vocabulary[vocab.getString(i)] = i
                unknown = vocabulary["<unk>"] ?: throw IllegalArgumentException("Nara SAR language model lacks <unk>")
                val bos = vocabulary["<s>"] ?: throw IllegalArgumentException("Nara SAR language model lacks <s>")
                beginning = listOf(bos)
                val blocks = header.getJSONArray("blocks")
                if (blocks.length() != blockOffsets.size) throw IllegalArgumentException("Unexpected Nara SAR language model order")
                for (i in blockOffsets.indices) {
                    val block = blocks.getJSONObject(i)
                    blockOffsets[i] = block.getLong("offset").toInt()
                    blockCounts[i] = block.getInt("count")
                }
            }
            channel = FileInputStreamCompat.openChannel(file)
            mapped = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size()).order(ByteOrder.LITTLE_ENDIAN)
        }

        fun advance(state: List<Int>, character: Char): Pair<Float, List<Int>> {
            val cacheKey = state.joinToString(",") + ":" + character
            synchronized(advanceCache) { advanceCache[cacheKey]?.let { return it } }
            val word = if (character == ' ') "▁" else character.toString()
            val token = vocabulary[word] ?: unknown
            var history = state
            var backoff = 0f
            var score: Float
            while (true) {
                val found = lookup(history + token)
                if (found != null) { score = backoff + found.first; break }
                if (history.isEmpty()) throw IllegalStateException("Missing SAR language-model unigram")
                lookup(history)?.let { backoff += it.second }
                history = history.drop(1)
            }
            val next = (state + token).takeLast(5)
            val result = score to next
            synchronized(advanceCache) { advanceCache[cacheKey] = result }
            return result
        }

        private fun lookup(ids: List<Int>): Pair<Float, Float>? {
            if (ids.isEmpty() || ids.size > blockOffsets.size) return null
            val cacheKey = ids.joinToString(",")
            synchronized(lookupCache) { if (lookupCache.containsKey(cacheKey)) return lookupCache[cacheKey] }
            val order = ids.size - 1
            val key = ByteArray(ids.size * keyWidth)
            var keyAt = 0
            for (id in ids) for (shift in (keyWidth - 1) downTo 0) key[keyAt++] = (id ushr (shift * 8)).toByte()
            val recordSize = key.size + 8
            val start = dataStart + blockOffsets[order]
            var low = 0
            var high = blockCounts[order]
            var answer: Pair<Float, Float>? = null
            while (low < high) {
                val middle = (low + high) ushr 1
                val at = start + middle * recordSize
                var cmp = 0
                for (i in key.indices) {
                    val a = mapped.get(at + i).toInt() and 0xff
                    val b = key[i].toInt() and 0xff
                    if (a != b) { cmp = a.compareTo(b); break }
                }
                if (cmp < 0) low = middle + 1
                else if (cmp > 0) high = middle
                else {
                    answer = mapped.getFloat(at + key.size) to mapped.getFloat(at + key.size + 4)
                    break
                }
            }
            synchronized(lookupCache) { lookupCache[cacheKey] = answer }
            return answer
        }

        override fun close() {
            synchronized(lookupCache) { lookupCache.clear() }
            synchronized(advanceCache) { advanceCache.clear() }
            channel.close()
        }
    }

    private class BurmeseSyllableGate {
        data class State(val syllable: String, val wordInitial: Boolean)
        fun start(): Set<State> = setOf(State("", true))

        fun step(states: Set<State>, ch: Char): Set<State>? {
            if (!isPoliced(ch)) {
                if (states.none { it.syllable.isEmpty() || done(it.syllable, it.wordInitial) }) return null
                return setOf(State("", ch in WORD_RESET))
            }
            val next = LinkedHashSet<State>()
            for (state in states) {
                val grown = state.syllable + ch
                if (feasible(grown)) next.add(State(grown, state.wordInitial))
                val nextWordInitial = if (state.syllable.isEmpty()) state.wordInitial else false
                if ((state.syllable.isEmpty() || done(state.syllable, state.wordInitial)) && feasible(ch.toString())) next.add(State(ch.toString(), nextWordInitial))
            }
            return next.takeIf { it.isNotEmpty() }
        }

        fun canFinish(states: Set<State>): Boolean = states.any { it.syllable.isEmpty() || done(it.syllable, it.wordInitial) }
        private fun feasible(value: String): Boolean = value.length <= MAX_SYL && marksOk(value) && asatOk(value) && value.firstOrNull()?.let(::isOnset) == true
        private fun done(value: String, wordInitial: Boolean): Boolean {
            if (value.isEmpty() || value.length > MAX_SYL || !isOnset(value.first()) || value.last() == VIRAMA || !marksOk(value) || !asatOk(value)) return false
            if (wordInitial && hasNoNucleus(value)) return false
            return true
        }

        private fun hasNoNucleus(value: String): Boolean {
            var i = 1
            while (i < value.length && value[i] in MEDIALS) i++
            if (i >= value.length || value[i] != ASAT) return false
            i++
            if (i < value.length && value[i] in TONES) i++
            return i == value.length
        }

        private fun marksOk(value: String): Boolean {
            for (i in 1 until value.length) if (value[i] == value[i - 1] && value[i] in MARKS) return false
            if (hasRepeated(value, "ုူ") || hasRepeated(value, "ိီ") || hasRepeated(value, "ာါ") || hasRepeated(value, "ေဲ")) return false
            if (value.count { it == ASAT } > 2) return false
            if (value.indices.any { i -> value[i] == ASAT && i + 1 < value.length && isOnset(value[i + 1]) }) return false
            val medials = value.filter { it in MEDIALS }
            var previous = -1
            for (ch in medials) {
                val order = MEDIALS.indexOf(ch)
                if (order <= previous) return false
                previous = order
            }
            return true
        }

        private fun hasRepeated(value: String, chars: String): Boolean {
            for (i in 1 until value.length) if (value[i] in chars && value[i - 1] in chars) return true
            return false
        }

        private fun asatOk(value: String): Boolean {
            for (i in value.indices) if (value[i] == ASAT) {
                var end = i
                if (end > 0 && value[end - 1] in TONES) end--
                val base = value.substring(0, end)
                val valid = (base.length == 1 && base[0] in "ာါ") ||
                    (base.isNotEmpty() && isOnset(base[0]) && base.drop(1).all { it in MEDIALS })
                if (!valid) return false
            }
            return true
        }

        private fun isOnset(ch: Char): Boolean = ch in CONS || ch in INDEP || ch in SYMBOLS || ch in EXTRA_CONS
        private fun isPoliced(ch: Char): Boolean = isOnset(ch) || ch in '\u102B'..'\u103F'

        companion object {
            private const val MAX_SYL = 10
            private const val VIRAMA = '\u1039'
            private const val ASAT = '\u103A'
            private val CONS = '\u1000'..'\u1021'
            private val INDEP = '\u1023'..'\u102A'
            private const val SYMBOLS = "၌၍၎၏"
            private const val EXTRA_CONS = "ဿၐၑၒၓၔၕၖၗၘၙ"
            private const val MEDIALS = "ျြွှ"
            private const val TONES = "့း"
            private const val MARKS = "ါာိီုူေဲံ့းျြွှ်္"
            private val WORD_RESET = setOf(' ', '\t', '\n', '\r', '\u00a0', '\u200b', '။', '၊')
        }
    }

    companion object {
        private const val VOCAB_SIZE = 9_865
        private const val TOP_K = 10
        private const val BEAM_SIZE = 16
        private const val BLANK_ID = 0
        private const val ALPHA = .6
        private fun logAdd(a: Double, b: Double): Double {
            if (a == Double.NEGATIVE_INFINITY) return b
            if (b == Double.NEGATIVE_INFINITY) return a
            val high = max(a, b)
            return high + ln(exp(a - high) + exp(b - high))
        }
    }
}

private object FileInputStreamCompat {
    fun openChannel(file: File): FileChannel = RandomAccessFile(file, "r").channel
}
