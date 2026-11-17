@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.example.videoediting

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.OverlaySettings
import androidx.media3.effect.Presentation
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private val Background = Color(0xFF10131B)
private val Panel = Color(0xFF1A1E29)
private val Accent = Color(0xFF9C7BFF)
private data class BlurRect(var x: Float = .08f, var y: Float = .70f, var w: Float = .84f, var h: Float = .11f)

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
    var videoUri by remember { mutableStateOf<Uri?>(null) }
    var audioUri by remember { mutableStateOf<Uri?>(null) }
    var mirror by remember { mutableStateOf(false) }
    var zoom by remember { mutableFloatStateOf(1.08f) }
    var warmEnabled by remember { mutableStateOf(true) }
    var warmAmount by remember { mutableFloatStateOf(.30f) }
    var muteOriginal by remember { mutableStateOf(true) }
    var blurEnabled by remember { mutableStateOf(true) }
    var blurRect by remember { mutableStateOf(BlurRect()) }
    var message by remember { mutableStateOf<String?>(null) }
    var exporting by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var activeTransformer by remember { mutableStateOf<Transformer?>(null) }
    var tempOutput by remember { mutableStateOf<File?>(null) }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) runCatching {
            saveLogoPermanently(context, uri).also { prefs.edit().putString("custom_logo_path", it.absolutePath).apply(); savedLogo = it }
        }.onFailure { message = it.message ?: "Couldn't save that image. Choose a PNG or JPG." }
    }
    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) { videoUri = uri; blurRect = BlurRect() }
    }
    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> audioUri = uri }

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

    Column(Modifier.fillMaxSize().background(Background).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 18.dp)) {
        Text("VIDEO STUDIO", color = Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
        Spacer(Modifier.height(7.dp))
        Text("Make it yours.", color = Color.White, fontSize = 29.sp, fontWeight = FontWeight.Bold)
        Text("Edit in 9:16, preview, then export to your gallery.", color = Color(0xFFA7ACBC), fontSize = 13.sp)
        Spacer(Modifier.height(18.dp))

        Box(Modifier.fillMaxWidth().aspectRatio(9f / 16f).clip(RoundedCornerShape(22.dp)).background(Color(0xFF08090D)).border(1.dp, Color(0xFF303443), RoundedCornerShape(22.dp))) {
            if (videoUri != null) VideoPreview(videoUri!!, mirror, zoom, Modifier.fillMaxSize())
            if (videoUri == null) Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.Movie, null, tint = Color(0xFF73798C), modifier = Modifier.size(44.dp))
                Spacer(Modifier.height(10.dp)); Text("Your 9:16 video preview", color = Color(0xFFA7ACBC))
            }
            if (warmEnabled && videoUri != null) {
                Box(Modifier.fillMaxSize().background(Color(0xFFFF9F4C).copy(alpha = warmAmount * .22f)))
            }
            if (blurEnabled && videoUri != null) InteractiveBlurBox(blurRect, Modifier.fillMaxSize(), onChange = { blurRect = it })
            savedLogo?.let { file ->
                val bitmap = remember(file.absolutePath) { decodeLogoPreview(file)?.asImageBitmap() }
                bitmap?.let {
                    BoxWithConstraints(Modifier.align(Alignment.BottomEnd).padding(14.dp)) {
                        androidx.compose.foundation.Image(it, null, Modifier.width(maxWidth * .22f).height(maxHeight * .12f), contentScale = ContentScale.Fit)
                    }
                }
            }
            if (warmEnabled && videoUri != null) Text("WARM ${ (warmAmount * 100).roundToInt()}%", Modifier.align(Alignment.TopStart).padding(9.dp).clip(RoundedCornerShape(7.dp)).background(Color.Black.copy(alpha = .55f)).padding(horizontal = 8.dp, vertical = 5.dp), color = Color.White, fontSize = 9.sp)
        }
        Spacer(Modifier.height(12.dp))
        Button(onClick = { videoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) }, modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(15.dp)) {
            Icon(Icons.Default.Movie, null); Spacer(Modifier.width(8.dp)); Text(if (videoUri == null) "Choose a video" else "Change video", fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(17.dp))

        SectionTitle("VIDEO")
        SettingSwitch("Mirror horizontally", "Flip the frame left-to-right", mirror) { mirror = it }
        Text("Slight zoom · ${"%.2f".format(zoom)}×", color = Color.White, fontWeight = FontWeight.Medium, fontSize = 14.sp)
        Slider(value = zoom, onValueChange = { zoom = it }, valueRange = 1f..1.18f)
        SettingSwitch("Rome-inspired warm look", "Subtle warm tint and contrast", warmEnabled) { warmEnabled = it }
        if (warmEnabled) {
            Text("Filter intensity · ${(warmAmount * 100).roundToInt()}%", color = Color(0xFFBEC2D0), fontSize = 12.sp)
            Slider(value = warmAmount, onValueChange = { warmAmount = it }, valueRange = 0f..1f)
        }
        Spacer(Modifier.height(10.dp))
        SectionTitle("SUBTITLE COVER")
        SettingSwitch("Show adjustable blur box", "Drag the panel; pull its lower-right handle to resize", blurEnabled) { blurEnabled = it }
        Text("Box location and size are normalized to the 9:16 output frame.", color = Color(0xFF858BA0), fontSize = 12.sp)
        Spacer(Modifier.height(10.dp))

        SectionTitle("AUDIO")
        SettingSwitch("Mute original audio", "Recommended when replacing the soundtrack", muteOriginal) { muteOriginal = it }
        OutlinedButton(onClick = { audioPicker.launch(arrayOf("audio/mpeg", "audio/mp4", "audio/wav", "audio/x-wav", "audio/*")) }, modifier = Modifier.fillMaxWidth().height(46.dp), shape = RoundedCornerShape(14.dp)) {
            Text(if (audioUri == null) "Add replacement audio" else "Change replacement audio")
        }
        if (audioUri != null) Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Replacement audio selected (loops to video length)", Modifier.weight(1f), color = Color(0xFF9EA5B6), fontSize = 12.sp)
            TextButton(onClick = { audioUri = null }) { Text("Remove") }
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
                Text("Bottom-right · saved on device", color = Color(0xFFA7ACBC), fontSize = 11.sp)
            }
            if (savedLogo != null) IconButton(onClick = { savedLogo?.delete(); prefs.edit().remove("custom_logo_path").apply(); savedLogo = null }) { Icon(Icons.Default.Close, "Remove Logo", tint = Color(0xFFCF9DA6)) }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, modifier = Modifier.fillMaxWidth().height(46.dp), shape = RoundedCornerShape(14.dp)) {
            Icon(Icons.Default.PhotoCamera, null); Spacer(Modifier.width(7.dp)); Text(if (savedLogo == null) "Choose Logo" else "Change Logo")
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
                        add(Presentation.createForWidthAndHeight(1080, 1920, Presentation.LAYOUT_SCALE_TO_FIT))
                        add(ScaleAndRotateTransformation.Builder().setScale(if (mirror) -zoom else zoom, zoom).build())
                        if (warmEnabled && warmAmount > 0f) add(OverlayEffect(listOf(BitmapOverlay.createStaticBitmapOverlay(makeWarmOverlay(warmAmount), fullFrameOverlaySettings()))))
                        savedLogo?.takeIf { it.isFile }?.let { add(OverlayEffect(listOf(BitmapOverlay.createStaticBitmapOverlay(makeLogoOverlay(it), fullFrameOverlaySettings())))) }
                        if (blurEnabled) add(OverlayEffect(listOf(BitmapOverlay.createStaticBitmapOverlay(makeBlurCoverOverlay(blurRect), fullFrameOverlaySettings()))))
                    }
                    val videoEdited = EditedMediaItem.Builder(MediaItem.fromUri(source))
                        .setRemoveAudio(muteOriginal || audioUri != null)
                        .setEffects(Effects(emptyList(), outputEffects)).build()
                    val videoSequence = EditedMediaItemSequence.Builder(videoEdited).build()
                    val composition = if (audioUri != null) {
                        val audioEdited = EditedMediaItem.Builder(MediaItem.fromUri(audioUri!!)).setRemoveVideo(true).build()
                        Composition.Builder(videoSequence, EditedMediaItemSequence.Builder(audioEdited).setIsLooping(true).build()).build()
                    } else Composition.Builder(videoSequence).build()
                    progress = 0f
                    exporting = true
                    val transformer = Transformer.Builder(context).addListener(object : Transformer.Listener {
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
        Text("Output: 1080 × 1920 MP4 · Movies/VideoEditing", color = Color(0xFF858BA0), fontSize = 11.sp)
        Spacer(Modifier.height(24.dp))
    }
    message?.let { text -> AlertDialog(onDismissRequest = { message = null }, confirmButton = { TextButton(onClick = { message = null }) { Text("OK") } }, title = { Text(if (text.startsWith("Video exported")) "Export complete" else "Notice") }, text = { Text(text) }) }
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
private fun InteractiveBlurBox(rect: BlurRect, modifier: Modifier, onChange: (BlurRect) -> Unit) {
    BoxWithConstraints(modifier) {
        val parentWidth = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val parentHeight = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        val boxWidth = maxWidth * rect.w
        val boxHeight = maxHeight * rect.h
        Box(
            Modifier.offset { IntOffset((parentWidth * rect.x).roundToInt(), (parentHeight * rect.y).roundToInt()) }
                .size(boxWidth, boxHeight).clip(RoundedCornerShape(5.dp))
                .background(Color(0x88444C60)).border(1.dp, Color(0xFFE2D8FF), RoundedCornerShape(5.dp))
                .pointerInput(rect, parentWidth, parentHeight) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        onChange(rect.copy(x = (rect.x + drag.x / parentWidth).coerceIn(0f, 1f - rect.w), y = (rect.y + drag.y / parentHeight).coerceIn(0f, 1f - rect.h)))
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Text("BLUR / SUBTITLE COVER", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = .7.sp)
            Box(Modifier.align(Alignment.BottomEnd).size(24.dp).background(Accent.copy(alpha = .9f), RoundedCornerShape(topStart = 7.dp)).pointerInput(rect, parentWidth, parentHeight) {
                detectDragGestures { change, drag ->
                    change.consume()
                    val newW = (rect.w + drag.x / parentWidth).coerceIn(.16f, .92f - rect.x)
                    val newH = (rect.h + drag.y / parentHeight).coerceIn(.06f, .3f)
                    onChange(rect.copy(w = newW, h = newH, y = rect.y.coerceAtMost(1f - newH)))
                }
            }, contentAlignment = Alignment.Center) { Text("↘", color = Color.White, fontSize = 15.sp) }
        }
    }
}

@Composable
private fun VideoPreview(uri: Uri, mirror: Boolean, zoom: Float, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val player = remember(uri) { ExoPlayer.Builder(context).build().apply { setMediaItem(MediaItem.fromUri(uri)); prepare(); playWhenReady = false } }
    DisposableEffect(player) { onDispose { player.release() } }
    AndroidView(
        modifier = modifier,
        factory = { ctx -> (LayoutInflater.from(ctx).inflate(R.layout.preview_player, android.widget.FrameLayout(ctx), false) as PlayerView).apply { this.player = player; scaleX = if (mirror) -zoom else zoom; scaleY = zoom } },
        update = { it.player = player; it.scaleX = if (mirror) -zoom else zoom; it.scaleY = zoom }
    )
}

private fun fullFrameOverlaySettings(): OverlaySettings = OverlaySettings.Builder().setBackgroundFrameAnchor(0f, 0f).setOverlayFrameAnchor(0f, 0f).setScale(1f, 1f).build()

private fun makeWarmOverlay(intensity: Float): Bitmap {
    val bitmap = Bitmap.createBitmap(1080, 1920, Bitmap.Config.ARGB_8888)
    Canvas(bitmap).drawColor(AndroidColor.argb((intensity.coerceIn(0f, 1f) * 62).roundToInt(), 255, 159, 76))
    return bitmap
}

private fun makeLogoOverlay(file: File): Bitmap {
    val output = Bitmap.createBitmap(1080, 1920, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(output)
    val source = BitmapFactory.decodeFile(file.absolutePath) ?: return output
    val maxWidth = 205f; val maxHeight = 150f
    val factor = minOf(maxWidth / source.width, maxHeight / source.height, 1f)
    val width = source.width * factor; val height = source.height * factor
    val left = 1080f - width - 48f; val top = 1920f - height - 54f
    canvas.drawBitmap(source, null, RectF(left, top, left + width, top + height), Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
    source.recycle()
    return output
}

private fun makeBlurCoverOverlay(rect: BlurRect): Bitmap {
    val output = Bitmap.createBitmap(1080, 1920, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(output)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AndroidColor.argb(185, 216, 221, 233) }
    val left = rect.x.coerceIn(0f, 1f) * 1080f; val top = rect.y.coerceIn(0f, 1f) * 1920f
    canvas.drawRoundRect(RectF(left, top, left + rect.w.coerceIn(.05f, 1f) * 1080f, top + rect.h.coerceIn(.03f, 1f) * 1920f), 18f, 18f, paint)
    return output
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
        else -> {
            val name = context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            when (name?.substringAfterLast('.', "")?.lowercase()) { "png" -> ".png"; "jpg", "jpeg" -> ".jpg"; else -> error("Choose a PNG or JPG image") }
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
