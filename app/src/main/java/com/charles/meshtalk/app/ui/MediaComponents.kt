package com.charles.meshtalk.app.ui

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.charles.meshtalk.app.data.MessageEntity
import com.charles.meshtalk.app.media.FilePrep
import com.charles.meshtalk.app.media.ImageCompressor
import com.charles.meshtalk.app.media.MediaPrepResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Renders a message's body: plain text, an inline decoded image, or a file chip with save action. */
@Composable
fun MessageContentBody(message: MessageEntity) {
    when (message.contentType) {
        "IMAGE" -> {
            val bytes = message.mediaBytes
            val bitmap = remember(message.id) {
                bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            }
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Photo",
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .heightIn(max = 240.dp)
                        .clip(RoundedCornerShape(12.dp))
                )
            } else {
                Text("[photo unavailable]", modifier = Modifier.padding(top = 2.dp))
            }
        }
        "FILE" -> FileChip(message)
        else -> Text(message.body, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
private fun FileChip(message: MessageEntity) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .padding(top = 4.dp)
            .widthIn(max = 260.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { openFile(context, message) }
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.InsertDriveFile, contentDescription = null)
        Column(modifier = Modifier.padding(start = 8.dp).widthIn(max = 160.dp)) {
            Text(message.mediaFilename ?: message.body, maxLines = 1)
            Text(
                "${(message.mediaBytes?.size ?: 0) / 1000}KB · tap to open",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = { saveToDownloads(context, message) }) {
            Icon(Icons.Filled.Download, contentDescription = "Save")
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

/** Two small icon buttons for picking a photo (auto-compressed) or a generic file (size-capped). */
@Composable
fun AttachButtons(
    onImagePicked: (ByteArray, String) -> Unit,
    onFilePicked: (ByteArray, String, String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

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

    Row {
        IconButton(onClick = { imagePicker.launch("image/*") }) {
            Icon(Icons.Filled.Photo, contentDescription = "Attach photo")
        }
        IconButton(onClick = { filePicker.launch(arrayOf("*/*")) }) {
            Icon(Icons.Filled.AttachFile, contentDescription = "Attach file")
        }
    }
}

private fun queryDisplayName(context: Context, uri: android.net.Uri): String? {
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (nameIndex >= 0 && cursor.moveToFirst()) return cursor.getString(nameIndex)
    }
    return null
}
