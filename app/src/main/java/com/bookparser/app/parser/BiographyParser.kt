package com.bookparser.app.parser

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class BiographyParser(private val context: Context) {

    companion object {
        private const val TAG = "BiographyParser"
        private const val UA = "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        const val SOURCE_WIKIPEDIA = "wikipedia"
        const val SOURCE_RUWIKI = "ruwiki"
        const val SOURCE_RUWIKI_AI = "ruwiki_ai"
    }
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
    
    /**
     * Получает биографию автора с контекстом книги
     */
    suspend fun getBiography(
        authorName: String,
        source: String = SOURCE_WIKIPEDIA,
        bookTitle: String? = null,
        bookGenre: String? = null
    ): String? = withContext(Dispatchers.IO) {
        val title = authorName.trim()
    
        return@withContext when (source) {
            SOURCE_RUWIKI -> fetchRuwikiREST(title)
            SOURCE_RUWIKI_AI -> fetchRuwikiAI(title, bookTitle, bookGenre)
            else -> fetchWikipedia(title)
        }
    }
    
    private fun fetchWikipedia(title: String): String? {
        // 1) REST summary RU
        fetchRestSummary("ru", title, "wikipedia.org")?.let { return it }
        // 2) REST summary EN
        fetchRestSummary("en", title, "wikipedia.org")?.let { return it }
        // 3) Action API parse RU
        fetchActionParseLead("ru", title)?.let { return it }
        // 4) Action API parse EN
        return fetchActionParseLead("en", title)
    }
    
    private fun fetchRuwikiREST(authorName: String): String? {
        return try {
            Log.d(TAG, "Запрос биографии через РУВИКИ REST: $authorName")
    
            // Попытка 1: Формат "Фамилия, Имя" с подчёркиванием
            val parts = authorName.split(" ")
            if (parts.size >= 2) {
                val wikiFormat = "${parts.last()},_${parts.first()}"
                fetchRestSummary("ru", wikiFormat, "ruwiki.ru")?.let {
                    Log.d(TAG, "✓ Биография найдена в формате Wiki")
                    return it
                }
            }
    
            // Попытка 2: Opensearch поиск
            val searchUrl = "https://ru.ruwiki.ru/w/api.php?action=opensearch&format=json&search=${URLEncoder.encode(authorName, "UTF-8")}&limit=1"
    
            val searchReq = Request.Builder()
                .url(searchUrl)
                .addHeader("User-Agent", UA)
                .build()
    
            var pageTitle: String? = null
    
            client.newCall(searchReq).execute().use { rsp ->
                if (!rsp.isSuccessful) {
                    Log.w(TAG, "РУВИКИ opensearch вернул код: ${rsp.code}")
                    return null
                }
    
                val body = rsp.body?.string() ?: return null
                val searchArray = org.json.JSONArray(body)
    
                if (searchArray.length() < 2 || searchArray.getJSONArray(1).length() == 0) {
                    Log.w(TAG, "РУВИКИ не нашёл статей")
                    return null
                }
    
                pageTitle = searchArray.getJSONArray(1).getString(0)
                Log.d(TAG, "Найдена статья: $pageTitle")
            }
    
            if (pageTitle == null) return null
    
            // Преобразуем заголовок в URL-формат
            val urlTitle = pageTitle!!.replace(" ", "_")
    
            // Попытка 3: REST summary
            val summaryUrl = "https://ru.ruwiki.ru/api/rest_v1/page/summary/$urlTitle"
    
            Log.d(TAG, "Финальный запрос: $summaryUrl")
    
            val summaryReq = Request.Builder()
                .url(summaryUrl)
                .addHeader("User-Agent", UA)
                .build()
    
            client.newCall(summaryReq).execute().use { rsp ->
                if (!rsp.isSuccessful) {
                    Log.w(TAG, "REST summary финал вернул код: ${rsp.code}")
                    return null
                }
    
                val body = rsp.body?.string() ?: return null
                val obj = JSONObject(body)
    
                val extract = obj.optString("extract", "").trim()
                if (extract.isNotEmpty()) {
                    Log.d(TAG, "✓ Биография получена из РУВИКИ: ${extract.length} символов")
                    return truncateNicely(cleanFormatting(extract))
                }
    
                Log.w(TAG, "РУВИКИ не вернул extract")
                null
            }
    
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка РУВИКИ: ${e.message}", e)
            null
        }
    }
    
    private suspend fun fetchRuwikiAI(
        authorName: String,
        bookTitle: String?,
        bookGenre: String?
    ): String? {
        return try {
            Log.d(TAG, "Запрос биографии через РУВИКИ ИИ: $authorName")
    
            val aiClient = com.bookparser.app.api.RuwikiAIClient()
            val response = aiClient.getBiography(authorName, bookTitle, bookGenre)
    
            if (response != null) {
                Log.d(TAG, "✓ Биография получена через РУВИКИ ИИ: ${response.length} символов")
                return cleanFormatting(truncateNicely(response))
            }
    
            Log.w(TAG, "РУВИКИ ИИ не вернул ответ")
            null
    
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка РУВИКИ ИИ: ${e.message}", e)
            null
        }
    }
    
    private fun fetchRestSummary(lang: String, title: String, domain: String): String? {
        return try {
            val encoded = URLEncoder.encode(title, "UTF-8")
            val url = "https://$lang.$domain/api/rest_v1/page/summary/$encoded"
    
            Log.d(TAG, "Запрос: $url")
    
            val req = Request.Builder()
                .url(url)
                .addHeader("User-Agent", UA)
                .build()
    
            client.newCall(req).execute().use { rsp ->
                if (!rsp.isSuccessful) {
                    Log.d(TAG, "REST summary вернул код: ${rsp.code}")
                    return null
                }
    
                val body = rsp.body?.string() ?: return null
                val obj = JSONObject(body)
    
                val extract = obj.optString("extract", "").trim()
                if (extract.isNotEmpty()) {
                    Log.d(TAG, "✓ Биография получена: ${extract.length} символов")
                    return truncateNicely(cleanFormatting(extract))
                }
    
                val desc = obj.optString("description", "").trim()
                if (desc.isNotEmpty()) {
                    return cleanFormatting(desc)
                }
    
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "REST summary failed: ${e.message}")
            null
        }
    }
    
    private fun fetchActionParseLead(lang: String, title: String): String? {
        return try {
            val encoded = URLEncoder.encode(title, "UTF-8")
            val url = "https://$lang.wikipedia.org/w/api.php?action=parse&format=json&page=$encoded&prop=text&section=0&redirects=1"
            val req = Request.Builder().url(url).addHeader("User-Agent", UA).build()
            client.newCall(req).execute().use { rsp ->
                if (!rsp.isSuccessful) return null
                val body = rsp.body?.string() ?: return null
                val root = JSONObject(body)
                val parse = root.optJSONObject("parse") ?: return null
                val textObj = parse.optJSONObject("text") ?: return null
                val html = textObj.optString("*", "")
                if (html.isBlank()) return null
    
                val plain = htmlToPlainLead(html)
                if (plain.isNotBlank()) {
                    Log.d(TAG, "✓ Биография получена из Parse API ($lang)")
                    truncateNicely(plain)
                } else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Action parse $lang failed: ${e.message}")
            null
        }
    }
    
    private fun htmlToPlainLead(html: String): String {
        var s = html
    
        s = s.replace(Regex("(?is)<table[\\s\\S]*?</table>"), " ")
        s = s.replace(Regex("(?is)<sup[\\s\\S]*?</sup>"), " ")
        s = s.replace(Regex("(?is)<!--([\\s\\S]*?)-->"), " ")
        s = s.replace(Regex("(?is)<script[\\s\\S]*?</script>"), " ")
        s = s.replace(Regex("(?is)<style[\\s\\S]*?</style>"), " ")
        s = s.replace(Regex("(?is)<ref[\\s\\S]*?</ref>"), " ")
    
        val m = Regex("(?is)<p[\\s\\S]*?>([\\s\\S]*?)</p>").find(s) ?: return ""
        var p = m.groupValues[1]
    
        p = p.replace(Regex("<[^>]+>"), " ")
        p = p.replace("&nbsp;", " ")
            .replace("&mdash;", "—")
            .replace("&ndash;", "–")
            .replace("&laquo;", "«")
            .replace("&raquo;", "»")
            .replace("&quot;", "\"")
            .replace("&amp;", "&")
    
        p = p.replace(Regex("\\s+"), " ").trim()
    
        return cleanFormatting(p)
    }
    
    private fun cleanFormatting(text: String): String {
        var result = text
        result = result.replace(" ,", ",")
        result = result.replace("( ", "(")
        result = result.replace(" )", ")")
        result = result.replace(" «", " «")
        result = result.replace("« ", "«")
        result = result.replace(" »", "»")
        result = result.replace(" .", ".")
        result = result.replace(" :", ":")
        result = result.replace(" ;", ";")
        result = result.replace(Regex("\\s+"), " ")
        return result.trim()
    }
    
    private fun truncateNicely(text: String, limit: Int = 800): String {
        if (text.length <= limit) return text
        val cut = text.substring(0, limit)
        val lastDot = cut.lastIndexOf('.')
        return if (lastDot > limit / 2) {
            cut.substring(0, lastDot + 1)
        } else {
            "$cut..."
        }
    }
}
