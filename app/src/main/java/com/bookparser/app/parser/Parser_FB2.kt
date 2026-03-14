package com.bookparser.app.parser

import android.content.Context
import android.net.Uri
import android.util.Log
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStreamReader

data class Fb2Author(
    val firstName: String? = null,
    val lastName: String? = null,
    val middleName: String? = null
) {
    fun toDisplayString(): String {
        val parts = listOfNotNull(lastName, firstName, middleName)
        return parts.joinToString(" ")
    }
}

data class Fb2Series(
    val name: String? = null,
    val index: Int? = null
)

data class Fb2Metadata(
    val authors: List<Fb2Author> = emptyList(),
    val title: String = "Без названия",
    val annotation: String = "",
    val genres: List<String> = emptyList(),
    val series: Fb2Series? = null,
    val coverImageBase64: String? = null
)

class Parser_FB2(private val context: Context) {

    fun parse(uri: Uri): Fb2Metadata {
        try {
            val inputStream = context.contentResolver.openInputStream(uri)
                ?: throw Exception("Не удалось открыть файл")
    
            val reader = InputStreamReader(inputStream, detectEncoding(uri))
            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            parser.setInput(reader)
    
            var eventType = parser.eventType
            var insideDescription = false
            var insideTitleInfo = false
            var insideAuthor = false
            var insideAnnotation = false
            var insideCoverpage = false
            var insideBinary = false
    
            val authors = mutableListOf<Fb2Author>()
            var currentAuthor = Fb2Author()
            var title = "Без названия"
            val annotationBuilder = StringBuilder()
            val genres = mutableListOf<String>()
            var series: Fb2Series? = null
            var coverHref: String? = null
            val binaryImages = mutableMapOf<String, String>()
            var currentBinaryId: String? = null
    
            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        when (parser.name) {
                            "description" -> insideDescription = true
                            "title-info" -> if (insideDescription) insideTitleInfo = true
                            "author" -> if (insideTitleInfo) {
                                insideAuthor = true
                                currentAuthor = Fb2Author()
                            }
                            "first-name" -> if (insideAuthor) {
                                currentAuthor = currentAuthor.copy(firstName = parser.nextText())
                            }
                            "last-name" -> if (insideAuthor) {
                                currentAuthor = currentAuthor.copy(lastName = parser.nextText())
                            }
                            "middle-name" -> if (insideAuthor) {
                                currentAuthor = currentAuthor.copy(middleName = parser.nextText())
                            }
                            "book-title" -> if (insideTitleInfo) {
                                title = parser.nextText()
                            }
                            "genre" -> if (insideTitleInfo) {
                                genres.add(parser.nextText())
                            }
                            "annotation" -> if (insideTitleInfo) {
                                insideAnnotation = true
                            }
                            "p" -> if (insideAnnotation) {
                                annotationBuilder.append(parser.nextText()).append("\n")
                            }
                            "sequence" -> if (insideTitleInfo) {
                                val name = parser.getAttributeValue(null, "name")
                                val number = parser.getAttributeValue(null, "number")?.toIntOrNull()
                                series = Fb2Series(name, number)
                            }
                            "coverpage" -> if (insideTitleInfo) {
                                insideCoverpage = true
                            }
                            "image" -> if (insideCoverpage) {
                                coverHref = parser.getAttributeValue(null, "href")
                                    ?: parser.getAttributeValue(null, "l:href")
                            }
                            "binary" -> {
                                insideBinary = true
                                currentBinaryId = parser.getAttributeValue(null, "id")
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        when (parser.name) {
                            "description" -> insideDescription = false
                            "title-info" -> insideTitleInfo = false
                            "author" -> {
                                if (insideAuthor) {
                                    authors.add(currentAuthor)
                                    insideAuthor = false
                                }
                            }
                            "annotation" -> insideAnnotation = false
                            "coverpage" -> insideCoverpage = false
                            "binary" -> {
                                insideBinary = false
                                currentBinaryId = null
                            }
                        }
                    }
                    XmlPullParser.TEXT -> {
                        if (insideBinary && currentBinaryId != null) {
                            binaryImages[currentBinaryId] = parser.text
                        }
                    }
                }
                eventType = parser.next()
            }
    
            val coverBase64 = coverHref?.let { href ->
                val cleanHref = href.removePrefix("#")
                binaryImages[cleanHref]
            }
    
            return Fb2Metadata(
                authors = authors,
                title = title,
                annotation = annotationBuilder.toString().trim(),
                genres = genres,
                series = series,
                coverImageBase64 = coverBase64
            )
    
        } catch (e: Exception) {
            Log.e("Parser_FB2", "Ошибка парсинга FB2: ${e.message}", e)
            throw Exception("Не удалось прочитать метаданные из FB2: ${e.message}")
        }
    }
    
    private fun detectEncoding(uri: Uri): String {
        val inputStream = context.contentResolver.openInputStream(uri) ?: return "UTF-8"
        val buffer = ByteArray(200)
        val bytesRead = inputStream.read(buffer)
        inputStream.close()
    
        if (bytesRead > 0) {
            val header = String(buffer, 0, bytesRead, Charsets.UTF_8)
            val encodingRegex = Regex("""encoding=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            val match = encodingRegex.find(header)
            if (match != null) {
                return match.groupValues[1].uppercase()
            }
        }
        return "UTF-8"
    }
}
