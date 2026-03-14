package com.bookparser.app

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class FileRemovalManager(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    
    /**
     * Удаляет файл с сервера 4PDA
     * @param fileId ID загруженного файла
     * @param cookies Cookies для авторизации
     * @param index 1 = обложка, 2 = книга
     */
    suspend fun removeFile(fileId: String, cookies: String, index: Int): Boolean = withContext(Dispatchers.IO) {
        try {
            val allowExt = if (index == 1) {
                "jpg,jpeg,gif,png,webp"
            } else {
                "fb2,epub,djvu,zip,rar,doc,docx,rtf,txt,pdf,r00,r01,r02,r03,r04,r05,r06,r07,r08,r09,r10,r11,r12,r13,r14,r15,r16,r17,r18,r19,z00,z01,z02,z03,z04,z05,z06,z07,z08,z09,z10,z11,z12,z13,z14,z15,z16,z17,z18,z19"
            }
    
            val formBody = FormBody.Builder()
                .add("act", "attach")
                .add("topic_id", "0")
                .add("index", index.toString())
                .add("relId", "0")
                .add("maxSize", "201326592")
                .add("allowExt", allowExt)
                .add("code", "remove")
                .add("id", fileId)
                .build()
    
            val request = Request.Builder()
                .url("https://4pda.to/forum/index.php?act=attach")
                .addHeader("Cookie", cookies)
                .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Mobile Safari/537.36")
                .addHeader("Accept", "*/*")
                .addHeader("Referer", "https://4pda.to/forum/index.php?act=zfw&f=218")
                .post(formBody)
                .build()
    
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()
    
            Log.d("FileRemovalManager", "Remove response (index=$index, id=$fileId): $responseBody")
    
            response.isSuccessful
    
        } catch (e: Exception) {
            Log.e("FileRemovalManager", "Error removing file (index=$index, id=$fileId): ${e.message}", e)
            false
        }
    }
}
