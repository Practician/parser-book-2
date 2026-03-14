package com.bookparser.app

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.bookparser.app.databinding.ActivitySettingsBinding
import com.bookparser.app.parser.BiographyParser

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
    
        // Настройка ActionBar
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Настройки"
        
        binding.toolbar.setNavigationOnClickListener {
             onBackPressedDispatcher.onBackPressed()
        }
    
        // Загрузка сохранённых настроек
        loadSettings()
    
        // Обработчик кнопки сохранения
        binding.buttonSaveSettings.setOnClickListener {
            saveSettings()
        }
    }
    
    /**
     * Загружает сохранённые настройки из SharedPreferences
     */
    private fun loadSettings() {
        val prefs = getSharedPreferences("app_settings", MODE_PRIVATE)
    
        // Загрузка настройки автопубликации
        val autoPublish = prefs.getBoolean("auto_publish", false)
        binding.checkboxAutoPublish.isChecked = autoPublish
    
        // Загрузка настройки источника биографии
        val biographySource = prefs.getString("biography_source", BiographyParser.SOURCE_WIKIPEDIA)
            ?: BiographyParser.SOURCE_WIKIPEDIA
    
        when (biographySource) {
            BiographyParser.SOURCE_WIKIPEDIA -> {
                binding.radioWikipedia.isChecked = true
            }
            BiographyParser.SOURCE_RUWIKI -> {
                binding.radioRuwiki.isChecked = true
            }
            BiographyParser.SOURCE_RUWIKI_AI -> {
                binding.radioRuwikiAi.isChecked = true
            }
            else -> {
                // По умолчанию Wikipedia
                binding.radioWikipedia.isChecked = true
            }
        }
    }
    
    /**
     * Сохраняет настройки в SharedPreferences
     */
    private fun saveSettings() {
        val prefs = getSharedPreferences("app_settings", MODE_PRIVATE)
    
        with(prefs.edit()) {
            // Сохранение настройки автопубликации
            putBoolean("auto_publish", binding.checkboxAutoPublish.isChecked)
    
            // Сохранение настройки источника биографии
            val biographySource = when {
                binding.radioWikipedia.isChecked -> BiographyParser.SOURCE_WIKIPEDIA
                binding.radioRuwiki.isChecked -> BiographyParser.SOURCE_RUWIKI
                binding.radioRuwikiAi.isChecked -> BiographyParser.SOURCE_RUWIKI_AI
                else -> BiographyParser.SOURCE_WIKIPEDIA
            }
            putString("biography_source", biographySource)
    
            // Применяем изменения
            apply()
        }
    
        // Показываем уведомление об успешном сохранении
        val sourceName = when {
            binding.radioWikipedia.isChecked -> "Wikipedia"
            binding.radioRuwiki.isChecked -> "РУВИКИ"
            binding.radioRuwikiAi.isChecked -> "РУВИКИ ИИ (экспериментально)"
            else -> "Wikipedia"
        }
    
        val autoPublishText = if (binding.checkboxAutoPublish.isChecked) {
            "включена"
        } else {
            "выключена"
        }
    
        Toast.makeText(
            this,
            "✓ Настройки сохранены\n" +
                    "Источник: $sourceName\n" +
                    "Автопубликация: $autoPublishText",
            Toast.LENGTH_LONG
        ).show()
    
        // Закрываем активность
        finish()
    }
    
    /**
     * Обработчик системной кнопки "Назад"
     */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Проверяем, были ли изменены настройки
        val prefs = getSharedPreferences("app_settings", MODE_PRIVATE)
        val savedAutoPublish = prefs.getBoolean("auto_publish", false)
        val savedBiographySource = prefs.getString("biography_source", BiographyParser.SOURCE_WIKIPEDIA)
    
        val currentAutoPublish = binding.checkboxAutoPublish.isChecked
        val currentBiographySource = when {
            binding.radioWikipedia.isChecked -> BiographyParser.SOURCE_WIKIPEDIA
            binding.radioRuwiki.isChecked -> BiographyParser.SOURCE_RUWIKI
            binding.radioRuwikiAi.isChecked -> BiographyParser.SOURCE_RUWIKI_AI
            else -> BiographyParser.SOURCE_WIKIPEDIA
        }
    
        // Если настройки изменены, показываем предупреждение
        if (savedAutoPublish != currentAutoPublish || savedBiographySource != currentBiographySource) {
            Toast.makeText(
                this,
                "⚠ Настройки не сохранены",
                Toast.LENGTH_SHORT
            ).show()
        }
    
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }
}
