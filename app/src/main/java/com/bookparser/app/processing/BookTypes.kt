package com.bookparser.app.processing

import android.net.Uri
import android.os.Parcelable
import kotlinx.parcelize.Parcelize

// Data class to hold the final result of processing a single book
@Parcelize
data class BookProcessingResult(
    val metadata: BookMetadata,
    val assets: BookAssets,
    val originalName: String,
    var formattedOutput: String? = null // For the final BBCode or display format
) : Parcelable

// Holds URIs or paths to asset files generated during processing
@Parcelize
data class BookAssets(
    val coverPath: Uri? = null, // Path to the saved cover image
    val bookArchivePath: Uri? = null // Path to the final book archive (e.g., .zip)
) : Parcelable
