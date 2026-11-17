package com.example.videoediting

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.graphics.BitmapFactory
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

private val Background = Color(0xFF10131B)
private val Panel = Color(0xFF1A1E29)
private val Accent = Color(0xFF9C7BFF)

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
    var message by remember { mutableStateOf<String?>(null) }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            runCatching { saveLogoPermanently(context, uri).also { prefs.edit().putString("custom_logo_path", it.absolutePath).apply(); savedLogo = it } }
                .onFailure { message = "Couldn't save that image. Please choose a PNG or JPG." }
        }
    }
    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> videoUri = uri }

    Column(Modifier.fillMaxSize().background(Background).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 18.dp)) {
        Text("VIDEO STUDIO", color = Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
        Spacer(Modifier.height(8.dp))
        Text("Make it yours.", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Text("Your saved logo stays ready for every edit.", color = Color(0xFFA7ACBC), fontSize = 14.sp)
        Spacer(Modifier.height(22.dp))

        Box(Modifier.fillMaxWidth().aspectRatio(9f / 16f).clip(RoundedCornerShape(22.dp)).background(Color(0xFF08090D)).border(1.dp, Color(0xFF303443), RoundedCornerShape(22.dp))) {
            if (videoUri != null) VideoPreview(videoUri!!, Modifier.fillMaxSize())
            if (videoUri == null) Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.Movie, null, tint = Color(0xFF73798C), modifier = Modifier.size(44.dp))
                Spacer(Modifier.height(12.dp)); Text("Your video preview", color = Color(0xFFA7ACBC))
            }
            savedLogo?.let { file ->
                val bitmap = remember(file.absolutePath) { decodeLogoPreview(file)?.asImageBitmap() }
                bitmap?.let {
                    BoxWithConstraints(Modifier.align(Alignment.BottomEnd).padding(14.dp)) {
                        androidx.compose.foundation.Image(
                            it, null,
                            Modifier.width(maxWidth * 0.22f).height(maxHeight * 0.12f),
                            contentScale = ContentScale.Fit
                        )
                    }
                }
            }
            if (videoUri == null) Text("LOGO PREVIEW", Modifier.align(Alignment.BottomStart).padding(14.dp), color = Color.White.copy(alpha = .5f), fontSize = 10.sp, letterSpacing = 1.sp)
        }
        Spacer(Modifier.height(18.dp))
        Button(onClick = { videoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) }, modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(16.dp)) {
            Icon(Icons.Default.Movie, null); Spacer(Modifier.width(9.dp)); Text(if (videoUri == null) "Choose a video" else "Change video", fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(20.dp))
        Text("YOUR WATERMARK", color = Color(0xFFA7ACBC), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Panel).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(58.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF292E3B)), contentAlignment = Alignment.Center) {
                val bmp = remember(savedLogo?.absolutePath) { savedLogo?.let { decodeLogoPreview(it)?.asImageBitmap() } }
                if (bmp != null) androidx.compose.foundation.Image(bmp, null, Modifier.fillMaxSize().padding(5.dp), contentScale = ContentScale.Fit)
                else Icon(Icons.Default.AddPhotoAlternate, null, tint = Color(0xFF858BA0))
            }
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text(if (savedLogo == null) "No logo selected" else "Custom logo saved", color = Color.White, fontWeight = FontWeight.SemiBold)
                Text(if (savedLogo == null) "Choose a PNG or JPG image" else "Applied bottom-right on preview", color = Color(0xFFA7ACBC), fontSize = 12.sp)
            }
            if (savedLogo != null) IconButton(onClick = {
                savedLogo?.delete(); prefs.edit().remove("custom_logo_path").apply(); savedLogo = null
            }) { Icon(Icons.Default.Close, "Remove Logo", tint = Color(0xFFCF9DA6)) }
        }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(onClick = { imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(15.dp)) {
            Icon(Icons.Default.PhotoCamera, null); Spacer(Modifier.width(8.dp)); Text(if (savedLogo == null) "Choose Logo" else "Change Logo")
        }
        if (savedLogo != null) TextButton(onClick = { savedLogo?.delete(); prefs.edit().remove("custom_logo_path").apply(); savedLogo = null }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Remove Logo", color = Color(0xFFCF9DA6)) }
        Spacer(Modifier.height(12.dp))
        Text("Logo image is stored privately on this device and automatically restored when you reopen the app.", color = Color(0xFF858BA0), fontSize = 12.sp, lineHeight = 18.sp)
        Spacer(Modifier.height(22.dp))
        if (videoUri != null) Text("Video loaded. Logo preview is positioned at the bottom-right with safe edge padding.", color = Color(0xFFB8BFD0), fontSize = 13.sp)
        else Text("Pick a video to preview it with your watermark.", color = Color(0xFF858BA0), fontSize = 13.sp)
        Spacer(Modifier.height(24.dp))
    }
    message?.let { text -> AlertDialog(onDismissRequest = { message = null }, confirmButton = { TextButton(onClick = { message = null }) { Text("OK") } }, title = { Text("Logo not saved") }, text = { Text(text) }) }
}

@Composable
private fun VideoPreview(uri: Uri, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val player = remember(uri) { ExoPlayer.Builder(context).build().apply { setMediaItem(MediaItem.fromUri(uri)); prepare(); playWhenReady = false } }
    DisposableEffect(player) { onDispose { player.release() } }
    AndroidView(modifier = modifier, factory = { ctx -> PlayerView(ctx).apply { this.player = player; useController = true; resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT } }, update = { it.player = player })
}

private fun readValidLogo(prefs: android.content.SharedPreferences): File? {
    val path = prefs.getString("custom_logo_path", null) ?: return null
    return File(path).takeIf { it.isFile && it.length() > 0 }
}

private fun decodeLogoPreview(file: File): android.graphics.Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / sample > 640 || bounds.outHeight / sample > 640) sample *= 2
    return BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
}

private fun saveLogoPermanently(context: Context, uri: Uri): File {
    val mime = context.contentResolver.getType(uri)?.lowercase()
    val extension = when (mime) { "image/png" -> ".png"; "image/jpeg", "image/jpg" -> ".jpg"; else -> {
        val name = context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        when (name?.substringAfterLast('.', "")?.lowercase()) {
            "png" -> ".png"
            "jpg", "jpeg" -> ".jpg"
            else -> error("Choose a PNG or JPG image")
        }
    } }
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
