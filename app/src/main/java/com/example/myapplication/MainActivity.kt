package com.creplaz.newslistener

import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.*
import android.view.*
import android.widget.*
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
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

data class Article(val title: String, val content: String, val date: String = "Recent", val url: String = "")

class ArticleAdapter(private val articles: MutableList<Article>, private val onItemClick: (Article) -> Unit) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private val typeHeader = 0
    private val typeItem = 1
    private var displayList = mutableListOf<Any>()

    init { updateDisplayList() }

    fun updateDisplayList() {
        displayList.clear()
        val sdf = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())
        fun getLabel(d: String): String {
            if (d == "Today" || d == "Recent" || d == "Saved") return d
            return try {
                sdf.parse(d) ?: return d
                val now = Calendar.getInstance()
                val today = sdf.format(now.time)
                now.add(Calendar.DAY_OF_YEAR, -1)
                val yesterday = sdf.format(now.time)
                if (d == today) "Today" else if (d == yesterday) "Yesterday" else d
            } catch(_: Exception) { d }
        }
        val sortedArticles = articles.sortedWith(compareByDescending<Article> { 
            if (it.date == "Today" || it.date == "Recent") Long.MAX_VALUE 
            else try { sdf.parse(it.date)?.time ?: 0L } catch (_: Exception) { 0L }
        }.thenByDescending { it.title })
        val grouped = sortedArticles.groupBy { getLabel(it.date) }
        grouped.forEach { (label, items) ->
            displayList.add(label)
            displayList.addAll(items)
        }
        notifyDataSetChanged()
    }

    class HeaderViewHolder(view: View) : RecyclerView.ViewHolder(view) { val tvDate: TextView = view.findViewById(R.id.tvDate) }
    class ItemViewHolder(view: View) : RecyclerView.ViewHolder(view) { val tvTitle: TextView = view.findViewById(R.id.tvTitle) }

    override fun getItemViewType(position: Int): Int = if (displayList[position] is String) typeHeader else typeItem
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == typeHeader) {
            HeaderViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_header, parent, false))
        } else {
            ItemViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_article, parent, false))
        }
    }
    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder is HeaderViewHolder) {
            holder.tvDate.text = displayList[position] as String
        } else if (holder is ItemViewHolder) {
            val article = displayList[position] as Article
            holder.tvTitle.text = article.title
            holder.itemView.setOnClickListener { onItemClick(article) }
        }
    }
    override fun getItemCount() = displayList.size
    fun removeItem(position: Int): Article? {
        if (position in displayList.indices) {
            val item = displayList[position]
            if (item is Article) { articles.remove(item); updateDisplayList(); return item }
        }
        return null
    }
    fun isHeader(position: Int): Boolean = position in displayList.indices && displayList[position] is String
}

class MainActivity : AppCompatActivity() {

    private var playbackService: PlaybackService? = null
    private var isBound = false
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
    private var currentSpeed = 1.0f

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as PlaybackService.LocalBinder
            playbackService = binder.getService()
            isBound = true
            playbackService?.setPlaylist(articlePlaylist)
            playbackService?.setSpeed(currentSpeed)
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            playbackService = null
            isBound = false
        }
    }

    private val uiUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "UI_UPDATE") {
                val isPlaying = intent.getBooleanExtra("isPlaying", false)
                val topTitle = intent.getStringExtra("topTitle") ?: ""
                val playlistSize = intent.getIntExtra("playlistSize", 0)
                
                runOnUiThread {
                    tvStatus.text = if (isPlaying) "Playing: $topTitle" else if (playlistSize > 0) "Paused" else "No articles"

                    if (playlistSize != articlePlaylist.size) {
                        loadSavedArticles()
                    }
                }
            }
        }
    }

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
        
        adapter = ArticleAdapter(articlePlaylist) { article ->
            if (article.url.isNotEmpty()) {
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(article.url))
                    startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(this, "Could not open link", Toast.LENGTH_SHORT).show()
                }
            }
        }
        rvArticles.layoutManager = LinearLayoutManager(this)
        rvArticles.adapter = adapter

        val sharedPrefs = getSharedPreferences("creplaz_prefs", MODE_PRIVATE)
        currentSpeed = sharedPrefs.getFloat("last_speed", 1.0f)
        tvSpeed.text = String.format(Locale.getDefault(), "%.1fx", currentSpeed)

        val itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
            override fun getSwipeDirs(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int {
                if (adapter.isHeader(viewHolder.adapterPosition)) return 0
                return super.getSwipeDirs(recyclerView, viewHolder)
            }
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, t: RecyclerView.ViewHolder) = false
            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.adapterPosition
                val removedArticle = adapter.removeItem(position)
                removedArticle?.let { 
                    removeArticleFromPrefs(it.title)
                    playbackService?.setPlaylist(articlePlaylist)
                }
            }
        })
        itemTouchHelper.attachToRecyclerView(rvArticles)

        try {
            val pInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0)
            }
            tvVersion.text = "v${pInfo.versionName}"
        } catch (_: Exception) { tvVersion.text = "v" }

        btnScan.setOnClickListener { showScanDaysDialog() }
        btnSchedule.setOnClickListener { showTimePicker() }
        
        btnPlay.setOnClickListener {
            if (articlePlaylist.isEmpty()) return@setOnClickListener
            playbackService?.setPlaylist(articlePlaylist)
            playbackService?.play()
        }
        btnPause.setOnClickListener { playbackService?.pause() }
        btnSkip.setOnClickListener { 
            playbackService?.skip() 
        }
        btnStop.setOnClickListener { playbackService?.stop() }

        btnSpeedUp.setOnClickListener {
            if (currentSpeed < 2.5f) { currentSpeed += 0.1f; updateSpeedAndSave() }
        }
        btnSpeedDown.setOnClickListener {
            if (currentSpeed > 0.5f) { currentSpeed -= 0.1f; updateSpeedAndSave() }
        }

        loadSavedArticles()
        showSavedScheduleStatus()
        
        val intent = Intent(this, PlaybackService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        bindService(intent, connection, BIND_AUTO_CREATE)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(uiUpdateReceiver, IntentFilter("UI_UPDATE"), RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(uiUpdateReceiver, IntentFilter("UI_UPDATE"))
        }
    }

    private fun showScanDaysDialog() {
        val sharedPrefs = getSharedPreferences("creplaz_prefs", MODE_PRIVATE)
        val savedSources = sharedPrefs.getString("scan_sources", "telegram|https://t.me/geektimecoil") ?: "telegram|https://t.me/geektime"
        val savedDays = sharedPrefs.getInt("last_days_back", 1)
        
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_scan_days, null)
        val llSources = dialogView.findViewById<LinearLayout>(R.id.llSources)
        val btnAddSource = dialogView.findViewById<Button>(R.id.btnAddSource)
        
        fun addSourceRow(type: String = "Website", value: String = "") {
            val row = LayoutInflater.from(this).inflate(R.layout.item_source_row, llSources, false)
            val spinner = row.findViewById<Spinner>(R.id.spinnerType)
            val etValue = row.findViewById<EditText>(R.id.etSourceValue)
            val btnRemove = row.findViewById<ImageButton>(R.id.btnRemoveSource)
            
            spinner.setSelection(if (type.lowercase() == "telegram") 1 else 0)
            etValue.setText(value)
            btnRemove.setOnClickListener { llSources.removeView(row) }
            llSources.addView(row)
        }

        // Load existing sources
        savedSources.split(";;").filter { it.isNotEmpty() }.forEach { 
            val parts = it.split("|")
            if (parts.size == 2) addSourceRow(parts[0], parts[1])
        }
        if (llSources.childCount == 0) addSourceRow()

        btnAddSource.setOnClickListener { addSourceRow() }

        val btnMinus = dialogView.findViewById<Button>(R.id.btnMinus)
        val btnPlus = dialogView.findViewById<Button>(R.id.btnPlus)
        val tvDayCount = dialogView.findViewById<TextView>(R.id.tvDayCount)
        var count = savedDays
        tvDayCount.text = count.toString()

        btnMinus.setOnClickListener { if (count > 1) { count--; tvDayCount.text = count.toString() } }
        btnPlus.setOnClickListener { if (count < 14) { count++; tvDayCount.text = count.toString() } }

        AlertDialog.Builder(this)
            .setView(dialogView)
            .setPositiveButton("Scan") { _, _ ->
                val sourcesList = mutableListOf<String>()
                for (i in 0 until llSources.childCount) {
                    val row = llSources.getChildAt(i)
                    val spinner = row.findViewById<Spinner>(R.id.spinnerType)
                    val etValue = row.findViewById<EditText>(R.id.etSourceValue)
                    val type = if (spinner.selectedItemPosition == 1) "telegram" else "website"
                    val value = etValue.text.toString().trim()
                    if (value.isNotEmpty()) sourcesList.add("$type|$value")
                }
                
                if (sourcesList.isNotEmpty()) {
                    val sourcesString = sourcesList.joinToString(";;")
                    sharedPrefs.edit {
                        putInt("last_days_back", count)
                        putString("scan_sources", sourcesString)
                    }
                    startMultiScan(sourcesList, count)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startMultiScan(sources: List<String>, daysBack: Int) {
        tvStatus.text = "Starting multi-scan..."
        btnScan.isEnabled = false
        lifecycleScope.launch {
            var totalAdded = 0
            for (source in sources) {
                val parts = source.split("|")
                if (parts.size != 2) continue
                val type = parts[0]
                val value = parts[1]
                
                try {
                    val links = if (type == "telegram") {
                        val channel = value.replace("https://t.me/s/", "").replace("https://t.me/", "").replace("@", "").trim().split("/")[0]
                        val url = "https://t.me/s/$channel"
                        val doc = withContext(Dispatchers.IO) { Jsoup.connect(url).userAgent("Mozilla/5.0").timeout(20000).get() }
                        doc.select(".tgme_widget_message_text a[href], .tgme_widget_message_inline_button, a.tgme_widget_message_link_preview")
                            .map { it.attr("abs:href") }.distinct().filter { it.length > 20 && !it.contains("t.me/") }
                    } else {
                        val doc = withContext(Dispatchers.IO) { Jsoup.connect(value).userAgent("Mozilla/5.0").timeout(20000).get() }
                        doc.select("article a[href], .post-item a[href], h2 a[href], h3 a[href]").map { it.attr("abs:href") }.distinct()
                            .filter { it.contains(value.replace("https://www.", "").split("/")[0]) && it.length > 30 }
                    }
                    totalAdded += processLinksForMultiScan(links, daysBack)
                } catch (_: Exception) {}
            }
            tvStatus.text = "Multi-scan complete. $totalAdded new items."
            btnScan.isEnabled = true
            playbackService?.setPlaylist(articlePlaylist)
        }
    }

    private suspend fun processLinksForMultiScan(links: List<String>, daysBack: Int): Int {
        val sharedPrefs = getSharedPreferences("creplaz_prefs", MODE_PRIVATE)
        var addedCount = 0
        val sdf = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())
        val calendar = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -daysBack); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0) }
        val limitDate = calendar.time

        for (link in links) {
            try {
                val articleDoc = withContext(Dispatchers.IO) { Jsoup.connect(link).userAgent("Mozilla/5.0").timeout(10000).get() }
                val dateText = articleDoc.select(".post-date, time, .date").text()
                var articleDate: Date? = null
                val match = Regex("(\\d{1,2})[./](\\d{1,2})[./](\\d{4})").find(dateText)
                if (match != null) articleDate = sdf.parse(match.value.replace("/", "."))
                
                if (articleDate != null && articleDate.before(limitDate)) continue
                val groupDate = if (articleDate != null) sdf.format(articleDate) else "Recent"
                val title = articleDoc.select("h1, .entry-title").first()?.text() ?: articleDoc.title()
                
                // Improved content extraction: filter out English-only paragraphs at the end of Hebrew articles
                val paragraphs = articleDoc.select(".entry-content p, article p")
                val contentBuilder = StringBuilder()
                var hasHebrewInArticle = false
                
                val pTexts = paragraphs.map { it.text() }.filter { it.length > 20 }
                
                for (pText in pTexts) {
                    val hasHebrew = pText.any { it in '\u0590'..'\u05FF' }
                    if (hasHebrew) hasHebrewInArticle = true
                    
                    // Heuristic: If this is a Hebrew article and we hit a paragraph with NO Hebrew 
                    // that is significantly long, it might be the English research paper starting.
                    if (hasHebrewInArticle && !hasHebrew && pText.length > 200) {
                        // Check if the rest of the paragraphs are also English
                        val remaining = pTexts.subList(pTexts.indexOf(pText), pTexts.size)
                        val anyHebrewLeft = remaining.any { rel -> rel.any { c -> c in '\u0590'..'\u05FF' } }
                        if (!anyHebrewLeft) break // Stop here, it's all English now
                    }
                    
                    contentBuilder.append(pText).append(" ")
                }
                
                val content = contentBuilder.toString().trim()
                
                val currentTitles = sharedPrefs.getStringSet("article_titles", emptySet()) ?: emptySet()
                val historyTitles = sharedPrefs.getStringSet("history_titles", emptySet()) ?: emptySet()

                if (content.length > 100 && !currentTitles.contains(title) && !historyTitles.contains(title)) {
                    val article = Article(title, content, groupDate)
                    articlePlaylist.add(article); addedCount++
                    runOnUiThread { adapter.updateDisplayList(); tvStatus.text = "Found articles... ($addedCount new)" ; hsvControls.visibility = View.VISIBLE }
                    
                    val newTitles = currentTitles.toMutableSet().apply { add(title) }
                    val newHistory = historyTitles.toMutableSet().apply { add(title) }
                    
                    sharedPrefs.edit { 
                        putStringSet("article_titles", newTitles)
                        putStringSet("history_titles", newHistory)
                        putString("article_content_$title", content)
                        putString("article_date_$title", groupDate)
                        putString("article_url_$title", link)
                    }
                }
            } catch (_: Exception) {}
            if (articlePlaylist.size >= 100) break
        }
        return addedCount
    }

    override fun onDestroy() {
        if (isBound) { unbindService(connection); isBound = false }
        try { unregisterReceiver(uiUpdateReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun showTimePicker() {
        val sharedPrefs = getSharedPreferences("creplaz_prefs", MODE_PRIVATE)
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
        val sharedPrefs = getSharedPreferences("creplaz_prefs", MODE_PRIVATE)
        sharedPrefs.edit { putInt("schedule_hour", hour); putInt("schedule_minute", minute) }
        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute); set(Calendar.SECOND, 0) }
        if (target.before(now)) target.add(Calendar.DAY_OF_YEAR, 1)
        val delay = target.timeInMillis - now.timeInMillis
        val scanRequest = PeriodicWorkRequestBuilder<ScanWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("daily_scan", ExistingPeriodicWorkPolicy.REPLACE, scanRequest)
        tvStatus.text = "Daily scan scheduled for ${String.format(Locale.getDefault(), "%02d:%02d", hour, minute)}"
    }

    private fun showSavedScheduleStatus() {
        val sharedPrefs = getSharedPreferences("creplaz_prefs", MODE_PRIVATE)
        val hour = sharedPrefs.getInt("schedule_hour", -1)
        val minute = sharedPrefs.getInt("schedule_minute", -1)
        if (hour != -1 && minute != -1) {
            val timeStr = String.format(Locale.getDefault(), "%02d:%02d", hour, minute)
            if (articlePlaylist.isEmpty()) tvStatus.text = "Next scan at $timeStr"
        }
    }

    private fun updateSpeedAndSave() {
        tvSpeed.text = String.format(Locale.getDefault(), "%.1fx", currentSpeed)
        playbackService?.setSpeed(currentSpeed)
        getSharedPreferences("creplaz_prefs", MODE_PRIVATE).edit { putFloat("last_speed", currentSpeed) }
    }

    private fun loadSavedArticles() {
        val sharedPrefs = getSharedPreferences("creplaz_prefs", MODE_PRIVATE)
        val titles = sharedPrefs.getStringSet("article_titles", emptySet()) ?: emptySet()
        articlePlaylist.clear()
        titles.forEach { title ->
            val content = sharedPrefs.getString("article_content_$title", "") ?: ""
            val date = sharedPrefs.getString("article_date_$title", "Saved") ?: "Saved"
            val url = sharedPrefs.getString("article_url_$title", "") ?: ""
            articlePlaylist.add(Article(title, content, date, url))
        }
        runOnUiThread {
            if (articlePlaylist.isNotEmpty()) {
                adapter.updateDisplayList()
                hsvControls.visibility = View.VISIBLE
            }
        }
        playbackService?.setPlaylist(articlePlaylist)
    }

    private fun removeArticleFromPrefs(title: String) {
        val sharedPrefs = getSharedPreferences("creplaz_prefs", MODE_PRIVATE)
        val titles = sharedPrefs.getStringSet("article_titles", emptySet())?.toMutableSet() ?: mutableSetOf()
        titles.remove(title)
        sharedPrefs.edit { 
            putStringSet("article_titles", titles)
            remove("article_content_$title")
            remove("article_date_$title")
            remove("article_url_$title")
        }
    }
}
