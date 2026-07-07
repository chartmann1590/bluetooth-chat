package com.charles.meshtalk.app.ui

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.charles.meshtalk.app.data.MessageEntity
import com.charles.meshtalk.app.media.FilePrep
import com.charles.meshtalk.app.media.ImageCompressor
import com.charles.meshtalk.app.media.LocationFetcher
import com.charles.meshtalk.app.media.MediaPrepResult
import com.charles.meshtalk.app.media.OfflineMapCache
import com.charles.meshtalk.app.repository.MeshRepository
import com.charles.meshtalk.app.ui.theme.SignalGreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Renders a message's body: plain text, an inline decoded image, a file chip, or a location card.
 * [onLongPress], when provided, fires on a long-press anywhere on the content — needed because a
 * photo has its own tap target (to view full screen) that would otherwise swallow a long-press
 * meant for the surrounding message's action sheet before it ever reaches an outer long-press
 * listener. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageContentBody(message: MessageEntity, onLongPress: (() -> Unit)? = null) {
    when (message.contentType) {
        "IMAGE" -> {
            val bytes = message.mediaBytes
            val bitmap = remember(message.id) {
                bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            }
            var showFullScreen by remember { mutableStateOf(false) }
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Photo",
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .heightIn(max = 240.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .combinedClickable(
                            onClick = { showFullScreen = true },
                            onLongClick = { onLongPress?.invoke() }
                        )
                )
                if (showFullScreen) {
                    FullScreenImageDialog(bitmap = bitmap, onDismiss = { showFullScreen = false })
                }
            } else {
                Text("[photo unavailable]", modifier = Modifier.padding(top = 2.dp))
            }
        }
        "FILE" -> FileChip(message)
        "LOCATION" -> LocationCard(message)
        else -> Text(message.body, modifier = Modifier.padding(top = 2.dp))
    }
}

/** A full-screen, tap-anywhere-to-dismiss viewer for a message's photo — same for every message
 * bubble, in both the public feed and DM threads, and on every device (it's just decoding the
 * same mesh-delivered bytes that are already stored locally). */
@Composable
fun FullScreenImageDialog(bitmap: android.graphics.Bitmap, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center
        ) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Photo, full screen",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(16.dp)
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)
            ) {
                Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White)
            }
        }
    }
}

@Composable
private fun LocationCard(message: MessageEntity) {
    val context = LocalContext.current
    val lat = message.latitude
    val lng = message.longitude
    if (lat == null || lng == null) {
        Text("[location unavailable]", modifier = Modifier.padding(top = 2.dp))
        return
    }
    val bytes = message.mediaBytes
    val bitmap = remember(message.id) { bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } }

    // Fixed dark card + light text regardless of the surrounding bubble color (sent bubbles are
    // bright green, incoming ones are dark) so this nested card stays legible either way.
    Surface(
        color = Color.Black.copy(alpha = 0.25f),
        contentColor = Color(0xFFECECEC),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.padding(top = 4.dp).widthIn(max = 280.dp),
        onClick = { openInMaps(context, lat, lng) }
    ) {
        Column {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Map",
                    modifier = Modifier.heightIn(max = 180.dp)
                )
            }
            Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.LocationOn, contentDescription = null, tint = SignalGreen)
                Column(modifier = Modifier.padding(start = 8.dp)) {
                    Text("%.5f, %.5f".format(lat, lng), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Tap to open in Maps",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFECECEC).copy(alpha = 0.7f)
                    )
                }
            }
        }
    }
}

private fun openInMaps(context: Context, latitude: Double, longitude: Double) {
    val uri = Uri.parse("geo:$latitude,$longitude?q=$latitude,$longitude")
    val intent = Intent(Intent.ACTION_VIEW, uri)
    try {
        context.startActivity(intent)
    } catch (e: Exception) {
        Toast.makeText(context, "No maps app available", Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun FileChip(message: MessageEntity) {
    val context = LocalContext.current
    Surface(
        color = Color.Black.copy(alpha = 0.25f),
        contentColor = Color(0xFFECECEC),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.padding(top = 4.dp).widthIn(max = 260.dp),
        onClick = { openFile(context, message) }
    ) {
        Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.InsertDriveFile, contentDescription = null)
            Column(modifier = Modifier.padding(start = 8.dp).widthIn(max = 160.dp)) {
                Text(message.mediaFilename ?: message.body, maxLines = 1)
                Text(
                    "${(message.mediaBytes?.size ?: 0) / 1000}KB · tap to open",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFECECEC).copy(alpha = 0.7f)
                )
            }
            IconButton(onClick = { saveToDownloads(context, message) }) {
                Icon(Icons.Filled.Download, contentDescription = "Save")
            }
        }
    }
}

/** Opens the attachment immediately in whatever app the OS offers, via a FileProvider content URI
 * written to cache — so users don't have to dig through Downloads/a file manager to see it. */
private fun openFile(context: Context, message: MessageEntity) {
    val bytes = message.mediaBytes
    if (bytes == null) {
        Toast.makeText(context, "Nothing to open", Toast.LENGTH_SHORT).show()
        return
    }
    val filename = message.mediaFilename ?: "meshtalk_${message.id.take(8)}"
    val mime = message.mediaMimeType ?: "application/octet-stream"
    val sharedDir = File(context.cacheDir, "shared").apply { mkdirs() }
    val file = File(sharedDir, filename)
    file.writeBytes(bytes)
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mime)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try {
        context.startActivity(intent)
    } catch (e: Exception) {
        Toast.makeText(context, "No app can open this file type", Toast.LENGTH_SHORT).show()
    }
}

private fun saveToDownloads(context: Context, message: MessageEntity) {
    val bytes = message.mediaBytes
    if (bytes == null) {
        Toast.makeText(context, "Nothing to save", Toast.LENGTH_SHORT).show()
        return
    }
    val filename = message.mediaFilename ?: "meshtalk_${message.id.take(8)}"
    val mime = message.mediaMimeType ?: "application/octet-stream"
    val resolver = context.contentResolver

    val savedOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        if (uri != null) {
            resolver.openOutputStream(uri)?.use { it.write(bytes) }
            true
        } else false
    } else {
        // Pre-API29 fallback: app-specific external storage (no extra permission needed).
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        if (dir != null) {
            dir.mkdirs()
            java.io.File(dir, filename).writeBytes(bytes)
            true
        } else false
    }
    Toast.makeText(
        context,
        if (savedOk) "Saved \"$filename\"" else "Couldn't save file",
        Toast.LENGTH_SHORT
    ).show()
}

/** Three small icon buttons for picking a photo (auto-compressed), a generic file (size-capped),
 * or sharing the current location (with a best-effort static map thumbnail). */
@Composable
fun AttachButtons(
    onImagePicked: (ByteArray, String) -> Unit,
    onFilePicked: (ByteArray, String, String) -> Unit,
    onLocationPicked: (Double, Double, ByteArray?, String?) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var fetchingLocation by remember { mutableStateOf(false) }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) { ImageCompressor.compress(context.contentResolver, uri) }
            when (result) {
                is MediaPrepResult.Image -> onImagePicked(result.bytes, "image/jpeg")
                is MediaPrepResult.Rejected -> Toast.makeText(context, result.reason, Toast.LENGTH_LONG).show()
                else -> Unit
            }
        }
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                val mime = context.contentResolver.getType(uri)
                val filename = queryDisplayName(context, uri) ?: "file"
                FilePrep.prepare(context.contentResolver, uri, mime, filename)
            }
            when (result) {
                is MediaPrepResult.GenericFile -> onFilePicked(result.bytes, result.mime, result.filename)
                is MediaPrepResult.Rejected -> Toast.makeText(context, result.reason, Toast.LENGTH_LONG).show()
                else -> Unit
            }
        }
    }

    fun fetchAndShareLocation() {
        fetchingLocation = true
        LocationFetcher.getCurrentLocation(context) { location ->
            if (location == null) {
                fetchingLocation = false
                Toast.makeText(context, "Couldn't get current location", Toast.LENGTH_LONG).show()
                return@getCurrentLocation
            }
            scope.launch {
                val mapBytes = withContext(Dispatchers.IO) {
                    OfflineMapCache.getOrFetch(context, location.latitude, location.longitude, zoom = 16, withMarker = true)?.readBytes()
                }
                fetchingLocation = false
                onLocationPicked(location.latitude, location.longitude, mapBytes, if (mapBytes != null) "image/jpeg" else null)
            }
        }
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) fetchAndShareLocation() else {
            Toast.makeText(context, "Location permission needed to share your location", Toast.LENGTH_LONG).show()
        }
    }

    Row {
        IconButton(onClick = { imagePicker.launch("image/*") }) {
            Icon(Icons.Filled.Photo, contentDescription = "Attach photo")
        }
        IconButton(onClick = { filePicker.launch(arrayOf("*/*")) }) {
            Icon(Icons.Filled.AttachFile, contentDescription = "Attach file")
        }
        IconButton(
            enabled = !fetchingLocation,
            onClick = {
                val granted = ContextCompat.checkSelfPermission(
                    context, Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
                if (granted) fetchAndShareLocation()
                else locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        ) {
            if (fetchingLocation) {
                CircularProgressIndicator(modifier = Modifier.padding(4.dp))
            } else {
                Icon(Icons.Filled.LocationOn, contentDescription = "Share location")
            }
        }
    }
}

private fun queryDisplayName(context: Context, uri: Uri): String? {
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (nameIndex >= 0 && cursor.moveToFirst()) return cursor.getString(nameIndex)
    }
    return null
}

/** Small "sent/read" indicator shown under a message: a single check once sent, a colored double
 * check once at least one reader (DM: the recipient; public: any peer) has read it. */
@Composable
fun ReadReceiptIndicator(repository: MeshRepository, message: MessageEntity) {
    if (!message.isMine) return
    val readers by repository.readersFor(message.id).collectAsState(initial = emptyList())

    Row(verticalAlignment = Alignment.CenterVertically) {
        if (readers.isEmpty()) {
            Icon(
                Icons.Filled.Done, contentDescription = "Sent",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 4.dp).heightIn(max = 14.dp)
            )
            Text("Sent", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Icon(
                Icons.Filled.DoneAll, contentDescription = "Read",
                tint = SignalGreen,
                modifier = Modifier.padding(end = 4.dp).heightIn(max = 14.dp)
            )
            val label = if (message.type == "PUBLIC") {
                "Read by " + readers.joinToString(", ") { it.readerNickname }
            } else {
                "Read"
            }
            Text(label, style = MaterialTheme.typography.bodySmall, color = SignalGreen, maxLines = 1)
        }
    }
}
