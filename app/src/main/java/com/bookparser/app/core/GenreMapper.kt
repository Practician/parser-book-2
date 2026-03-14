package com.bookparser.app.core

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException

class GenreMapper(private val context: Context) {

    private val genreMap: Map<String, String> by lazy {
        loadGenreMappings()
    }

    private fun loadGenreMappings(): Map<String, String> {
        return try {
            val jsonString = context.assets.open("genreMappings.json").bufferedReader().use { it.readText() }
            Json.decodeFromString<Map<String, String>>(jsonString)
        } catch (ioException: IOException) {
            ioException.printStackTrace()
            emptyMap()
        }
    }

    fun mapGenres(fb2Genres: List<String>): List<String> {
        return fb2Genres.mapNotNull { genreMap[it] }
    }
}
