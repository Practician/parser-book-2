package com.bookparser.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.bookparser.app.processing.BookMetadata
import com.bookparser.app.parser.GenreMapping
import com.bookparser.app.web.WebDomAutomation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import kotlin.coroutines.resume
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val DOMAIN = "4pda.to"
        private const val URL_LOGIN = "https://4pda.to/forum/index.php?act=auth"
        private const val URL_NEW_TOPIC = "https://4pda.to/forum/index.php?act=zfw&f=218"
        private const val URL_SEARCH_BASE = "https://4pda.to/forum/index.php?act=search&source=all&result=topics&no_top=1&forums%5B%5D=18&forums%5B%5D=218&query="
        private const val MOBILE_UA = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Mobile Safari/537.36"
    }

    private lateinit var webViewParser: WebView
    private lateinit var webViewForum: WebView

    // Log panel views
    private lateinit var logPanel: LinearLayout
    private lateinit var logTextView: TextView
    private lateinit var logScrollView: ScrollView
    private val logListener: (String) -> Unit = { line -> runOnUiThread { appendLogLine(line) } }

    private var isForumVisible = false
    private var pendingSearchQuery: String? = null
    private var isWaitingForLogin = false
    private var pendingBookJson: String? = null
    private var isPublishing = false
    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null
    
    // Staged book files — filled one-by-one via stageBookFile bridge calls
    private val stagedBookFiles = mutableListOf<Triple<String, String, String>>() // (name, base64, mime)

    // FIX 1: храним креденциалы для вставки в форму после загрузки страницы (delay(3000) больше не нужен)
    private var pendingLoginUsername: String? = null
    private var pendingLoginPassword: String? = null

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        fileUploadCallback?.onReceiveValue(uris?.toTypedArray() ?: arrayOf())
        fileUploadCallback = null
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webViewParser = findViewById(R.id.webViewParser)
        webViewForum = findViewById(R.id.webViewForum)
        logPanel = findViewById(R.id.logPanel)
        logTextView = findViewById(R.id.logTextView)
        logScrollView = findViewById(R.id.logScrollView)

        setupLogPanel() 
        findViewById<View>(R.id.btnShowLog)?.visibility = View.GONE
        setupParserWebView()
        setupForumWebView()

        webViewParser.loadUrl("file:///android_asset/parser.html")
        checkExistingAuth()
    }

    private fun setupLogPanel() {
        AppLogger.addListener(logListener)

        findViewById<ImageButton>(R.id.btnShowLog).setOnClickListener {
            logPanel.visibility = View.VISIBLE
            logTextView.text = AppLogger.getAll()
            logScrollView.post { logScrollView.fullScroll(View.FOCUS_DOWN) }
        }
        findViewById<Button>(R.id.btnCloseLog).setOnClickListener {
            logPanel.visibility = View.GONE
        }
        findViewById<Button>(R.id.btnClearLog).setOnClickListener {
            AppLogger.clear()
            logTextView.text = ""
        }
        findViewById<Button>(R.id.btnCopyLog).setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("log", AppLogger.getAll()))
            Toast.makeText(this, "Лог скопирован в буфер обмена", Toast.LENGTH_SHORT).show()
        }
    }

    private fun appendLogLine(line: String) {
        if (logPanel.visibility == View.VISIBLE) {
            logTextView.append(line + "\n")
            logScrollView.post { logScrollView.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun getCookie(name: String): String {
        val cookies = CookieManager.getInstance().getCookie("https://4pda.to") ?: return ""
        val pairs = cookies.split(";")
        for (pair in pairs) {
            val parts = pair.trim().split("=")
            if (parts.size == 2 && parts[0] == name) {
                return parts[1]
            }
        }
        return ""
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupParserWebView() {
        webViewParser.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
        }
        webViewParser.addJavascriptInterface(ParserJsInterface(), "AndroidBridge")
        webViewParser.webViewClient = WebViewClient()

        // Enable file upload via <input type="file">
        webViewParser.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                fileUploadCallback?.onReceiveValue(null)
                fileUploadCallback = filePathCallback
                try {
                    fileChooserLauncher.launch(arrayOf("*/*"))
                } catch (e: Exception) {
                    fileUploadCallback = null
                    AppLogger.e(TAG, "File chooser error: ${e.message}")
                    return false
                }
                return true
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupForumWebView() {
        webViewForum.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            userAgentString = MOBILE_UA
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            cacheMode = WebSettings.LOAD_DEFAULT
            databaseEnabled = true
            useWideViewPort = false
            loadWithOverviewMode = false
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            allowFileAccess = false
            allowContentAccess = true
        }
        WebView.setWebContentsDebuggingEnabled(true)
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webViewForum, true)
        webViewForum.addJavascriptInterface(ForumJsInterface(), "ForumBridge")

        // ДОБАВЛЕНО: Обработка загрузки файлов (Вариант Б) для форума 4PDA
        webViewForum.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                fileUploadCallback?.onReceiveValue(null)
                fileUploadCallback = filePathCallback
                try {
                    fileChooserLauncher.launch(arrayOf("*/*"))
                } catch (e: Exception) {
                    fileUploadCallback = null
                    AppLogger.e(TAG, "File chooser error: ${e.message}")
                    return false
                }
                return true
            }
        }

        webViewForum.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                AppLogger.d(TAG, "Forum page loaded: $url")

                // Handle search results
                if (url != null && url.contains("act=search") && pendingSearchQuery != null) {
                    extractSearchResults(view)
                    pendingSearchQuery = null
                }

                // FIX 1: вставляем креденциалы когда страница авторизации полностью загрузилась
                if (url != null
                    && (url.contains("act=auth") || url.contains("act=login"))
                    && pendingLoginUsername != null
                    && pendingLoginPassword != null
                ) {
                    val escapedUser = pendingLoginUsername!!.escapeJs()
                    val escapedPass = pendingLoginPassword!!.escapeJs()
                    val savedUser = pendingLoginUsername!!
                    pendingLoginUsername = null
                    pendingLoginPassword = null
                    view?.evaluateJavascript("""
                        (function() {
                            var userField = document.querySelector('input[name="login"]');
                            var passField = document.querySelector('input[name="password"]');
                            if (userField && passField) {
                                userField.value = '$escapedUser';
                                passField.value = '$escapedPass';
                                var form = userField.closest('form');
                                if (form) { form.submit(); return 'submitted'; }
                            }
                            return 'fields_not_found';
                        })()
                    """.trimIndent()) { result ->
                        AppLogger.d(TAG, "Login form result: $result")
                        // Начинаем polling кук сразу после submit — не ждём onPageFinished
                        if (result?.contains("submitted") == true) {
                            pollForLoginCookie(savedUser)
                        }
                    }
                }

                // Check for successful login — skip the auth page itself
                if (isWaitingForLogin && url != null && !url.contains("act=auth") && !url.contains("act=login")) {
                    val cookies = CookieManager.getInstance().getCookie(DOMAIN)
                    if (cookies != null && cookies.contains("member_id")) {
                        AppLogger.d(TAG, "Login detected via onPageFinished")
                        completeLogin()
                    }
                }

                // Handle post-publication redirect to the new topic
                if (isPublishing && url != null && url.contains("showtopic=")) {
                    AppLogger.d(TAG, "New topic published: $url")
                    isPublishing = false
                    val cleanUrl = url.split("&").firstOrNull { it.contains("showtopic=") } ?: url
                    parserCallback("if(window.onBookPublished) window.onBookPublished('${cleanUrl.escapeJs()}');")
                }

                // Handle publication trigger
                if (url != null && url.contains("act=zfw") && pendingBookJson != null) {
                    val json = pendingBookJson!!
                    pendingBookJson = null
                    isPublishing = true
                    processPendingPublication(json)
                }
            }
        }
    }

    /**
     * Polling кук каждые 300мс после submit формы логина — быстрее чем ждать onPageFinished
     */
    private fun pollForLoginCookie(username: String) {
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        var attempts = 0
        fun check() {
            val cookies = CookieManager.getInstance().getCookie(DOMAIN)
            if (cookies != null && cookies.contains("member_id")) {
                AppLogger.d(TAG, "Login detected via cookie polling (attempt $attempts)")
                completeLogin()
            } else if (++attempts < 30) { // max 9 seconds
                handler.postDelayed(::check, 300)
            }
        }
        handler.postDelayed(::check, 300)
    }

    private fun completeLogin() {
        if (!isWaitingForLogin) return
        isWaitingForLogin = false
        val cookies = CookieManager.getInstance().getCookie(DOMAIN)
        if (cookies != null) saveCookies(cookies)
        val uname = getSavedUsername() ?: "User"
        showParserWebView()
        parserCallback("window.onAuthStateChanged(true, '${uname.escapeJs()}')")
    }

    /**
     * Check if user already has saved cookies (already logged in)
     */
    private fun checkExistingAuth() {
        val cookies = getSavedCookies()
        if (cookies != null) {
            restoreCookies(cookies)
            val memberCookie = cookies.split(";").find { it.trim().startsWith("member_id=") }
            if (memberCookie != null) {
                val username = getSavedUsername() ?: "User"
                parserCallback("window.onAuthStateChanged(true, '${username.escapeJs()}')")
                AppLogger.d(TAG, "Restored login session for: $username")
                webViewForum.loadUrl(URL_NEW_TOPIC)
            }
        }
    }

    // ════════════════════════════════════════════════
    //  PARSER JS INTERFACE (AndroidBridge)
    // ════════════════════════════════════════════════
    inner class ParserJsInterface {

        @android.webkit.JavascriptInterface
        fun saveFile(base64: String, fileName: String, mimeType: String) {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
                    val cacheFile = java.io.File(cacheDir, fileName)
                    cacheFile.writeBytes(bytes)

                    val uri = FileProvider.getUriForFile(
                        this@MainActivity,
                        "${packageName}.fileprovider",
                        cacheFile
                    )

                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = mimeType
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }

                    withContext(Dispatchers.Main) {
                        startActivity(Intent.createChooser(intent, "Сохранить файл"))
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "Ошибка сохранения: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        @android.webkit.JavascriptInterface
        fun copyToClipboard(text: String) {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("4PDA Book Parser", text)
            clipboard.setPrimaryClip(clip)
        }

        @android.webkit.JavascriptInterface
        fun showToast(message: String) {
            runOnUiThread {
                Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
            }
        }

        @android.webkit.JavascriptInterface
        fun openForumUrl(url: String) {
            runOnUiThread {
                if (url.contains("act=auth") || url.contains("act=login")) {
                    isWaitingForLogin = true
                    AppLogger.d(TAG, "openForumUrl: auth page detected, isWaitingForLogin=true")
                }
                showForumWebView()
                webViewForum.loadUrl(url)
            }
        }

        @android.webkit.JavascriptInterface
        fun returnToForum() {
            runOnUiThread {
                showForumWebView()
            }
        }

        @android.webkit.JavascriptInterface
        fun searchOnForum(query: String) {
            runOnUiThread {
                pendingSearchQuery = query
                try {
                    val encoded = java.net.URLEncoder.encode(query, "windows-1251")
                    val searchUrl = URL_SEARCH_BASE + encoded
                    AppLogger.d(TAG, "Searching forum: $searchUrl")
                    webViewForum.loadUrl(searchUrl)
                } catch (e: Exception) {
                    AppLogger.e(TAG, "Encoding error", e)
                }
            }
        }

        // FIX 1: loginToForum больше не использует delay(3000).
        // Храним креденциалы и вставляем их в onPageFinished когда страница загрузилась.
        @android.webkit.JavascriptInterface
        fun loginToForum(username: String, password: String) {
            runOnUiThread {
                AppLogger.d(TAG, "Login attempt for: $username")
                isWaitingForLogin = true
                pendingLoginUsername = username
                pendingLoginPassword = password
                saveUsername(username)
                showForumWebView()
                webViewForum.loadUrl(URL_LOGIN)
            }
        }

        @android.webkit.JavascriptInterface
        fun logoutFromForum() {
            runOnUiThread {
                clearCookies()
                parserCallback("window.onAuthStateChanged(false, '')")
                Toast.makeText(this@MainActivity, "Выход выполнен", Toast.LENGTH_SHORT).show()
                AppLogger.d(TAG, "Logged out")
            }
        }

        @android.webkit.JavascriptInterface
        fun stageBookFile(name: String, base64: String, mime: String) {
            AppLogger.d(TAG, "stageBookFile: $name (${base64.length} chars)")
            synchronized(stagedBookFiles) {
                stagedBookFiles.add(Triple(name, base64, mime))
            }
        }

        @android.webkit.JavascriptInterface
        fun sendBookDataToForum(json: String) {
            runOnUiThread {
                pendingBookJson = json
                showForumWebView()
                webViewForum.loadUrl(URL_NEW_TOPIC)
            }
        }

        @android.webkit.JavascriptInterface
        fun parseForumPost(url: String) {
            // Capture cookies on the main thread — CookieManager may not work from IO
            val cmCookies = CookieManager.getInstance().getCookie("https://4pda.to") ?: ""
            val savedCookies = getSavedCookies() ?: ""
            // Use whichever has more data (both contain member_id + pass_hash if the user is logged in)
            val allCookies = if (cmCookies.length >= savedCookies.length) cmCookies else savedCookies
            AppLogger.d(TAG, "parseForumPost: cookies length=${allCookies.length}, first 80 chars: ${allCookies.take(80)}")
            
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    // ── Extract IDs from URL ──
                    var postId = -1
                    // Check for specific post ID in URL: &p=, &pid=, #entry
                    val pMatch = Regex("[?&]p=(\\d+)").find(url)
                    val pidMatch = Regex("[?&]pid=(\\d+)").find(url)
                    val entryMatch = Regex("#entry(\\d+)").find(url)
                    val urlPostId = (pMatch ?: pidMatch ?: entryMatch)?.groupValues?.get(1)?.toIntOrNull() ?: -1
                    
                    // Check for topic ID
                    val topicMatcher = Regex("showtopic=(\\d+)").find(url)
                    
                    // Helper to fetch HTML content robustly
                    fun fetchHtml(url: String): String? {
                        return try {
                            val client = okhttp3.OkHttpClient.Builder()
                                .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                                .followRedirects(true)
                                .build()
                            val request = okhttp3.Request.Builder()
                                .url(url)
                                .header("User-Agent", MOBILE_UA)
                                .header("Cookie", allCookies)
                                .header("Referer", "https://4pda.to/forum/index.php")
                                .build()
                            val resp = client.newCall(request).execute()
                            if (!resp.isSuccessful && resp.code != 403 && resp.code != 404) {
                                AppLogger.w(TAG, "Fetch failed: HTTP ${resp.code} for $url")
                            }
                            val bodyBytes = resp.body?.bytes() ?: return null
                            // Try windows-1251 first as it's 4PDA's default
                            String(bodyBytes, java.nio.charset.Charset.forName("windows-1251"))
                        } catch (e: Exception) {
                            AppLogger.e(TAG, "Fetch error for $url: ${e.message}")
                            null
                        }
                    }

                    if (urlPostId > 0) {
                        // URL contains a specific post ID — use it directly
                        postId = urlPostId
                        AppLogger.d(TAG, "Extracted post ID from URL params: $postId")
                    } else if (topicMatcher != null) {
                        // Only topic ID — fetch the page and find the first post ID
                        AppLogger.d(TAG, "No post ID in URL, fetching topic page to find first post...")
                        val html = fetchHtml(url) ?: throw Exception("Не удалось загрузить страницу темы")
                        val doc = org.jsoup.Jsoup.parse(html, url)
                        
                        // Find the first post ID on the page
                        val postElem = doc.selectFirst("a[name^=p]")
                        if (postElem != null) {
                            val pName = postElem.attr("name") // e.g. p1234567
                            postId = if (pName.startsWith("p")) pName.substring(1).toIntOrNull() ?: -1 else -1
                        }
                        
                        if (postId == -1) {
                            val dataPostElem = doc.selectFirst("[data-post]")
                            if (dataPostElem != null) {
                                val dataPost = dataPostElem.attr("data-post")
                                postId = dataPost.toIntOrNull() ?: -1
                            }
                        }
                        
                        if (postId == -1) {
                            AppLogger.e(TAG, "Could not find post ID in topic HTML. Title: ${doc.title()}")
                            throw Exception("Не удалось найти ID первого поста в теме. Возможно, нужна авторизация.")
                        }
                        AppLogger.d(TAG, "Extracted first post ID from topic: $postId")
                    } else {
                        throw Exception("URL не содержит showtopic или pid.")
                    }

                    AppLogger.d(TAG, "Parsing post ID: $postId")
                    
                    var postData: JSONObject? = null
                    try {
                        val wsClient = com.bookparser.app.api.FourPDAWebSocketClient(
                            memberId = getCookie("member_id"),
                            passHash = getCookie("pass_hash"),
                            userAgent = MOBILE_UA
                        )
                        
                        val connected = wsClient.connect()
                        if (connected) {
                            postData = wsClient.getPostData(postId)
                        }
                        wsClient.close()
                    } catch (e: Exception) {
                        AppLogger.w(TAG, "WebSocket failed: ${e.message}, falling back to HTML parsing")
                    }
                    
                    if (postData == null) {
                        // HTTP FALLBACK — search full page HTML
                        AppLogger.d(TAG, "Using HTML parsing fallback for post $postId")
                        val html = fetchHtml(url) ?: throw Exception("Не удалось загрузить страницу для парсинга")
                        val doc = org.jsoup.Jsoup.parse(html, url)
                        
                        val fullHtml = html
                        AppLogger.d(TAG, "Page HTML length: ${fullHtml.length}")
                        
                        val title = doc.title().replace(" - 4PDA", "").trim()
                        var authorName = "Неизвестно"
                        var totalDownloads = 0
                        
                        // Let's find the post container first to scope our search
                        val postWrapper = doc.selectFirst("div[data-post=$postId], article[data-post=$postId], div[id=post-$postId]")
                        
                        var extractedAuthorId: String? = null
                        
                        // In 4PDA mobile, the nickname block is often a previous sibling, or inside a parent container
                        if (postWrapper != null) {
                            var currentEl: org.jsoup.nodes.Element? = postWrapper
                            // Go up to 3 levels to find a container that holds both the user info and the post body
                            for (i in 0..2) {
                                if (currentEl == null) break
                                val nickEl = currentEl.selectFirst("a[href*=showuser], a.nickname, span.nickname, .nick a, .post_nick a, .post-user-name a, [itemprop=name]")
                                if (nickEl != null && nickEl.text().isNotBlank()) {
                                    authorName = nickEl.text().trim()
                                    var href = nickEl.attr("abs:href").ifEmpty { nickEl.attr("href") }
                                    if (!href.contains("showuser")) {
                                        href = nickEl.parent()?.attr("abs:href")?.ifEmpty { nickEl.parent()?.attr("href") } ?: ""
                                    }
                                    if (!href.contains("showuser")) {
                                        val parentLink = nickEl.closest("a[href*=showuser]")
                                        if (parentLink != null) {
                                            href = parentLink.attr("abs:href").ifEmpty { parentLink.attr("href") }
                                        }
                                    }
                                    var idMatch = Regex("showuser=(\\d+)").find(href)
                                    extractedAuthorId = idMatch?.groupValues?.get(1)
                                    
                                    if (extractedAuthorId == null) {
                                        val fallbackLink = currentEl.selectFirst("a[href*=showuser]")
                                        if (fallbackLink != null) {
                                            href = fallbackLink.attr("abs:href").ifEmpty { fallbackLink.attr("href") }
                                            extractedAuthorId = Regex("showuser=(\\d+)").find(href)?.groupValues?.get(1)
                                        }
                                    }
                                    break
                                }
                                currentEl = currentEl.parent()
                            }
                            
                            // If still not found, search preceding elements
                            if (authorName == "Неизвестно") {
                                var prev = postWrapper.previousElementSibling()
                                for (i in 0..3) {
                                    if (prev == null) break
                                    val nickEl = prev.selectFirst("a[href*=showuser], a.nickname, span.nickname, .nick a, .post_nick a, .post-user-name a, [itemprop=name]")
                                    if (nickEl != null && nickEl.text().isNotBlank()) {
                                        authorName = nickEl.text().trim()
                                        var href = nickEl.attr("abs:href").ifEmpty { nickEl.attr("href") }
                                        if (!href.contains("showuser")) {
                                            href = nickEl.parent()?.attr("abs:href")?.ifEmpty { nickEl.parent()?.attr("href") } ?: ""
                                        }
                                        if (!href.contains("showuser")) {
                                            val parentLink = nickEl.closest("a[href*=showuser]")
                                            if (parentLink != null) {
                                                href = parentLink.attr("abs:href").ifEmpty { parentLink.attr("href") }
                                            }
                                        }
                                        var idMatch = Regex("showuser=(\\d+)").find(href)
                                        extractedAuthorId = idMatch?.groupValues?.get(1)
                                        
                                        if (extractedAuthorId == null) {
                                            val fallbackLink = prev.selectFirst("a[href*=showuser]")
                                            if (fallbackLink != null) {
                                                href = fallbackLink.attr("abs:href").ifEmpty { fallbackLink.attr("href") }
                                                extractedAuthorId = Regex("showuser=(\\d+)").find(href)?.groupValues?.get(1)
                                            }
                                        }
                                        break
                                    }
                                    prev = prev.previousElementSibling()
                                }
                            }
                        }

                        if (authorName == "Неизвестно") {
                            val nickEl = doc.selectFirst("a[href*=showuser], .post_nick a")
                            if (nickEl != null) {
                                authorName = nickEl.text().trim()
                                val href = nickEl.attr("abs:href").ifEmpty { nickEl.attr("href") }
                                val idMatch = Regex("showuser=(\\d+)").find(href)
                                extractedAuthorId = idMatch?.groupValues?.get(1)
                            } else {
                                val rx = Regex("class=\"[^\"]*nick[^\"]*\"[^>]*>\\s*<a[^>]*href=\"[^\"]*showuser=(\\d+)[^\"]*\"[^>]*>([^<]+)</a>")
                                val match = rx.find(fullHtml)
                                if (match != null) {
                                    extractedAuthorId = match.groupValues[1]
                                    authorName = match.groupValues[2].trim()
                                }
                            }
                        }
                        
                        // Count downloads from full page text since attachments might be spread out
                        val downloadScope = postWrapper?.html() ?: fullHtml
                        if (authorName == "Неизвестно") {
                            AppLogger.d(TAG, "DEBUG: Could not find author. First 3000 chars:\n${fullHtml.take(3000)}")
                        }
                        
                        // Count downloads from post scope
                        val downloadRegex = Regex("Скачиваний:\\s*(\\d+)", RegexOption.IGNORE_CASE)
                        val dlMatches = downloadRegex.findAll(downloadScope)
                        for (match in dlMatches) {
                            totalDownloads += match.groupValues[1].toIntOrNull() ?: 0
                        }
                        
                        AppLogger.d(TAG, "HTTP fallback result: author=$authorName downloads=$totalDownloads title=$title")
                        
                        // Extract file attachment URL if available
                        var bookFileUrl: String? = null
                        var bookFileName: String? = null
                        
                        // Search scope: try postWrapper first, then full doc
                        val searchScopes = mutableListOf<org.jsoup.nodes.Element>()
                        if (postWrapper != null) {
                            searchScopes.add(postWrapper)
                            AppLogger.d(TAG, "postWrapper found, searching for attachments inside it first")
                        } else {
                            AppLogger.d(TAG, "postWrapper is NULL — searching full document for attachments")
                        }
                        searchScopes.add(doc) // Always add full doc as fallback
                        
                        for (scope in searchScopes) {
                            AppLogger.d(TAG, "DUMPING HTML SCOPE (first 1000 chars): ${scope.html().take(1000)}")
                            if (bookFileUrl != null) break
                            val links = scope.select("a[href*=\"dl.4pda.to\"], a[href*=\"4pda.to/forum/dl/post/\"], a[href*=\"/forum/dl/post/\"], a[href*=\"act=attach\"]")
                            AppLogger.d(TAG, "Found ${links.size} potential attachment links in scope ${if (scope == doc) "FULL DOC" else "postWrapper"}")
                            
                            for (link in links) {
                                val href = link.attr("abs:href").ifEmpty { link.attr("href") }
                                val text = link.text().lowercase()
                                val hrefLower = href.lowercase()
                                
                                AppLogger.d(TAG, "Checking link: text='$text' href='$href'")
                                
                                val isImage = text.contains(".jpg") || text.contains(".png") || text.contains(".jpeg") || text.contains(".gif") || text.contains(".webp") ||
                                              hrefLower.endsWith(".jpg") || hrefLower.endsWith(".png") || hrefLower.endsWith(".jpeg") || hrefLower.endsWith(".gif") || hrefLower.endsWith(".webp")
                                
                                val isBookFile = !isImage && (text.contains(".fb2") || text.contains(".epub") || 
                                                 text.contains(".zip") || text.contains(".pdf") ||
                                                 hrefLower.endsWith(".fb2") || hrefLower.endsWith(".epub") ||
                                                 hrefLower.endsWith(".zip") || hrefLower.endsWith(".pdf") ||
                                                 hrefLower.contains(".fb2?") || hrefLower.contains(".epub?") ||
                                                 hrefLower.contains(".zip?") || hrefLower.contains(".pdf?"))
                                
                                if (isBookFile) {
                                    AppLogger.d(TAG, "Found book attachment in HTML: $text -> $href")
                                    bookFileUrl = when {
                                        href.startsWith("//") -> "https:$href"
                                        href.startsWith("/") -> "https://4pda.to$href"
                                        else -> href
                                    }
                                    
                                    var fname = link.text()
                                    val extMatch = Regex("(?i)(.*\\.(?:zip|fb2|epub|pdf))").find(fname)
                                    if (extMatch != null) {
                                        fname = extMatch.groupValues[1]
                                    } else {
                                        fname = "book.fb2.zip" // fallback filename (like in reference APK)
                                    }
                                    bookFileName = fname
                                    AppLogger.d(TAG, "Book file URL: $bookFileUrl, fileName: $bookFileName")
                                    break
                                }
                            }
                        }
                        
                        if (bookFileUrl == null) {
                            AppLogger.w(TAG, "No book file attachment found in HTML! Page title: $title, HTML length: ${fullHtml.length}")
                        }
                        
                        postData = JSONObject().apply {
                            put("postId", postId)
                            put("topicTitle", title)
                            put("authorName", authorName)
                            if (extractedAuthorId != null) put("authorId", extractedAuthorId)
                            put("totalDownloads", totalDownloads)
                            if (bookFileUrl != null) {
                                put("bookFileUrl", bookFileUrl)
                                put("bookFileName", bookFileName)
                            }
                        }
                    }
                    
                    if (postData != null) {
                        // If we have a book file URL, download it now
                        val bookFileUrl = postData?.optString("bookFileUrl", null)
                        val bookFileName = postData?.optString("bookFileName", null)
                        
                        if (!bookFileUrl.isNullOrEmpty()) {
                            try {
                                AppLogger.d(TAG, "Downloading attachment from $bookFileUrl ...")
                                
                                val sb = StringBuilder()
                                for (c in bookFileUrl) {
                                    if (c > '\u007F' || c == ' ') {
                                        sb.append(java.net.URLEncoder.encode(c.toString(), "UTF-8").replace("+", "%20"))
                                    } else {
                                        sb.append(c)
                                    }
                                }
                                val safeBookFileUrl = sb.toString()

                                val dohClient = okhttp3.OkHttpClient.Builder()
                                    .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                                    .build()
                                    
                                val customDns = object : okhttp3.Dns {
                                    override fun lookup(hostname: String): List<java.net.InetAddress> {
                                        try {
                                            return okhttp3.Dns.SYSTEM.lookup(hostname)
                                        } catch (e: java.net.UnknownHostException) {
                                            AppLogger.w(TAG, "System DNS failed for $hostname, trying DoH fallback...")
                                            try {
                                                val req = okhttp3.Request.Builder()
                                                    .url("https://dns.google/resolve?name=$hostname&type=A")
                                                    .build()
                                                val resp = dohClient.newCall(req).execute()
                                                val body = resp.body?.string()
                                                if (resp.isSuccessful && body != null) {
                                                    val json = org.json.JSONObject(body)
                                                    val answers = json.optJSONArray("Answer")
                                                    if (answers != null && answers.length() > 0) {
                                                        val ips = mutableListOf<java.net.InetAddress>()
                                                        for (i in 0 until answers.length()) {
                                                            val answer = answers.getJSONObject(i)
                                                            if (answer.optInt("type") == 1) { // A record
                                                                ips.add(java.net.InetAddress.getByName(answer.getString("data")))
                                                            }
                                                        }
                                                        if (ips.isNotEmpty()) {
                                                            AppLogger.d(TAG, "DoH resolved $hostname to ${ips.map { it.hostAddress }}")
                                                            return ips
                                                        }
                                                    }
                                                }
                                            } catch (ex: Exception) {
                                                AppLogger.e(TAG, "DoH lookup failed", ex)
                                            }
                                            throw e
                                        }
                                    }
                                }

                                val client = okhttp3.OkHttpClient.Builder()
                                    .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                                    .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                                    .dns(customDns)
                                    .followRedirects(true)
                                    .followSslRedirects(true)
                                    .addNetworkInterceptor { chain ->
                                        val builder = chain.request().newBuilder()
                                            .header("User-Agent", MOBILE_UA)
                                            .header("Cookie", allCookies)
                                            .header("Referer", url)
                                            .header("Accept", "*/*")
                                        chain.proceed(builder.build())
                                    }
                                    .build()

                                val req = okhttp3.Request.Builder()
                                    .url(safeBookFileUrl)
                                    .build()
                                    
                                val response = client.newCall(req).execute()
                                if (response.isSuccessful) {
                                    val bytes = response.body?.bytes()
                                    if (bytes != null && bytes.isNotEmpty()) {
                                        val headStr = String(bytes.take(200).toByteArray(), Charsets.UTF_8).lowercase()
                                        if (headStr.contains("<!doctype html") || headStr.contains("<html")) {
                                            val fullHtml = String(bytes, Charsets.UTF_8)
                                            val titleMatch = Regex("<title>(.*?)</title>", RegexOption.IGNORE_CASE).find(fullHtml)
                                            val pageTitle = titleMatch?.groupValues?.get(1)?.trim() ?: "HTML страница"
                                            AppLogger.e(TAG, "Server returned HTML instead of file! Title: $pageTitle")
                                            postData?.put("downloadError", "Сервер 4PDA вернул веб-страницу вместо файла: \"$pageTitle\". Возможно, проблема с авторизацией или ссылка устарела.")
                                        } else {
                                            val fileBase64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                                            postData?.put("fileBase64", fileBase64)
                                            postData?.put("fileName", bookFileName)
                                            AppLogger.d(TAG, "Attachment downloaded successfully: ${bytes.size} bytes")
                                        }
                                    } else {
                                        postData?.put("downloadError", "Файл пуст")
                                    }
                                } else {
                                    AppLogger.e(TAG, "Failed to download attachment: HTTP ${response.code}")
                                    postData?.put("downloadError", "HTTP ${response.code}")
                                }
                            } catch (e: Exception) {
                                AppLogger.e(TAG, "Error downloading attachment", e)
                                postData?.put("downloadError", "Ошибка сети: ${e.message}")
                            }
                        }
                        
                        withContext(Dispatchers.Main) {
                            val jsonBase64 = android.util.Base64.encodeToString(postData.toString().toByteArray(), android.util.Base64.NO_WRAP)
                            parserCallback("window.onPostParsed(decodeURIComponent(escape(atob('$jsonBase64'))))")
                        }
                    } else {
                        throw Exception("Не удалось получить данные поста.")
                    }
                    
                } catch (e: Exception) {
                    AppLogger.e(TAG, "Error parsing forum post", e)
                    withContext(Dispatchers.Main) {
                        val msgBase64 = android.util.Base64.encodeToString((e.message ?: "Неизвестная ошибка").toByteArray(), android.util.Base64.NO_WRAP)
                        parserCallback("window.onPostParseError(decodeURIComponent(escape(atob('$msgBase64'))))")
                        Toast.makeText(this@MainActivity, "Ошибка парсинга: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun processPendingPublication(json: String) {
        lifecycleScope.launch {
            try {
                val data = JSONObject(json)
                AppLogger.d(TAG, "processPendingPublication: ${data.optString("title")}")

                // Ждём пока JS форума отрендерит поля (onPageFinished срабатывает раньше)
                var formReady = false
                for (i in 1..40) {
                    delay(200)
                    formReady = withContext(Dispatchers.Main) {
                        suspendCancellableCoroutine { cont ->
                            webViewForum.evaluateJavascript(
                                "!!document.getElementById('forum-template-field-0-f218-t0')"
                            ) { result -> cont.resume(result == "true") }
                        }
                    }
                    if (formReady) {
                        AppLogger.d(TAG, "Form ready after ${i * 200}ms")
                        break
                    }
                }
                if (!formReady) {
                    throw Exception("Форма не загрузилась за 8 секунд. Проверьте соединение и авторизацию.")
                }

                // Fill form via WebDomAutomation (создаём раньше, чтобы использовать для загрузки файлов)
                val webDomAutomation = WebDomAutomation(webViewForum)

                // Диагностика формы перед заполнением
                withContext(Dispatchers.Main) {
                    webDomAutomation.diagnoseForm()
                }

                val authKey = withContext(Dispatchers.Main) {
                    webDomAutomation.extractAuthKey()
                }
                if (authKey.isNullOrEmpty()) {
                    throw Exception("Не удалось получить auth_key. Проверьте авторизацию.")
                }
                AppLogger.d(TAG, "authKey obtained: ${authKey.take(8)}...")

                // Step 1: Upload files через JS внутри WebView
                withContext(Dispatchers.Main) {
                    parserCallback("window.onPublishProgress('upload_files', '')")
                }

                var coverAttached = false
                var bookAttached = false

                // Step 1: Upload cover images sequentially
                val coverFiles = data.optJSONArray("coverFiles")
                var coversAttachedCount = 0
                
                if (coverFiles == null || coverFiles.length() == 0) {
                    val coverBase64 = data.optString("coverImageBase64")
                    if (coverBase64.isNotEmpty()) {
                        val coverMime = data.optString("coverMime", "image/jpeg")
                        val ext = if (coverMime.contains("png")) "png" else "jpg"
                        val coverFileName = "cover_upload.$ext"
                        try {
                            AppLogger.d(TAG, "Attaching fallback single cover: $coverFileName")
                            val success = withContext(Dispatchers.Main) {
                                webDomAutomation.attachFileToDomInput(
                                    coverBase64, coverFileName, coverMime, inputIndex = 0, dispatchChange = true
                                )
                            }
                            if (success) coversAttachedCount++
                        } catch (e: Exception) {
                            AppLogger.e(TAG, "Cover attach error: ${e.message}", e)
                        }
                    }
                } else {
                    for (i in 0 until coverFiles.length()) {
                        val fileObj = coverFiles.getJSONObject(i)
                        val b64 = fileObj.optString("data")
                        val name = fileObj.optString("name")
                        val mime = fileObj.optString("mime", "image/jpeg")
                        if (b64.isNotEmpty()) {
                            try {
                                AppLogger.d(TAG, "Attaching cover file $i: $name")
                                val isLast = i == coverFiles.length() - 1
                                val success = withContext(Dispatchers.Main) {
                                    webDomAutomation.attachFileToDomInput(
                                        b64, name, mime, inputIndex = 0, dispatchChange = isLast
                                    )
                                }
                                if (success) coversAttachedCount++
                                delay(if (isLast) 500L else 50L) // Increased delay only on last
                            } catch (e: Exception) {
                                AppLogger.e(TAG, "Cover file attach error ($name): ${e.message}", e)
                            }
                        }
                    }
                }
                coverAttached = coversAttachedCount > 0

                // Upload book files sequentially AND SAVE to Downloads
                // Use staged files (sent individually via stageBookFile bridge calls)
                val stagedFiles: List<Triple<String, String, String>>
                synchronized(stagedBookFiles) {
                    stagedFiles = stagedBookFiles.toList()
                    stagedBookFiles.clear()
                }
                
                val bookFilesAttached = mutableListOf<String>()
                var savedToDownloadsCount = 0
                
                if (stagedFiles.isNotEmpty()) {
                    AppLogger.d(TAG, "Processing ${stagedFiles.size} staged book files")
                    for ((idx, triple) in stagedFiles.withIndex()) {
                        val (name, b64, mime) = triple
                        
                        // 1. Save to Downloads
                        try {
                            saveFileToDownloads(b64, name)
                            savedToDownloadsCount++
                        } catch (e: Exception) {
                            AppLogger.e(TAG, "Error saving $name to Downloads: ${e.message}")
                        }

                        // 2. Attach to Forum
                        try {
                            AppLogger.d(TAG, "Attaching book file $idx: $name")
                            val isLast = idx == stagedFiles.lastIndex
                            var success = withContext(Dispatchers.Main) {
                                webDomAutomation.attachFileToDomInput(
                                    b64, name, mime, inputIndex = 1, dispatchChange = isLast
                                )
                            }
                            
                            if (!success) {
                                AppLogger.w(TAG, "Attachment to index 1 failed for $name, trying index 0")
                                success = withContext(Dispatchers.Main) {
                                    webDomAutomation.attachFileToDomInput(
                                        b64, name, mime, inputIndex = 0, dispatchChange = isLast
                                    )
                                }
                            }

                            if (success) {
                                bookFilesAttached.add(name)
                            } else {
                                AppLogger.e(TAG, "Failed to attach $name to any input")
                            }
                            delay(if (isLast) 800L else 50L)
                        } catch (e: Exception) {
                            AppLogger.e(TAG, "Book file attach error ($name): ${e.message}", e)
                        }
                    }
                } else {
                    AppLogger.w(TAG, "No staged book files found")
                }
                bookAttached = bookFilesAttached.isNotEmpty()
                val bookUploadMsg = if (bookFilesAttached.size > 1) "${bookFilesAttached.size} книг прикреплено" else if (bookAttached) "Книга прикреплена" else ""
                val coverUploadMsg = if (coversAttachedCount > 1) "$coversAttachedCount обложек прикреплено" else if (coverAttached) "Обложка прикреплена" else ""
                val savedMsg = if (savedToDownloadsCount > 0) "$savedToDownloadsCount файлов сохранено в Загрузки" else ""

                // Step 2: Upload done, fill form
                withContext(Dispatchers.Main) {
                    parserCallback("window.onPublishProgress('upload_done', '')")
                    parserCallback("window.onPublishProgress('fill_form', '')")
                }

                // Parse genres
                val genresArray = run {
                    val arr = data.optJSONArray("genres")
                    if (arr != null && arr.length() > 0) arr
                    else {
                        // Fallback: parse "genre" string field (comma-separated)
                        val genreStr = data.optString("genre")
                        if (genreStr.isNotEmpty()) {
                            org.json.JSONArray(genreStr.split(",").map { it.trim() }.filter { it.isNotEmpty() })
                        } else null
                    }
                }
                val genres = mutableListOf<String>()
                if (genresArray != null) {
                    for (i in 0 until genresArray.length()) {
                        genres.add(genresArray.getString(i))
                    }
                }

                // Build metadata
                val metadata = BookMetadata(
                    title = data.optString("title"),
                    authors = data.optString("author").split(",").map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { listOf("Неизвестный автор") },
                    genres = genres,
                    annotation = data.optString("annotation"),
                    series = data.optString("series").takeIf { it.isNotEmpty() },
                    seriesBooks = data.optString("seriesBooks").ifEmpty { data.optString("series") }.takeIf { it.isNotEmpty() },
                    authorInfo = data.optString("authorInfo").takeIf { it.isNotEmpty() },
                    downloads = if (data.has("downloads") && !data.isNull("downloads")) { val d = data.optInt("downloads"); if (d > 0) d else null } else null,
                    originalPostUrl = data.optString("originalPostUrl").takeIf { it.isNotEmpty() },
                    uploader = data.optString("uploader").takeIf { it.isNotEmpty() },
                                    publishYear = data.optString("year").takeIf { it.isNotEmpty() },
                publisher = data.optString("publisher").takeIf { it.isNotEmpty() },
                format = data.optString("format").takeIf { it.isNotEmpty() },
                language = data.optString("lang").takeIf { it.isNotEmpty() },
                quality = data.optString("quality").takeIf { it.isNotEmpty() },
                pages = data.optString("pages").takeIf { it.isNotEmpty() },
                webLink = data.optString("webLink").takeIf { it.isNotEmpty() },
                    enableSmilies = data.optBoolean("enableSmilies", false),
                    enableSignature = data.optBoolean("enableSignature", true),
                    enableEmailNotification = data.optBoolean("enableEmailNotification", false)
                )

                val bbCode = data.optString("bbcode")

                val fillSuccess = withContext(Dispatchers.Main) {
                    webDomAutomation.fillForm(metadata, authKey)
                }
                if (!fillSuccess) {
                    throw Exception("Ошибка заполнения формы")
                }

                // Вставить BB-код в поле текста поста
                if (bbCode.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        webDomAutomation.fillBbCode(bbCode)
                    }
                }

                // Complete (User must click Submit manually to review contents)
                withContext(Dispatchers.Main) {
                    val uploadMsg = buildString {
                        if (coverUploadMsg.isNotEmpty()) append("$coverUploadMsg. ")
                        if (bookUploadMsg.isNotEmpty()) append("$bookUploadMsg. ")
                        if (savedMsg.isNotEmpty()) append("$savedMsg. ")
                        if (!coverAttached && !bookAttached) append("Файлы не прикреплены — прикрепите вручную. ")
                    }
                    parserCallback("window.onPublishProgress('complete', '')")
                    Toast.makeText(this@MainActivity, "Форма заполнена. ${uploadMsg}Проверьте и отправьте!", Toast.LENGTH_LONG).show()
                }

            } catch (e: Exception) {
                AppLogger.e(TAG, "Publish error: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    val msg = (e.message ?: "Unknown error").escapeJs()
                    parserCallback("window.onPublishProgress('error', '$msg')")
                    Toast.makeText(this@MainActivity, "Ошибка: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // ════════════════════════════════════════════════
    //  FORUM JS INTERFACE (ForumBridge)
    // ════════════════════════════════════════════════
    inner class ForumJsInterface {
        @android.webkit.JavascriptInterface
        fun showToast(message: String) {
            runOnUiThread {
                Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ════════════════════════════════════════════════
    //  SEARCH RESULT EXTRACTION
    // ════════════════════════════════════════════════
    private fun extractSearchResults(view: WebView?) {
        view?.evaluateJavascript("""
            (function() {
                var results = [];
                var seen = {};
                var links = document.querySelectorAll('a[href]');
                for (var i = 0; i < links.length; i++) {
                    var a = links[i];
                    var href = (a.href || '').toString();
                    var text = (a.textContent || '').trim();
                    var tLower = text.toLowerCase();
                    if (tLower.indexOf('новинки книгохранилища') > -1 || tLower.indexOf('черновик') > -1) continue;
                    
                    if (href.indexOf('showtopic') > -1 && text.length > 3 && 
                        href.indexOf('view=') === -1 && href.indexOf('&p=') === -1 && href.indexOf('pid=') === -1 && href.indexOf('#entry') === -1) {
                        
                        var topicMatch = href.match(/showtopic=(\d+)/);
                        if (topicMatch) {
                            var baseUrl = 'https://4pda.to/forum/index.php?showtopic=' + topicMatch[1];
                            if (!seen[baseUrl]) {
                                seen[baseUrl] = true;
                                results.push({title: text, url: baseUrl});
                            }
                        }
                    }
                }
                return JSON.stringify(results);
            })()
        """.trimIndent()) { result ->
            AppLogger.d(TAG, "Search results raw: $result")
            if (result != null && result != "null" && result != "\"\"") {
                val cleanResult = result.trim('"').replace("\\\"", "\"").replace("\\\\", "\\")
                runOnUiThread {
                    parserCallback("window.onSearchFinished($cleanResult)")
                }
            } else {
                runOnUiThread {
                    parserCallback("window.onSearchFinished([])")
                }
            }
        }
    }

    // ════════════════════════════════════════════════
    //  VIEW TOGGLING (forum for debug)
    // ════════════════════════════════════════════════
    private fun showForumWebView() {
        isForumVisible = true
        webViewForum.visibility = View.VISIBLE
        webViewParser.visibility = View.GONE
    }

    private fun showParserWebView() {
        isForumVisible = false
        webViewParser.visibility = View.VISIBLE
        webViewForum.visibility = View.GONE
    }

    // ════════════════════════════════════════════════
    //  COOKIES / AUTH
    // ════════════════════════════════════════════════
    private fun saveCookies(cookies: String) {
        getSharedPreferences("4pda_cookies", MODE_PRIVATE).edit()
            .putString("cookies", cookies)
            .apply()
    }

    private fun getSavedCookies(): String? {
        return getSharedPreferences("4pda_cookies", MODE_PRIVATE).getString("cookies", null)
    }

    private fun saveUsername(username: String) {
        getSharedPreferences("4pda_cookies", MODE_PRIVATE).edit()
            .putString("username", username)
            .apply()
    }

    private fun getSavedUsername(): String? {
        return getSharedPreferences("4pda_cookies", MODE_PRIVATE).getString("username", null)
    }

    private fun restoreCookies(cookies: String) {
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookies.split(";").forEach { cookie ->
            val trimmed = cookie.trim()
            if (trimmed.isNotEmpty()) {
                cookieManager.setCookie(DOMAIN, trimmed)
            }
        }
        cookieManager.flush()
    }

    private fun clearCookies() {
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        getSharedPreferences("4pda_cookies", MODE_PRIVATE).edit()
            .remove("cookies")
            .remove("username")
            .apply()
    }

    /**
     * Extract username from the current forum page
     */
    private fun extractUsername(view: WebView?) {
        view?.evaluateJavascript("""
            (function() {
                var el = document.querySelector('.uname') || document.querySelector('[data-member-name]');
                if (el) return el.textContent.trim();
                // Fallback: look in header area
                var header = document.querySelector('#header-member-link');
                if (header) return header.textContent.trim();
                return null;
            })()
        """.trimIndent()) { result ->
            val name = result?.trim('"')
            if (!name.isNullOrEmpty() && name != "null") {
                saveUsername(name)
                AppLogger.d(TAG, "Extracted username from page: $name")
            }
        }
    }

    // ════════════════════════════════════════════════
    //  UTILITY
    // ════════════════════════════════════════════════
    private fun saveFileToDownloads(base64: String, fileName: String) {
        try {
            val bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
            val resolver = contentResolver
            val contentValues = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "application/zip")
                put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
            uri?.let {
                resolver.openOutputStream(it)?.use { outputStream ->
                    outputStream.write(bytes)
                }
                AppLogger.d(TAG, "File saved to Downloads: $fileName")
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to save file to Downloads ($fileName): ${e.message}", e)
        }
    }

    private fun parserCallback(js: String) {
        val safeJs = "try { $js } catch(e) { console.error('parserCallback error:', e); if(typeof actionLog==='function') actionLog('parserCallback error: ' + e.message, 'err'); }"
        AppLogger.d(TAG, "parserCallback: $safeJs")
        // Always run Javascript on the main thread
        runOnUiThread {
            webViewParser.evaluateJavascript(safeJs, null)
        }
    }

    private fun String.escapeJs(): String {
        return this.replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "")
    }

    override fun onBackPressed() {
        if (isForumVisible) {
            showParserWebView()
        } else if (webViewParser.canGoBack()) {
            webViewParser.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        AppLogger.removeListener(logListener)
        webViewParser.destroy()
        webViewForum.destroy()
        super.onDestroy()
    }
}
