package com.bookparser.app

import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bookparser.app.databinding.ListItemBookBinding
import com.bookparser.app.processing.BookProcessingResult

class BookResultAdapter(
    private val results: List<BookProcessingResult>,
    private val onPublishClick: (BookProcessingResult) -> Unit,
    private val onEditClick: (BookProcessingResult) -> Unit
) : RecyclerView.Adapter<BookResultAdapter.BookViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BookViewHolder {
        val binding = ListItemBookBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return BookViewHolder(binding)
    }

    override fun onBindViewHolder(holder: BookViewHolder, position: Int) {
        val result = results[position]
        holder.bind(result)
    }

    override fun getItemCount(): Int = results.size

    inner class BookViewHolder(private val binding: ListItemBookBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(result: BookProcessingResult) {
            binding.textViewTitle.text = result.metadata.title
            binding.textViewAuthor.text = result.metadata.authors.joinToString(", ")

            result.metadata.coverImage?.let {
                val bitmap = BitmapFactory.decodeByteArray(it, 0, it.size)
                binding.imageViewCover.setImageBitmap(bitmap)
            } ?: run {
                // Set a placeholder if no cover is available
                binding.imageViewCover.setImageResource(android.R.drawable.ic_menu_gallery)
            }

            binding.buttonPublish.setOnClickListener {
                onPublishClick(result)
            }

            binding.buttonEdit.setOnClickListener {
                onEditClick(result)
            }
        }
    }
}
