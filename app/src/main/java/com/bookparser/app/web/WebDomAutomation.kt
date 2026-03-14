package com.bookparser.app.web

import android.util.Log
import android.webkit.WebView
import com.bookparser.app.AppLogger
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import com.bookparser.app.processing.BookMetadata
import com.bookparser.app.parser.GenreMapping
import org.json.JSONArray

class WebDomAutomation(private val webView: WebView) {

    companion object {
        private const val TAG = "WebDomAutomation"
    }

    suspend fun extractAuthKey(): String? = suspendCancellableCoroutine { continuation ->
        webView.evaluateJavascript(
            """(function() {
                var input = document.querySelector('input[name="auth_key"]');
                return input ? input.value : null;
            })();"""
        ) { result ->
            if (continuation.isActive) continuation.resume(result?.trim('"'))
        }
    }

    /**
     * Диагностика: логирует все поля шаблона и file input-ы
     */
    suspend fun diagnoseForm(): String = suspendCancellableCoroutine { continuation ->
        webView.evaluateJavascript(
            """(function() {
                var result = [];
                var fields = document.querySelectorAll('[name^="forum-template-field"]');
                fields.forEach(function(el) {
                    result.push(el.tagName + ' name=' + el.name + ' id=' + el.id + ' type=' + (el.type||''));
                });
                var dfrms = document.querySelectorAll('div.dfrms');
                dfrms.forEach(function(d, i) {
                    var inp = d.querySelector('input[type="file"]');
                    result.push('dfrms['+i+'] text=' + d.innerText.substring(0,50).replace(/\n/g,' ') + ' hasFileInput=' + !!inp);
                });
                ['ed-0','ed-1'].forEach(function(id) {
                    var el = document.getElementById(id);
                    result.push('id=' + id + ' found=' + !!el + (el ? ' tag=' + el.tagName : ''));
                });
                return JSON.stringify(result);
            })();"""
        ) { result ->
            AppLogger.d(TAG, "=== FORM DIAGNOSIS ===\n$result")
            if (continuation.isActive) continuation.resume(result ?: "null")
        }
    }

    /**
     * Заполняет поля формы по name-атрибуту (надёжнее чем по id).
     * Раскладка полей (из topicCreator.ts):
     *   [0] авторы, [1] название, [2] серия выкладываемая, [3] жанры (чекбоксы),
     *   [4] год, [5] издательство, [6] аннотация, [7] инфо об авторе (не заполняем),
     *   [8] качество, [9] страниц, [10] формат, [11] язык,
     *   [12] ссылка в сети, [13] скачивания, [14] оригинальный пост, [15] автор релиза
     */
    suspend fun fillForm(metadata: BookMetadata, authKey: String): Boolean =
        suspendCancellableCoroutine { continuation ->

        val genreValues = metadata.genres.take(3).mapNotNull { GenreMapping.genreToValue[it] }

        AppLogger.d(TAG, "fillForm: title=${metadata.title} authors=${metadata.authors} annotation_len=${metadata.annotation?.length ?: 0}")
        AppLogger.d(TAG, "fillForm: genres=$genreValues year=${metadata.publishYear} publisher=${metadata.publisher}")

        val js = """
            (function() {
                var log = [];
                try {
                    function setByName(name, v) {
                        var el = document.querySelector('[name="' + name + '"]');
                        if (el) {
                            el.value = v;
                            el.dispatchEvent(new Event('input', { bubbles: true }));
                            el.dispatchEvent(new Event('change', { bubbles: true }));
                            log.push('OK:' + name);
                        } else {
                            log.push('MISS:' + name);
                        }
                    }

                    setByName('forum-template-field[0]', '${metadata.authors.joinToString(", ").escapeJs()}');
                    setByName('forum-template-field[1]', '${metadata.title.escapeJs()}');
                    setByName('forum-template-field[2]', '${metadata.seriesBooks?.escapeJs() ?: ""}');

                    var selectedGenres = ${JSONArray(genreValues)};
                    var genreCbs = document.querySelectorAll('input[name="forum-template-field[3][]"]');
                    var sampleVals = [];
                    for (var gi = 0; gi < Math.min(5, genreCbs.length); gi++) {
                        sampleVals.push(genreCbs[gi].value);
                    }
                    log.push('genres_cbs:' + genreCbs.length + ' need:' + JSON.stringify(selectedGenres) + ' sample_vals:' + JSON.stringify(sampleVals));
                    var matched = 0;
                    genreCbs.forEach(function(cb) {
                        var shouldCheck = selectedGenres.includes(cb.value);
                        if (shouldCheck) matched++;
                        cb.checked = shouldCheck;
                        cb.dispatchEvent(new Event('change', { bubbles: true }));
                    });
                    log.push('genres_matched:' + matched + '/' + selectedGenres.length);

                    setByName('forum-template-field[4]', '${metadata.publishYear?.escapeJs() ?: ""}');
                    // Removed extra unsupported fields that map to wrong inputs and cause "ru" series bugs
                    // setByName('forum-template-field[5]', ...);
                    setByName('forum-template-field[6]', '${metadata.annotation?.escapeJs() ?: ""}');
                    // setByName('forum-template-field[8]', ...);
                    // setByName('forum-template-field[9]', ...);
                    // setByName('forum-template-field[10]', ...);
                    // setByName('forum-template-field[11]', '');  <-- This was causing the "ru" bug!
                    // setByName('forum-template-field[12]', ...);
                    setByName('forum-template-field[13]', '${metadata.downloads ?: ""}');
                    setByName('forum-template-field[14]', '${metadata.originalPostUrl?.escapeJs() ?: ""}');
                    setByName('forum-template-field[15]', '${metadata.uploader?.escapeJs() ?: ""}');

                    function setCheckbox(name, checked) {
                        var cb = document.querySelector('input[name="' + name + '"]');
                        if (cb) { cb.checked = checked; cb.dispatchEvent(new Event('change', { bubbles: true })); log.push('CB:' + name + '=' + checked); }
                        else { log.push('CB_MISS:' + name); }
                    }
                    setCheckbox('enableemo', ${metadata.enableSmilies});
                    setCheckbox('enablesig', ${metadata.enableSignature});
                    setCheckbox('enableemail', ${metadata.enableEmailNotification});

                    return 'SUCCESS|' + log.join(';');
                } catch (e) {
                    return 'ERROR:' + e.message + '|' + log.join(';');
                }
            })();
        """.trimIndent()

        webView.evaluateJavascript(js) { result ->
            AppLogger.d(TAG, "fillForm result: $result")
            if (continuation.isActive) continuation.resume(result?.contains("SUCCESS") == true)
        }
    }

    /**
     * Вставляет BB-код в текстовое поле поста.
     * На 4PDA act=zfw поле Post скрыто — видимый редактор это TinyMCE iframe (ed-0_ifr)
     * или скрытая textarea. Пишем в оба варианта.
     */
    suspend fun fillBbCode(bbCode: String): Boolean = suspendCancellableCoroutine { continuation ->
        val escaped = bbCode.escapeJs()
        webView.evaluateJavascript(
            """(function() {
                var log = [];
                var text = '$escaped';

                // Вариант 1: textarea с именем Post/post (стандартный BB-режим)
                var ta = document.querySelector('textarea[name="Post"]')
                    || document.querySelector('textarea[name="post"]')
                    || document.getElementById('ed-0')
                    || document.getElementById('ed-1');

                if (ta) {
                    ta.value = text;
                    ta.dispatchEvent(new Event('input', { bubbles: true }));
                    ta.dispatchEvent(new Event('change', { bubbles: true }));
                    try { if (typeof updateRTE === 'function') updateRTE(ta.id); } catch(e) {}
                    log.push('textarea:' + ta.name + '/' + ta.id);
                }

                // Вариант 2: TinyMCE iframe (по id или любой iframe в форме REPLIER)
                var ifr = document.getElementById('ed-0_ifr')
                    || document.getElementById('ed-1_ifr');
                if (!ifr) {
                    var form = document.querySelector('form[name="REPLIER"]') || document.querySelector('form');
                    if (form) {
                        ifr = form.querySelector('iframe');
                    }
                }
                if (!ifr) {
                    // Последняя попытка: любой iframe на странице
                    var allIframes = document.querySelectorAll('iframe');
                    for (var i = 0; i < allIframes.length; i++) {
                        try {
                            if (allIframes[i].contentDocument && allIframes[i].contentDocument.body) {
                                ifr = allIframes[i];
                                break;
                            }
                        } catch(e) {}
                    }
                }
                if (ifr) {
                    try {
                        var body = ifr.contentDocument ? ifr.contentDocument.body : null;
                        if (body) {
                            body.innerHTML = text.replace(/\n/g, '<br>');
                            log.push('iframe:' + (ifr.id || 'no-id'));
                        }
                    } catch(e) {
                        log.push('iframe_error:' + e.message);
                    }
                }

                // Вариант 3: contenteditable div
                var ce = document.querySelector('[contenteditable="true"]:not([class*="template"])');
                if (ce) {
                    ce.innerText = text;
                    ce.dispatchEvent(new Event('input', { bubbles: true }));
                    log.push('contenteditable:' + ce.id);
                }

                // Вариант 4: если ничего не нашли — создаём скрытую textarea Post в форме
                if (log.length === 0) {
                    var form = document.querySelector('form[name="REPLIER"]') || document.querySelector('form');
                    if (form) {
                        var newTa = document.createElement('textarea');
                        newTa.name = 'Post';
                        newTa.style.display = 'none';
                        newTa.value = text;
                        form.appendChild(newTa);
                        log.push('created_hidden_textarea');
                    }
                }

                if (log.length === 0) {
                    var allTa = Array.from(document.querySelectorAll('textarea')).map(function(t){ return t.name+'/'+t.id; });
                    var allIframes = Array.from(document.querySelectorAll('iframe')).map(function(f){ return f.id+'/'+f.name; });
                    var allCE = Array.from(document.querySelectorAll('[contenteditable]')).map(function(e){ return e.tagName+'/'+e.id+'/'+e.className.substring(0,30); });
                    return 'NOT_FOUND|ta:' + JSON.stringify(allTa) + '|iframes:' + JSON.stringify(allIframes) + '|ce:' + JSON.stringify(allCE);
                }

                return 'OK|' + log.join(';');
            })();"""
        ) { result ->
            AppLogger.d(TAG, "fillBbCode result: $result")
            if (continuation.isActive) continuation.resume(result?.contains("OK") == true)
        }
    }

    /** Helper: evaluate JS on main thread and return result via suspendCancellableCoroutine */
    private suspend fun evalJs(js: String): String? = suspendCancellableCoroutine { cont ->
        webView.evaluateJavascript(js) { result ->
            if (cont.isActive) cont.resume(result)
        }
    }

    /**
     * Внедряет base64 как File в DOM-элемент <input type="file" name="FILE_UPLOAD[]">
     */
    suspend fun attachFileToDomInput(
        base64Data: String,
        fileName: String,
        mimeType: String,
        inputIndex: Int = 0,
        dispatchChange: Boolean = true
    ): Boolean {
        AppLogger.d(TAG, "attachFileToDomInput: fileName=$fileName mimeType=$mimeType base64len=${base64Data.length}")

        val escapedFileName = fileName.escapeJs()
        val escapedMimeType = mimeType.escapeJs()

        // 1. Инжектим base64 по частям, чтобы избежать лимитов WebView (уменьшено до 500k)
        val chunkSize = 500_000
        val chunks = base64Data.chunked(chunkSize)

        evalJs("window.__uploadBase64 = '';")
        for ((i, chunk) in chunks.withIndex()) {
            val escapedChunk = chunk.replace("\\", "\\\\").replace("'", "\\'")
            evalJs("window.__uploadBase64 += '$escapedChunk';")
            if (i % 5 == 4) AppLogger.d(TAG, "attachFileToDomInput: injected chunk ${i + 1}/${chunks.size}")
        }

        // 2. Создаем File из base64 и добавляем его через DataTransfer в input
        val injectJs = """
            (function() {
                try {
                    var b64 = window.__uploadBase64;
                    if (!b64) return 'ERROR:no_base64_data';

                    var raw = atob(b64);
                    var bytes = new Uint8Array(raw.length);
                    for (var i = 0; i < raw.length; i++) bytes[i] = raw.charCodeAt(i);
                    var file = new File([bytes], '$escapedFileName', {type: '$escapedMimeType'});
                    window.__uploadBase64 = null; // очищаем память

                    var fileInputs = document.querySelectorAll('input[type="file"]');
                    if (fileInputs.length === 0) return 'ERROR:no_file_inputs_found';
                    
                    var fileInput = fileInputs[$inputIndex]; 
                    if (!fileInput) {
                        // If index is out of bounds, use the first one
                        fileInput = fileInputs[0];
                    }
                    
                    // Enable multiple files support
                    fileInput.multiple = true;
                    
                    // Используем DataTransfer чтобы подменить FileList
                    var dt = new DataTransfer();
                    // Добавляем уже существующие файлы, если есть
                    if (fileInput.files && fileInput.files.length > 0) {
                        for (var i = 0; i < fileInput.files.length; i++) {
                            dt.items.add(fileInput.files[i]);
                        }
                    }
                    // Добавляем наш новый файл
                    dt.items.add(file);
                    
                    fileInput.files = dt.files;
                    
                    if ($dispatchChange) {
                        // Оповещаем скрипты 4PDA об изменениях
                        fileInput.dispatchEvent(new Event('change', { bubbles: true }));
                    }

                    return 'SUCCESS:' + fileInput.name + ':' + fileInput.files.length;
                } catch(e) {
                    return 'ERROR:' + e.message;
                }
            })();
        """.trimIndent()

        val result = evalJs(injectJs)
        AppLogger.d(TAG, "attachFileToDomInput result: $result")
        return result?.startsWith("\"SUCCESS") == true || result?.startsWith("SUCCESS") == true
    }

    fun submitPreview() {
        webView.evaluateJavascript(
            "(function(){var b=document.querySelector('input[name=\"preview\"]'); if(b) b.click();})()", null
        )
    }

    fun submitForm() {
        webView.evaluateJavascript(
            "(function(){var b=document.querySelector('input[name=\"submit\"]'); if(b) b.click();})()", null
        )
    }

    private fun String.escapeJs(): String {
        return this.replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "")
    }
}
