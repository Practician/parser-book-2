package com.bookparser.app

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Встроенный логгер: перехватывает Log.d/i/w/e и хранит последние 500 строк в памяти.
 * Слушатели уведомляются на любом потоке — в UI обязательно runOnUiThread.
 */
object AppLogger {

    private const val MAX_LINES = 500
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    private val lines = CopyOnWriteArrayList<String>()
    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()

    fun d(tag: String, msg: String) { append("D", tag, msg); Log.d(tag, msg) }
    fun i(tag: String, msg: String) { append("I", tag, msg); Log.i(tag, msg) }
    fun w(tag: String, msg: String) { append("W", tag, msg); Log.w(tag, msg) }
    fun e(tag: String, msg: String, t: Throwable? = null) {
        val full = if (t != null) "$msg\n${t.stackTraceToString()}" else msg
        append("E", tag, full)
        if (t != null) Log.e(tag, msg, t) else Log.e(tag, msg)
    }

    private fun append(level: String, tag: String, msg: String) {
        // Logging to UI disabled per user request
        /*
        val line = "${fmt.format(Date())} $level/$tag: $msg"
        if (lines.size >= MAX_LINES) lines.removeAt(0)
        lines.add(line)
        listeners.forEach { it(line) }
        */
    }

    fun addListener(l: (String) -> Unit) { listeners.add(l) }
    fun removeListener(l: (String) -> Unit) { listeners.remove(l) }

    fun getAll(): String = lines.joinToString("\n")
    fun clear() { lines.clear() }
}
