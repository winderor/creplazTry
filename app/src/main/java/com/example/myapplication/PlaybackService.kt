package com.creplaz.newslistener

import android.app.*
import android.content.Intent
import android.media.AudioAttributes
import android.os.*
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.content.edit
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat as MediaStyleNotification
import java.util.*

class PlaybackService : Service(), TextToSpeech.OnInitListener {

    private lateinit var tts: TextToSpeech
    private var ttsReady = false
    private var mediaSession: MediaSessionCompat? = null
    private val playlist = mutableListOf<Article>()
    private var isPlaying = false
    private var currentSpeed = 1.0f
    private var lastCharIndex = 0
    private var currentArticleText = ""
    private var currentUtteranceId: String? = null
    private var currentTimeSecs = 0

    private val updateHandler = Handler(Looper.getMainLooper())
    private val updateRunnable = object : Runnable {
        override fun run() {
            if (isPlaying) {
                currentTimeSecs++
                val estimatedIndex = currentTimeSecs * 15
                if (estimatedIndex > lastCharIndex) {
                    lastCharIndex = minOf(estimatedIndex, currentArticleText.length)
                }
                updateHandler.postDelayed(this, 1000)
            }
        }
    }

    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): PlaybackService = this@PlaybackService
    }

    override fun onCreate() {
        super.onCreate()
        tts = TextToSpeech(this, this)
        setupMediaSession()
        createNotificationChannel()
    }

    private fun setupMediaSession() {
        mediaSession = MediaSessionCompat(this, "CreplazPlayback").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() { play() }
                override fun onPause() { pause() }
                override fun onSkipToNext() { skip() }
                override fun onStop() { stop() }
            })
            isActive = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "PLAY" -> play()
            "PAUSE" -> pause()
            "SKIP" -> skip()
            "STOP" -> stop()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            tts.setAudioAttributes(audioAttributes)
            tts.language = Locale("he", "IL")
            ttsReady = true
            tts.setSpeechRate(currentSpeed)
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    if (utteranceId != currentUtteranceId) return
                    Handler(Looper.getMainLooper()).post {
                        if (isPlaying && playlist.isNotEmpty()) {
                            val removed = playlist.removeAt(0)
                            removeArticleFromPrefs(removed.title)
                            lastCharIndex = 0
                            currentArticleText = "" 
                            if (playlist.isNotEmpty()) {
                                playNext()
                            } else {
                                stop()
                            }
                            updateUI()
                        }
                    }
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) { 
                    Handler(Looper.getMainLooper()).post { stop() } 
                }
                override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
                    val offset = utteranceId?.split("_offset_")?.lastOrNull()?.toIntOrNull() ?: 0
                    lastCharIndex = offset + start
                    updateUI()
                }
            })
        }
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

    fun setPlaylist(newArticles: List<Article>) {
        playlist.clear()
        playlist.addAll(newArticles)
    }

    fun setSpeed(speed: Float) {
        currentSpeed = speed
        if (ttsReady) {
            tts.setSpeechRate(currentSpeed)
            if (isPlaying) {
                resumeAt(lastCharIndex)
            }
        }
    }

    fun play() {
        if (playlist.isEmpty() || !ttsReady) return
        isPlaying = true
        updateMediaSessionState(PlaybackStateCompat.STATE_PLAYING)
        val topArticle = playlist[0]
        
        mediaSession?.setMetadata(MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, topArticle.title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, "Creplaz News")
            .build())

        val expectedPrefix = "Title: ${topArticle.title}."
        if (currentArticleText.startsWith(expectedPrefix)) {
            resumeAt(lastCharIndex)
        } else {
            playNext()
        }
        showNotification()
        updateUI()
        
        updateHandler.removeCallbacks(updateRunnable)
        updateHandler.postDelayed(updateRunnable, 1000)
    }

    fun pause() {
        isPlaying = false
        currentUtteranceId = null
        tts.stop()
        updateMediaSessionState(PlaybackStateCompat.STATE_PAUSED)
        showNotification()
        updateUI()
        updateHandler.removeCallbacks(updateRunnable)
    }

    fun skip() {
        tts.stop()
        lastCharIndex = 0
        if (playlist.isNotEmpty()) {
            val removed = playlist.removeAt(0)
            removeArticleFromPrefs(removed.title)
            if (playlist.isEmpty()) {
                stop()
            } else {
                if (isPlaying) playNext() else updateUI()
            }
        }
    }

    fun stop() {
        isPlaying = false
        currentUtteranceId = null
        tts.stop()
        lastCharIndex = 0
        currentTimeSecs = 0
        updateMediaSessionState(PlaybackStateCompat.STATE_STOPPED)
        stopForeground(STOP_FOREGROUND_REMOVE)
        updateUI()
        updateHandler.removeCallbacks(updateRunnable)
    }

    private fun playNext() {
        if (playlist.isNotEmpty()) {
            val article = playlist[0]
            currentArticleText = "Title: ${article.title}. Content: ${article.content}"
            lastCharIndex = 0
            currentTimeSecs = 0
            
            mediaSession?.setMetadata(MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, article.title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, "Creplaz News")
                .build())
                
            resumeAt(0)
            showNotification()
        }
    }

    private fun resumeAt(startIndex: Int) {
        if (currentArticleText.isNotEmpty()) {
            val textToSpeak = if (startIndex < currentArticleText.length) {
                currentArticleText.substring(startIndex)
            } else { "" }
            if (textToSpeak.isNotEmpty()) {
                val uid = "art_" + System.currentTimeMillis()
                speakInChunks(textToSpeak, uid, startIndex)
            }
        }
    }

    private fun speakInChunks(text: String, baseUid: String, startIndex: Int) {
        val maxLen = 3500
        var pos = 0
        var chunkIndex = 0
        val textLength = text.length
        val numChunks = if (textLength == 0) 0 else (textLength + maxLen - 1) / maxLen
        if (numChunks == 0) {
            currentUtteranceId = null
            return
        }
        while (pos < textLength) {
            val end = minOf(pos + maxLen, textLength)
            val chunk = text.substring(pos, end)
            val params = Bundle()
            val chunkUid = "${baseUid}_part_${chunkIndex}_offset_${startIndex + pos}"
            params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, chunkUid)
            if (chunkIndex == 0) {
                tts.speak(chunk, TextToSpeech.QUEUE_FLUSH, params, chunkUid)
            } else {
                tts.speak(chunk, TextToSpeech.QUEUE_ADD, params, chunkUid)
            }
            if (chunkIndex == numChunks - 1) {
                currentUtteranceId = chunkUid
            }
            pos = end
            chunkIndex++
        }
    }

    private fun updateMediaSessionState(state: Int) {
        val playbackState = PlaybackStateCompat.Builder()
            .setActions(PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or 
                       PlaybackStateCompat.ACTION_SKIP_TO_NEXT or PlaybackStateCompat.ACTION_STOP)
            .setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1.0f)
            .build()
        mediaSession?.setPlaybackState(playbackState)
    }

    private fun showNotification() {
        if (playlist.isEmpty()) return
        val article = playlist[0]
        
        val playPauseAction = if (isPlaying) {
            NotificationCompat.Action(android.R.drawable.ic_media_pause, "Pause", getPendingIntent("PAUSE"))
        } else {
            NotificationCompat.Action(android.R.drawable.ic_media_play, "Play", getPendingIntent("PLAY"))
        }

        val notification = NotificationCompat.Builder(this, "playback_channel")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(article.title)
            .setContentText("Reading news...")
            .setOngoing(isPlaying)
            .setSilent(true)
            .addAction(playPauseAction)
            .addAction(NotificationCompat.Action(android.R.drawable.ic_media_next, "Skip", getPendingIntent("SKIP")))
            .addAction(NotificationCompat.Action(android.R.drawable.ic_delete, "Stop", getPendingIntent("STOP")))
            .setStyle(MediaStyleNotification.MediaStyle()
                .setMediaSession(mediaSession?.sessionToken)
                .setShowActionsInCompactView(0, 1, 2))
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .build()

        startForeground(1, notification)
    }

    private fun getPendingIntent(action: String): PendingIntent {
        val intent = Intent(this, PlaybackService::class.java).apply { this.action = action }
        return PendingIntent.getService(this, action.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("playback_channel", "Playback Controls", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun updateUI() {
        val intent = Intent("UI_UPDATE")
        intent.putExtra("isPlaying", isPlaying)
        intent.putExtra("playlistSize", playlist.size)
        intent.putExtra("topTitle", if (playlist.isNotEmpty()) playlist[0].title else "")
        
        sendBroadcast(intent)
    }

    private fun formatTime(seconds: Int): String {
        val m = seconds / 60
        val s = seconds % 60
        return String.format(Locale.getDefault(), "%02d:%02d", m, s)
    }

    fun seekTo(progressPercentage: Int) {
        if (currentArticleText.isNotEmpty()) {
            val newIndex = (currentArticleText.length * progressPercentage) / 100
            lastCharIndex = newIndex
            currentTimeSecs = lastCharIndex / 15
            if (isPlaying) {
                resumeAt(lastCharIndex)
            } else {
                updateUI()
            }
        }
    }

    override fun onDestroy() {
        updateHandler.removeCallbacks(updateRunnable)
        tts.shutdown()
        mediaSession?.release()
        super.onDestroy()
    }
}
