package com.bookparser.app.api

import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class FourPDAWebSocketClient(
    private val memberId: String,
    private val passHash: String,
    private val userAgent: String = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Mobile Safari/537.36"
) {
    companion object {
        private const val TAG = "FourPDAWSClient"
        private const val WS_URL = "wss://4pda.to/ws"
    }

    private val client = OkHttpClient()
    private var webSocket: WebSocket? = null
    private var messageIdCounter = 1
    
    // Commands mapped by message ID
    private val pendingCommands = mutableMapOf<Int, (JSONArray) -> Unit>()
    private var handshakeCompletion: ((Boolean) -> Unit)? = null

    /**
     * Connects to the WebSocket and performs the "ah" handshake
     */
    suspend fun connect(): Boolean = suspendCancellableCoroutine { continuation ->
        val cookieHeader = "member_id=$memberId; pass_hash=$passHash"
        val request = Request.Builder()
            .url(WS_URL)
            .addHeader("Cookie", cookieHeader)
            .addHeader("User-Agent", userAgent)
            .addHeader("Origin", "https://4pda.to")
            .build()
            
        handshakeCompletion = { success ->
            if (continuation.isActive) {
                continuation.resume(success)
            }
        }

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "WebSocket opened. Sending handshake.")
                val handshakeId = messageIdCounter++
                // format: [1, "ah", 147, "ru.fourpda.client-1.4.7", "userAgent", 0, 0]
                val handshakeContent = JSONArray().apply {
                    put(handshakeId)
                    put("ah")
                    put(147)
                    put("ru.fourpda.client-1.4.7")
                    put(userAgent)
                    put(0)
                    put(0)
                }
                
                pendingCommands[handshakeId] = { result ->
                    // Successful handshake returns [id, 0] or similar 
                    handshakeCompletion?.invoke(true)
                    handshakeCompletion = null
                }
                
                webSocket.send(handshakeContent.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "Received message: $text")
                handleMessageText(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                val text = bytes.string(java.nio.charset.Charset.forName("windows-1251"))
                Log.d(TAG, "Received binary message decoded: $text")
                handleMessageText(text)
            }

            private fun handleMessageText(text: String) {
                try {
                    val jsonArray = JSONArray(text)
                    val id = jsonArray.getInt(0)
                    // If element 1 is an integer, it's the status code (0 = success)
                    // If element 1 is a string, it might be an ongoing stream or error 
                    // To keep it simple, we just pass the full array to the callback
                    
                    pendingCommands.remove(id)?.invoke(jsonArray)
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing message: $text", e)
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket closed")
                handshakeCompletion?.invoke(false)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure", t)
                handshakeCompletion?.invoke(false)
                handshakeCompletion = null
            }
        })
    }

    /**
     * Sends a command and waits for its response
     */
    private suspend fun sendCommand(command: String, args: List<Any>): JSONArray = suspendCancellableCoroutine { continuation ->
        val id = messageIdCounter++
        val jsonArray = JSONArray().apply {
            put(id)
            put(command)
            args.forEach { put(it) }
        }
        
        pendingCommands[id] = { result ->
            if (continuation.isActive) {
                val status = result.optInt(1, -1)
                if (status == 0 || command == "ah") { // status 0 is success
                    continuation.resume(result)
                } else {
                    continuation.resumeWithException(Exception("API returned error status: $status, full result: $result"))
                }
            }
        }
        
        webSocket?.send(jsonArray.toString()) ?: run {
            if (continuation.isActive) {
                continuation.resumeWithException(Exception("WebSocket is not connected"))
            }
        }
    }

    /**
     * Forum Jump: gets forumId and topicId info 
     */
    suspend fun forumJump(topicId: Int): JSONArray {
        // "fj", [3, topicId]
        return sendCommand("fj", listOf(3, topicId))
    }

    /**
     * Forum Read: gets the topic content block
     */
    suspend fun forumRead(forumId: Int, topicId: Int): JSONArray {
        // "fr", [forumId, topicId, 1]
        return sendCommand("fr", listOf(forumId, topicId, 1))
    }

    fun close() {
        webSocket?.close(1000, "Done")
        client.dispatcher.executorService.shutdown()
    }

    suspend fun getPostData(postId: Int): JSONObject? {
        val fjResult = forumJump(postId)
        if (fjResult.optInt(1) != 0) return null
        
        val forumId = fjResult.getInt(2)
        val topicId = fjResult.getInt(3)
        Log.i(TAG, "getPostData: found forumId=$forumId, topicId=$topicId for post $postId")
        
        val frResult = forumRead(forumId, topicId)
        if (frResult.optInt(1) != 0) return null
        
        // Topic info usually at index 2 or 3.
        val topicTitle = frResult.optJSONArray(3)?.optString(0) ?: "Unknown Title"
        Log.i(TAG, "Topic title: $topicTitle")
        
        val postsArray = frResult.optJSONArray(14) ?: return null
        Log.i(TAG, "Found ${postsArray.length()} posts in topic.")
        
        // Find the target post and the first post (шапка)
        var targetPost: JSONArray? = null
        var firstPost: JSONArray? = null
        
        for (i in 0 until postsArray.length()) {
            val post = postsArray.optJSONArray(i) ?: continue
            val pId = post.getInt(0)
            if (i == 0) firstPost = post
            if (pId == postId) {
                targetPost = post
                break
            }
        }
        
        // If target post is not found on this page, return null
        // so the HTML fallback in MainActivity can fetch the correct page
        if (targetPost == null) {
            Log.i(TAG, "Target post $postId not found in first page of topic, returning null for HTML fallback")
            return null
        }
        
        val authorId = targetPost.optString(2)
        val authorName = targetPost.optString(3)
        Log.i(TAG, "MATCHED POST! Author: $authorName (ID: $authorId)")
        
        var totalDownloads = 0
        var bookFileUrl: String? = null
        var bookFileName: String? = null
        
        // Helper to extract attachments from a post
        fun extractAttachments(post: JSONArray) {
            val attachments = post.optJSONArray(11)
            if (attachments != null) {
                for (j in 0 until attachments.length()) {
                    val att = attachments.optJSONArray(j) ?: continue
                    val attId = att.optInt(0)
                    val attFilename = att.optString(2)
                    val attDownloads = att.optInt(4)
                    Log.i(TAG, "Attachment $j: $attFilename (Downloads: $attDownloads)")
                    val isBookFile = attFilename.endsWith(".fb2", true) || 
                                     attFilename.endsWith(".epub", true) || 
                                     attFilename.endsWith(".zip", true) || 
                                     attFilename.endsWith(".pdf", true)
                    
                    if (isBookFile) {
                        totalDownloads += attDownloads
                        if (bookFileUrl == null) {
                            val encodedName = java.net.URLEncoder.encode(attFilename, "UTF-8").replace("+", "%20")
                            bookFileUrl = "https://4pda.to/forum/dl/post/$attId/$encodedName"
                            bookFileName = attFilename
                            Log.i(TAG, "Found book file in WebSocket attachments: $bookFileUrl")
                        }
                    }
                }
            }
        }
        
        // Extract from the target post
        extractAttachments(targetPost)
        
        // If no book file found on target post and first post is different, try first post (шапка)
        if (bookFileUrl == null && firstPost != null && firstPost != targetPost) {
            Log.i(TAG, "No book file on target post, checking first post (шапка)...")
            extractAttachments(firstPost)
        }
        
        val result = JSONObject().apply {
            put("postId", postId)
            put("topicId", topicId)
            put("topicTitle", topicTitle)
            put("authorId", authorId)
            put("authorName", authorName)
            put("totalDownloads", totalDownloads)
            if (bookFileUrl != null) {
                put("bookFileUrl", bookFileUrl)
                put("bookFileName", bookFileName)
            }
        }
        return result
    }
}
