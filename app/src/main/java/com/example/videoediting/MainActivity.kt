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
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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
import kotlin.math.roundToLong
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private val Background = Color(0xFF10131B)
private val Panel = Color(0xFF1A1E29)
private val Accent = Color(0xFF9C7BFF)
private const val OUTPUT_WIDTH = 720
private const val OUTPUT_HEIGHT = 1280
private const val OUTPUT_VIDEO_BITRATE = 1_200_000
private const val OUTPUT_AUDIO_BITRATE = 96_000
private data class BlurRect(val x: Float = .22f, val y: Float = .70f, val w: Float = .56f, val h: Float = .11f)
private data class LogoPosition(val x: Float = .86f, val y: Float = .92f)
internal data class SubtitleCue(val startMs: Long, val endMs: Long, val text: String)
private data class EditorPreset(
    val mirror: Boolean,
    val zoom: Float,
    val warmEnabled: Boolean,
    val warmAmount: Float,
    val borderColorHex: String,
    val muteOriginal: Boolean,
    val videoAudioVolume: Float,
    val replacementAudioEnabled: Boolean,
    val replacementAudioVolume: Float,
    val blurEnabled: Boolean,
    val blurRect: BlurRect,
    val blurStrength: Float,
    val logoPosition: LogoPosition,
    val logoScale: Float,
    val scrollingCaption: String,
    val subtitleScript: String,
    val subtitleOffsetMs: Float,
    val subtitleFontSp: Float,
    val subtitleColorHex: String,
    val subtitlePositionX: Float,
    val subtitlePositionY: Float
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = Background, surface = Panel)) { EditorScreen() } }
    }
}

@Composable
private fun EditorScreen() {
    val context = LocalContext.current
    val density = LocalDensity.current
    val screenScope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("video_editing", Context.MODE_PRIVATE) }
    val logoPrefsInitialized = remember(prefs) {
        if (!prefs.getBoolean("china_ai_default_logo_v1", false)) {
            prefs.edit().remove("custom_logo_path").putBoolean("china_ai_default_logo_v1", true).apply()
        }
        true
    }
    var savedLogo by remember(logoPrefsInitialized) { mutableStateOf(readValidLogo(prefs)) }
    val bundledLogo = remember(context) { runCatching { copyBundledLogo(context) }.getOrNull() }
    val activeLogo = savedLogo ?: bundledLogo
    var editingPresets by remember { mutableStateOf(loadEditingPresets(prefs)) }
    var selectedPresetName by remember { mutableStateOf<String?>(null) }
    var presetMenuExpanded by remember { mutableStateOf(false) }
    var showSavePresetDialog by remember { mutableStateOf(false) }
    var presetNameInput by remember { mutableStateOf("") }
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
    var scrollingCaption by remember { mutableStateOf(prefs.getString("scrolling_caption", "") ?: "") }
    var subtitleCues by remember { mutableStateOf<List<SubtitleCue>>(emptyList()) }
    var subtitleScript by remember { mutableStateOf("") }
    var subtitleOffsetMs by remember { mutableFloatStateOf(0f) }
    var subtitleFontSp by remember { mutableFloatStateOf(12f) }
    var subtitleColorHex by remember { mutableStateOf(prefs.getString("subtitle_color", "#FFFFFF") ?: "#FFFFFF") }
    var subtitlePositionX by remember { mutableFloatStateOf(.5f) }
    var subtitlePositionY by remember { mutableFloatStateOf(.83f) }
    var subtitlePreviewRestartKey by remember { mutableIntStateOf(0) }
    val naraSar = remember(context) { NaraSarTranscriber(context) }
    var naraModelReady by remember { mutableStateOf(naraSar.isModelInstalled()) }
    var naraDownloading by remember { mutableStateOf(false) }
    var naraDownloadProgress by remember { mutableFloatStateOf(0f) }
    var naraTranscribing by remember { mutableStateOf(false) }
    var previewCanvasWidthPx by remember { mutableFloatStateOf(0f) }
    var previewPositionMs by remember { mutableLongStateOf(0L) }
    var message by remember { mutableStateOf<String?>(null) }
    var exporting by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var activeTransformer by remember { mutableStateOf<Transformer?>(null) }
    var tempOutput by remember { mutableStateOf<File?>(null) }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) runCatching { saveLogoPermanently(context, uri) }
            .onSuccess { file ->
                savedLogo = file
                logoPosition = LogoPosition()
                logoScale = 1f
                prefs.edit().putString("custom_logo_path", file.absolutePath).putFloat("logo_x", logoPosition.x).putFloat("logo_y", logoPosition.y).putFloat("logo_scale", 1f)
                    .remove("logo_crop_x").remove("logo_crop_y").remove("logo_crop_w").remove("logo_crop_h").apply()
            }
            .onFailure { message = it.message ?: "Couldn't save that image. Choose a PNG, JPG, or GIF." }
    }
    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) { videoUri = uri; previewPositionMs = 0L; subtitlePreviewRestartKey++ }
    }
    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        audioUri = uri
        if (uri != null) replacementAudioEnabled = true
    }
    val subtitlePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching {
            context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                ?: error("Subtitle file could not be opened")
        }.onSuccess { text ->
            runCatching { parseSubtitleScript(text) }
                .onSuccess { cues -> subtitleScript = text; subtitleCues = cues; previewPositionMs = 0L; subtitlePreviewRestartKey++; message = "Loaded ${cues.size} subtitle lines. Adjust the text, timing, size or position below." }
                .onFailure { message = it.message ?: "Could not read this subtitle file." }
        }.onFailure { message = it.message ?: "Could not open this subtitle file." }
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
            val previewHeight = previewWidth * 16f / 9f
            Box(Modifier.width(previewWidth).aspectRatio(9f / 16f).align(Alignment.Center).onSizeChanged { previewCanvasWidthPx = it.width.toFloat() }.clip(RoundedCornerShape(18.dp)).background(Color(0xFF08090D))) {
                if (videoUri != null) VideoPreview(videoUri!!, audioUri, mirror, zoom, muteOriginal, videoAudioVolume, replacementAudioEnabled, replacementAudioVolume, subtitlePreviewRestartKey, Modifier.fillMaxSize(), onPosition = { previewPositionMs = it })
                if (videoUri == null) Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Movie, null, tint = Color(0xFF73798C), modifier = Modifier.size(36.dp))
                    Spacer(Modifier.height(8.dp)); Text("Your 9:16 video preview", color = Color(0xFFA7ACBC), fontSize = 12.sp)
                }
                if (warmEnabled && videoUri != null) Box(Modifier.fillMaxSize().background(Color(0xFFFF9F4C).copy(alpha = warmAmount * .48f)))
                if (blurEnabled && videoUri != null) InteractiveBlurBox(blurRect, blurStrength, Modifier.fillMaxSize(), onChange = { blurRect = it })
                activeLogo?.let { file ->
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
                activeSubtitleCue(subtitleCues, previewPositionMs, subtitleOffsetMs.toLong())?.let { cue ->
                    InteractiveTimedSubtitle(cue.text, subtitleFontSp, subtitleColorHex, subtitlePositionX, subtitlePositionY, Modifier.fillMaxSize(),
                        onMove = { x, y -> subtitlePositionX = x; subtitlePositionY = y })
                }
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
        SectionTitle("သိမ်းထားသော တည်းဖြတ်မှုအပြင်အဆင်များ")
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.weight(1f)) {
                OutlinedButton(onClick = { presetMenuExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(selectedPresetName ?: "သိမ်းထားသောအမည်ကို ရွေးပါ", maxLines = 1)
                }
                DropdownMenu(expanded = presetMenuExpanded, onDismissRequest = { presetMenuExpanded = false }) {
                    if (editingPresets.isEmpty()) {
                        DropdownMenuItem(text = { Text("သိမ်းထားသောအပြင်အဆင် မရှိသေးပါ") }, onClick = { presetMenuExpanded = false }, enabled = false)
                    }
                    editingPresets.forEach { (name, preset) ->
                        DropdownMenuItem(text = { Text(name, maxLines = 1) }, onClick = {
                            selectedPresetName = name
                            presetMenuExpanded = false
                            mirror = preset.mirror
                            zoom = preset.zoom.coerceIn(1f, 1.18f)
                            warmEnabled = preset.warmEnabled
                            warmAmount = preset.warmAmount.coerceIn(0f, 1f)
                            borderColorHex = preset.borderColorHex
                            muteOriginal = preset.muteOriginal
                            videoAudioVolume = preset.videoAudioVolume.coerceIn(0f, 1f)
                            replacementAudioEnabled = preset.replacementAudioEnabled
                            replacementAudioVolume = preset.replacementAudioVolume.coerceIn(0f, 1f)
                            blurEnabled = preset.blurEnabled
                            blurRect = preset.blurRect.copy(x = preset.blurRect.x.coerceIn(0f, 1f), y = preset.blurRect.y.coerceIn(0f, 1f), w = preset.blurRect.w.coerceIn(.16f, 1f), h = preset.blurRect.h.coerceIn(.06f, .35f))
                            blurStrength = preset.blurStrength.coerceIn(.15f, 1f)
                            logoPosition = preset.logoPosition
                            logoScale = preset.logoScale.coerceIn(.4f, 2.2f)
                            scrollingCaption = preset.scrollingCaption
                            subtitleScript = preset.subtitleScript
                            subtitleCues = if (preset.subtitleScript.isBlank()) emptyList() else runCatching { parseSubtitleScript(preset.subtitleScript) }.getOrDefault(emptyList())
                            subtitleOffsetMs = preset.subtitleOffsetMs.coerceIn(-5000f, 5000f)
                            subtitleFontSp = preset.subtitleFontSp.coerceIn(8f, 28f)
                            subtitleColorHex = preset.subtitleColorHex
                            subtitlePositionX = preset.subtitlePositionX.coerceIn(.08f, .92f)
                            subtitlePositionY = preset.subtitlePositionY.coerceIn(.08f, .92f)
                            prefs.edit().putFloat("logo_x", logoPosition.x).putFloat("logo_y", logoPosition.y).putFloat("logo_scale", logoScale)
                                .putString("border_color", borderColorHex).putString("scrolling_caption", scrollingCaption).putString("subtitle_color", subtitleColorHex).apply()
                            previewPositionMs = 0L
                            subtitlePreviewRestartKey++
                            message = "‘$name’ အပြင်အဆင်ကို ပြန်သုံးထားပါတယ်။"
                        })
                    }
                }
            }
            if (selectedPresetName != null && editingPresets.containsKey(selectedPresetName)) {
                IconButton(onClick = {
                    val updated = LinkedHashMap(editingPresets)
                    updated.remove(selectedPresetName)
                    if (saveEditingPresets(prefs, updated)) {
                        editingPresets = updated
                        selectedPresetName = null
                    } else message = "ဒီအပြင်အဆင်ကို ဖျက်၍မရပါ။ ထပ်ကြိုးစားပါ။"
                }) { Icon(Icons.Default.Close, "ရွေးထားသောအပြင်အဆင်ကို ဖျက်ရန်", tint = Color(0xFFCF9DA6)) }
            }
            OutlinedButton(onClick = { presetNameInput = ""; showSavePresetDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = null)
                Text(" ထည့်ရန်")
            }
        }
        Text("ဖုန်းထဲမှာ သိမ်းထားပြီး app ပိတ်ဖွင့်ပြီးနောက်လည်း ရှိနေပါမယ်။ Uninstall သို့မဟုတ် Clear data လုပ်ပါက ပျောက်သွားပါမယ်။ Video/Audio ဖိုင်တွေ မပါဝင်ပါ။", color = Color(0xFF858BA0), fontSize = 11.sp)
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
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Panel).padding(13.dp)) {
            SectionTitle("SAVED SCROLLING TEXT")
            OutlinedTextField(value = scrollingCaption, onValueChange = { text -> scrollingCaption = text; prefs.edit().putString("scrolling_caption", text).apply() }, modifier = Modifier.fillMaxWidth(),
                label = { Text("Text to scroll across the video") }, placeholder = { Text("Type a message for the scrolling caption") }, singleLine = true)
            Spacer(Modifier.height(5.dp))
            Text("Saved separately on this device · automatically used in previews and every export.", color = Color(0xFF858BA0), fontSize = 11.sp)
            if (scrollingCaption.isNotBlank()) TextButton(onClick = { scrollingCaption = ""; prefs.edit().putString("scrolling_caption", "").apply() }, modifier = Modifier.align(Alignment.End)) { Text("Clear saved text", color = Color(0xFFCF9DA6)) }
        }
        Spacer(Modifier.height(10.dp))
        SectionTitle("SUBTITLES · PASTE OR IMPORT TEXT")
        Text("Each line: [start - end] subtitle text. Supports mm:ss and hh:mm:ss, with optional decimal seconds.", color = Color(0xFFA7ACBC), fontSize = 12.sp)
        OutlinedTextField(
            value = subtitleScript,
            onValueChange = { subtitleScript = it },
            modifier = Modifier.fillMaxWidth().heightIn(min = 170.dp, max = 300.dp),
            label = { Text("Timed subtitle lines") },
            placeholder = { Text("[00:00 - 00:02.5] မြစ်ကမ်းဘေးမှာ လမ်းလျှောက်လာတဲ့\n[00:02.5 - 00:05.0] မိန်းကလေးဟာ သားရဲကို တွေ့တယ်") },
            minLines = 6,
            maxLines = 14
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                runCatching { parseSubtitleScript(subtitleScript) }
                    .onSuccess { cues -> subtitleCues = cues; previewPositionMs = 0L; subtitlePreviewRestartKey++; message = "Loaded ${cues.size} subtitle lines. Adjust timing, size or position below." }
                    .onFailure { message = it.message ?: "Check the subtitle line format and timing." }
            }, modifier = Modifier.weight(1f).height(44.dp), shape = RoundedCornerShape(13.dp)) { Text("Apply subtitles") }
            OutlinedButton(onClick = { subtitlePicker.launch(arrayOf("text/plain", "application/x-subrip", "text/srt", "*/*")) }, modifier = Modifier.weight(1f).height(44.dp), shape = RoundedCornerShape(13.dp)) { Text("Import .txt / .srt") }
        }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xFF242033)).padding(12.dp)) {
            Text("BURMESE AUTO-SUBTITLES", color = Accent, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            Text(
                if (audioUri == null) "Add replacement audio below first. The selected added audio will be transcribed on this device."
                else if (!naraModelReady) "Download the Nara SAR Burmese speech engine after installing the APK (about 450 MB)."
                else "Ready to transcribe the selected added audio offline. Review the generated timing and text before export.",
                color = Color(0xFFB9B1CC), fontSize = 11.sp
            )
            Spacer(Modifier.height(7.dp))
            if (!naraModelReady) {
                Button(
                    enabled = !naraDownloading,
                    onClick = {
                        naraDownloading = true
                        naraDownloadProgress = 0f
                        screenScope.launch {
                            runCatching {
                                naraSar.downloadModel { value -> naraDownloadProgress = value }
                            }.onSuccess {
                                naraModelReady = true
                                message = "Burmese subtitle engine downloaded. You can now generate subtitles from added audio."
                            }.onFailure { message = it.message ?: "Could not download the Burmese subtitle engine." }
                            naraDownloading = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(44.dp), shape = RoundedCornerShape(13.dp)
                ) { Text(if (naraDownloading) "Downloading ${(naraDownloadProgress * 100).roundToInt()}%" else "Download Burmese subtitle engine") }
            } else {
                Button(
                    enabled = audioUri != null && !naraTranscribing,
                    onClick = {
                        val selectedAudio = audioUri ?: return@Button
                        naraTranscribing = true
                        screenScope.launch {
                            runCatching { naraSar.transcribe(selectedAudio) }
                                .onSuccess { cues ->
                                    subtitleCues = cues
                                    subtitleColorHex = "#FFFFFF"
                                    subtitleScript = cues.joinToString("\n") { cue -> "[${formatSubtitleTime(cue.startMs)} - ${formatSubtitleTime(cue.endMs)}] ${cue.text}" }
                                    previewPositionMs = 0L
                                    subtitlePreviewRestartKey++
                                    message = "Generated ${cues.size} Burmese subtitle lines from the added audio. Please review the text before export."
                                }
                                .onFailure { message = it.message ?: "Could not transcribe the added audio." }
                            naraTranscribing = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(44.dp), shape = RoundedCornerShape(13.dp)
                ) { Text(if (naraTranscribing) "Generating Burmese subtitles…" else "Generate subtitles from added audio") }
            }
            Text("Powered by Nara SAR · Apache-2.0 · offline CPU inference", color = Color(0xFF85809A), fontSize = 10.sp, modifier = Modifier.padding(top = 6.dp))
        }
        if (subtitleCues.isNotEmpty()) {
            Text("${subtitleCues.size} subtitle lines loaded · edit the text above, then Apply again", color = Color(0xFFA7ACBC), fontSize = 11.sp)
            Text("Drag the subtitle text on the preview to move it. Adjust its size with the slider below.", color = Accent, fontSize = 11.sp)
            Text("Timing delay · ${"%+.1f".format(subtitleOffsetMs / 1000f)} sec", color = Color(0xFFBEC2D0), fontSize = 12.sp)
            Slider(value = subtitleOffsetMs, onValueChange = { subtitleOffsetMs = it }, valueRange = -5000f..5000f, steps = 39)
            Text("Subtitle size · ${subtitleFontSp.roundToInt()} sp", color = Color(0xFFBEC2D0), fontSize = 12.sp)
            Slider(value = subtitleFontSp, onValueChange = { subtitleFontSp = it }, valueRange = 8f..28f, steps = 19)
            Text("Subtitle text color", color = Color(0xFFBEC2D0), fontSize = 12.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                listOf("#FFFFFF", "#000000", "#FFFF00", "#00FFFF", "#FF0000", "#00FF00", "#FF00FF").forEach { hex ->
                    Box(Modifier.size(30.dp).clip(RoundedCornerShape(15.dp)).background(Color(AndroidColor.parseColor(hex)))
                    .border(1.dp, Color.White.copy(alpha = .45f), RoundedCornerShape(15.dp))
                    .then(if (hex == subtitleColorHex) Modifier.border(3.dp, Accent, RoundedCornerShape(15.dp)) else Modifier)
                        .clickable { subtitleColorHex = hex; prefs.edit().putString("subtitle_color", hex).apply() })
                }
            }
            Text("Horizontal position · ${(subtitlePositionX * 100).roundToInt()}%", color = Color(0xFFBEC2D0), fontSize = 12.sp)
            Slider(value = subtitlePositionX, onValueChange = { subtitlePositionX = it }, valueRange = .08f.. .92f, steps = 41)
            Text("Vertical position · ${(subtitlePositionY * 100).roundToInt()}% from top", color = Color(0xFFBEC2D0), fontSize = 12.sp)
            Slider(value = subtitlePositionY, onValueChange = { subtitlePositionY = it }, valueRange = .08f.. .92f, steps = 41)
            TextButton(onClick = { subtitleScript = ""; subtitleCues = emptyList(); subtitleOffsetMs = 0f }) { Text("Remove subtitles") }
        }
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
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Panel).padding(13.dp)) {
        SectionTitle("SAVED LOGO WATERMARK")
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(54.dp).clip(RoundedCornerShape(11.dp)).background(Color(0xFF292E3B)), contentAlignment = Alignment.Center) {
                val bmp = remember(activeLogo?.absolutePath) { activeLogo?.let { decodeLogoPreview(it)?.asImageBitmap() } }
                if (bmp != null) androidx.compose.foundation.Image(bmp, null, Modifier.fillMaxSize().padding(5.dp), contentScale = ContentScale.Fit)
                else Icon(Icons.Default.AddPhotoAlternate, null, tint = Color(0xFF858BA0))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(if (savedLogo == null) "Built-in China AI logo" else "Custom logo saved", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text(if (savedLogo == null) "Included in the APK · drag and resize in preview" else "Saved on this device · automatically used in previews and exports", color = Color(0xFFA7ACBC), fontSize = 11.sp)
            }
            if (savedLogo != null) IconButton(onClick = { savedLogo?.delete(); prefs.edit().remove("custom_logo_path").apply(); savedLogo = null }) { Icon(Icons.Default.Close, "Use built-in logo", tint = Color(0xFFCF9DA6)) }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, modifier = Modifier.fillMaxWidth().height(46.dp), shape = RoundedCornerShape(14.dp)) {
            Icon(Icons.Default.PhotoCamera, null); Spacer(Modifier.width(7.dp)); Text(if (savedLogo == null) "Choose another logo" else "Change Logo")
        }
        Text("PNG/JPG are still images. GIF logos animate and loop. Drag the logo in the preview to move; pinch with two fingers to resize.", color = Color(0xFF858BA0), fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
        if (activeLogo != null) TextButton(onClick = {
            logoPosition = LogoPosition()
            logoScale = 1f
            prefs.edit().putFloat("logo_x", logoPosition.x).putFloat("logo_y", logoPosition.y).putFloat("logo_scale", 1f).apply()
        }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Reset logo position") }
        if (activeLogo != null) {
            Text("Logo size · ${(logoScale * 100).roundToInt()}%", color = Color(0xFFBEC2D0), fontSize = 12.sp)
            Slider(value = logoScale, onValueChange = { logoScale = it; prefs.edit().putFloat("logo_scale", it).apply() }, valueRange = .4f..2.2f)
            Text("Logo left / right · ${(logoPosition.x * 100).roundToInt()}%", color = Color(0xFFBEC2D0), fontSize = 12.sp)
            Slider(value = logoPosition.x.coerceIn(-.30f, 1.30f), onValueChange = { value -> logoPosition = logoPosition.copy(x = value); prefs.edit().putFloat("logo_x", value).apply() }, valueRange = -.30f..1.30f)
            Text("Logo top / bottom · ${(logoPosition.y * 100).roundToInt()}%", color = Color(0xFFBEC2D0), fontSize = 12.sp)
            Slider(value = logoPosition.y.coerceIn(-.20f, 1.20f), onValueChange = { value -> logoPosition = logoPosition.copy(y = value); prefs.edit().putFloat("logo_y", value).apply() }, valueRange = -.20f..1.20f)
            Text("Snap logo to a corner", color = Color(0xFFA7ACBC), fontSize = 11.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("Top left" to LogoPosition(0f, 0f), "Top right" to LogoPosition(1f, 0f), "Bottom left" to LogoPosition(0f, 1f), "Bottom right" to LogoPosition(1f, 1f)).forEach { (label, target) ->
                    TextButton(onClick = { logoPosition = target; prefs.edit().putFloat("logo_x", target.x).putFloat("logo_y", target.y).apply() }, modifier = Modifier.weight(1f)) { Text(label, fontSize = 10.sp, maxLines = 1) }
                }
            }
        }
        if (savedLogo != null) TextButton(onClick = { savedLogo?.delete(); prefs.edit().remove("custom_logo_path").apply(); savedLogo = null }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Use built-in logo", color = Color(0xFFCF9DA6)) }
        }

        Spacer(Modifier.height(16.dp))
        Button(
            enabled = videoUri != null && !exporting,
            onClick = {
                val source = videoUri
                if (source == null) { message = "Choose a video first."; return@Button }
                runCatching {
                    val subtitleRenderScale = if (previewCanvasWidthPx > 0f) OUTPUT_WIDTH / previewCanvasWidthPx else 2f
                    val subtitleRenderFontPx = with(density) { subtitleFontSp.sp.toPx() } * subtitleRenderScale
                    val temp = File(context.cacheDir, "export-${UUID.randomUUID()}.mp4")
                    tempOutput = temp
                    val outputEffects = buildList {
                        add(ScaleAndRotateTransformation.Builder().setScale(if (mirror) -zoom else zoom, zoom).build())
                        add(Crop(-1f / zoom, 1f / zoom, -1f / zoom, 1f / zoom))
                        add(Presentation.createForWidthAndHeight(OUTPUT_WIDTH, OUTPUT_HEIGHT, Presentation.LAYOUT_SCALE_TO_FIT))
                        if (warmEnabled && warmAmount > 0f) add(OverlayEffect(listOf(BitmapOverlay.createStaticBitmapOverlay(makeWarmOverlay(warmAmount), fullFrameOverlaySettings()))))
                        if (blurEnabled) add(OverlayEffect(listOf(BitmapOverlay.createStaticBitmapOverlay(makeBlurCoverOverlay(blurRect, blurStrength), fullFrameOverlaySettings()))))
                        activeLogo?.takeIf { it.isFile }?.let { add(OverlayEffect(listOf(createLogoOverlay(it, logoPosition, logoScale)))) }
                        if (scrollingCaption.isNotBlank()) add(OverlayEffect(listOf(ScrollingCaptionOverlay(scrollingCaption))))
                        if (subtitleCues.isNotEmpty()) add(OverlayEffect(listOf(TimedSubtitleOverlay(
                            subtitleCues, subtitleOffsetMs.toLong(), subtitleRenderFontPx, subtitleColorHex, subtitlePositionX, subtitlePositionY
                        ))))
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
    message?.let { text -> AlertDialog(onDismissRequest = { message = null }, confirmButton = { TextButton(onClick = { message = null }) { Text("အိုကေ") } }, title = { Text(if (text.startsWith("Video exported")) "Export complete" else "အသိပေးချက်") }, text = { Text(text) } ) }
    if (showSavePresetDialog) AlertDialog(
        onDismissRequest = { showSavePresetDialog = false },
        title = { Text("တည်းဖြတ်မှုအပြင်အဆင်ကို သိမ်းရန်") },
        text = {
            Column {
                Text("အမည်ထည့်ပါ။ ရှိပြီးသားအမည်ကို ထပ်သိမ်းပါက အရင်အပြင်အဆင်ကို အစားထိုးပါမယ်။", color = Color(0xFFA7ACBC))
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(value = presetNameInput, onValueChange = { presetNameInput = it }, singleLine = true, label = { Text("အမည်") }, placeholder = { Text("ဥပမာ - အနွေးရောင်") })
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val name = presetNameInput.trim()
                if (name.isBlank()) {
                    showSavePresetDialog = false
                    message = "သိမ်းရန်အမည်တစ်ခု ထည့်ပါ။"
                } else {
                    val preset = EditorPreset(
                        mirror, zoom, warmEnabled, warmAmount, borderColorHex, muteOriginal, videoAudioVolume,
                        replacementAudioEnabled, replacementAudioVolume, blurEnabled, blurRect, blurStrength,
                        logoPosition, logoScale, scrollingCaption, subtitleScript, subtitleOffsetMs,
                        subtitleFontSp, subtitleColorHex, subtitlePositionX, subtitlePositionY
                    )
                    val updated = LinkedHashMap(editingPresets)
                    updated[name] = preset
                    showSavePresetDialog = false
                    if (saveEditingPresets(prefs, updated)) {
                        editingPresets = updated
                        selectedPresetName = name
                        message = "‘$name’ ကို သိမ်းပြီးပါပြီ။ နောက်တစ်ခါ အမည်ကို ရွေးပြီး အပြင်အဆင်ကို ပြန်သုံးနိုင်ပါတယ်။"
                    } else message = "အပြင်အဆင်ကို ဖုန်းထဲသို့ သိမ်း၍မရပါ။ ထပ်ကြိုးစားပါ။"
                }
            }) { Text("သိမ်းမည်") }
        },
        dismissButton = { TextButton(onClick = { showSavePresetDialog = false }) { Text("မလုပ်တော့ပါ") } }
    )
}

@Composable
private fun SectionTitle(text: String) { Text(text, color = Color(0xFFA7ACBC), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp, modifier = Modifier.padding(bottom = 5.dp)) }

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
            snapLegacyLogoEdge(position.x, minX),
            snapLegacyLogoEdge(position.y, minY)
        )
        val latestDisplayPosition = rememberUpdatedState(displayPosition)
        val latestMinX = rememberUpdatedState(minX)
        val latestMinY = rememberUpdatedState(minY)
        Box(
            Modifier.offset { IntOffset((parentWidth * (displayPosition.x - widthFraction / 2f)).roundToInt(), (parentHeight * (displayPosition.y - heightFraction / 2f)).roundToInt()) }
                .width(maxWidth * widthFraction).height(maxHeight * heightFraction)
                .pointerInput(parentWidth, parentHeight) {
                    detectTransformGestures { _, pan, zoomChange, _ ->
                        val current = latestDisplayPosition.value
                        val next = LogoPosition(
                            x = logoPositionFromCenter(current.x + pan.x / parentWidth, latestMinX.value),
                            y = logoPositionFromCenter(current.y + pan.y / parentHeight, latestMinY.value)
                        )
                        latestOnMove.value(next)
                        latestOnScale.value((latestScale.value * zoomChange).coerceIn(.4f, 2.2f))
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
    restartToZeroKey: Int,
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
    val lastHandledRestartKey = remember(player) { mutableIntStateOf(restartToZeroKey) }
    SideEffect {
        player.volume = if (muteVideoAudio) 0f else videoAudioVolume.coerceIn(0f, 1f)
        audioPlayer?.volume = if (replacementAudioEnabled) replacementAudioVolume.coerceIn(0f, 1f) else 0f
    }
    LaunchedEffect(player, audioPlayer, replacementAudioEnabled, restartToZeroKey) {
        if (lastHandledRestartKey.intValue != restartToZeroKey) {
            lastHandledRestartKey.intValue = restartToZeroKey
            player.pause()
            audioPlayer?.pause()
            for (attempt in 0 until 40) {
                if (player.playbackState == Player.STATE_READY || player.playbackState == Player.STATE_ENDED) break
                delay(50)
            }
            player.seekTo(0L)
            audioPlayer?.seekTo(0L)
            for (attempt in 0 until 20) {
                if (player.playbackState == Player.STATE_READY && player.currentPosition <= 100L) break
                delay(25)
            }
            latestOnPosition.value(0L)
        }
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
        modifier = modifier.background(Color.Black.copy(alpha = .68f)).basicMarquee(
            iterations = Int.MAX_VALUE,
            initialDelayMillis = 0,
            repeatDelayMillis = 0,
            velocity = 30.dp
        ).padding(horizontal = 8.dp, vertical = 5.dp),
        color = Color.White,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1
    )
}

@Composable
private fun InteractiveTimedSubtitle(
    text: String,
    fontSp: Float,
    textColorHex: String,
    positionX: Float,
    positionY: Float,
    modifier: Modifier = Modifier,
    onMove: (Float, Float) -> Unit
) {
    BoxWithConstraints(modifier) {
        val localDensity = LocalDensity.current
        val canvasWidthPx = with(localDensity) { maxWidth.toPx() }.coerceAtLeast(1f)
        val canvasHeightPx = with(LocalDensity.current) { maxHeight.toPx() }.coerceAtLeast(1f)
        val fontSizePx = with(localDensity) { fontSp.sp.toPx() }
        val outlineColor = Color(subtitleOutlineColor(textColorHex))
        val outlineBlurPx = with(localDensity) { 2.dp.toPx() }
        val displayText = remember(text, fontSizePx, canvasWidthPx) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = fontSizePx
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
            }
            val maxTextWidth = (canvasWidthPx * .78f).coerceAtLeast(1f)
            wrapSubtitleText(text, paint, maxTextWidth).joinToString("\n")
        }
        val currentX = rememberUpdatedState(positionX)
        val currentY = rememberUpdatedState(positionY)
        val moveCallback = rememberUpdatedState(onMove)

        Box(
            Modifier.align(Alignment.Center)
                .offset(x = maxWidth * (positionX - .5f), y = maxHeight * (positionY - .5f))
                .widthIn(max = maxWidth * .78f)
                .pointerInput(canvasWidthPx, canvasHeightPx) {
                    var dragX = 0f
                    var dragY = 0f
                    var startX = positionX
                    var startY = positionY
                    detectDragGestures(
                        onDragStart = {
                            dragX = 0f
                            dragY = 0f
                            startX = currentX.value
                            startY = currentY.value
                        }
                    ) { change, amount ->
                        change.consume()
                        dragX += amount.x
                        dragY += amount.y
                        moveCallback.value(
                            (startX + dragX / canvasWidthPx).coerceIn(.08f, .92f),
                            (startY + dragY / canvasHeightPx).coerceIn(.08f, .92f)
                        )
                    }
                }
        ) {
            Box(Modifier.clip(RoundedCornerShape(5.dp)).background(Color(0xFF3569B8)).padding(horizontal = 8.dp, vertical = 4.dp)) {
            Text(
                text = displayText,
                color = Color(AndroidColor.parseColor(textColorHex)),
                fontSize = fontSp.sp,
                fontWeight = FontWeight.Bold,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                lineHeight = (fontSp * 1.18f).sp,
                style = androidx.compose.ui.text.TextStyle(
                    shadow = androidx.compose.ui.graphics.Shadow(outlineColor, Offset.Zero, outlineBlurPx)
                ),
                maxLines = 8,
                softWrap = true
            )
            }
        }
    }
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
    private val message = "   $text   •   "
    private val durationUs = (((OUTPUT_WIDTH + textPaint.measureText(message)) / 90f) * 1_000_000L).roundToLong().coerceAtLeast(1L)

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        bitmap.eraseColor(AndroidColor.TRANSPARENT)
        val bandTop = OUTPUT_HEIGHT - 86f
        canvas.drawRect(0f, bandTop, OUTPUT_WIDTH.toFloat(), OUTPUT_HEIGHT.toFloat(), backgroundPaint)
        val textWidth = textPaint.measureText(message)
        val phase = (presentationTimeUs.coerceAtLeast(0L) % durationUs).toFloat() / durationUs
        canvas.drawText(message, OUTPUT_WIDTH - phase * (OUTPUT_WIDTH + textWidth), OUTPUT_HEIGHT - 29f, textPaint)
        return bitmap
    }

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings = fullFrameOverlaySettings()
}

private fun subtitleOutlineColor(textColorHex: String): Int {
    val color = AndroidColor.parseColor(textColorHex)
    val brightness = AndroidColor.red(color) * .299f + AndroidColor.green(color) * .587f + AndroidColor.blue(color) * .114f
    return if (brightness >= 145f) AndroidColor.BLACK else AndroidColor.WHITE
}

private class TimedSubtitleOverlay(
    private val cues: List<SubtitleCue>,
    private val offsetMs: Long,
    fontSizePx: Float,
    textColorHex: String,
    private val horizontalPosition: Float,
    private val verticalPosition: Float
) : BitmapOverlay() {
    private val bitmap = Bitmap.createBitmap(OUTPUT_WIDTH, OUTPUT_HEIGHT, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(bitmap)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.parseColor(textColorHex)
        textSize = fontSizePx.coerceAtLeast(1f)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
    }
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = subtitleOutlineColor(textColorHex)
        textSize = fontSizePx.coerceAtLeast(1f)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeWidth = (textSize * .09f).coerceAtLeast(1f)
    }
    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        bitmap.eraseColor(AndroidColor.TRANSPARENT)
        val timeMs = presentationTimeUs.coerceAtLeast(0L) / 1_000L
        val cue = activeSubtitleCue(cues, timeMs, offsetMs) ?: return bitmap
        val maxTextWidth = OUTPUT_WIDTH * .78f
        val lines = wrapSubtitleText(cue.text, paint, maxTextWidth)
        val lineHeight = paint.textSize * 1.18f
        val centerX = horizontalPosition.coerceIn(.08f, .92f) * OUTPUT_WIDTH
        val centerY = verticalPosition.coerceIn(.1f, .95f) * OUTPUT_HEIGHT
        val textBlockHeight = lines.size * lineHeight
        val top = centerY - textBlockHeight / 2f
        val metrics = paint.fontMetrics
        val firstBaseline = top + (lineHeight - (metrics.descent - metrics.ascent)) / 2f - metrics.ascent
        val widestLine = lines.maxOfOrNull { paint.measureText(it) } ?: 0f
        val background = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AndroidColor.rgb(53, 105, 184) }
        canvas.drawRoundRect(RectF(centerX - widestLine / 2f - 18f, top - 8f, centerX + widestLine / 2f + 18f, top + textBlockHeight + 8f), 10f, 10f, background)
        lines.forEachIndexed { index, line ->
            val baseline = firstBaseline + index * lineHeight
            canvas.drawText(line, centerX, baseline, outlinePaint)
            canvas.drawText(line, centerX, baseline, paint)
        }
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
        if (paint.measureText(candidate) <= maxWidth) {
            current = candidate
            continue
        }
        if (current.isNotEmpty()) { lines += current; current = "" }
        var piece = ""
        for (character in word) {
            val next = piece + character
            if (piece.isNotEmpty() && paint.measureText(next) > maxWidth) {
                lines += piece
                piece = character.toString()
            } else piece = next
        }
        current = piece
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
    val centerX = snapLegacyLogoEdge(position.x, width / (2f * OUTPUT_WIDTH))
    val centerY = snapLegacyLogoEdge(position.y, height / (2f * OUTPUT_HEIGHT))
    val left = centerX * OUTPUT_WIDTH - width / 2f
    val top = centerY * OUTPUT_HEIGHT - height / 2f
    canvas.drawBitmap(source, null, RectF(left, top, left + width, top + height), Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
    source.recycle()
    return output
}

private fun snapLegacyLogoEdge(coordinate: Float, visibleHalf: Float): Float {
    val half = visibleHalf.coerceIn(0f, .49f)
    val safeCoordinate = coordinate.coerceIn(-half, 1f + half)
    return when {
        safeCoordinate < 0f -> half + safeCoordinate
        safeCoordinate > 1f -> 1f - half + safeCoordinate - 1f
        else -> half + safeCoordinate * (1f - 2f * half)
    }
}

private fun logoPositionFromCenter(center: Float, visibleHalf: Float): Float {
    val half = visibleHalf.coerceIn(0f, .49f)
    return when {
        center < half -> (center - half).coerceAtLeast(-half)
        center > 1f - half -> (1f + center - (1f - half)).coerceAtMost(1f + half)
        else -> ((center - half) / (1f - 2f * half).coerceAtLeast(.02f)).coerceIn(0f, 1f)
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
        // Media3 may cache the overlay texture by Bitmap identity. Return a new snapshot
        // at every presentation timestamp so each GIF frame is uploaded and rendered.
        return frameBitmap.copy(Bitmap.Config.ARGB_8888, false)
    }

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
        val centerX = snapLegacyLogoEdge(position.x, frameWidth / (2f * OUTPUT_WIDTH))
        val centerY = snapLegacyLogoEdge(position.y, frameHeight / (2f * OUTPUT_HEIGHT))
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

private fun copyBundledLogo(context: Context): File {
    val target = File(context.filesDir, "bundled-china-ai-logo.png")
    if (!target.isFile || target.length() == 0L) {
        context.resources.openRawResource(R.raw.bundled_china_ai_logo).use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
    }
    check(target.isFile && target.length() > 0L) { "The built-in logo could not be loaded" }
    return target
}

private fun loadEditingPresets(prefs: android.content.SharedPreferences): LinkedHashMap<String, EditorPreset> {
    val saved = LinkedHashMap<String, EditorPreset>()
    val raw = prefs.getString("editing_presets", null) ?: return saved
    runCatching {
        val array = JSONArray(raw)
        for (index in 0 until array.length()) runCatching {
            val entry = array.getJSONObject(index)
            val name = entry.optString("name", "").trim()
            val settings = entry.optJSONObject("settings") ?: return@runCatching
            if (name.isNotEmpty()) saved[name] = editorPresetFromJson(settings)
        }
    }
    return saved
}

private fun saveEditingPresets(prefs: android.content.SharedPreferences, presets: Map<String, EditorPreset>): Boolean {
    val array = JSONArray()
    presets.forEach { (name, preset) ->
        array.put(JSONObject().put("name", name).put("settings", editorPresetToJson(preset)))
    }
    return prefs.edit().putString("editing_presets", array.toString()).commit()
}

private fun editorPresetToJson(preset: EditorPreset): JSONObject = JSONObject()
    .put("mirror", preset.mirror)
    .put("zoom", preset.zoom)
    .put("warmEnabled", preset.warmEnabled)
    .put("warmAmount", preset.warmAmount)
    .put("borderColorHex", preset.borderColorHex)
    .put("muteOriginal", preset.muteOriginal)
    .put("videoAudioVolume", preset.videoAudioVolume)
    .put("replacementAudioEnabled", preset.replacementAudioEnabled)
    .put("replacementAudioVolume", preset.replacementAudioVolume)
    .put("blurEnabled", preset.blurEnabled)
    .put("blurX", preset.blurRect.x)
    .put("blurY", preset.blurRect.y)
    .put("blurW", preset.blurRect.w)
    .put("blurH", preset.blurRect.h)
    .put("blurStrength", preset.blurStrength)
    .put("logoX", preset.logoPosition.x)
    .put("logoY", preset.logoPosition.y)
    .put("logoScale", preset.logoScale)
    .put("scrollingCaption", preset.scrollingCaption)
    .put("subtitleScript", preset.subtitleScript)
    .put("subtitleOffsetMs", preset.subtitleOffsetMs)
    .put("subtitleFontSp", preset.subtitleFontSp)
    .put("subtitleColorHex", preset.subtitleColorHex)
    .put("subtitlePositionX", preset.subtitlePositionX)
    .put("subtitlePositionY", preset.subtitlePositionY)

private fun editorPresetFromJson(json: JSONObject): EditorPreset = EditorPreset(
    mirror = json.optBoolean("mirror", false),
    zoom = json.optDouble("zoom", 1.08).toFloat().coerceIn(1f, 1.18f),
    warmEnabled = json.optBoolean("warmEnabled", true),
    warmAmount = json.optDouble("warmAmount", .3).toFloat().coerceIn(0f, 1f),
    borderColorHex = json.optString("borderColorHex", "#9C7BFF"),
    muteOriginal = json.optBoolean("muteOriginal", true),
    videoAudioVolume = json.optDouble("videoAudioVolume", .8).toFloat().coerceIn(0f, 1f),
    replacementAudioEnabled = json.optBoolean("replacementAudioEnabled", true),
    replacementAudioVolume = json.optDouble("replacementAudioVolume", .9).toFloat().coerceIn(0f, 1f),
    blurEnabled = json.optBoolean("blurEnabled", true),
    blurRect = BlurRect(
        json.optDouble("blurX", .22).toFloat().coerceIn(0f, 1f),
        json.optDouble("blurY", .70).toFloat().coerceIn(0f, 1f),
        json.optDouble("blurW", .56).toFloat().coerceIn(.16f, 1f),
        json.optDouble("blurH", .11).toFloat().coerceIn(.06f, .35f)
    ),
    blurStrength = json.optDouble("blurStrength", .92).toFloat().coerceIn(.15f, 1f),
    logoPosition = LogoPosition(json.optDouble("logoX", .86).toFloat(), json.optDouble("logoY", .92).toFloat()),
    logoScale = json.optDouble("logoScale", 1.0).toFloat().coerceIn(.4f, 2.2f),
    scrollingCaption = json.optString("scrollingCaption", ""),
    subtitleScript = json.optString("subtitleScript", ""),
    subtitleOffsetMs = json.optDouble("subtitleOffsetMs", 0.0).toFloat().coerceIn(-5000f, 5000f),
    subtitleFontSp = json.optDouble("subtitleFontSp", 12.0).toFloat().coerceIn(8f, 28f),
    subtitleColorHex = json.optString("subtitleColorHex", "#FFFFFF"),
    subtitlePositionX = json.optDouble("subtitlePositionX", .5).toFloat().coerceIn(.08f, .92f),
    subtitlePositionY = json.optDouble("subtitlePositionY", .83).toFloat().coerceIn(.08f, .92f)
)

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

private fun parseSubtitleScript(raw: String): List<SubtitleCue> {
    val normalized = raw.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
    if (normalized.lineSequence().any { "-->" in it }) return parseSrtText(normalized)
    val linePattern = Regex("^\\s*\\[([^\\]]+)\\]\\s*(.+?)\\s*$")
    val cues = ArrayList<SubtitleCue>()
    normalized.lineSequence().forEachIndexed { index, line ->
        if (line.isBlank()) return@forEachIndexed
        val match = linePattern.matchEntire(line)
            ?: error("Line ${index + 1}: expected [start - end] subtitle text")
        val range = match.groupValues[1].split('-', limit = 2)
        if (range.size != 2) error("Line ${index + 1}: use [start - end] before the subtitle text")
        val start = parseSubtitleTime(range[0].trim())
        val end = parseSubtitleTime(range[1].trim())
        if (end <= start) error("Line ${index + 1}: end time must be later than start time")
        cues += SubtitleCue(start, end, match.groupValues[2].trim())
    }
    require(cues.isNotEmpty()) { "Add at least one line in [start - end] subtitle text format." }
    require(cues.size <= 20_000) { "Subtitle text contains too many lines." }
    return cues.sortedBy { it.startMs }
}

private fun parseSubtitleTime(value: String): Long {
    val parts = value.trim().split(':')
    require(parts.size in 1..3) { "Invalid time '$value'. Use mm:ss, hh:mm:ss, or seconds." }
    val secondsPart = parts.last()
    val secondsWhole = secondsPart.substringBefore('.').toLongOrNull()
        ?: error("Invalid seconds in '$value'")
    val fraction = secondsPart.substringAfter('.', "").take(3).padEnd(3, '0').toLongOrNull() ?: 0L
    require(secondsWhole < 60 || parts.size == 1) { "Seconds must be less than 60 in '$value'." }
    val preceding = parts.dropLast(1).map { it.toLongOrNull() ?: error("Invalid time '$value'") }
    if (parts.size == 3) require(preceding.last() < 60) { "Minutes must be less than 60 in '$value'." }
    val wholeSeconds = when (parts.size) {
        1 -> secondsWhole
        2 -> preceding[0] * 60 + secondsWhole
        else -> preceding[0] * 3600 + preceding[1] * 60 + secondsWhole
    }
    return wholeSeconds * 1000 + fraction
}

private fun parseSrtText(raw: String): List<SubtitleCue> {
    val blocks = raw.replace("\r\n", "\n").replace('\r', '\n').split(Regex("\\n\\s*\\n"))
    val timestamp = Regex("(\\d{1,2}:\\d{2}:\\d{2}[,\\.]\\d{1,3})\\s*-->\\s*(\\d{1,2}:\\d{2}:\\d{2}[,\\.]\\d{1,3})")
    val cues = blocks.mapNotNull { block ->
        val lines = block.lines().map(String::trim).filter(String::isNotEmpty)
        val lineIndex = lines.indexOfFirst { timestamp.containsMatchIn(it) }
        if (lineIndex < 0) return@mapNotNull null
        val match = timestamp.find(lines[lineIndex]) ?: return@mapNotNull null
        val start = parseSubtitleTime(match.groupValues[1].replace(',', '.'))
        val end = parseSubtitleTime(match.groupValues[2].replace(',', '.'))
        val text = lines.drop(lineIndex + 1).joinToString(" ").replace(Regex("<[^>]*>"), "").trim()
        if (text.isBlank() || end <= start) null else SubtitleCue(start, end, text)
    }.sortedBy { it.startMs }
    require(cues.isNotEmpty()) { "No valid SRT subtitle lines were found." }
    require(cues.size <= 20_000) { "Subtitle file contains too many lines." }
    return cues
}

private fun activeSubtitleCue(cues: List<SubtitleCue>, timeMs: Long, offsetMs: Long): SubtitleCue? =
    cues.firstOrNull { timeMs >= it.startMs + offsetMs && timeMs < it.endMs + offsetMs }

private fun formatSubtitleTime(milliseconds: Long): String {
    val safe = milliseconds.coerceAtLeast(0L)
    val minutes = safe / 60_000L
    val seconds = (safe % 60_000L) / 1_000L
    val millis = safe % 1_000L
    return "%02d:%02d.%01d".format(minutes, seconds, millis / 100L)
}
