package com.example.talkingtimer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.util.Locale

/**
 * Counts and speaks numbers from a foreground service, so Android keeps it
 * alive while the screen is locked.
 */
class TimerService : Service(), TextToSpeech.OnInitListener {

    companion object {
        const val ACTION_START = "com.example.talkingtimer.START"
        const val ACTION_PAUSE = "com.example.talkingtimer.PAUSE"
        const val ACTION_RESUME = "com.example.talkingtimer.RESUME"
        const val ACTION_STOP = "com.example.talkingtimer.STOP"

        const val EXTRA_DOWN = "down"
        const val EXTRA_START = "start"
        const val EXTRA_STOP = "stop"
        const val EXTRA_INTERVAL = "interval" // seconds
        const val EXTRA_RATE = "rate"         // speech speed, 1.0 = normal
        const val NONE = Int.MIN_VALUE

        private const val CHANNEL_ID = "talking_timer"
        private const val NOTIF_ID = 1

        // Read by the UI
        @Volatile var current = 0
        @Volatile var active = false   // service is counting or paused
        @Volatile var running = false  // counting right now
        var onChange: (() -> Unit)? = null
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var tts: TextToSpeech
    private var ttsReady = false
    private var pendingStart = false

    private var wakeLock: PowerManager.WakeLock? = null
    private var focusRequest: AudioFocusRequest? = null

    private var down = false
    private var step = 1
    private var end: Int? = null
    private var intervalMs = 1000L
    private var value = 0          // next number to speak
    private var nextAt = 0L

    private val tickRunnable = object : Runnable {
        override fun run() {
            speak(value.toString())
            current = value
            notifyUi()
            if (reachedEnd(value)) {
                speak("Done", queue = true)
                running = false
                notifyUi()
                handler.postDelayed({ shutdown() }, 3000)
                return
            }
            value = nextValue(value)
            nextAt += intervalMs
            handler.postDelayed(this, (nextAt - SystemClock.elapsedRealtime()).coerceAtLeast(0))
        }
    }

    override fun onCreate() {
        super.onCreate()
        tts = TextToSpeech(this, this)
        val channel = NotificationChannel(CHANNEL_ID, "Talking Timer", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.getDefault()
            tts.setAudioAttributes(speechAttrs())
            ttsReady = true
            if (pendingStart) begin()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                // Must be called quickly after startForegroundService()
                ServiceCompat.startForeground(
                    this, NOTIF_ID, buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
                handler.removeCallbacksAndMessages(null)
                down = intent.getBooleanExtra(EXTRA_DOWN, false)
                value = intent.getIntExtra(EXTRA_START, 0)
                current = value
                step = 1
                intervalMs = (intent.getIntExtra(EXTRA_INTERVAL, 1).coerceAtLeast(1)) * 1000L
                tts.setSpeechRate(intent.getFloatExtra(EXTRA_RATE, 1f).coerceIn(0.3f, 3f))
                val stop = intent.getIntExtra(EXTRA_STOP, NONE)
                end = if (stop != NONE) stop else if (down) 0 else null
                active = true
                if (ttsReady) begin() else pendingStart = true
            }
            ACTION_PAUSE -> if (running) {
                handler.removeCallbacks(tickRunnable)
                running = false
                tts.stop()
                refresh()
            }
            ACTION_RESUME -> if (active && !running) {
                running = true
                nextAt = SystemClock.elapsedRealtime()
                handler.post(tickRunnable)
                refresh()
            }
            ACTION_STOP -> shutdown()
            else -> stopSelf() // restarted by the system with no intent
        }
        return START_NOT_STICKY
    }

    private fun begin() {
        pendingStart = false
        running = true
        // Keeps the CPU awake while the screen is off
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock?.release()
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "talkingtimer:count").apply {
            acquire(24 * 60 * 60 * 1000L)
        }
        requestFocus()
        nextAt = SystemClock.elapsedRealtime()
        handler.post(tickRunnable)
        refresh()
    }

    private fun reachedEnd(v: Int): Boolean {
        val e = end ?: return false
        return if (down) v <= e else v >= e
    }

    private fun nextValue(v: Int): Int {
        val n = if (down) v - step else v + step
        val e = end ?: return n
        return if (down) maxOf(n, e) else minOf(n, e)
    }

    private fun speak(text: String, queue: Boolean = false) {
        tts.speak(text, if (queue) TextToSpeech.QUEUE_ADD else TextToSpeech.QUEUE_FLUSH, null, "u$text")
    }

    private fun speechAttrs() = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private fun requestFocus() {
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(speechAttrs()).build()
        getSystemService(AudioManager::class.java).requestAudioFocus(req)
        focusRequest = req
    }

    private fun refresh() {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
        notifyUi()
    }

    private fun notifyUi() {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
        onChange?.invoke()
    }

    private fun action(name: String, label: String, code: Int): NotificationCompat.Action {
        val pi = PendingIntent.getService(
            this, code, Intent(this, TimerService::class.java).setAction(name),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Action(0, label, pi)
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val text = if (running) "$current  (${if (down) "counting down" else "counting up"})"
        else "Paused at $current"
        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Talking Timer")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        if (running) b.addAction(action(ACTION_PAUSE, "Pause", 1))
        else b.addAction(action(ACTION_RESUME, "Resume", 2))
        b.addAction(action(ACTION_STOP, "Stop", 3))
        return b.build()
    }

    private fun shutdown() {
        handler.removeCallbacksAndMessages(null)
        running = false
        active = false
        wakeLock?.let { if (it.isHeld) it.release() }
        focusRequest?.let { getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it) }
        onChange?.invoke()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        tts.stop()
        tts.shutdown()
        wakeLock?.let { if (it.isHeld) it.release() }
        running = false
        active = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
