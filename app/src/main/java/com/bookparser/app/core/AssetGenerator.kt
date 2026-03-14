package com.bookparser.app.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class AssetGenerator(private val context: Context) {

    suspend fun saveCover(coverImage: ByteArray): Uri = withContext(Dispatchers.IO) {
        val bitmap = BitmapFactory.decodeByteArray(coverImage, 0, coverImage.size)
        val file = File(context.cacheDir, "cover_${System.currentTimeMillis()}.jpg")
        FileOutputStream(file).use {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)
        }
        Uri.fromFile(file)
    }
}
