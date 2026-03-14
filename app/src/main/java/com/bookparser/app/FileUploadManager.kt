package com.bookparser.app

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log // kept for AppLogger internal use
import com.bookparser.app.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.IOException
import java.security.MessageDigest

data class UploadResult(
    val success: Boolean,
    val response: String
)

class FileUploadManager(private val context: Context) {

    companion object {
        private const val TAG = "FileUploadManager"
        private const val BASE_URL = "https://4pda.to/forum/index.php"
    }

    private val client = OkHttpClient()

    /**
     * Uploads a file to the 4PDA forum attachment system.
     * @param uri      Content URI of the file to upload
     * @param cookies  Session cookies string
     * @param authKey  Forum auth_key required by the attachment API
     * @param index    1 = cover image, 2 = book file
     * @return UploadResult with the server response (file ID) or null on failure
     */
    suspend fun uploadFile(uri: Uri, cookies: String, authKey: String, index: Int): UploadResult? =
        withContext(Dispatchers.IO) {
            try {
                AppLogger.d(TAG, "=== Начало загрузки файла ===")
                AppLogger.d(TAG, "URI: $uri, Index: $index")

                val file = convertUriToFile(uri, context)
                val fileName = file.name
                val fileSize = file.length()
                val md5 = calculateMD5(file)

                AppLogger.d(TAG, "Файл: $fileName, Размер: $fileSize bytes, MD5: $md5")

                val allowExtMap = mapOf(
                    1 to "jpg,jpeg,gif,png,webp",
                    2 to "fb2,epub,djvu,zip,rar,doc,docx,rtf,txt,pdf,r00,r01,r02,r03,r04,r05,r06,r07,r08,r09,r10,r11,r12,r13,r14,r15,r16,r17,r18,r19,z00,z01,z02,z03,z04,z05,z06,z07,z08,z09,z10,z11,z12,z13,z14,z15,z16,z17,z18,z19"
                )
                val allowExt = allowExtMap[index] ?: "jpg,jpeg,gif,png,webp"

                AppLogger.d(TAG, "Шаг 1: Проверка файла (code=check)")
                val checkResult = checkFile(fileName, fileSize, md5, cookies, authKey, index, allowExt)
                if (!checkResult) {
                    AppLogger.e(TAG, "Ошибка проверки файла")
                    return@withContext null
                }
                AppLogger.d(TAG, "✓ Проверка успешна")

                AppLogger.d(TAG, "Шаг 2: Загрузка бинарного файла (code=upload)")
                val uploadResponse = uploadBinaryFile(file, cookies, authKey, index, allowExt)
                if (uploadResponse.isNullOrEmpty()) {
                    AppLogger.e(TAG, "Ошибка загрузки файла")
                    return@withContext null
                }
                AppLogger.d(TAG, "✓ Загрузка успешна: $uploadResponse")

                // Ответ сервера: поля разделены \u0002 (STX), группы \u0003/\u0004
                // Первое поле — числовой ID файла
                val fileId = uploadResponse.trim()
                    .split(Regex("[\\x02\\x03\\x04 ]"))
                    .firstOrNull { it.isNotEmpty() }
                    ?.trim() ?: ""
                AppLogger.d(TAG, "Parsed fileId: '$fileId' from response")
                UploadResult(success = true, response = fileId)

            } catch (e: Exception) {
                AppLogger.e(TAG, "Ошибка загрузки файла: ${e.message}", e)
                null
            }
        }

    private fun convertUriToFile(uri: Uri, context: Context): File {
        AppLogger.d(TAG, "Конвертация URI в File...")

        // Если URI указывает на файл в нашем cache через FileProvider — возвращаем напрямую
        if (uri.authority == "${context.packageName}.fileprovider") {
            // Извлекаем путь из content://com.example.myapplication.fileprovider/cache/file.jpg
            val fileName = uri.path?.substringAfterLast("/") ?: throw IOException("Неверный путь FileProvider URI")
            val cacheFile = File(context.cacheDir, fileName)

            if (cacheFile.exists() && cacheFile.length() > 0) {
                AppLogger.d(TAG, "Файл найден в cache: ${cacheFile.absolutePath}, размер: ${cacheFile.length()}")
                return cacheFile
            } else {
                throw IOException("Файл в cache не найден или пустой: ${cacheFile.absolutePath}")
            }
        }

        // Для внешних URI (Downloads, Gallery и т.д.) — копируем содержимое
        val inputStream = context.contentResolver.openInputStream(uri)
            ?: throw IOException("Не удалось открыть InputStream для URI: $uri")

        val fileName = getFileName(uri, context)
        val tempFile = File(context.cacheDir, fileName)

        inputStream.use { input ->
            tempFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }

        AppLogger.d(TAG, "Файл создан: ${tempFile.absolutePath}, размер: ${tempFile.length()}")
        return tempFile
    }

    private fun getFileName(uri: Uri, context: Context): String {
        var fileName = "unknown_file"

        if (uri.scheme == "content") {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1) {
                        fileName = it.getString(nameIndex)
                    }
                }
            }
        } else if (uri.scheme == "file") {
            fileName = uri.lastPathSegment ?: "unknown_file"
        }

        return fileName
    }

    private fun calculateMD5(file: File): String {
        val md = MessageDigest.getInstance("MD5")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                md.update(buffer, 0, bytesRead)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun checkFile(
        fileName: String,
        fileSize: Long,
        md5: String,
        cookies: String,
        authKey: String,
        index: Int,
        allowExt: String
    ): Boolean {
        AppLogger.d(TAG, "Отправка check-запроса... (allowExt: $allowExt)")

        val requestBody = FormBody.Builder()
            .add("code", "check")
            .add("auth_key", authKey)
            .add("topic_id", "0")
            .add("index", index.toString())
            .add("relId", "0")
            .add("maxSize", "201326592")
            .add("name", fileName)
            .add("size", fileSize.toString())
            .add("md5", md5)
            .add("allowExt", allowExt)
            .build()

        val request = Request.Builder()
            .url("$BASE_URL?act=attach&code=check")
            .post(requestBody)
            .addHeader("Cookie", cookies)
            .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Mobile Safari/537.36")
            .addHeader("X-Requested-With", "XMLHttpRequest")
            .addHeader("Referer", "https://4pda.to/forum/index.php?act=zfw&f=218")
            .addHeader("Accept", "*/*")
            .build()

        val response = client.newCall(request).execute()
        val responseBody = response.body?.string() ?: ""

        AppLogger.d(TAG, "Check Response code: ${response.code}")
        AppLogger.d(TAG, "Check Response body: $responseBody")

        // 4PDA returns JSON: {"result":"ok"} or {"result":"error","reason":"..."}
        if (!response.isSuccessful) return false
        if (responseBody.contains("\"error\"") && !responseBody.contains("\"ok\"")) {
            AppLogger.e(TAG, "Check rejected by server: $responseBody")
            return false
        }
        return true
    }

    private fun uploadBinaryFile(
        file: File,
        cookies: String,
        authKey: String,
        index: Int,
        allowExt: String
    ): String? {
        AppLogger.d(TAG, "Отправка upload-запроса (${file.length()} bytes, allowExt: $allowExt)...")

        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("code", "upload")
            .addFormDataPart("auth_key", authKey)
            .addFormDataPart("topic_id", "0")
            .addFormDataPart("index", index.toString())
            .addFormDataPart("relId", "0")
            .addFormDataPart("maxSize", "201326592")
            .addFormDataPart("allowExt", allowExt)
            .addFormDataPart(
                "FILE_UPLOAD",
                file.name,
                file.asRequestBody("application/octet-stream".toMediaTypeOrNull())
            )
            .build()

        val request = Request.Builder()
            .url("$BASE_URL?act=attach&code=upload")
            .post(requestBody)
            .addHeader("Cookie", cookies)
            .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Mobile Safari/537.36")
            .addHeader("X-Requested-With", "XMLHttpRequest")
            .addHeader("Referer", "https://4pda.to/forum/index.php?act=zfw&f=218")
            .addHeader("Accept", "*/*")
            .build()

        val response = client.newCall(request).execute()
        val responseBody = response.body?.string() ?: ""

        AppLogger.d(TAG, "Upload Response code: ${response.code}")
        AppLogger.d(TAG, "Upload Response body: $responseBody")

        return if (response.isSuccessful) responseBody else null
    }
}
