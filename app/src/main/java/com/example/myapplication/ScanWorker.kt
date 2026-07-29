package com.creplaz.newslistener

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import org.jsoup.Jsoup
import java.text.SimpleDateFormat
import java.util.*

class ScanWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        Log.d("ScanWorker", "Starting background scan")
        val sharedPrefs = applicationContext.getSharedPreferences("creplaz_prefs", Context.MODE_PRIVATE)
        
        val savedSources = sharedPrefs.getString("scan_sources", "telegram|https://t.me/geektime") ?: "telegram|https://t.me/geektime"
        val sources = savedSources.split(";;").filter { it.isNotEmpty() }
        
        // Background scan is always for the previous 24h (last 1 day)
        val daysBack = 1
        val allArticles = mutableListOf<Article>()

        for (source in sources) {
            val parts = source.split("|")
            if (parts.size != 2) continue
            val type = parts[0]
            val value = parts[1]
            
            val articles = if (type == "telegram") {
                fetchArticlesFromTelegram(value, daysBack)
            } else {
                fetchArticlesFromWebsite(value, daysBack)
            }
            allArticles.addAll(articles)
        }
        
        if (allArticles.isNotEmpty()) {
            saveArticles(allArticles)
        }
        
        return Result.success()
    }

    private fun fetchArticlesFromWebsite(url: String, daysBack: Int): List<Article> {
        val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        return try {
            val doc = Jsoup.connect(url).userAgent(userAgent).timeout(20000).get()
            val elements = doc.select("article a[href], .post-item a[href], .elementor-post__title a, h2 a[href], h3 a[href], .entry-title a")
            val links = elements.asSequence()
                .map { it.attr("abs:href") }
                .distinct()
                .filter { it.contains(url.replace("https://www.", "").split("/")[0]) && (it.length > 30) && !it.contains("/category/") }
                .toList()
            processLinks(links, daysBack)
        } catch (_: Exception) { emptyList() }
    }

    private fun fetchArticlesFromTelegram(channelName: String, daysBack: Int): List<Article> {
        val channel = channelName
            .replace("https://t.me/s/", "")
            .replace("https://t.me/", "")
            .replace("@", "")
            .trim()
            .split("/")[0]
            
        val url = "https://t.me/s/$channel"
        val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        return try {
            val doc = Jsoup.connect(url).userAgent(userAgent).timeout(20000).get()
            val elements = doc.select(".tgme_widget_message_text a[href], .tgme_widget_message_inline_button, a.tgme_widget_message_link_preview")
            
            val links = mutableListOf<String>()
            elements.forEach { el ->
                val href = el.attr("abs:href")
                if (href.isNotEmpty()) links.add(href)
                if (el.tagName() != "a") {
                    val innerLink = el.select("a[href]").attr("abs:href")
                    if (innerLink.isNotEmpty()) links.add(innerLink)
                }
            }
            
            val filteredLinks = links.distinct()
                .filter { (it.length > 20) && !it.contains("t.me/") && !it.contains("facebook.com") && !it.contains("twitter.com") && !it.contains("instagram.com") }
            processLinks(filteredLinks, daysBack)
        } catch (_: Exception) { emptyList() }
    }

    private fun processLinks(links: List<String>, daysBack: Int): List<Article> {
        val result = mutableListOf<Article>()
        val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        val sdf = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())
        val sharedPrefs = applicationContext.getSharedPreferences("creplaz_prefs", Context.MODE_PRIVATE)
        
        val limitDate = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, -daysBack) // Use configured days back
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.time

        for (link in links) {
            try {
                val articleDoc = Jsoup.connect(link).userAgent(userAgent).timeout(10000).get()
                val timeElement = articleDoc.select("time[datetime]").first()
                val dateAttr = timeElement?.attr("datetime")
                val dateText = articleDoc.select(".post-date, .entry-date, time, .date, .meta").text()
                
                var articleDate: Date? = null
                
                if (!dateAttr.isNullOrEmpty()) {
                    try {
                        val isoSdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                        articleDate = isoSdf.parse(dateAttr.substring(0, 10))
                    } catch (_: Exception) {}
                }
                if ((articleDate == null) && dateText.isNotEmpty()) {
                    val dateRegex = Regex("(\\d{1,2})[./](\\d{1,2})[./](\\d{4})")
                    val match = dateRegex.find(dateText)
                    if (match != null) {
                        try {
                            articleDate = sdf.parse(match.value.replace("/", "."))
                        } catch (_: Exception) {}
                    }
                }
                if (articleDate == null) {
                    val urlDateRegex = Regex("/(\\d{4})/(\\d{2})/(\\d{2})/")
                    val match = urlDateRegex.find(link)
                    if (match != null) {
                        try {
                            val urlDateStr = "${match.groupValues[3]}.${match.groupValues[2]}.${match.groupValues[1]}"
                            articleDate = sdf.parse(urlDateStr)
                        } catch (_: Exception) {}
                    }
                }

                if (articleDate != null && articleDate.before(limitDate)) continue
                val groupDate = if (articleDate != null) sdf.format(articleDate) else "Recent"

                val title = articleDoc.select("h1, .entry-title, .post-title").first()?.text() ?: articleDoc.title()
                
                // Improved content extraction: filter out English-only paragraphs at the end of Hebrew articles
                val paragraphs = articleDoc.select(".entry-content p, .post-content p, article p")
                val contentBuilder = StringBuilder()
                var hasHebrewInArticle = false
                
                val pTexts = paragraphs.map { it.text() }.filter { it.length > 20 }
                
                for (pText in pTexts) {
                    val hasHebrew = pText.any { it in '\u0590'..'\u05FF' }
                    if (hasHebrew) hasHebrewInArticle = true
                    
                    if (hasHebrewInArticle && !hasHebrew && pText.length > 200) {
                        val remaining = pTexts.subList(pTexts.indexOf(pText), pTexts.size)
                        val anyHebrewLeft = remaining.any { rel -> rel.any { c -> c in '\u0590'..'\u05FF' } }
                        if (!anyHebrewLeft) break
                    }
                    contentBuilder.append(pText).append(" ")
                }
                
                val content = contentBuilder.toString().trim()
                
                if (content.length > 100) {
                    val currentTitles = sharedPrefs.getStringSet("article_titles", emptySet()) ?: emptySet()
                    val historyTitles = sharedPrefs.getStringSet("history_titles", emptySet()) ?: emptySet()
                    
                    if (!currentTitles.contains(title) && !historyTitles.contains(title)) {
                        result.add(Article(title, content, groupDate))
                    }
                }
            } catch (_: Exception) {}
            if (result.size >= 15) break
        }
        return result
    }

    private fun saveArticles(newArticles: List<Article>) {
        val sharedPrefs = applicationContext.getSharedPreferences("creplaz_prefs", Context.MODE_PRIVATE)
        val existingTitles = sharedPrefs.getStringSet("article_titles", emptySet())?.toMutableSet() ?: mutableSetOf()
        val historyTitles = sharedPrefs.getStringSet("history_titles", emptySet())?.toMutableSet() ?: mutableSetOf()
        
        newArticles.forEach { article ->
            if (!existingTitles.contains(article.title) && !historyTitles.contains(article.title)) {
                existingTitles.add(article.title)
                historyTitles.add(article.title)
                sharedPrefs.edit {
                    putString("article_content_${article.title}", article.content)
                    putString("article_date_${article.title}", article.date)
                }
            }
        }
        sharedPrefs.edit {
            putStringSet("article_titles", existingTitles)
            putStringSet("history_titles", historyTitles)
        }
    }
}