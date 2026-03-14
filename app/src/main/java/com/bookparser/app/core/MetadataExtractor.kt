package com.bookparser.app.core

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Xml
import com.bookparser.app.processing.BookMetadata
import com.bookparser.app.processing.BookProcessingResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.util.zip.ZipInputStream

class MetadataExtractor(private val context: Context) {

    suspend fun extract(uri: Uri): BookProcessingResult = withContext(Dispatchers.IO) {
        val metadata = BookMetadata()
        val inputStream = context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("Could not open input stream for a given uri")
        when (uri.path?.substringAfterLast('.', "")) {
            "epub" -> parseEpub(inputStream, metadata, uri)
            "fb2" -> parseFb2(inputStream, metadata)
            else -> throw IllegalArgumentException("Unsupported file type")
        }
        BookProcessingResult(
            metadata = metadata,
            assets = com.bookparser.app.processing.BookAssets(), // Assets will be handled later
            originalName = uri.path?.substringAfterLast('/') ?: "unknown"
        )
    }

    private fun parseEpub(inputStream: InputStream, metadata: BookMetadata, uri: Uri) {
        val zipInputStream = ZipInputStream(inputStream)
        var entry = zipInputStream.nextEntry
        var opfFilePath: String? = null

        // First pass: find the .opf file path from container.xml
        while (entry != null) {
            if (entry.name == "META-INF/container.xml") {
                opfFilePath = parseContainerXml(zipInputStream)
                break
            }
            entry = zipInputStream.nextEntry
        }

        if (opfFilePath == null) throw IllegalStateException("container.xml not found or failed to parse")

        // Second pass: find and parse the .opf file and extract cover
        // We need to re-open the stream to parse from the beginning
        context.contentResolver.openInputStream(uri)?.use { secondInputStream ->
            val secondZipStream = ZipInputStream(secondInputStream)
            var secondEntry = secondZipStream.nextEntry
            var coverImageId: String? = null

            while (secondEntry != null) {
                if (secondEntry.name == opfFilePath) {
                    coverImageId = parseOpf(secondZipStream, metadata)
                    break // Found and parsed, can stop this loop
                }
                secondEntry = secondZipStream.nextEntry
            }

            if (coverImageId != null) {
                 context.contentResolver.openInputStream(uri)?.use { thirdInputStream ->
                    val thirdZipStream = ZipInputStream(thirdInputStream)
                    var thirdEntry = thirdZipStream.nextEntry
                    while(thirdEntry != null) {
                        // Find the entry corresponding to the cover image id
                        if (thirdEntry.name.contains(coverImageId)) {
                            metadata.coverImage = thirdZipStream.readBytes()
                            break
                        }
                        thirdEntry = thirdZipStream.nextEntry
                    }
                }
            }
        }
    }

    private fun parseContainerXml(inputStream: InputStream): String? {
        val parser = Xml.newPullParser()
        parser.setInput(inputStream, null)
        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG && parser.name == "rootfile") {
                return parser.getAttributeValue(null, "full-path")
            }
            eventType = parser.next()
        }
        return null
    }

    private fun parseOpf(inputStream: InputStream, metadata: BookMetadata): String? {
        val parser = Xml.newPullParser()
        parser.setInput(inputStream, null)
        var eventType = parser.eventType
        var currentTag: String? = null
        var coverId: String? = null

        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    currentTag = parser.name
                    if (currentTag == "meta" && parser.getAttributeValue(null, "name") == "cover") {
                        coverId = parser.getAttributeValue(null, "content")
                    }
                }
                XmlPullParser.TEXT -> {
                    when (currentTag) {
                        "dc:title" -> metadata.title = parser.text
                        "dc:creator" -> metadata.authors = listOf(parser.text)
                        "dc:description" -> metadata.annotation = parser.text
                        "dc:language" -> metadata.language = parser.text
                        "dc:subject" -> metadata.genres = metadata.genres + parser.text
                    }
                }
                XmlPullParser.END_TAG -> {
                    currentTag = null
                }
            }
            eventType = parser.next()
        }
        return coverId
    }

    private fun parseFb2(inputStream: InputStream, metadata: BookMetadata) {
        val parser: XmlPullParser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(inputStream, null)

        var eventType = parser.eventType
        var currentTag: String? = null
        var inTitleInfo = false
        var inAuthor = false

        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    currentTag = parser.name
                    when (currentTag) {
                        "title-info" -> inTitleInfo = true
                        "author" -> inAuthor = true
                    }
                }
                XmlPullParser.TEXT -> {
                    if (inTitleInfo) {
                        when (currentTag) {
                            "book-title" -> metadata.title = parser.text
                            "genre" -> metadata.genres = metadata.genres + parser.text
                            "annotation" -> metadata.annotation = parser.text
                            "lang" -> metadata.language = parser.text
                        }
                        if (inAuthor) {
                            when (currentTag) {
                                "first-name" -> metadata.authors = metadata.authors.toMutableList().apply { add(parser.text) }
                                "last-name" -> metadata.authors = metadata.authors.toMutableList().apply { 
                                    val index = lastIndex
                                    this[index] = this[index] + " " + parser.text
                                 }
                            }
                        }
                    }
                    if (currentTag == "binary" && parser.attributeCount > 0 && parser.getAttributeValue(null, "id") == "cover.jpg") {
                        metadata.coverImage = Base64.decode(parser.nextText(), Base64.DEFAULT)
                    }
                }
                XmlPullParser.END_TAG -> {
                     when (parser.name) {
                        "title-info" -> inTitleInfo = false
                        "author" -> inAuthor = false
                    }
                    currentTag = null
                }
            }
            eventType = parser.next()
        }
    }
}
