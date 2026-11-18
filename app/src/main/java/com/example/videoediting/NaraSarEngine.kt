package com.example.videoediting

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.security.MessageDigest
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Local Nara SAR inference support. Call ensureModel before transcribeVideo; both are blocking and
 * should be invoked from a worker thread. No model binary is packaged in the APK.
 *
 * ONNX Runtime Android must be present at runtime (for example com.microsoft.onnxruntime:onnxruntime-android).
 * Reflection is intentional so this source remains compilable without an ORT compile-time dependency.
 */
internal object NaraSarEngine {
    private const val REPO = "https://huggingface.co/yelin201875/nara-sar/resolve/main/"
    private const val SAMPLE_RATE = 16_000
    private const val CHUNK_SECONDS = 9
    private const val VOCAB_SIZE = 9_865

    data class Segment(val startSeconds: Double, val endSeconds: Double, val text: String)
    data class DownloadProgress(val file: String, val downloadedBytes: Long, val totalBytes: Long, val fraction: Float)
    data class AudioChunk(val startSeconds: Double, val samples: FloatArray)

    fun interface ProgressListener { fun onProgress(progress: DownloadProgress) }
    fun interface Cancellation { fun isCancelled(): Boolean }

    /**
     * Adapter boundary for the repository's CTC prefix beam search + KenLM scoring and its
     * Myanmar syllable constraints. Must return token-decoded text for one [frames,9865] logits.
     * The engine does not substitute invented output when no decoder is provided.
     */
    fun interface SarDecoder {
        fun decode(logits: Array<FloatArray>, modelDirectory: File): String
    }

    class EngineException(message: String, cause: Throwable? = null) : Exception(message, cause)

    private data class Asset(val relativePath: String, val sha256: String, val optional: Boolean = false)
    private val assets = listOf(
        Asset("onnx/acoustic.onnx", "f16c6e4bc3274699004bef99af18bb4be08add6ec7d8b0a036fa48210cdab058"),
        Asset("language.lm", "a3d35159009285b32973acbfea40ebc1dd03df71032b83119217f255d4ce3558"),
        Asset("sar.json", "2ccd3f72f3db378ec2a2749d99ed8e28e532a356d8ed0ab634c2962f75852571"),
        Asset("tokenizer.model", "41db3cb37ff5e55bd371b373bb3bdb3cf3daf676b3800afd4d086b611e1a0fef", optional = true),
        Asset("LICENSE", "cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30"),
        Asset("LICENSES/Meta-omniASR-LICENSE.txt", "a70a523bafbb595c2844104feb313d204904dac91c3d186c05f22a10a71c7a94"),
        Asset("LICENSES/fairseq2-MIT.txt", "da6d3703ed11cbe42bd212c725957c98da23cbff1998c05fa4b3d976d1a58e93"),
        Asset("NOTICE", "5608e30fea7207e29fdf6f5b8c9d071f74593fe8410af85df19af67597a12fb6"),
        Asset("ATTRIBUTION.md", "044693340f47e635504058a5a38244f5ef0dc0cbb05a400216776228767c11e0"),
        Asset("THIRD_PARTY.md", "03b47558b4fbe6ab539dcf77b3c9173efd07e8e37b303b788f38535d5c378246")
    )

    fun modelDirectory(context: Context): File = File(context.filesDir, "nara-sar")

    /** Downloads the compact INT8 acoustic model and LM after installation; verifies published SHA-256. */
    @Throws(EngineException::class)
    fun ensureModel(
        context: Context,
        progress: ProgressListener? = null,
        cancellation: Cancellation = Cancellation { false }
    ): File {
        val root = modelDirectory(context)
        try {
            if (!root.exists() && !root.mkdirs()) throw EngineException("Cannot create private model directory.")
            for (asset in assets) {
                checkCancelled(cancellation)
                val target = File(root, asset.relativePath)
                if (target.isFile && sha256(target) == asset.sha256) continue
                target.parentFile?.let { if (!it.exists() && !it.mkdirs()) throw EngineException("Cannot create model folder.") }
                val temp = File(target.parentFile, target.name + ".part")
                try {
                    download(REPO + asset.relativePath.split("/").joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") } + "?download=true", temp, asset.relativePath, progress, cancellation)
                    if (sha256(temp) != asset.sha256) throw EngineException("Integrity check failed for ${asset.relativePath}; the downloaded file was discarded.")
                    if (target.exists() && !target.delete()) throw EngineException("Cannot replace invalid ${asset.relativePath}.")
                    if (!temp.renameTo(target)) throw EngineException("Cannot finalize ${asset.relativePath}.")
                } catch (e: Exception) {
                    temp.delete()
                    if (asset.optional) continue
                    if (e is EngineException) throw e
                    throw EngineException("Could not download ${asset.relativePath}: ${e.localizedMessage ?: "network error"}", e)
                }
            }
            File(root, ".ready").writeText("Nara SAR INT8 assets verified", Charsets.UTF_8)
            return root
        } catch (e: EngineException) { throw e }
        catch (e: Exception) { throw EngineException("Nara SAR model setup failed: ${e.localizedMessage ?: "unknown error"}", e) }
    }

    /**
     * Decode an imported video's first audio track to normalized float mono at 16 kHz, infer in
     * bounded ~9-second windows and return chunk-level (not word-level) segments.
     */
    @Throws(EngineException::class)
    fun transcribeVideo(
        context: Context,
        videoUri: Uri,
        decoder: SarDecoder,
        cancellation: Cancellation = Cancellation { false },
        progress: ((completedChunks: Int, totalChunks: Int) -> Unit)? = null
    ): List<Segment> {
        val root = modelDirectory(context)
        val acoustic = File(root, "onnx/acoustic.onnx")
        if (!acoustic.isFile || !File(root, "language.lm").isFile || !File(root, "sar.json").isFile) {
            throw EngineException("Nara SAR is not installed yet. Download the model before transcription.")
        }
        val chunks = decodeAudioChunks(context, videoUri, cancellation)
        if (chunks.isEmpty()) throw EngineException("The selected video contains no decodable audio.")
        val result = ArrayList<Segment>(chunks.size)
        OnnxRunner(acoustic).use { runner ->
            chunks.forEachIndexed { index, chunk ->
                checkCancelled(cancellation)
                val samples = normalize(chunk.samples)
                if (samples.any { kotlin.math.abs(it) > 1e-5f }) {
                    val logits = runner.infer(samples)
                    val text = decoder.decode(logits, root).trim()
                    if (text.isNotEmpty()) result += Segment(chunk.startSeconds, chunk.startSeconds + chunk.samples.size.toDouble() / SAMPLE_RATE, text)
                }
                progress?.invoke(index + 1, chunks.size)
            }
        }
        return result
    }

    /** Explicit greedy CTC option for integration tests; production Myanmar text needs SarDecoder. */
    fun interface LogitConsumer { fun accept(logits: Array<FloatArray>): String }

    private fun normalize(audio: FloatArray): FloatArray {
        val mean = audio.average().toFloat()
        var squareSum = 0.0
        for (sample in audio) { val centered = sample - mean; squareSum += centered.toDouble() * centered }
        val denominator = kotlin.math.sqrt(squareSum / audio.size + 1e-5).toFloat()
        return FloatArray(audio.size) { (audio[it] - mean) / denominator }
    }

    private class OnnxRunner(model: File) : AutoCloseable {
        private val ort: Class<*>
        private val env: Any
        private val optionsClass: Class<*>
        private val options: Any
        private val session: Any

        init {
          try {
            ort = Class.forName("ai.onnxruntime.OrtEnvironment")
            env = ort.getMethod("getEnvironment").invoke(null)
            optionsClass = Class.forName("ai.onnxruntime.OrtSession\$SessionOptions")
            options = optionsClass.getConstructor().newInstance()
            try { optionsClass.getMethod("setIntraOpNumThreads", Int::class.javaPrimitiveType).invoke(options, max(1, Runtime.getRuntime().availableProcessors() / 2)) } catch (_: Exception) { }
            session = ort.getMethod("createSession", String::class.java, optionsClass).invoke(env, model.absolutePath, options)
          } catch (e: ClassNotFoundException) { throw EngineException("ONNX Runtime Android is missing.", e) }
          catch (e: Exception) { throw EngineException("Cannot load the Nara SAR ONNX model: ${e.cause?.localizedMessage ?: e.localizedMessage}", e) }
        }

        fun infer(audio: FloatArray): Array<FloatArray> {
            try {
                val tensorClass = Class.forName("ai.onnxruntime.OnnxTensor")
                val direct = ByteBuffer.allocateDirect(audio.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
                direct.put(audio); direct.rewind()
                val tensor = tensorClass.getMethod("createTensor", ort, FloatBuffer::class.java, LongArray::class.java)
                    .invoke(null, env, direct, longArrayOf(1L, audio.size.toLong()))
                try {
                    val result = session.javaClass.getMethod("run", Map::class.java).invoke(session, mapOf("audio" to tensor))
                    try {
                        val value = result.javaClass.getMethod("get", Int::class.javaPrimitiveType).invoke(result, 0).javaClass.getMethod("getValue").invoke(result.javaClass.getMethod("get", Int::class.javaPrimitiveType).invoke(result, 0))
                        val batch = value as? Array<*> ?: throw EngineException("Unexpected ONNX output; expected [1, frames, 9865].")
                        val frames = batch.firstOrNull() as? Array<*> ?: throw EngineException("Unexpected ONNX batch output.")
                        return Array(frames.size) { i -> (frames[i] as? FloatArray)?.also { if (it.size != VOCAB_SIZE) throw EngineException("Expected $VOCAB_SIZE output classes, got ${it.size}.") } ?: throw EngineException("Unexpected ONNX logits type.") }
                    } finally { try { result.javaClass.getMethod("close").invoke(result) } catch (_: Exception) { } }
                } finally { try { tensorClass.getMethod("close").invoke(tensor) } catch (_: Exception) { } }
            } catch (e: EngineException) { throw e }
            catch (e: Exception) { throw EngineException("ONNX CPU inference failed: ${e.cause?.localizedMessage ?: e.localizedMessage ?: "runtime error"}", e) }
        }

        override fun close() {
            try { session.javaClass.getMethod("close").invoke(session) } catch (_: Exception) { }
            try { optionsClass.getMethod("close").invoke(options) } catch (_: Exception) { }
        }
    }

    private fun decodeAudioChunks(context: Context, uri: Uri, cancellation: Cancellation): List<AudioChunk> {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd -> extractor.setDataSource(pfd.fileDescriptor) }
                ?: throw EngineException("Cannot open the selected video.")
            var track = -1
            var sourceFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) { track = i; sourceFormat = f; break }
            }
            if (track < 0) throw EngineException("The selected video has no audio track.")
            extractor.selectTrack(track)
            val format = sourceFormat!!
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()
            val inInfo = MediaCodec.BufferInfo()
            val outInfo = MediaCodec.BufferInfo()
            val chunks = ArrayList<AudioChunk>()
            val wantedSamples = SAMPLE_RATE * CHUNK_SECONDS
            var pending = FloatArray(wantedSamples)
            var pendingCount = 0
            var pendingStart = 0L
            var sourceFrames = 0L
            var inputDone = false
            var outputDone = false
            var outRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var outChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var pcmEncoding = 2 // AudioFormat.ENCODING_PCM_16BIT
            while (!outputDone) {
                checkCancelled(cancellation)
                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex)!!
                        val size = extractor.readSampleData(inputBuffer, 0)
                        if (size < 0) { codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                        else {
                            val time = extractor.sampleTime.coerceAtLeast(0)
                            codec.queueInputBuffer(inputIndex, 0, size, time, extractor.sampleFlags)
                            extractor.advance()
                        }
                    }
                }
                val outputIndex = codec.dequeueOutputBuffer(outInfo, 10_000)
                if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val of = codec.outputFormat
                    outRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    outChannels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    if (of.containsKey(MediaFormat.KEY_PCM_ENCODING)) pcmEncoding = of.getInteger(MediaFormat.KEY_PCM_ENCODING)
                } else if (outputIndex >= 0) {
                    val buffer = codec.getOutputBuffer(outputIndex)
                    if (buffer != null && outInfo.size > 0) {
                        buffer.position(outInfo.offset); buffer.limit(outInfo.offset + outInfo.size)
                        val pcm = buffer.slice().order(ByteOrder.LITTLE_ENDIAN)
                        val bytesPerSample = if (pcmEncoding == 4) 4 else 2 // float32 or signed PCM16
                        val frameCount = pcm.remaining() / (bytesPerSample * outChannels)
                        val mono = FloatArray(frameCount)
                        for (frame in 0 until frameCount) {
                            var sum = 0f
                            for (ch in 0 until outChannels) {
                                sum += if (bytesPerSample == 4) pcm.getFloat((frame * outChannels + ch) * 4)
                                else pcm.getShort((frame * outChannels + ch) * 2) / 32768f
                            }
                            mono[frame] = (sum / outChannels).coerceIn(-1f, 1f)
                        }
                        val ptsUs = outInfo.presentationTimeUs.coerceAtLeast(0)
                        if (pendingCount == 0) pendingStart = ptsUs
                        val count = max(1, ceil(frameCount.toDouble() * SAMPLE_RATE / outRate).toInt())
                        for (j in 0 until count) {
                            val srcPosition = j.toDouble() * outRate / SAMPLE_RATE
                            val left = min(floor(srcPosition).toInt(), max(0, mono.lastIndex))
                            val right = min(left + 1, max(0, mono.lastIndex))
                            val fraction = (srcPosition - left).toFloat().coerceIn(0f, 1f)
                            val value = if (mono.isEmpty()) 0f else mono[left] * (1f - fraction) + mono[right] * fraction
                            pending[pendingCount++] = value
                            if (pendingCount == wantedSamples) {
                                chunks += AudioChunk(pendingStart / 1_000_000.0, pending)
                                pending = FloatArray(wantedSamples); pendingCount = 0
                                pendingStart = ptsUs + (j.toLong() * 1_000_000L / SAMPLE_RATE)
                            }
                        }
                        sourceFrames += frameCount
                    }
                    outputDone = outInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    codec.releaseOutputBuffer(outputIndex, false)
                }
            }
            if (pendingCount > 0) chunks += AudioChunk(pendingStart / 1_000_000.0, pending.copyOf(pendingCount))
            if (sourceFrames == 0L) throw EngineException("The video's audio track decoded no samples.")
            return chunks
        } catch (e: EngineException) { throw e }
        catch (e: Exception) { throw EngineException("Could not decode video audio: ${e.localizedMessage ?: "unsupported audio format"}", e) }
        finally {
            try { codec?.stop() } catch (_: Exception) { }
            try { codec?.release() } catch (_: Exception) { }
            try { extractor.release() } catch (_: Exception) { }
        }
    }

    private fun download(url: String, destination: File, file: String, listener: ProgressListener?, cancellation: Cancellation) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000; readTimeout = 60_000; instanceFollowRedirects = true
            setRequestProperty("User-Agent", "VideoEditing-NaraSAR/1.0")
        }
        try {
            connection.connect()
            val status = connection.responseCode
            if (status !in 200..299) throw EngineException("Model server returned HTTP $status for $file.")
            val total = connection.contentLengthLong
            BufferedInputStream(connection.inputStream).use { input ->
                FileOutputStream(destination).use { output ->
                    val bytes = ByteArray(128 * 1024); var read: Int; var done = 0L
                    while (input.read(bytes).also { read = it } >= 0) {
                        checkCancelled(cancellation)
                        if (read == 0) continue
                        output.write(bytes, 0, read); done += read
                        listener?.onProgress(DownloadProgress(file, done, total, if (total > 0) (done.toDouble() / total).toFloat().coerceIn(0f, 1f) else -1f))
                    }
                    output.fd.sync()
                }
            }
        } finally { connection.disconnect() }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input -> val bytes = ByteArray(128 * 1024); var n: Int
            while (input.read(bytes).also { n = it } >= 0) if (n > 0) digest.update(bytes, 0, n)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun checkCancelled(cancellation: Cancellation) {
        if (cancellation.isCancelled()) throw EngineException("Nara SAR operation cancelled.")
    }
}
