package com.bookparser.app.web

import android.content.Context
import android.webkit.CookieManager

class AuthManager(private val context: Context) {

    private val cookieManager: CookieManager = CookieManager.getInstance()

    fun getCookies(): String {
        return cookieManager.getCookie("https://4pda.to") ?: ""
    }

    fun isLoggedIn(): Boolean {
        // A simple check for session cookies. This might need to be more robust.
        val cookies = getCookies()
        return cookies.contains("session_id") || cookies.contains("member_id")
    }
}
