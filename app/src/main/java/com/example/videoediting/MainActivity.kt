@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.example.videoediting

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Movie
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.TextureView
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.media3.common.MediaItem
import androidx.media3.common.AudioAttributes
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.Crop
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.OverlaySettings
import androidx.media3.effect.Presentation
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.AudioEncoderSettings
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Background = Color(0xFF10131B)
private val Panel = Color(0xFF1A1E29)
private val Accent = Color(0xFF9C7BFF)
private const val OUTPUT_WIDTH = 720
private const val OUTPUT_HEIGHT = 1280
private const val OUTPUT_VIDEO_BITRATE = 1_200_000
private const val OUTPUT_AUDIO_BITRATE = 96_000
private data class BlurRect(val x: Float = .22f, val y: Float = .70f, val w: Float = .56f, val h: Float = .11f)
private data class LogoPosition(val x: Float = .86f, val y: Float = .92f)
private data class CropRect(val x: Float = .1f, val y: Float = .1f, val w: Float = .8f, val h: Float = .8f)
private data class SubtitleCue(val startMs: Long, val endMs: Long, val text: String)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = Background, surface = Panel)) { EditorScreen() } }
    }
}

@Composable
private fun EditorScreen() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("video_editing", Context.MODE_PRIVATE) }
    var savedLogo by remember { mutableStateOf(readValidLogo(prefs)) }
    var logoCropBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var logoCropUri by remember { mutableStateOf<Uri?>(null) }
    var logoCropIsGif by remember { mutableStateOf(false) }
    var videoUri by remember { mutableStateOf<Uri?>(null) }
    var audioUri by remember { mutableStateOf<Uri?>(null) }
    var mirror by remember { mutableStateOf(false) }
    var zoom by remember { mutableFloatStateOf(1.08f) }
    var warmEnabled by remember { mutableStateOf(true) }
    var warmAmount by remember { mutableFloatStateOf(.30f) }
    var muteOriginal by remember { mutableStateOf(true) }
    var videoAudioVolume by remember { mutableFloatStateOf(.8f) }
    var replacementAudioEnabled by remember { mutableStateOf(true) }
    var replacementAudioVolume by remember { mutableFloatStateOf(.9f) }
    var blurEnabled by remember { mutableStateOf(true) }
    var blurRect by remember { mutableStateOf(BlurRect()) }
    var blurStrength by remember { mutableFloatStateOf(.92f) }
    var logoPosition by remember { mutableStateOf(LogoPosition(prefs.getFloat("logo_x", .86f), prefs.getFloat("logo_y", .92f))) }
    var logoScale by remember { mutableFloatStateOf(prefs.getFloat("logo_scale", 1f).coerceIn(.4f, 2.2f)) }
    var borderColorHex by remember { mutableStateOf(prefs.getString("border_color", "#9C7BFF") ?: "#9C7BFF") }
    var scrollingCaption by remember { mutableStateOf("") }
    var subtitleCues by remember { mutableStateOf<List<SubtitleCue>>(emptyList()) }
    var previewPositionMs by remember { mutableLongStateOf(0L) }
    var modelDownloading by remember { mutableStateOf(false) }
    var transcriptionRunning by remember { mutableStateOf(false) }
    var modelReady by remember { mutableStateOf(File(context.filesDir, "nara-sar/.ready").isFile) }
    var modelDownloadProgress by remember { mutableFloatStateOf(0f) }
    var modelStatus by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    var exporting by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var activeTransformer by remember { mutableStateOf<Transformer?>(null) }
    var tempOutput by remember { mutableStateOf<File?>(null) }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) runCatching {
            logoCropIsGif = context.contentResolver.getType(uri)?.contains("gif", ignoreCase = true) == true || uri.lastPathSegment?.substringAfterLast('.', "")?.equals("gif", true) == true
            logoCropBitmap = decodeLogoForCrop(context, uri)
            logoCropUri = uri
        }.onFailure { message = it.message ?: "Couldn't save that image. Choose a PNG, JPG, or GIF." }
    }
    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
                if (uri != null) { videoUri = uri; blurRect = BlurRect(); subtitleCues = emptyList(); previewPositionMs = 0L }
    }
    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        audioUri = uri
        if (uri != null) replacementAudioEnabled = true
    }
    val subtitlePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching { readSrtCues(context, uri) }
            .onSuccess { cues -> subtitleCues = cues; message = "Loaded ${cues.size} timed subtitle cues. They will appear in preview and the exported video." }
            .onFailure { message = it.message ?: "Could not read this SRT subtitle file." }
    }

    fun downloadModel() {
        if (modelReady || modelDownloading) return
        scope.launch {
            modelDownloading = true; modelDownloadProgress = 0f; modelStatus = "Downloading Nara SAR model (~450 MB)…"
            try {
                withContext(Dispatchers.IO) {
                    NaraSarEngine.ensureModel(context, NaraSarEngine.ProgressListener { item ->
                        if (item.totalBytes > 0) modelDownloadProgress = item.fraction
                        modelStatus = "Downloading ${item.file.substringAfterLast('/')} · ${item.downloadedBytes / (1024 * 1024)} MB"
                    })
                }
                modelReady = true; modelStatus = "Myanmar speech model is ready for offline use."
            } catch (error: Exception) { modelStatus = error.message ?: "Model download failed. Check your internet and try again." }
            finally { modelDownloading = false }
        }
    }

    fun transcribeVideoAudio() {
        val source = videoUri
        if (source == null) { modelStatus = "Choose a video first."; return }
        if (!modelReady) { modelStatus = "Download the Myanmar speech model first."; downloadModel(); return }
        if (transcriptionRunning) return
        scope.launch {
            transcriptionRunning = true; modelStatus = "Transcribing the video's audio…"
            try {
                val segments = withContext(Dispatchers.IO) {
                    NaraSarDecoder(NaraSarEngine.modelDirectory(context)).use { decoder ->
                        NaraSarEngine.transcribeVideo(context, source, decoder)
                    }
                }
                subtitleCues = makeTwoSecondCues(segments.map { it.copy(text = keepMyanmarSubtitleText(it.text)) })
                if (subtitleCues.isEmpty()) modelStatus = "No speech was recognized. You can import an SRT subtitle file instead."
                else { previewPositionMs = 0L; modelStatus = "${subtitleCues.size} subtitle cards created. They change every 2 seconds; review transcript and timing before export." }
            } catch (error: Exception) { modelStatus = error.message ?: "Transcription failed." }
            finally { transcriptionRunning = false }
        }
    }

    LaunchedEffect(exporting, activeTransformer) {
        while (isActive && exporting) {
            val current = activeTransformer
            if (current != null) {
                val holder = ProgressHolder()
                if (current.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) progress = holder.progress / 100f
            }
            delay(500)
        }
    }

    Column(Modifier.fillMaxSize().background(Background).padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("VIDEO STUDIO", color = Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                Text("Make it yours.", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold)
            }
            Text("Preview stays visible", color = Color(0xFFA7ACBC), fontSize = 11.sp)
        }
        Spacer(Modifier.height(4.dp))
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1.05f)) {
            val previewWidth = minOf(maxWidth, maxHeight * 9f / 16f)
            Box(Modifier.width(previewWidth).aspectRatio(9f / 16f).align(Alignment.Center).clip(RoundedCornerShape(18.dp)).background(Color(0xFF08090D))) {
                if (videoUri != null) VideoPreview(videoUri!!, audioUri, mirror, zoom, muteOriginal, videoAudioVolume, replacementAudioEnabled, replacementAudioVolume, Modifier.fillMaxSize(), onPosition = { previewPositionMs = it })
                if (videoUri == null) Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Movie, null, tint = Color(0xFF73798C), modifier = Modifier.size(36.dp))
                    Spacer(Modifier.height(8.dp)); Text("Your 9:16 video preview", color = Color(0xFFA7ACBC), fontSize = 12.sp)
                }
                if (warmEnabled && videoUri != null) Box(Modifier.fillMaxSize().background(Color(0xFFFF9F4C).copy(alpha = warmAmount * .48f)))
                if (blurEnabled && videoUri != null) InteractiveBlurBox(blurRect, blurStrength, Modifier.fillMaxSize(), onChange = { blurRect = it })
                savedLogo?.let { file ->
                    val isGif = file.extension.equals("gif", ignoreCase = true)
                    val bitmap = remember(file.absolutePath) { if (isGif) null else decodeLogoPreview(file)?.asImageBitmap() }
                    val animatedDrawable = remember(file.absolutePath) {
                        if (!isGif) null else runCatching {
                            ImageDecoder.decodeDrawable(ImageDecoder.createSource(file)) as? android.graphics.drawable.AnimatedImageDrawable
                        }.getOrNull()
                    }
                    DisposableEffect(animatedDrawable) {
                        animatedDrawable?.apply { repeatCount = android.graphics.drawable.AnimatedImageDrawable.REPEAT_INFINITE; start() }
                        onDispose { animatedDrawable?.stop() }
                    }
                    if (bitmap != null || animatedDrawable != null) DraggableLogo(bitmap, animatedDrawable, logoPosition, logoScale, Modifier.fillMaxSize(), onMove = { next ->
                        logoPosition = next
                        prefs.edit().putFloat("logo_x", next.x).putFloat("logo_y", next.y).apply()
                    }, onScale = { next ->
                        logoScale = next
                        prefs.edit().putFloat("logo_scale", next).apply()
                    })
                }
                if (warmEnabled && videoUri != null) Text("WARM ${ (warmAmount * 100).roundToInt()}%", Modifier.align(Alignment.TopStart).padding(7.dp).clip(RoundedCornerShape(7.dp)).background(Color.Black.copy(alpha = .55f)).padding(horizontal = 7.dp, vertical = 4.dp), color = Color.White, fontSize = 8.sp)
                if (scrollingCaption.isNotBlank() && videoUri != null) ScrollingCaptionPreview(scrollingCaption, Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(34.dp))
                subtitleCues.firstOrNull { previewPositionMs in it.startMs until it.endMs }?.let { cue -> TimedSubtitlePreview(cue.text, Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 38.dp)) }
                Box(Modifier.fillMaxSize().border(3.dp, Color(AndroidColor.parseColor(borderColorHex)), RoundedCornerShape(18.dp)))
            }
        }
        Spacer(Modifier.height(5.dp))
        Button(onClick = { videoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) }, modifier = Modifier.fillMaxWidth().height(42.dp), shape = RoundedCornerShape(13.dp)) {
            Icon(Icons.Default.Movie, null); Spacer(Modifier.width(8.dp)); Text(if (videoUri == null) "Choose a video" else "Change video", fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(6.dp))
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 12.dp)) {
        SectionTitle("VIDEO")
        SettingSwitch("Mirror horizontally", "Flip the frame left-to-right", mirror) { mirror = it }
        Text("Slight zoom · ${"%.2f".format(zoom)}×", color = Color.White, fontWeight = FontWeight.Medium, fontSize = 14.sp)
        Slider(value = zoom, onValueChange = { zoom = it }, valueRange = 1f..1.18f)
        SettingSwitch("Rome-inspired warm look", "Subtle warm tint and contrast", warmEnabled) { warmEnabled = it }
        if (warmEnabled) {
            Text("Filter intensity · ${(warmAmount * 100).roundToInt()}%", color = Color(0xFFBEC2D0), fontSize = 12.sp)
            Slider(value = warmAmount, onValueChange = { warmAmount = it }, valueRange = 0f..1f)
        }
        SectionTitle("VIDEO FRAME")
        Text("Border color", color = Color(0xFFBEC2D0), fontSize = 12.sp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf("#9C7BFF", "#FFFFFF", "#FF5252", "#FFD740", "#40C4FF", "#69F0AE").forEach { hex ->
                Box(Modifier.size(32.dp).clip(RoundedCornerShape(16.dp)).background(Color(AndroidColor.parseColor(hex)))
                    .then(if (hex == borderColorHex) Modifier.border(3.dp, Color.White, RoundedCornerShape(16.dp)) else Modifier)
                    .clickable { borderColorHex = hex; prefs.edit().putString("border_color", hex).apply() })
            }
        }
        SectionTitle("SCROLLING CAPTION")
        OutlinedTextField(value = scrollingCaption, onValueChange = { scrollingCaption = it }, modifier = Modifier.fillMaxWidth(),
            label = { Text("Text to scroll across the video") }, placeholder = { Text("Type a message for the scrolling caption") }, singleLine = true)
        Text("The caption scrolls across the bottom in preview and export.", color = Color(0xFF858BA0), fontSize = 11.sp)
        Spacer(Modifier.height(10.dp))
        SectionTitle("MYANMAR SPEECH SUBTITLES")
        Text("Create Burmese subtitles from the video's audio. The ASR model downloads only after installation (~450 MB), then works offline.", color = Color(0xFFA7ACBC), fontSize = 12.sp)
        Text("Powered by Nara SAR", color = Accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Button(onClick = { downloadModel() }, enabled = !modelReady && !modelDownloading, modifier = Modifier.fillMaxWidth().height(44.dp), shape = RoundedCornerShape(13.dp)) {
            Text(when { modelReady -> "Myanmar speech model downloaded"; modelDownloading -> "Downloading model…"; else -> "Download Myanmar speech model" })
        }
        if (modelDownloading) LinearProgressIndicator(progress = { modelDownloadProgress }, modifier = Modifier.fillMaxWidth().padding(top = 5.dp))
        OutlinedButton(onClick = { transcribeVideoAudio() }, enabled = videoUri != null && modelReady && !transcriptionRunning, modifier = Modifier.fillMaxWidth().height(44.dp), shape = RoundedCornerShape(13.dp)) {
            Text(if (transcriptionRunning) "Transcribing audio…" else "Generate subtitles from video audio")
        }
        OutlinedButton(onClick = { subtitlePicker.launch(arrayOf("application/x-subrip", "text/srt", "text/plain", "*/*")) }, modifier = Modifier.fillMaxWidth().height(42.dp), shape = RoundedCornerShape(13.dp)) { Text("Import SRT subtitles") }
        if (subtitleCues.isNotEmpty()) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("${subtitleCues.size} subtitles · changes every 2 seconds", Modifier.weight(1f), color = Color(0xFFA7ACBC), fontSize = 12.sp)
            TextButton(onClick = { subtitleCues = emptyList(); previewPositionMs = 0L }) { Text("Remove") }
        }
        modelStatus?.let { Text(it, color = if (it.contains("failed", true) || it.contains("error", true)) Color(0xFFFF9C9C) else Color(0xFFBEC2D0), fontSize = 11.sp) }
        Text("Two-second cue timing is estimated from chunk text, not word-level speech timestamps. Review before export.", color = Color(0xFF858BA0), fontSize = 10.sp)
        Spacer(Modifier.height(10.dp))
        SectionTitle("SUBTITLE COVER")
        SettingSwitch("Show adjustable blur box", "Drag anywhere inside; use the corner handle to resize", blurEnabled) { blurEnabled = it }
        Text("Cover strength · ${(blurStrength * 100).roundToInt()}%", color = Color(0xFFBEC2D0), fontSize = 12.sp)
        Slider(value = blurStrength, onValueChange = { blurStrength = it }, valueRange = .15f..1f)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { blurRect = BlurRect() }) { Text("Reset blur position") }
        }
        Spacer(Modifier.height(10.dp))

        SectionTitle("AUDIO")
        SettingSwitch("Play original video audio", "Enable to hear it together with added audio", !muteOriginal) { muteOriginal = !it }
        Text("Video volume · ${(videoAudioVolume * 100).roundToInt()}%", color = Color(0xFFBEC2D0), fontSize = 12.sp)
        Slider(value = videoAudioVolume, onValueChange = { videoAudioVolume = it }, valueRange = 0f..1f)
        OutlinedButton(onClick = { audioPicker.launch(arrayOf("audio/mpeg", "audio/mp4", "audio/wav", "audio/x-wav", "audio/*")) }, modifier = Modifier.fillMaxWidth().height(46.dp), shape = RoundedCornerShape(14.dp)) {
            Text(if (audioUri == null) "Add replacement audio" else "Change replacement audio")
        }
        if (audioUri != null) Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Replacement audio selected", Modifier.weight(1f), color = Color(0xFF9EA5B6), fontSize = 12.sp)
            TextButton(onClick = { audioUri = null }) { Text("Remove") }
        }
        if (audioUri != null) {
            SettingSwitch("Play added audio", "Mix with video sound when both are enabled", replacementAudioEnabled) { replacementAudioEnabled = it }
            Text("Added audio volume · ${(replacementAudioVolume * 100).roundToInt()}%", color = Color(0xFFBEC2D0), fontSize = 12.sp)
            Slider(value = replacementAudioVolume, onValueChange = { replacementAudioVolume = it }, valueRange = 0f..1f)
        }

        Spacer(Modifier.height(14.dp))
        SectionTitle("YOUR WATERMARK")
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Panel).padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(54.dp).clip(RoundedCornerShape(11.dp)).background(Color(0xFF292E3B)), contentAlignment = Alignment.Center) {
                val bmp = remember(savedLogo?.absolutePath) { savedLogo?.let { decodeLogoPreview(it)?.asImageBitmap() } }
                if (bmp != null) androidx.compose.foundation.Image(bmp, null, Modifier.fillMaxSize().padding(5.dp), contentScale = ContentScale.Fit)
                else Icon(Icons.Default.AddPhotoAlternate, null, tint = Color(0xFF858BA0))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(if (savedLogo == null) "No logo selected" else "Custom logo saved", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text("Drag logo on preview · saved on device", color = Color(0xFFA7ACBC), fontSize = 11.sp)
            }
            if (savedLogo != null) IconButton(onClick = { savedLogo?.delete(); prefs.edit().remove("custom_logo_path").apply(); savedLogo = null }) { Icon(Icons.Default.Close, "Remove Logo", tint = Color(0xFFCF9DA6)) }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, modifier = Modifier.fillMaxWidth().height(46.dp), shape = RoundedCornerShape(14.dp)) {
            Icon(Icons.Default.PhotoCamera, null); Spacer(Modifier.width(7.dp)); Text(if (savedLogo == null) "Choose Logo" else "Change Logo")
        }
        Text("PNG/JPG are still images. GIF logos animate and loop throughout the exported video.", color = Color(0xFF858BA0), fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
        if (savedLogo != null) TextButton(onClick = {
            logoPosition = LogoPosition()
            logoScale = 1f
            prefs.edit().putFloat("logo_x", logoPosition.x).putFloat("logo_y", logoPosition.y).putFloat("logo_scale", 1f).apply()
        }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Reset logo position") }
        if (savedLogo != null) {
            Text("Logo size · ${(logoScale * 100).roundToInt()}%", color = Color(0xFFBEC2D0), fontSize = 12.sp)
            Slider(value = logoScale, onValueChange = { logoScale = it; prefs.edit().putFloat("logo_scale", it).apply() }, valueRange = .4f..2.2f)
        }
        if (savedLogo != null) TextButton(onClick = { savedLogo?.delete(); prefs.edit().remove("custom_logo_path").apply(); savedLogo = null }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Remove Logo", color = Color(0xFFCF9DA6)) }

        Spacer(Modifier.height(16.dp))
        Button(
            enabled = videoUri != null && !exporting,
            onClick = {
                val source = videoUri
                if (source == null) { message = "Choose a video first."; return@Button }
                runCatching {
                    val temp = File(context.cacheDir, "export-${UUID.randomUUID()}.mp4")
                    tempOutput = temp
                    val outputEffects = buildList {
                        add(ScaleAndRotateTransformation.Builder().setScale(if (mirror) -zoom else zoom, zoom).build())
                        add(Crop(-1f / zoom, 1f / zoom, -1f / zoom, 1f / zoom))
                        add(Presentation.createForWidthAndHeight(OUTPUT_WIDTH, OUTPUT_HEIGHT, Presentation.LAYOUT_SCALE_TO_FIT))
                        if (warmEnabled && warmAmount > 0f) add(OverlayEffect(listOf(BitmapOverlay.createStaticBitmapOverlay(makeWarmOverlay(warmAmount), fullFrameOverlaySettings()))))
                        if (blurEnabled) add(OverlayEffect(listOf(BitmapOverlay.createStaticBitmapOverlay(makeBlurCoverOverlay(blurRect, blurStrength), fullFrameOverlaySettings()))))
                        savedLogo?.takeIf { it.isFile }?.let { add(OverlayEffect(listOf(createLogoOverlay(it, logoPosition, logoScale)))) }
                        if (scrollingCaption.isNotBlank()) add(OverlayEffect(listOf(ScrollingCaptionOverlay(scrollingCaption))))
                        if (subtitleCues.isNotEmpty()) add(OverlayEffect(listOf(TimedSubtitleOverlay(subtitleCues))))
                        add(OverlayEffect(listOf(BitmapOverlay.createStaticBitmapOverlay(makeBorderOverlay(borderColorHex), fullFrameOverlaySettings()))))
                    }
                    val videoEdited = EditedMediaItem.Builder(MediaItem.fromUri(source))
                        .setRemoveAudio(muteOriginal)
                        .setEffects(Effects(if (muteOriginal) emptyList() else listOf(GainAudioProcessor(videoAudioVolume)), outputEffects)).build()
                    val videoSequence = EditedMediaItemSequence.Builder(videoEdited).build()
                    val composition = if (audioUri != null && replacementAudioEnabled) {
                        val audioEdited = EditedMediaItem.Builder(MediaItem.fromUri(audioUri!!))
                            .setRemoveVideo(true)
                            .setEffects(Effects(listOf(GainAudioProcessor(replacementAudioVolume)), emptyList()))
                            .build()
                        Composition.Builder(videoSequence, EditedMediaItemSequence.Builder(audioEdited).setIsLooping(true).build()).build()
                    } else Composition.Builder(videoSequence).build()
                    progress = 0f
                    exporting = true
                    val compactEncoderFactory = DefaultEncoderFactory.Builder(context)
                        .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(OUTPUT_VIDEO_BITRATE).build())
                        .setRequestedAudioEncoderSettings(AudioEncoderSettings.Builder().setBitrate(OUTPUT_AUDIO_BITRATE).build())
                        .build()
                    val transformer = Transformer.Builder(context).setEncoderFactory(compactEncoderFactory).addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            activeTransformer = null
                            runCatching { saveToGallery(context, temp) }
                                .onSuccess { uri -> temp.delete(); exporting = false; progress = 1f; Toast.makeText(context, "Export saved to Movies/VideoEditing", Toast.LENGTH_LONG).show(); message = "Video exported successfully to your Gallery.\n$uri" }
                                .onFailure { temp.delete(); exporting = false; message = "The render finished, but the MP4 could not be saved to Gallery: ${it.message}" }
                        }
                        override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                            activeTransformer = null; exporting = false; temp.delete(); message = "Export failed: ${exportException.message ?: "unsupported video format or effect"}"
                        }
                    }).build()
                    activeTransformer = transformer
                    transformer.start(composition, temp.absolutePath)
                }.onFailure { exporting = false; activeTransformer = null; message = "Couldn't start export: ${it.message}" }
            },
            modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(17.dp)
        ) {
            if (exporting) { CircularProgressIndicator(progress = { progress }, modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White); Spacer(Modifier.width(10.dp)); Text("Exporting ${(progress * 100).roundToInt()}%") }
            else { Text("Export MP4 to Gallery", fontWeight = FontWeight.Bold) }
        }
        if (exporting) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().padding(top = 9.dp), color = Accent)
        Spacer(Modifier.height(18.dp))
        Text("720p · 720 × 1280 · 1.2 Mbps video", color = Color(0xFF858BA0), fontSize = 11.sp)
        Text("About 9–10 MB/min; varies with video and audio.", color = Color(0xFF858BA0), fontSize = 11.sp)
        Text("Saved to Movies/VideoEditing", color = Color(0xFF858BA0), fontSize = 11.sp)
        Spacer(Modifier.height(24.dp))
        }
    }
    logoCropBitmap?.let { sourceBitmap ->
        LogoCropDialog(
            bitmap = sourceBitmap,
            isGif = logoCropIsGif,
            onDismiss = { logoCropBitmap = null; logoCropUri = null },
            onUseOriginal = {
                runCatching { saveLogoPermanently(context, requireNotNull(logoCropUri)) }
                    .onSuccess { file -> prefs.edit().putString("custom_logo_path", file.absolutePath).apply(); savedLogo = file }
                    .onFailure { message = it.message ?: "Couldn't save this logo." }
                logoCropBitmap = null; logoCropUri = null
            },
            onCrop = { crop ->
                runCatching {
                    val left = (crop.x * sourceBitmap.width).toInt().coerceIn(0, sourceBitmap.width - 1)
                    val top = (crop.y * sourceBitmap.height).toInt().coerceIn(0, sourceBitmap.height - 1)
                    val width = (crop.w * sourceBitmap.width).toInt().coerceIn(1, sourceBitmap.width - left)
                    val height = (crop.h * sourceBitmap.height).toInt().coerceIn(1, sourceBitmap.height - top)
                    val croppedBitmap = Bitmap.createBitmap(sourceBitmap, left, top, width, height)
                    saveCroppedLogo(context, croppedBitmap)
                }.onSuccess { file -> prefs.edit().putString("custom_logo_path", file.absolutePath).apply(); savedLogo = file }
                    .onFailure { message = it.message ?: "Couldn't crop this logo." }
                logoCropBitmap = null; logoCropUri = null
            }
        )
    }
    message?.let { text -> AlertDialog(onDismissRequest = { message = null }, confirmButton = { TextButton(onClick = { message = null }) { Text("OK") } }, title = { Text(if (text.startsWith("Video exported")) "Export complete" else "Notice") }, text = { Text(text) } ) }
}

@Composable
private fun SectionTitle(text: String) { Text(text, color = Color(0xFFA7ACBC), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp, modifier = Modifier.padding(bottom = 5.dp)) }

@Composable
private fun LogoCropDialog(bitmap: Bitmap, isGif: Boolean, onDismiss: () -> Unit, onUseOriginal: () -> Unit, onCrop: (CropRect) -> Unit) {
    var cropRect by remember(bitmap) { mutableStateOf(CropRect()) }
    val aspectRatio = bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1)

    Dialog(onDismissRequest = onDismiss) {
        Surface(Modifier.fillMaxWidth().widthIn(max = 480.dp), shape = RoundedCornerShape(22.dp), color = Panel) {
            Column(Modifier.fillMaxWidth().padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Crop your logo", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("Drag inside the box to move it; drag the corner to resize.", color = Color(0xFFBEC2D0), fontSize = 12.sp)
                BoxWithConstraints(Modifier.fillMaxWidth().padding(vertical = 14.dp), contentAlignment = Alignment.Center) {
                    val imageWidth = minOf(maxWidth, 300.dp * aspectRatio)
                    val imageHeight = imageWidth / aspectRatio
                    BoxWithConstraints(Modifier.size(imageWidth, imageHeight).clip(RoundedCornerShape(8.dp))) {
                        val parentWidth = constraints.maxWidth.toFloat().coerceAtLeast(1f)
                        val parentHeight = constraints.maxHeight.toFloat().coerceAtLeast(1f)
                        val current = rememberUpdatedState(cropRect)
                        val updateCrop = rememberUpdatedState<(CropRect) -> Unit> { cropRect = it }
                        androidx.compose.foundation.Image(bitmap.asImageBitmap(), "Logo crop preview", Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                            val left = size.width * cropRect.x
                            val top = size.height * cropRect.y
                            val right = size.width * (cropRect.x + cropRect.w)
                            val bottom = size.height * (cropRect.y + cropRect.h)
                            val shade = Color.Black.copy(alpha = .58f)
                            drawRect(shade, topLeft = Offset.Zero, size = androidx.compose.ui.geometry.Size(size.width, top))
                            drawRect(shade, topLeft = Offset(0f, bottom), size = androidx.compose.ui.geometry.Size(size.width, size.height - bottom))
                            drawRect(shade, topLeft = Offset(0f, top), size = androidx.compose.ui.geometry.Size(left, bottom - top))
                            drawRect(shade, topLeft = Offset(right, top), size = androidx.compose.ui.geometry.Size(size.width - right, bottom - top))
                            drawRect(Color.White, topLeft = Offset(left, top), size = androidx.compose.ui.geometry.Size(right - left, bottom - top), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.dp.toPx()))
                            for (index in 1..2) {
                                val gx = left + (right - left) * index / 3f
                                val gy = top + (bottom - top) * index / 3f
                                drawLine(Color.White.copy(alpha = .55f), Offset(gx, top), Offset(gx, bottom), strokeWidth = 1.dp.toPx())
                                drawLine(Color.White.copy(alpha = .55f), Offset(left, gy), Offset(right, gy), strokeWidth = 1.dp.toPx())
                            }
                        }
                        Box(
                            Modifier.offset { IntOffset((parentWidth * cropRect.x).roundToInt(), (parentHeight * cropRect.y).roundToInt()) }
                                .size(maxWidth * cropRect.w, maxHeight * cropRect.h)
                                .pointerInput(parentWidth, parentHeight) {
                                    var moving: CropRect? = null
                                    detectDragGestures(onDragStart = { moving = current.value }, onDragEnd = { moving = null }, onDragCancel = { moving = null }) { change, drag ->
                                        change.consume()
                                        val start = moving ?: current.value
                                        val next = start.copy(x = (start.x + drag.x / parentWidth).coerceIn(0f, 1f - start.w), y = (start.y + drag.y / parentHeight).coerceIn(0f, 1f - start.h))
                                        moving = next
                                        updateCrop.value(next)
                                    }
                                }
                        ) {
                            Box(Modifier.fillMaxSize())
                            Box(Modifier.align(Alignment.BottomEnd).size(34.dp).background(Accent, RoundedCornerShape(topStart = 9.dp))
                                .pointerInput(parentWidth, parentHeight) {
                                    var resizing: CropRect? = null
                                    detectDragGestures(onDragStart = { resizing = current.value }, onDragEnd = { resizing = null }, onDragCancel = { resizing = null }) { change, drag ->
                                        change.consume()
                                        val start = resizing ?: current.value
                                        val nextW = (start.w + drag.x / parentWidth).coerceIn(.12f, 1f - start.x)
                                        val nextH = (start.h + drag.y / parentHeight).coerceIn(.12f, 1f - start.y)
                                        val next = start.copy(w = nextW, h = nextH)
                                        resizing = next
                                        updateCrop.value(next)
                                    }
                                }, contentAlignment = Alignment.Center) { Text("↘", color = Color.White, fontWeight = FontWeight.Bold) }
                        }
                    }
                }
                if (isGif) Text("Crop saves the visible GIF frame as a still PNG. Choose Keep animated to preserve the GIF.", color = Color(0xFFFFD78A), fontSize = 11.sp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    TextButton(onClick = onUseOriginal) { Text(if (isGif) "Keep animated" else "Use full image") }
                    Button(onClick = { onCrop(cropRect) }) { Text("Crop & Save") }
                }
            }
        }
    }
}

@Composable
private fun SettingSwitch(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium); Text(subtitle, color = Color(0xFF858BA0), fontSize = 11.sp) }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun InteractiveBlurBox(rect: BlurRect, blurStrength: Float, modifier: Modifier, onChange: (BlurRect) -> Unit) {
    val latestRect = rememberUpdatedState(rect)
    val latestOnChange = rememberUpdatedState(onChange)
    BoxWithConstraints(modifier) {
        val parentWidth = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val parentHeight = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        val boxWidth = maxWidth * rect.w
        val boxHeight = maxHeight * rect.h
        Box(
            Modifier.offset { IntOffset((parentWidth * rect.x).roundToInt(), (parentHeight * rect.y).roundToInt()) }
                .size(boxWidth, boxHeight).clip(RoundedCornerShape(5.dp))
                .background(Color(0xFFB8BEC8).copy(alpha = .2f + blurStrength * .74f)).border(1.dp, Color(0xFFE2D8FF), RoundedCornerShape(5.dp))
                .pointerInput(parentWidth, parentHeight) {
                    var draggedRect: BlurRect? = null
                    detectDragGestures(
                        onDragStart = { draggedRect = latestRect.value },
                        onDragEnd = { draggedRect = null },
                        onDragCancel = { draggedRect = null }
                    ) { change, drag ->
                        change.consume()
                        val current = draggedRect ?: latestRect.value
                        val next = current.copy(
                            x = (current.x + drag.x / parentWidth).coerceIn(0f, 1f - current.w),
                            y = (current.y + drag.y / parentHeight).coerceIn(0f, 1f - current.h)
                        )
                        draggedRect = next
                        latestOnChange.value(next)
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Text("BLUR / SUBTITLE COVER", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = .7.sp)
            Box(Modifier.align(Alignment.BottomEnd).size(36.dp).background(Accent.copy(alpha = .95f), RoundedCornerShape(topStart = 9.dp)).pointerInput(parentWidth, parentHeight) {
                var draggedRect: BlurRect? = null
                detectDragGestures(
                    onDragStart = { draggedRect = latestRect.value },
                    onDragEnd = { draggedRect = null },
                    onDragCancel = { draggedRect = null }
                ) { change, drag ->
                    change.consume()
                    val current = draggedRect ?: latestRect.value
                    val newW = (current.w + drag.x / parentWidth).coerceIn(.16f, 1f - current.x)
                    val newH = (current.h + drag.y / parentHeight).coerceIn(.06f, .35f)
                    val next = current.copy(w = newW, h = newH, y = current.y.coerceAtMost(1f - newH))
                    draggedRect = next
                    latestOnChange.value(next)
                }
            }, contentAlignment = Alignment.Center) { Text("↘", color = Color.White, fontSize = 18.sp) }
        }
    }
}

@Composable
private fun DraggableLogo(
    bitmap: androidx.compose.ui.graphics.ImageBitmap?,
    animatedDrawable: android.graphics.drawable.AnimatedImageDrawable?,
    position: LogoPosition,
    scale: Float,
    modifier: Modifier,
    onMove: (LogoPosition) -> Unit,
    onScale: (Float) -> Unit
) {
    val latestScale = rememberUpdatedState(scale)
    val latestOnMove = rememberUpdatedState(onMove)
    val latestOnScale = rememberUpdatedState(onScale)
    BoxWithConstraints(modifier) {
        val parentWidth = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val parentHeight = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        val widthFraction = .22f * scale
        val heightFraction = .12f * scale
        val sourceWidth = (bitmap?.width ?: animatedDrawable?.intrinsicWidth ?: 1).coerceAtLeast(1).toFloat()
        val sourceHeight = (bitmap?.height ?: animatedDrawable?.intrinsicHeight ?: 1).coerceAtLeast(1).toFloat()
        val fitScale = minOf(parentWidth * widthFraction / sourceWidth, parentHeight * heightFraction / sourceHeight)
        val visibleWidthFraction = sourceWidth * fitScale / parentWidth
        val visibleHeightFraction = sourceHeight * fitScale / parentHeight
        val minX = visibleWidthFraction / 2f
        val minY = visibleHeightFraction / 2f
        val displayPosition = LogoPosition(
            snapLegacyLogoEdge(position.x, widthFraction / 2f, minX),
            snapLegacyLogoEdge(position.y, heightFraction / 2f, minY)
        )
        val latestDisplayPosition = rememberUpdatedState(displayPosition)
        Box(
            Modifier.offset { IntOffset((parentWidth * (displayPosition.x - widthFraction / 2f)).roundToInt(), (parentHeight * (displayPosition.y - heightFraction / 2f)).roundToInt()) }
                .width(maxWidth * widthFraction).height(maxHeight * heightFraction)
                .pointerInput(parentWidth, parentHeight) {
                    var draggedPosition: LogoPosition? = null
                    detectDragGestures(
                        onDragStart = { draggedPosition = latestDisplayPosition.value },
                        onDragEnd = { draggedPosition = null },
                        onDragCancel = { draggedPosition = null }
                    ) { change, drag ->
                        change.consume()
                        val current = draggedPosition ?: latestDisplayPosition.value
                        val next = LogoPosition(
                            x = (current.x + drag.x / parentWidth).coerceIn(minX, 1f - minX),
                            y = (current.y + drag.y / parentHeight).coerceIn(minY, 1f - minY)
                        )
                        draggedPosition = next
                        latestOnMove.value(next)
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            if (bitmap != null) {
                androidx.compose.foundation.Image(bitmap, "Drag to move logo", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            } else if (animatedDrawable != null) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { viewContext -> ImageView(viewContext).apply { scaleType = ImageView.ScaleType.FIT_CENTER; setImageDrawable(animatedDrawable) } },
                    update = { imageView -> if (imageView.drawable !== animatedDrawable) imageView.setImageDrawable(animatedDrawable) }
                )
            }
            Box(
                Modifier.align(Alignment.BottomEnd).size(28.dp).background(Accent.copy(alpha = .96f), RoundedCornerShape(topStart = 8.dp))
                    .pointerInput(parentWidth, parentHeight) {
                        var currentScale = scale
                        detectDragGestures { change, drag ->
                            change.consume()
                            currentScale = (currentScale + drag.x / parentWidth * 1.5f + drag.y / parentHeight * 1.5f).coerceIn(.4f, 2.2f)
                            latestOnScale.value(currentScale)
                        }
                    },
                contentAlignment = Alignment.Center
            ) { Text("↘", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
private fun VideoPreview(
    uri: Uri,
    replacementAudioUri: Uri?,
    mirror: Boolean,
    zoom: Float,
    muteVideoAudio: Boolean,
    videoAudioVolume: Float,
    replacementAudioEnabled: Boolean,
    replacementAudioVolume: Float,
    modifier: Modifier = Modifier,
    onPosition: (Long) -> Unit
) {
    val context = LocalContext.current
    val latestOnPosition = rememberUpdatedState(onPosition)
    val player = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            setAudioAttributes(AudioAttributes.DEFAULT, false)
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
            playWhenReady = false
        }
    }
    val audioPlayer = remember(replacementAudioUri) {
        replacementAudioUri?.let { audioUri ->
            ExoPlayer.Builder(context).build().apply {
                setAudioAttributes(AudioAttributes.DEFAULT, false)
                repeatMode = Player.REPEAT_MODE_ONE
                setMediaItem(MediaItem.fromUri(audioUri))
                prepare()
                playWhenReady = false
            }
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    DisposableEffect(audioPlayer) { onDispose { audioPlayer?.release() } }
    SideEffect {
        player.volume = if (muteVideoAudio) 0f else videoAudioVolume.coerceIn(0f, 1f)
        audioPlayer?.volume = if (replacementAudioEnabled) replacementAudioVolume.coerceIn(0f, 1f) else 0f
    }
    LaunchedEffect(player, audioPlayer, replacementAudioEnabled) {
        while (isActive) {
            latestOnPosition.value(player.currentPosition.coerceAtLeast(0L))
            val addedPlayer = audioPlayer
            if (addedPlayer != null) {
                if (replacementAudioEnabled && player.isPlaying) {
                    val duration = addedPlayer.duration
                    val targetPosition = if (duration > 0) player.currentPosition % duration else player.currentPosition
                    if (abs(addedPlayer.currentPosition - targetPosition) > 500L) addedPlayer.seekTo(targetPosition)
                    if (!addedPlayer.isPlaying) addedPlayer.play()
                } else if (addedPlayer.isPlaying || addedPlayer.playWhenReady) {
                    addedPlayer.pause()
                }
            }
            delay(250)
        }
    }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            (LayoutInflater.from(ctx).inflate(R.layout.preview_player, android.widget.FrameLayout(ctx), false) as PlayerView).apply {
                this.player = player
                scaleX = 1f
                scaleY = 1f
                applyVideoTextureTransform(this, mirror, zoom)
                post { applyVideoTextureTransform(this, mirror, zoom) }
            }
        },
        update = { view ->
            view.player = player
            view.scaleX = 1f
            view.scaleY = 1f
            applyVideoTextureTransform(view, mirror, zoom)
            view.post { applyVideoTextureTransform(view, mirror, zoom) }
        }
    )
}

private fun applyVideoTextureTransform(playerView: PlayerView, mirror: Boolean, zoom: Float) {
    val videoTexture = playerView.videoSurfaceView as? TextureView ?: return
    videoTexture.scaleX = if (mirror) -zoom else zoom
    videoTexture.scaleY = zoom
}

private fun fullFrameOverlaySettings(): OverlaySettings = OverlaySettings.Builder().setBackgroundFrameAnchor(0f, 0f).setOverlayFrameAnchor(0f, 0f).setScale(1f, 1f).build()

@Composable
private fun ScrollingCaptionPreview(text: String, modifier: Modifier = Modifier) {
    Text(
        text = "   $text   •   $text   •   ",
        modifier = modifier.background(Color.Black.copy(alpha = .68f)).basicMarquee(iterations = Int.MAX_VALUE).padding(horizontal = 8.dp, vertical = 5.dp),
        color = Color.White,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1
    )
}

@Composable
private fun TimedSubtitlePreview(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.clip(RoundedCornerShape(7.dp)).background(Color.Black.copy(alpha = .82f)).padding(horizontal = 12.dp, vertical = 7.dp),
        color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 2)
}

private fun makeBorderOverlay(colorHex: String): Bitmap {
    val bitmap = Bitmap.createBitmap(OUTPUT_WIDTH, OUTPUT_HEIGHT, Bitmap.Config.ARGB_8888)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.parseColor(colorHex)
        style = Paint.Style.STROKE
        strokeWidth = 12f
    }
    Canvas(bitmap).drawRoundRect(RectF(8f, 8f, OUTPUT_WIDTH - 8f, OUTPUT_HEIGHT - 8f), 26f, 26f, paint)
    return bitmap
}

private class ScrollingCaptionOverlay(private val text: String) : BitmapOverlay() {
    private val bitmap = Bitmap.createBitmap(OUTPUT_WIDTH, OUTPUT_HEIGHT, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(bitmap)
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AndroidColor.argb(176, 0, 0, 0) }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.WHITE
        textSize = 42f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val durationUs = 8_000_000L

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        bitmap.eraseColor(AndroidColor.TRANSPARENT)
        val bandTop = OUTPUT_HEIGHT - 86f
        canvas.drawRect(0f, bandTop, OUTPUT_WIDTH.toFloat(), OUTPUT_HEIGHT.toFloat(), backgroundPaint)
        val message = "   $text   •   "
        val textWidth = textPaint.measureText(message)
        val phase = (presentationTimeUs.coerceAtLeast(0L) % durationUs).toFloat() / durationUs
        canvas.drawText(message, OUTPUT_WIDTH - phase * (OUTPUT_WIDTH + textWidth), OUTPUT_HEIGHT - 29f, textPaint)
        return bitmap
    }

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings = fullFrameOverlaySettings()
}

private class TimedSubtitleOverlay(private val cues: List<SubtitleCue>) : BitmapOverlay() {
    private val bitmap = Bitmap.createBitmap(OUTPUT_WIDTH, OUTPUT_HEIGHT, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(bitmap)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.WHITE
        textSize = 40f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setShadowLayer(3f, 1f, 2f, AndroidColor.BLACK)
    }
    private val background = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AndroidColor.argb(220, 0, 0, 0) }

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        bitmap.eraseColor(AndroidColor.TRANSPARENT)
        val timeMs = presentationTimeUs / 1_000L
        val cue = cues.firstOrNull { timeMs >= it.startMs && timeMs < it.endMs } ?: return bitmap
        val lines = wrapSubtitleText(cue.text, paint, OUTPUT_WIDTH - 72f).take(2)
        val lineHeight = 48f
        val boxHeight = lines.size * lineHeight + 28f
        val top = OUTPUT_HEIGHT - 160f - boxHeight
        canvas.drawRoundRect(RectF(24f, top, OUTPUT_WIDTH - 24f, top + boxHeight), 18f, 18f, background)
        lines.forEachIndexed { index, line -> canvas.drawText(line, OUTPUT_WIDTH / 2f, top + 38f + index * lineHeight, paint) }
        return bitmap
    }

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings = fullFrameOverlaySettings()
}

private fun wrapSubtitleText(text: String, paint: Paint, maxWidth: Float): List<String> {
    val words = text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
    if (words.isEmpty()) return emptyList()
    val lines = mutableListOf<String>()
    var current = ""
    for (word in words) {
        val candidate = if (current.isEmpty()) word else "$current $word"
        if (current.isNotEmpty() && paint.measureText(candidate) > maxWidth) { lines += current; current = word }
        else current = candidate
    }
    if (current.isNotEmpty()) lines += current
    return lines
}

private fun makeWarmOverlay(intensity: Float): Bitmap {
    val bitmap = Bitmap.createBitmap(OUTPUT_WIDTH, OUTPUT_HEIGHT, Bitmap.Config.ARGB_8888)
    Canvas(bitmap).drawColor(AndroidColor.argb((intensity.coerceIn(0f, 1f) * 128).roundToInt(), 255, 159, 76))
    return bitmap
}

private fun makeLogoOverlay(file: File, position: LogoPosition, scale: Float): Bitmap {
    val output = Bitmap.createBitmap(OUTPUT_WIDTH, OUTPUT_HEIGHT, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(output)
    val source = BitmapFactory.decodeFile(file.absolutePath) ?: return output
    val maxWidth = OUTPUT_WIDTH * .22f * scale.coerceIn(.4f, 2.2f)
    val maxHeight = OUTPUT_HEIGHT * .12f * scale.coerceIn(.4f, 2.2f)
    val factor = minOf(maxWidth / source.width, maxHeight / source.height, 1f)
    val width = source.width * factor; val height = source.height * factor
    val centerX = snapLegacyLogoEdge(position.x, maxWidth / (2f * OUTPUT_WIDTH), width / (2f * OUTPUT_WIDTH))
    val centerY = snapLegacyLogoEdge(position.y, maxHeight / (2f * OUTPUT_HEIGHT), height / (2f * OUTPUT_HEIGHT))
    val left = (centerX * OUTPUT_WIDTH - width / 2f).coerceIn(0f, OUTPUT_WIDTH - width)
    val top = (centerY * OUTPUT_HEIGHT - height / 2f).coerceIn(0f, OUTPUT_HEIGHT - height)
    canvas.drawBitmap(source, null, RectF(left, top, left + width, top + height), Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
    source.recycle()
    return output
}

private fun snapLegacyLogoEdge(coordinate: Float, slotHalf: Float, visibleHalf: Float): Float {
    val tolerance = .004f
    return when {
        kotlin.math.abs(coordinate - slotHalf) <= tolerance || kotlin.math.abs(coordinate - visibleHalf) <= tolerance -> visibleHalf
        kotlin.math.abs(coordinate - (1f - slotHalf)) <= tolerance || kotlin.math.abs(coordinate - (1f - visibleHalf)) <= tolerance -> 1f - visibleHalf
        else -> coordinate.coerceIn(visibleHalf, 1f - visibleHalf)
    }
}

private fun createLogoOverlay(file: File, position: LogoPosition, scale: Float): BitmapOverlay {
    return if (file.extension.equals("gif", ignoreCase = true)) {
        LoopingGifBitmapOverlay(file, position, scale)
    } else {
        BitmapOverlay.createStaticBitmapOverlay(makeLogoOverlay(file, position, scale), fullFrameOverlaySettings())
    }
}

private class LoopingGifBitmapOverlay(file: File, private val position: LogoPosition, scale: Float) : BitmapOverlay() {
    private val movie = Movie.decodeFile(file.absolutePath) ?: error("This GIF could not be decoded")
    private val durationMs = movie.duration().coerceAtLeast(100)
    private val maxWidth = OUTPUT_WIDTH * .22f * scale.coerceIn(.4f, 2.2f)
    private val maxHeight = OUTPUT_HEIGHT * .12f * scale.coerceIn(.4f, 2.2f)
    private val bitmapScale = minOf(maxWidth / movie.width().coerceAtLeast(1), maxHeight / movie.height().coerceAtLeast(1), 1f)
    private val frameWidth = (movie.width() * bitmapScale).roundToInt().coerceAtLeast(1)
    private val frameHeight = (movie.height() * bitmapScale).roundToInt().coerceAtLeast(1)
    private val frameBitmap = Bitmap.createBitmap(frameWidth, frameHeight, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(frameBitmap)

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        val timeMs = ((presentationTimeUs.coerceAtLeast(0L) / 1_000L) % durationMs).toInt()
        movie.setTime(timeMs)
        frameBitmap.eraseColor(AndroidColor.TRANSPARENT)
        canvas.save()
        canvas.scale(frameWidth / movie.width().toFloat(), frameHeight / movie.height().toFloat())
        movie.draw(canvas, 0f, 0f)
        canvas.restore()
        return frameBitmap
    }

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
        val centerX = snapLegacyLogoEdge(position.x, maxWidth / (2f * OUTPUT_WIDTH), frameWidth / (2f * OUTPUT_WIDTH))
        val centerY = snapLegacyLogoEdge(position.y, maxHeight / (2f * OUTPUT_HEIGHT), frameHeight / (2f * OUTPUT_HEIGHT))
        val x = centerX * 2f - 1f
        val y = 1f - centerY * 2f
        return OverlaySettings.Builder()
            .setBackgroundFrameAnchor(x, y)
            .setOverlayFrameAnchor(0f, 0f)
            .setScale(1f, 1f)
            .build()
    }
}

private fun makeBlurCoverOverlay(rect: BlurRect, strength: Float): Bitmap {
    val output = Bitmap.createBitmap(OUTPUT_WIDTH, OUTPUT_HEIGHT, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(output)
    val alpha = (80 + 170 * strength.coerceIn(0f, 1f)).roundToInt().coerceIn(0, 250)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AndroidColor.argb(alpha, 216, 221, 233) }
    val left = rect.x.coerceIn(0f, 1f) * OUTPUT_WIDTH; val top = rect.y.coerceIn(0f, 1f) * OUTPUT_HEIGHT
    canvas.drawRoundRect(RectF(left, top, left + rect.w.coerceIn(.05f, 1f) * OUTPUT_WIDTH, top + rect.h.coerceIn(.03f, 1f) * OUTPUT_HEIGHT), 12f, 12f, paint)
    return output
}

private class GainAudioProcessor(private val gain: Float) : BaseAudioProcessor() {
    private val safeGain = gain.coerceIn(0f, 1f)

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != androidx.media3.common.C.ENCODING_PCM_16BIT &&
            inputAudioFormat.encoding != androidx.media3.common.C.ENCODING_PCM_FLOAT
        ) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val outputBuffer = replaceOutputBuffer(inputBuffer.remaining())
        when (inputAudioFormat.encoding) {
            androidx.media3.common.C.ENCODING_PCM_16BIT -> while (inputBuffer.remaining() >= 2) {
                val sample = inputBuffer.short.toInt()
                outputBuffer.putShort((sample * safeGain).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
            }
            androidx.media3.common.C.ENCODING_PCM_FLOAT -> while (inputBuffer.remaining() >= 4) {
                val sample = inputBuffer.float
                outputBuffer.putFloat((sample * safeGain).coerceIn(-1f, 1f))
            }
        }
        inputBuffer.position(inputBuffer.limit())
        outputBuffer.flip()
    }
}

private fun saveToGallery(context: Context, source: File): Uri {
    val name = "VideoEditing-${System.currentTimeMillis()}.mp4"
    val values = ContentValues().apply {
        put(MediaStore.Video.Media.DISPLAY_NAME, name)
        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
        put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/VideoEditing")
        put(MediaStore.Video.Media.IS_PENDING, 1)
    }
    val uri = requireNotNull(context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)) { "MediaStore refused the output file" }
    try {
        context.contentResolver.openOutputStream(uri, "w").use { output -> requireNotNull(output) { "Could not open Gallery output" }.use { source.inputStream().use { input -> input.copyTo(output) } } }
        values.clear(); values.put(MediaStore.Video.Media.IS_PENDING, 0)
        context.contentResolver.update(uri, values, null, null)
        return uri
    } catch (error: Throwable) {
        context.contentResolver.delete(uri, null, null)
        throw error
    }
}

private fun readValidLogo(prefs: android.content.SharedPreferences): File? {
    val path = prefs.getString("custom_logo_path", null) ?: return null
    return File(path).takeIf { it.isFile && it.length() > 0 }
}

private fun decodeLogoPreview(file: File): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / sample > 640 || bounds.outHeight / sample > 640) sample *= 2
    return BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
}

private fun saveLogoPermanently(context: Context, uri: Uri): File {
    val mime = context.contentResolver.getType(uri)?.lowercase()
    val extension = when (mime) {
        "image/png" -> ".png"
        "image/jpeg", "image/jpg" -> ".jpg"
        "image/gif" -> ".gif"
        else -> {
            val name = context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            when (name?.substringAfterLast('.', "")?.lowercase()) { "png" -> ".png"; "jpg", "jpeg" -> ".jpg"; "gif" -> ".gif"; else -> error("Choose a PNG, JPG, or GIF image") }
        }
    }
    val directory = File(context.filesDir, "custom-logo").apply { mkdirs() }
    val temp = File(directory, "logo-${UUID.randomUUID()}.tmp")
    val finalFile = File(directory, "logo$extension")
    context.contentResolver.openInputStream(uri).use { input -> requireNotNull(input) { "Image could not be opened" }.use { source -> FileOutputStream(temp).use { target -> source.copyTo(target) } } }
    if (temp.length() == 0L) { temp.delete(); error("Empty image") }
    if (finalFile.exists()) finalFile.delete()
    check(temp.renameTo(finalFile)) { "Unable to save image" }
    directory.listFiles()?.filter { it != finalFile }?.forEach { it.delete() }
    return finalFile
}

private fun decodeLogoForCrop(context: Context, uri: Uri): Bitmap {
    val source = ImageDecoder.createSource(context.contentResolver, uri)
    return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        val maxSide = 2048
        val factor = minOf(1f, maxSide.toFloat() / maxOf(info.size.width, info.size.height))
        if (factor < 1f) decoder.setTargetSize((info.size.width * factor).roundToInt().coerceAtLeast(1), (info.size.height * factor).roundToInt().coerceAtLeast(1))
    }
}

private fun saveCroppedLogo(context: Context, bitmap: Bitmap): File {
    val directory = File(context.filesDir, "custom-logo").apply { mkdirs() }
    val target = File(directory, "logo-${UUID.randomUUID()}.png")
    FileOutputStream(target).use { output -> check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "Unable to encode the cropped logo" } }
    check(target.length() > 0L) { "Cropped logo is empty" }
    directory.listFiles()?.filter { it != target }?.forEach { it.delete() }
    return target
}

private fun readSrtCues(context: Context, uri: Uri): List<SubtitleCue> {
    val contents = context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
        ?: error("Subtitle file could not be opened")
    val blocks = contents.replace("\r\n", "\n").replace('\r', '\n').split(Regex("\\n\\s*\\n"))
    val timestamp = Regex("(\\d{1,2}):(\\d{2}):(\\d{2})[,\\.](\\d{3})\\s*-->\\s*(\\d{1,2}):(\\d{2}):(\\d{2})[,\\.](\\d{3})")
    val cues = blocks.mapNotNull { block ->
        val lines = block.lines().map(String::trim).filter(String::isNotEmpty)
        val match = lines.firstNotNullOfOrNull { timestamp.find(it) } ?: return@mapNotNull null
        val values = (1..8).map { match.groupValues[it].toLong() }
        fun time(h: Long, m: Long, s: Long, ms: Long) = ((h * 3600 + m * 60 + s) * 1000 + ms)
        val textIndex = lines.indexOfFirst { timestamp.containsMatchIn(it) } + 1
        val text = lines.drop(textIndex).joinToString(" ").replace(Regex("<[^>]*>"), "").trim()
        val start = time(values[0], values[1], values[2], values[3])
        val end = time(values[4], values[5], values[6], values[7])
        if (text.isBlank() || end <= start) null else SubtitleCue(start, end, text)
    }.sortedBy { it.startMs }
    require(cues.isNotEmpty()) { "No valid SRT subtitle cues were found." }
    require(cues.size <= 20_000) { "Subtitle file has too many cues." }
    return cues
}

private fun makeTwoSecondCues(segments: List<NaraSarEngine.Segment>): List<SubtitleCue> {
    val cues = ArrayList<SubtitleCue>()
    for (segment in segments) {
        val text = segment.text.trim()
        if (text.isEmpty()) continue
        val start = (segment.startSeconds * 1000).toLong().coerceAtLeast(0L)
        val end = (segment.endSeconds * 1000).toLong().coerceAtLeast(start + 1L)
        val slotCount = (((end - start) + 1999L) / 2000L).toInt().coerceAtLeast(1)
        val words = text.split(Regex("\\s+")).filter { it.isNotBlank() }
        val chunkSize = ((words.size + slotCount - 1) / slotCount).coerceAtLeast(1)
        val pieces = words.chunked(chunkSize).map { it.joinToString(" ") }.ifEmpty { listOf(text) }
        pieces.forEachIndexed { index, piece ->
            val cueStart = start + index * 2_000L
            if (cueStart < end) cues += SubtitleCue(cueStart, minOf(cueStart + 2_000L, end), piece)
        }
    }
    return cues
}

private fun keepMyanmarSubtitleText(text: String): String = buildString {
    var lastWasSpace = true
    for (character in text) {
        val isMyanmar = character in '\u1000'..'\u109F' || character in '\uA9E0'..'\uA9FF' || character in '\uAA60'..'\uAA7F'
        if (isMyanmar || character.isWhitespace() || character in "။၊၊!?.,:;()၀၁၂၃၄၅၆၇၈၉") {
            append(character)
            lastWasSpace = character.isWhitespace()
        } else if (!lastWasSpace && isMyanmar) {
            append(' ')
            lastWasSpace = true
        }
    }
}.replace(Regex("\\s+"), " ").trim()
