package com.example.videoediting

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.sqrt

internal class NaraSarTranscriber(private val context: Context) {
    companion object {
        private const val MODEL_URL = "https://huggingface.co/yelin201875/nara-sar/resolve/main/onnx/acoustic.onnx?download=true"
        private const val METADATA_URL = "https://huggingface.co/yelin201875/nara-sar/resolve/main/sar.json?download=true"
        private const val SAMPLE_RATE = 16_000
        // Generate a short subtitle cue every three seconds so captions change frequently
        // and remain readable on the vertical video canvas.
        private const val CHUNK_SAMPLES = 48_000
        private const val MAX_AUDIO_BYTES = 256L * 1024L * 1024L
    }

    private val modelDir = File(context.filesDir, "nara-sar")
    private val modelFile = File(modelDir, "acoustic.onnx")
    private val metadataFile = File(modelDir, "sar.json")

    fun isModelInstalled(): Boolean = modelFile.isFile && modelFile.length() > 100_000_000L && metadataFile.isFile

    suspend fun downloadModel(onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        modelDir.mkdirs()
        download(METADATA_URL, metadataFile, onProgress = {})
        download(MODEL_URL, modelFile, onProgress)
        validateMetadata()
    }

    suspend fun transcribe(uri: Uri): List<SubtitleCue> = withContext(Dispatchers.Default) {
        check(isModelInstalled()) { "Download the Burmese subtitle engine first." }
        val metadata = JSONObject(metadataFile.readText(Charsets.UTF_8))
        require(metadata.optString("checkpoint") == "run3-step_6000") { "This is not a compatible Nara SAR model." }
        val idToChar = buildMap<Int, String> {
            val json = metadata.getJSONObject("id2char")
            json.keys().forEach { key -> put(key.toInt(), json.getString(key)) }
        }
        val samples = decodeAudio(uri)
        require(samples.isNotEmpty()) { "The added audio is empty or could not be decoded." }
        val env = OrtEnvironment.getEnvironment()
        val options = OrtSession.SessionOptions().apply { setIntraOpNumThreads(2); setInterOpNumThreads(1) }
        val session = env.createSession(modelFile.absolutePath, options)
        try {
            val cues = ArrayList<SubtitleCue>()
            var start = 0
            while (start < samples.size) {
                val end = minOf(start + CHUNK_SAMPLES, samples.size)
                val chunk = normalize(samples.copyOfRange(start, end))
                if (chunk.any { kotlin.math.abs(it) > 1e-5f }) {
                    val input = OnnxTensor.createTensor(env, FloatBuffer.wrap(chunk), longArrayOf(1, chunk.size.toLong()))
                    try {
                        val result = session.run(mapOf("audio" to input))
                        try {
                            val output = result[0] as OnnxTensor
                            val shape = output.info.shape
                            require(shape.size == 3) { "Nara SAR returned an unexpected output shape." }
                            val frames = shape[1].toInt()
                            val vocab = shape[2].toInt()
                            val values = output.floatBuffer
                            val text = greedyDecode(values, frames, vocab, idToChar).trim()
                            if (text.isNotBlank()) {
                                val cueStart = start * 1000L / SAMPLE_RATE
                                val cueEnd = end * 1000L / SAMPLE_RATE
                                cues += SubtitleCue(cueStart, cueEnd, text)
                            }
                        } finally {
                            result.close()
                        }
                    } finally {
                        input.close()
                    }
                }
                start = end
            }
            require(cues.isNotEmpty()) { "No speech was detected in the added audio." }
            cues
        } finally {
            session.close()
        }
    }

    private fun greedyDecode(values: java.nio.FloatBuffer, frames: Int, vocab: Int, idToChar: Map<Int, String>): String {
        val text = StringBuilder()
        var previous = 0
        for (frame in 0 until frames) {
            var bestId = 0
            var bestValue = Float.NEGATIVE_INFINITY
            val row = frame * vocab
            for (token in 0 until vocab) {
                val value = values.get(row + token)
                if (value > bestValue) { bestValue = value; bestId = token }
            }
            if (bestId != 0 && bestId != previous) idToChar[bestId]?.let(text::append)
            previous = bestId
        }
        return text.toString().replace(Regex("[ \\t]+"), " ").trim()
    }

    private fun normalize(input: FloatArray): FloatArray {
        var mean = 0.0
        input.forEach { mean += it }
        mean /= input.size.coerceAtLeast(1)
        var energy = 0.0
        val centered = FloatArray(input.size)
        input.forEachIndexed { index, value -> centered[index] = (value - mean).toFloat(); energy += centered[index] * centered[index] }
        val scale = sqrt(energy / input.size.coerceAtLeast(1) + 1e-5)
        centered.indices.forEach { centered[it] = (centered[it] / scale).toFloat() }
        return centered
    }

    private fun decodeAudio(uri: Uri): FloatArray {
        val extractor = MediaExtractor()
        extractor.setDataSource(context, uri, null)
        var track = -1
        for (index in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(index)
            if (format.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { track = index; break }
        }
        require(track >= 0) { "The selected file has no audio track." }
        extractor.selectTrack(track)
        val inputFormat = extractor.getTrackFormat(track)
        val sourceRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: error("Unsupported audio format")
        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(inputFormat, null, null, 0)
        codec.start()
        val decoded = ArrayList<Float>()
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        var totalBytes = 0L
        try {
            while (!outputDone) {
                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val buffer = codec.getInputBuffer(inputIndex)!!
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) { codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                        else {
                            totalBytes += size
                            require(totalBytes <= MAX_AUDIO_BYTES) { "Audio exceeds the 256 MiB limit." }
                            codec.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outputIndex = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    outputIndex >= 0 -> {
                        val output = codec.getOutputBuffer(outputIndex)
                        if (output != null && info.size > 0) {
                            output.position(info.offset); output.limit(info.offset + info.size)
                            val pcm = output.slice().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                            while (pcm.hasRemaining()) {
                                var sum = 0f
                                repeat(channels) { if (pcm.hasRemaining()) sum += pcm.get() / 32768f }
                                decoded += sum / channels.coerceAtLeast(1)
                            }
                        }
                        codec.releaseOutputBuffer(outputIndex, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true
                    }
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                }
            }
        } finally {
            codec.stop(); codec.release(); extractor.release()
        }
        if (sourceRate == SAMPLE_RATE) return decoded.toFloatArray()
        val outputLength = (decoded.size.toLong() * SAMPLE_RATE / sourceRate).toInt().coerceAtLeast(1)
        return FloatArray(outputLength) { index ->
            val position = index.toDouble() * sourceRate / SAMPLE_RATE
            val low = position.toInt().coerceIn(0, decoded.lastIndex)
            val high = (low + 1).coerceAtMost(decoded.lastIndex)
            val fraction = (position - low).toFloat()
            decoded[low] * (1f - fraction) + decoded[high] * fraction
        }
    }

    private fun validateMetadata() {
        val json = JSONObject(metadataFile.readText(Charsets.UTF_8))
        require(json.optInt("sampleRate") == SAMPLE_RATE && json.optInt("blankId") == 0 && json.optInt("vocabSize") == 9865) {
            "Downloaded files do not match the Nara SAR release."
        }
    }

    private fun download(url: String, target: File, onProgress: (Float) -> Unit) {
        val temp = File(target.parentFile, "${target.name}.download")
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000; readTimeout = 120_000; instanceFollowRedirects = true
        }
        try {
            connection.connect()
            require(connection.responseCode in 200..299) { "Download failed (${connection.responseCode})." }
            val length = connection.contentLengthLong
            BufferedInputStream(connection.inputStream).use { input ->
                FileOutputStream(temp).use { output ->
                    val buffer = ByteArray(1024 * 1024)
                    var readTotal = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read); readTotal += read
                        if (length > 0) onProgress((readTotal.toFloat() / length).coerceIn(0f, 1f))
                    }
                }
            }
            require(temp.length() > 0L) { "Downloaded file was empty." }
            if (target.exists()) target.delete()
            check(temp.renameTo(target)) { "Could not store the downloaded model." }
        } finally {
            connection.disconnect(); temp.delete()
        }
    }
}
