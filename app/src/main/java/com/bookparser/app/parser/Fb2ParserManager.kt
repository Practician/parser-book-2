package com.bookparser.app.parser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File

class Fb2ParserManager(private val context: Context) {

    companion object {
        private const val TAG = "Fb2ParserManager"
    }
    
    private val fb2Parser = Parser_FB2(context)
    private val biographyParser = BiographyParser(context)
    
    /**
     * Парсит FB2 файл и возвращает метаданные
     */
    suspend fun parseAndGetMetadata(uri: Uri): Fb2Metadata {
        return fb2Parser.parse(uri)
    }
    
    /**
     * Получает биографию автора из выбранного источника с контекстом книги
     */
    suspend fun getBiographyForAuthor(
        authorName: String,
        bookTitle: String? = null,
        bookGenre: String? = null
    ): String? {
        val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        val source = prefs.getString("biography_source", BiographyParser.SOURCE_WIKIPEDIA)
            ?: BiographyParser.SOURCE_WIKIPEDIA
    
        Log.d(TAG, "Источник биографии: $source")
    
        return biographyParser.getBiography(authorName, source, bookTitle, bookGenre)
    }
    
    /**
     * Декодирует Base64 строку обложки в Bitmap
     */
    fun decodeCoverImage(base64String: String): Bitmap? {
        return try {
            val decodedBytes = Base64.decode(base64String, Base64.DEFAULT)
            val bitmap = BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
    
            if (bitmap != null) {
                compressCover(bitmap)
            } else {
                Log.w(TAG, "Не удалось декодировать обложку из Base64")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка декодирования обложки: ${e.message}", e)
            null
        }
    }
    
    /**
     * Сжимает обложку до разумных размеров (500x750 макс)
     */
    private fun compressCover(original: Bitmap): Bitmap {
        val maxWidth = 500
        val maxHeight = 750
    
        val width = original.width
        val height = original.height
    
        // Если изображение уже меньше максимальных размеров, возвращаем как есть
        if (width <= maxWidth && height <= maxHeight) {
            return original
        }
    
        // Вычисляем коэффициент масштабирования
        val ratio = minOf(maxWidth.toFloat() / width, maxHeight.toFloat() / height)
        val newWidth = (width * ratio).toInt()
        val newHeight = (height * ratio).toInt()
    
        // Масштабируем изображение
        val resized = Bitmap.createScaledBitmap(original, newWidth, newHeight, true)
    
        // Сжимаем в JPEG с качеством 85%
        // (Note: This step in memory doesn't affect Bitmap object size directly but helpful if written to stream.
        // The created bitmap is raw pixel data.)
        
        Log.d(TAG, "Обложка масштабирована: ${newWidth}x$newHeight")
    
        return resized
    }
    
    /**
     * Сохраняет Bitmap обложки во временный файл и возвращает Uri
     */
    fun saveCoverToTempUri(bitmap: Bitmap): Uri {
        val tempFile = File(context.cacheDir, "temp_cover_${System.currentTimeMillis()}.jpg")
    
        tempFile.outputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        }
    
        Log.d(TAG, "Обложка сохранена во временный файл: ${tempFile.absolutePath}")
    
        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            tempFile
        )
    }
}
