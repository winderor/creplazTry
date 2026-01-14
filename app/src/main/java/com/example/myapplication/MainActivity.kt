package com.example.myapplication

import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
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
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

data class Article(val title: String, val content: String, val date: String = "Recent")

class ArticleAdapter(private val articles: MutableList<Article>) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private val TYPE_HEADER = 0
    private val TYPE_ITEM = 1

    private var displayList = mutableListOf<Any>()

    init {
        updateDisplayList()
    }

    fun updateDisplayList() {
        displayList.clear()
        val sortedArticles = articles.sortedByDescending { it.date }
        val grouped = sortedArticles.groupBy { it.date }
        grouped.forEach { (date, items) ->
            displayList.add(date)
            displayList.addAll(items)
        }
        notifyDataSetChanged()
    }

    class HeaderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvDate: TextView = view.findViewById(R.id.tvTitle)
    }

    class ItemViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvTitle: TextView = view.findViewById(R.id.tvTitle)
    }

    override fun getItemViewType(position: Int): Int {
        return if (displayList[position] is String) TYPE_HEADER else TYPE_ITEM
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_article, parent, false)
        return if (viewType == TYPE_HEADER) HeaderViewHolder(view) else ItemViewHolder(view)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder is HeaderViewHolder) {
            holder.tvDate.text = displayList[position] as String
            holder.tvDate.setBackgroundColor(Color.TRANSPARENT)
            holder.tvDate.setTextColor(Color.GRAY)
            holder.tvDate.textSize = 10f
            holder.tvDate.setPadding(32, 8, 32, 0)
        } else if (holder is ItemViewHolder) {
            val article = displayList[position] as Article
            holder.tvTitle.text = article.title
            holder.tvTitle.setBackgroundColor(Color.WHITE)
            holder.tvTitle.setTextColor(Color.BLACK)
            holder.tvTitle.textSize = 16f
            holder.tvTitle.setPadding(32, 16, 32, 16)
        }
    }

    override fun getItemCount() = displayList.size
    
    fun removeItem(position: Int): Article? {
        if (position in displayList.indices) {
            val item = displayList[position]
            if (item is Article) {
                articles.remove(item)
                updateDisplayList()
                return item
            }
        }
        return null
    }

    fun isHeader(position: Int): Boolean {
        return position in displayList.indices && displayList[position] is String
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

        val sharedPrefs = getSharedPreferences("creplaz_prefs", Context.MODE_PRIVATE)
        currentSpeed = sharedPrefs.getFloat("last_speed", 1.0f)

        val itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
            override fun getSwipeDirs(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int {
                // Disable swipe for headers
                if (adapter.isHeader(viewHolder.adapterPosition)) return 0
                return super.getSwipeDirs(recyclerView, viewHolder)
            }

            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, t: RecyclerView.ViewHolder) = false
            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.adapterPosition
                val removedArticle = adapter.removeItem(position)
                removedArticle?.let { 
                    removeArticleFromPrefs(it.title)
                    if (isPlaying && articlePlaylist.isNotEmpty() && articlePlaylist[0].title == it.title) {
                        tts.stop()
                        playNext()
                    }
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
                val removed = articlePlaylist.removeAt(0)
                removeArticleFromPrefs(removed.title)
                adapter.updateDisplayList()
                
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
                updateSpeedAndSave()
            }
        }

        btnSpeedDown.setOnClickListener {
            if (currentSpeed > 0.5f) {
                currentSpeed -= 0.1f
                updateSpeedAndSave()
            }
        }

        loadSavedArticles()
        showSavedScheduleStatus()
    }

    private fun showScanDaysDialog() {
        val sharedPrefs = getSharedPreferences("creplaz_prefs", Context.MODE_PRIVATE)
        val savedUrl = sharedPrefs.getString("last_url", "https://www.geektime.co.il/")
        
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_scan_days, null)
        val etUrl = dialogView.findViewById<EditText>(R.id.etUrl)
        etUrl.setText(savedUrl)
        
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
                val url = etUrl.text.toString()
                if (url.isNotEmpty()) {
                    sharedPrefs.edit().putString("last_url", url).apply()
                    startScanning(url, count)
                }
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
        val sharedPrefs = getSharedPreferences("creplaz_prefs", Context.MODE_PRIVATE)
        val savedHour = sharedPrefs.getInt("schedule_hour", -1)
        val savedMinute = sharedPrefs.getInt("schedule_minute", -1)
        val calendar = Calendar.getInstance()
        val hour = if (savedHour != -1) savedHour else calendar.get(Calendar.HOUR_OF_DAY)
        val minute = if (savedMinute != -1) savedMinute else calendar.get(Calendar.MINUTE)

        TimePickerDialog(this, { _, selectedHour, selectedMinute ->
            saveAndScheduleDailyScan(selectedHour, selectedMinute)
        }, hour, minute, true).show()
    }

    private fun saveAndScheduleDailyScan(hour: Int, minute: Int) {
        val sharedPrefs = getSharedPreferences("creplaz_prefs", Context.MODE_PRIVATE)
        sharedPrefs.edit().putInt("schedule_hour", hour).putInt("schedule_minute", minute).apply()
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
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("daily_scan", ExistingPeriodicWorkPolicy.REPLACE, scanRequest)
        val timeStr = String.format("%02d:%02d", hour, minute)
        tvStatus.text = "Daily scan scheduled for $timeStr"
    }

    private fun showSavedScheduleStatus() {
        val sharedPrefs = getSharedPreferences("creplaz_prefs", Context.MODE_PRIVATE)
        val hour = sharedPrefs.getInt("schedule_hour", -1)
        val minute = sharedPrefs.getInt("schedule_minute", -1)
        if (hour != -1 && minute != -1) {
            val timeStr = String.format("%02d:%02d", hour, minute)
            if (articlePlaylist.isEmpty()) tvStatus.text = "Next scan at $timeStr"
        }
    }

    private fun updateSpeedAndSave() {
        val speedText = String.format("%.1fx", currentSpeed)
        tvSpeed.text = speedText
        if (ttsReady) tts.setSpeechRate(currentSpeed)
        val sharedPrefs = getSharedPreferences("creplaz_prefs", Context.MODE_PRIVATE)
        sharedPrefs.edit().putFloat("last_speed", currentSpeed).apply()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.setLanguage(Locale("he", "IL"))
            ttsReady = true
            updateSpeedAndSave()
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    runOnUiThread {
                        if (isPlaying && articlePlaylist.isNotEmpty()) {
                            val removed = articlePlaylist.removeAt(0)
                            removeArticleFromPrefs(removed.title)
                            adapter.updateDisplayList()
                            if (articlePlaylist.isNotEmpty()) playNext() else stopPlayback()
                        }
                    }
                }
                override fun onError(utteranceId: String?) { runOnUiThread { stopPlayback() } }
            })
            updateButtonStates()
        }
    }

    private fun loadSavedArticles() {
        val sharedPrefs = getSharedPreferences("creplaz_prefs", Context.MODE_PRIVATE)
        val titles = sharedPrefs.getStringSet("article_titles", emptySet()) ?: emptySet()
        articlePlaylist.clear()
        titles.forEach { title ->
            val content = sharedPrefs.getString("article_content_$title", "") ?: ""
            val date = sharedPrefs.getString("article_date_$title", "Saved") ?: "Saved"
            articlePlaylist.add(Article(title, content, date))
        }
        if (articlePlaylist.isNotEmpty()) {
            adapter.updateDisplayList()
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
            .remove("article_date_$title")
            .apply()
    }

    private fun startScanning(url: String, daysBack: Int) {
        tvStatus.text = "Scanning..."
        btnScan.isEnabled = false
        lifecycleScope.launch {
            val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
            try {
                val doc = withContext(Dispatchers.IO) { Jsoup.connect(url).userAgent(userAgent).timeout(20000).get() }
                val elements = doc.select("article a[href], .post-item a[href], .elementor-post__title a, h2 a[href], h3 a[href], .entry-title a")
                val links = elements.map { it.attr("abs:href") }.distinct()
                    .filter { it.contains(url.replace("https://www.", "").split("/")[0]) && it.length > 30 && !it.contains("/category/") }

                val sharedPrefs = getSharedPreferences("creplaz_prefs", Context.MODE_PRIVATE)
                var addedCount = 0

                for (link in links) {
                    try {
                        val articleDoc = withContext(Dispatchers.IO) { Jsoup.connect(link).userAgent(userAgent).timeout(10000).get() }
                        val fullDateText = articleDoc.select(".post-date, .entry-date, time, .date, .meta").first()?.text() ?: "Today"
                        
                        val dateParts = fullDateText.split(" ").filter { it.contains(".") || it.length > 3 }
                        val groupDate = if (dateParts.isNotEmpty()) dateParts[0] else "Today"

                        val title = articleDoc.select("h1, .entry-title, .post-title").first()?.text() ?: articleDoc.title()
                        val content = articleDoc.select(".entry-content p, .post-content p, article p").text()
                        
                        if (content.length > 100) {
                            val existingTitles = sharedPrefs.getStringSet("article_titles", emptySet()) ?: emptySet()
                            if (!existingTitles.contains(title)) {
                                val article = Article(title, content, groupDate)
                                articlePlaylist.add(article)
                                addedCount++
                                
                                runOnUiThread {
                                    adapter.updateDisplayList()
                                    tvStatus.text = "Found $addedCount articles..."
                                    hsvControls.visibility = View.VISIBLE
                                }

                                val newTitles = existingTitles.toMutableSet()
                                newTitles.add(title)
                                sharedPrefs.edit()
                                    .putStringSet("article_titles", newTitles)
                                    .putString("article_content_$title", content)
                                    .putString("article_date_$title", groupDate)
                                    .apply()
                            }
                        }
                    } catch (e: Exception) {}
                    if (articlePlaylist.size >= 40) break
                }
                tvStatus.text = if (addedCount > 0) "Scan complete. $addedCount new items." else "No new articles found."
            } catch (e: Exception) { tvStatus.text = "Scan failed." }
            btnScan.isEnabled = true
            updateButtonStates()
        }
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