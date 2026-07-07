package com.charles.meshtalk.app.data.feedback

import android.content.Context
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream

object ImageUploadHelper {

    fun uriToBase64(context: Context, uri: Uri): String {
        val inputStream = context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("Cannot open image URI")
        return inputStream.use { stream ->
            val bytes = stream.readBytes()
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        }
    }
}
