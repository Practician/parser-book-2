package com.bookparser.app

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.bookparser.app.databinding.ActivityResultsBinding
import com.bookparser.app.processing.BookProcessingResult

class ResultsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityResultsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityResultsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val results = intent.getParcelableArrayListExtra<BookProcessingResult>(EXTRA_RESULTS)

        if (results.isNullOrEmpty()) {
            Toast.makeText(this, "Нет результатов для отображения", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        setupRecyclerView(results)
    }

    private fun setupRecyclerView(results: List<BookProcessingResult>) {
        val adapter = BookResultAdapter(
            results = results,
            onPublishClick = { result ->
                // Not used anymore
            },
            onEditClick = { result ->
                // TODO: Implement editing functionality
                Toast.makeText(this, "Редактирование: ${result.metadata.title}", Toast.LENGTH_SHORT).show()
            }
        )
        binding.recyclerViewResults.layoutManager = LinearLayoutManager(this)
        binding.recyclerViewResults.adapter = adapter
    }

    companion object {
        const val EXTRA_RESULTS = "extra_results"
    }
}
