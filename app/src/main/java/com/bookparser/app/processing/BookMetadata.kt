package com.bookparser.app.processing

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class BookMetadata(
    var title: String = "",
    var authors: List<String> = emptyList(),
    var genres: List<String> = emptyList(),
    var annotation: String = "",
    
    // Новые поля для полного заполнения шаблона 4PDA
    var publishYear: String? = null,
    var publisher: String? = null,
    var format: String? = null,
    var quality: String? = null,
    var pages: String? = null,
    var webLink: String? = null,
    
    var authorInfo: String? = null,
    var series: String? = null,
    var seriesBooks: String? = null,
    var uploader: String? = null,
    var downloads: Int? = null,
    var originalPostUrl: String? = null,
    
    // Arrays need custom equality check if used in data class equals/hashCode normally,
    // but ByteArray in Kotlin doesn't have contentEquals in default generated equals.
    // So we override equals/hashCode anyway.
    var coverImage: ByteArray? = null,
    var bookFile: ByteArray? = null, // Можно использовать, если книга передается массивом
    
    // Fields required by parser logic
    var sequenceNumber: Int? = null,
    var language: String? = null,
    var genresSource: String? = null,
    
    var enableSmilies: Boolean = true,
    var enableSignature: Boolean = true,
    var enableEmailNotification: Boolean = false
) : Parcelable {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as BookMetadata

        if (title != other.title) return false
        if (authors != other.authors) return false
        if (genres != other.genres) return false
        if (annotation != other.annotation) return false
        
        if (publishYear != other.publishYear) return false
        if (publisher != other.publisher) return false
        if (format != other.format) return false
        if (quality != other.quality) return false
        if (pages != other.pages) return false
        if (webLink != other.webLink) return false
        
        if (authorInfo != other.authorInfo) return false
        if (series != other.series) return false
        if (seriesBooks != other.seriesBooks) return false
        if (uploader != other.uploader) return false
        if (downloads != other.downloads) return false
        if (originalPostUrl != other.originalPostUrl) return false
        if (coverImage != null) {
            if (other.coverImage == null) return false
            if (!coverImage.contentEquals(other.coverImage)) return false
        } else if (other.coverImage != null) return false
        
        if (bookFile != null) {
            if (other.bookFile == null) return false
            if (!bookFile!!.contentEquals(other.bookFile!!)) return false
        } else if (other.bookFile != null) return false
        
        if (sequenceNumber != other.sequenceNumber) return false
        if (language != other.language) return false
        if (genresSource != other.genresSource) return false
        
        if (enableSmilies != other.enableSmilies) return false
        if (enableSignature != other.enableSignature) return false
        if (enableEmailNotification != other.enableEmailNotification) return false

        return true
    }

    override fun hashCode(): Int {
        var result = title.hashCode()
        result = 31 * result + authors.hashCode()
        result = 31 * result + genres.hashCode()
        result = 31 * result + annotation.hashCode()
        
        result = 31 * result + (publishYear?.hashCode() ?: 0)
        result = 31 * result + (publisher?.hashCode() ?: 0)
        result = 31 * result + (format?.hashCode() ?: 0)
        result = 31 * result + (quality?.hashCode() ?: 0)
        result = 31 * result + (pages?.hashCode() ?: 0)
        result = 31 * result + (webLink?.hashCode() ?: 0)
        
        result = 31 * result + (authorInfo?.hashCode() ?: 0)
        result = 31 * result + (series?.hashCode() ?: 0)
        result = 31 * result + (seriesBooks?.hashCode() ?: 0)
        result = 31 * result + (uploader?.hashCode() ?: 0)
        result = 31 * result + (downloads ?: 0)
        result = 31 * result + (originalPostUrl?.hashCode() ?: 0)
        result = 31 * result + (coverImage?.contentHashCode() ?: 0)
        result = 31 * result + (bookFile?.contentHashCode() ?: 0)
        
        result = 31 * result + (sequenceNumber ?: 0)
        result = 31 * result + (language?.hashCode() ?: 0)
        result = 31 * result + (genresSource?.hashCode() ?: 0)
        result = 31 * result + enableSmilies.hashCode()
        result = 31 * result + enableSignature.hashCode()
        result = 31 * result + enableEmailNotification.hashCode()
        return result
    }
}