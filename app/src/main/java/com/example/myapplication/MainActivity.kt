package com.example.myapplication

import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.util.*
import java.util.concurrent.TimeUnit

data class Article(val title: String, val content: String)

class ArticleAdapter(private val articles: MutableList<Article>) : RecyclerView.Adapter<ArticleAdapter.ViewHolder>() {
    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvTitle: TextView = view.findViewById(R.id.tvTitle)
    }
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_article, parent, false)
        return ViewHolder(view)
    }
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.tvTitle.text = articles[position].title
    }
    override fun getItemCount() = articles.size
    
    fun removeItem(position: Int): Article? {
        if (position in articles.indices) {
            val removed = articles.removeAt(position)
            notifyItemRemoved(position)
            return removed
        }
        return null
    }
}

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var tts: TextToSpeech
    private var ttsReady = false
    private lateinit var btnScan: Button
    private lateinit var btnSchedule: Button
    private lateinit var btnPlay: Button
    private lateinit var btnPause: Button
    private lateinit var btnSkip: Button
    private lateinit var btnStop: Button
    private lateinit var btnSpeedUp: Button
    private lateinit var btnSpeedDown: Button
    private lateinit var tvSpeed: TextView
    private lateinit var hsvControls: View
    private lateinit var tvStatus: TextView
    private lateinit var tvVersion: TextView
    private lateinit var rvArticles: RecyclerView
    private lateinit var adapter: ArticleAdapter
    
    private val articlePlaylist = mutableListOf<Article>()
    private var isPlaying = false
    private var currentSpeed = 1.0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        btnScan = findViewById(R.id.btnScan)
        btnSchedule = findViewById(R.id.btnSchedule)
        btnPlay = findViewById(R.id.btnPlay)
        btnPause = findViewById(R.id.btnPause)
        btnSkip = findViewById(R.id.btnSkip)
        btnStop = findViewById(R.id.btnStop)
        btnSpeedUp = findViewById(R.id.btnSpeedUp)
        btnSpeedDown = findViewById(R.id.btnSpeedDown)
        tvSpeed = findViewById(R.id.tvSpeed)
        hsvControls = findViewById(R.id.hsvControls)
        tvStatus = findViewById(R.id.tvStatus)
        tvVersion = findViewById(R.id.tvVersion)
        rvArticles = findViewById(R.id.rvArticles)
        
        adapter = ArticleAdapter(articlePlaylist)
        rvArticles.layoutManager = LinearLayoutManager(this)
        rvArticles.adapter = adapter

        // Swipe to remove
        val itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, t: RecyclerView.ViewHolder) = false
            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.adapterPosition
                val removedArticle = adapter.removeItem(position)
                removedArticle?.let { removeArticleFromPrefs(it.title) }
                if (articlePlaylist.isEmpty()) {
                    hsvControls.visibility = View.GONE
                    tvStatus.text = "All articles cleared"
                }
                updateButtonStates()
            }
        })
        itemTouchHelper.attachToRecyclerView(rvArticles)

        try {
            val pInfo = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0)
            }
            tvVersion.text = "v${pInfo.versionName}"
        } catch (e: Exception) {
            tvVersion.text = "v"
        }

        tts = TextToSpeech(this, this)

        btnScan.setOnClickListener {
            showScanDaysDialog()
        }

        btnSchedule.setOnClickListener {
            showTimePicker()
        }
        
        btnPlay.setOnClickListener {
            if (articlePlaylist.isEmpty()) {
                Toast.makeText(this, "No articles to play. Scan first.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!ttsReady) {
                Toast.makeText(this, "Speech engine starting...", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            
            isPlaying = true
            playNext()
            updateButtonStates()
        }

        btnPause.setOnClickListener {
            isPlaying = false
            tts.stop()
            tvStatus.text = "Paused"
            updateButtonStates()
        }

        btnSkip.setOnClickListener {
            if (articlePlaylist.isNotEmpty()) {
                tts.stop()
                val removed = adapter.removeItem(0)
                removed?.let { removeArticleFromPrefs(it.title) }
                
                if (articlePlaylist.isEmpty()) {
                    hsvControls.visibility = View.GONE
                    tvStatus.text = "No more articles"
                    isPlaying = false
                } else if (isPlaying) {
                    playNext()
                }
                updateButtonStates()
            }
        }
        
        btnStop.setOnClickListener {
            stopPlayback()
        }

        btnSpeedUp.setOnClickListener {
            if (currentSpeed < 2.5f) {
                currentSpeed += 0.1f
                updateSpeed()
            }
        }

        btnSpeedDown.setOnClickListener {
            if (currentSpeed > 0.5f) {
                currentSpeed -= 0.1f
                updateSpeed()
            }
        }

        loadSavedArticles()
    }

    private fun showScanDaysDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_scan_days, null)
        val btnMinus = dialogView.findViewById<Button>(R.id.btnMinus)
        val btnPlus = dialogView.findViewById<Button>(R.id.btnPlus)
        val tvDayCount = dialogView.findViewById<TextView>(R.id.tvDayCount)
        var count = 1

        btnMinus.setOnClickListener {
            if (count > 1) {
                count--
                tvDayCount.text = count.toString()
            }
        }

        btnPlus.setOnClickListener {
            if (count < 14) {
                count++
                tvDayCount.text = count.toString()
            }
        }

        AlertDialog.Builder(this)
            .setView(dialogView)
            .setPositiveButton("Scan") { _, _ ->
                startScanning(count)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateButtonStates() {
        runOnUiThread {
            btnPlay.isEnabled = true
            btnPause.isEnabled = true
            btnSkip.isEnabled = true
            btnStop.isEnabled = true
            btnPlay.alpha = 1.0f
            btnPause.alpha = 1.0f
            btnSkip.alpha = 1.0f
            btnStop.alpha = 1.0f
        }
    }

    private fun showTimePicker() {
        val calendar = Calendar.getInstance()
        TimePickerDialog(this, { _, hour, minute ->
            scheduleDailyScan(hour, minute)
        }, calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE), true).show()
    }

    private fun scheduleDailyScan(hour: Int, minute: Int) {
        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
        }
        if (target.before(now)) target.add(Calendar.DAY_OF_YEAR, 1)

        val delay = target.timeInMillis - now.timeInMillis
        
        val scanRequest = PeriodicWorkRequestBuilder<ScanWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "daily_scan",
            ExistingPeriodicWorkPolicy.REPLACE,
            scanRequest
        )
        
        val timeStr = String.format("%02d:%02d", hour, minute)
        tvStatus.text = "Daily scan scheduled for $timeStr"
    }

    private fun updateSpeed() {
        val speedText = String.format("%.1fx", currentSpeed)
        tvSpeed.text = speedText
        if (ttsReady) {
            tts.setSpeechRate(currentSpeed)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts.setLanguage(Locale("he", "IL"))
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts.setLanguage(Locale.getDefault())
            }
            
            ttsReady = true
            updateSpeed()
            
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    runOnUiThread {
                        if (isPlaying && articlePlaylist.isNotEmpty()) {
                            val removed = adapter.removeItem(0)
                            removed?.let { removeArticleFromPrefs(it.title) }
                            
                            if (articlePlaylist.isNotEmpty()) {
                                playNext()
                            } else {
                                isPlaying = false
                                tvStatus.text = "All articles played"
                            }
                        }
                    }
                }
                override fun onError(utteranceId: String?) { 
                    runOnUiThread { stopPlayback() }
                }
            })
            updateButtonStates()
        } else {
            runOnUiThread { tvStatus.text = "TTS engine error" }
        }
    }

    private fun loadSavedArticles() {
        val sharedPrefs = getSharedPreferences("creplaz_prefs", Context.MODE_PRIVATE)
        val titles = sharedPrefs.getStringSet("article_titles", emptySet()) ?: emptySet()
        articlePlaylist.clear()
        titles.forEach { title ->
            val content = sharedPrefs.getString("article_content_$title", "") ?: ""
            articlePlaylist.add(Article(title, content))
        }
        if (articlePlaylist.isNotEmpty()) {
            adapter.notifyDataSetChanged()
            hsvControls.visibility = View.VISIBLE
            tvStatus.text = "Loaded ${articlePlaylist.size} saved articles"
        }
        updateButtonStates()
    }

    private fun removeArticleFromPrefs(title: String) {
        val sharedPrefs = getSharedPreferences("creplaz_prefs", Context.MODE_PRIVATE)
        val titles = sharedPrefs.getStringSet("article_titles", emptySet())?.toMutableSet() ?: mutableSetOf()
        titles.remove(title)
        sharedPrefs.edit()
            .putStringSet("article_titles", titles)
            .remove("article_content_$title")
            .apply()
    }

    private fun startScanning(daysBack: Int) {
        tvStatus.text = "Scanning Geektime ($daysBack days back)..."
        btnScan.isEnabled = false
        lifecycleScope.launch {
            val articles = fetchYesterdayArticles(daysBack)
            btnScan.isEnabled = true
            if (articles.isNotEmpty()) {
                val sharedPrefs = getSharedPreferences("creplaz_prefs", Context.MODE_PRIVATE)
                val existingTitles = sharedPrefs.getStringSet("article_titles", emptySet()) ?: emptySet()
                
                var addedCount = 0
                articles.forEach { article ->
                    if (!existingTitles.contains(article.title)) {
                        articlePlaylist.add(article)
                        sharedPrefs.edit().putString("article_content_${article.title}", article.content).apply()
                        addedCount++
                    }
                }
                val allTitles = articlePlaylist.map { it.title }.toSet()
                sharedPrefs.edit().putStringSet("article_titles", allTitles).apply()
                
                adapter.notifyDataSetChanged()
                tvStatus.text = "Found $addedCount new articles."
                hsvControls.visibility = View.VISIBLE
            } else {
                tvStatus.text = "No articles found for the selected period."
            }
            updateButtonStates()
        }
    }

    private suspend fun fetchYesterdayArticles(daysBack: Int): List<Article> = withContext(Dispatchers.IO) {
        val result = mutableListOf<Article>()
        val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        try {
            val doc = Jsoup.connect("https://www.geektime.co.il/")
                .userAgent(userAgent).timeout(20000).get()

            val targetDate = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -daysBack) }
            val day = targetDate.get(Calendar.DAY_OF_MONTH).toString()
            val month = (targetDate.get(Calendar.MONTH) + 1).toString().padStart(2, '0')

            val elements = doc.select("article a[href], .post-item a[href], .elementor-post__title a, h2 a[href], h3 a[href]")
            val links = elements.map { it.attr("abs:href") }.distinct()
                .filter { it.contains("geektime.co.il") && it.length > 35 && !it.contains("/category/") }

            for (link in links) {
                try {
                    val articleDoc = Jsoup.connect(link).userAgent(userAgent).timeout(10000).get()
                    val dateText = articleDoc.select(".post-date, .entry-date, time").text()
                    val title = articleDoc.select("h1, .entry-title, .post-title").first()?.text() ?: articleDoc.title()
                    
                    // Check if the article matches the specific day
                    if (dateText.contains(day) && (dateText.contains(month) || dateText.contains("."))) {
                        val content = articleDoc.select(".entry-content p, .post-content p, article p").text()
                        if (content.length > 100) {
                            result.add(Article(title, content))
                        }
                    }
                } catch (e: Exception) {}
                if (result.size >= 10) break 
            }
        } catch (e: Exception) {}
        result
    }

    private fun playNext() {
        if (articlePlaylist.isNotEmpty() && isPlaying) {
            val article = articlePlaylist[0]
            runOnUiThread { tvStatus.text = "Playing: ${article.title}" }
            val params = Bundle()
            val uid = "id_" + System.currentTimeMillis()
            params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, uid)
            tts.speak("Title: ${article.title}. Content: ${article.content}", TextToSpeech.QUEUE_FLUSH, params, uid)
        } else {
            stopPlayback()
        }
    }

    private fun stopPlayback() {
        isPlaying = false
        if (ttsReady) tts.stop()
        runOnUiThread { 
            tvStatus.text = if (articlePlaylist.isEmpty()) "Scan to see articles" else "Stopped" 
            updateButtonStates()
        }
    }

    override fun onDestroy() {
        if (::tts.isInitialized) tts.shutdown()
        super.onDestroy()
    }
}