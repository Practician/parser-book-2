package com.bookparser.app.core

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

class BookFileHandler(private val context: Context) {

    suspend fun handleFile(uri: Uri): List<Uri> = withContext(Dispatchers.IO) {
        when (getFileExtension(uri)) {
            "zip" -> handleZip(uri)
            "epub", "fb2", "pdf" -> listOf(uri)
            else -> throw IllegalArgumentException("Unsupported file type")
        }
    }

    private fun getFileExtension(uri: Uri): String? {
        if (uri.scheme == "content") {
            val fileName = getFileName(uri)
            return fileName.substringAfterLast('.', "").lowercase()
        }
        return uri.path?.substringAfterLast('.', "")?.lowercase()
    }

    @SuppressLint("Range")
    private fun getFileName(uri: Uri): String {
        var result: String? = null
        if (uri.scheme == "content") {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            try {
                if (cursor != null && cursor.moveToFirst()) {
                    result = cursor.getString(cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME))
                }
            } finally {
                cursor?.close()
            }
        }
        if (result == null) {
            result = uri.path
            val lastSlash = result?.lastIndexOf('/')
            if (lastSlash != null && lastSlash != -1) {
                result = result?.substring(lastSlash + 1)
            }
        }
        return result ?: "unknown_file"
    }

    private suspend fun handleZip(uri: Uri): List<Uri> {
        val fileUris = mutableListOf<Uri>()
        val tempDir = File(context.cacheDir, "unzipped_${System.currentTimeMillis()}")
        try {
            if (!tempDir.exists()) tempDir.mkdirs()

            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                val zipInputStream = ZipInputStream(inputStream)
                var entry = zipInputStream.nextEntry
                while (entry != null) {
                    val file = File(tempDir, entry.name)
                    if (entry.isDirectory) {
                        file.mkdirs()
                    } else {
                        file.parentFile?.mkdirs()
                        FileOutputStream(file).use { outputStream ->
                            zipInputStream.copyTo(outputStream)
                        }
                        if (file.extension.lowercase() in listOf("fb2", "epub", "pdf")) {
                            fileUris.add(Uri.fromFile(file))
                        }
                    }
                    entry = zipInputStream.nextEntry
                }
            }
        } catch (e: Exception) {
            // Cleanup and rethrow
            tempDir.deleteRecursively()
            throw e
        }
        // The tempDir will be cleaned up by the caller after processing
        return fileUris
    }
}
