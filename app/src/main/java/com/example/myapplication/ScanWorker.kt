package com.example.myapplication

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import org.jsoup.Jsoup
import java.util.*

class ScanWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        Log.d("ScanWorker", "Starting background scan")
        val articles = fetchYesterdayArticles()
        
        if (articles.isNotEmpty()) {
            saveArticles(articles)
            return Result.success()
        }
        
        return Result.retry()
    }

    private fun fetchYesterdayArticles(): List<Article> {
        val result = mutableListOf<Article>()
        try {
            val doc = Jsoup.connect("https://www.geektime.co.il/")
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
                .timeout(15000)
                .get()

            val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
            val day = yesterday.get(Calendar.DAY_OF_MONTH).toString()
            val month = (yesterday.get(Calendar.MONTH) + 1).toString().padStart(2, '0')

            val elements = doc.select("h2 a, h3 a, .post-title a, article a[href]")
            val links = elements.map { it.attr("abs:href") }
                .distinct()
                .filter { it.contains("geektime.co.il") && !it.contains("/category/") && !it.contains("/tag/") && it.length > 30 }

            for (link in links) {
                try {
                    val articleDoc = Jsoup.connect(link).timeout(8000).get()
                    val dateText = articleDoc.select(".post-date, .entry-date, time, .date, .meta").text()
                    val title = articleDoc.title().split("|")[0].trim()

                    if (dateText.contains(day) && (dateText.contains(month) || dateText.contains(".") || dateText.isEmpty())) {
                        val contentElements = articleDoc.select(".entry-content p, .post-content p, .article-content p, article p")
                        val content = contentElements.text()
                        
                        if (content.length > 100) {
                            result.add(Article(title, content))
                        }
                    }
                } catch (e: Exception) {
                    Log.e("ScanWorker", "Error fetching article: $link")
                }
                if (result.size >= 10) break 
            }
        } catch (e: Exception) {
            Log.e("ScanWorker", "Error scanning site", e)
        }
        return result
    }

    private fun saveArticles(newArticles: List<Article>) {
        val sharedPrefs = applicationContext.getSharedPreferences("creplaz_prefs", Context.MODE_PRIVATE)
        val existingTitles = sharedPrefs.getStringSet("article_titles", emptySet()) ?: emptySet()
        val allArticles = mutableListOf<Article>()
        
        // Load existing
        existingTitles.forEach { title ->
            val content = sharedPrefs.getString("article_content_$title", "") ?: ""
            allArticles.add(Article(title, content))
        }
        
        // Add new (avoid duplicates)
        newArticles.forEach { article ->
            if (!existingTitles.contains(article.title)) {
                allArticles.add(article)
                sharedPrefs.edit().putString("article_content_${article.title}", article.content).apply()
            }
        }
        
        // Save titles set
        sharedPrefs.edit().putStringSet("article_titles", allArticles.map { it.title }.toSet()).apply()
    }
}