package com.example.mywatchtimerv2application.presentation

import android.annotation.SuppressLint
import android.app.*
import android.content.*
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.IBinder
import android.util.Log
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.mywatchtimerv2application.R
import java.util.Locale
import java.util.concurrent.TimeUnit

// ---
// 1. TIMER SERVICE CLASS (The core of the background execution)
// This must be a top-level class (not an inner class of MainActivity) to be registered
// in the Android Manifest and run independently.
// ---

class TimerService : Service() {
    private val TAG = "TimerService"

    // Constants for Intent Actions and Broadcasts
    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val ACTION_RESTART = "ACTION_RESTART"
        const val TIMER_BR = "com.example.myapplication.presentation.timer_broadcast"
        const val TIME_LEFT_KEY = "time_left"

        // 30 minutes in milliseconds
        // CHANGE THIS LINE: Calculate 1 hour + 15 minutes
        private val INITIAL_TIME_MS = TimeUnit.HOURS.toMillis(1) + TimeUnit.MINUTES.toMillis(15)
        private const val NOTIFICATION_ID = 101
        private const val CHANNEL_ID = "TimerChannel"
    }

    private var countDownTimer: CountDownTimer? = null
    private var timeRemaining: Long = INITIAL_TIME_MS
    private var isRunning: Boolean = false

    // Binder for direct communication with MainActivity
    private val binder = LocalBinder()
    inner class LocalBinder : Binder() {
        fun getService(): TimerService = this@TimerService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // Start as a foreground service immediately upon creation
        startForeground(NOTIFICATION_ID, buildNotification(timeRemaining, "Paused"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        when (action) {
            ACTION_START -> startTimer()
            ACTION_STOP -> pauseTimer()
            ACTION_RESTART -> restartTimer()
        }
        return START_STICKY
    }

    // --- Service Control Methods ---
    fun startTimer() {
        if (isRunning) return

        if (timeRemaining <= 0) {
            timeRemaining = INITIAL_TIME_MS
        }

        isRunning = true
        countDownTimer?.cancel()

        countDownTimer = object : CountDownTimer(timeRemaining, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                timeRemaining = millisUntilFinished
                updateNotification(timeRemaining, "Running...")
                sendUpdateBroadcast(timeRemaining)
            }

            override fun onFinish() {
                timeRemaining = 0
                isRunning = false
                updateNotification(0, "Finished!")
                sendUpdateBroadcast(0)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }.start()

        updateNotification(timeRemaining, "Running...")
        sendUpdateBroadcast(timeRemaining)
    }

    fun pauseTimer() {
        countDownTimer?.cancel()
        isRunning = false
        updateNotification(timeRemaining, "Paused")
        sendUpdateBroadcast(timeRemaining)
    }

    fun restartTimer() {
        pauseTimer()
        timeRemaining = INITIAL_TIME_MS
        startTimer()
    }

    fun isTimerRunning(): Boolean = isRunning
    fun getTimeRemaining(): Long = timeRemaining

    // --- Notification Methods ---
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(CHANNEL_ID, "Timer Service Channel", NotificationManager.IMPORTANCE_LOW )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun buildNotification(time: Long, status: String): Notification {
        val timeStr = formatTime(time)
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE)

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("30 Minute Timer")
            .setContentText("$timeStr | $status")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setContentIntent(pendingIntent)

        // Action buttons
        if (isRunning) {
            builder.addAction(R.drawable.ic_launcher_foreground, "Pause", getPendingIntent(ACTION_STOP))
        } else if (time > 0) {
            builder.addAction(R.drawable.ic_launcher_foreground, "Resume", getPendingIntent(ACTION_START))
        }
        builder.addAction(R.drawable.ic_launcher_foreground, "Restart", getPendingIntent(ACTION_RESTART))

        return builder.build()
    }

    private fun updateNotification(time: Long, status: String) {
        val notification = buildNotification(time, status)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun getPendingIntent(action: String): PendingIntent {
        val intent = Intent(this, TimerService::class.java).apply { this.action = action }
        return PendingIntent.getService(this, action.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun sendUpdateBroadcast(time: Long) {
        val intent = Intent(TIMER_BR).setPackage(/* TODO: provide the application ID. For example: */
            packageName
        ).apply {
            putExtra(TIME_LEFT_KEY, time)
        }
        sendBroadcast(intent)
    }

    // In TimerService class

    private fun formatTime(timeMs: Long): String {
        val totalSeconds = timeMs / 1000

        // Calculate hours, minutes, and seconds
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60

        // Return string in HH:MM:SS format
        return String.format("%02d:%02d:%02d", hours, minutes, seconds)
    }

    override fun onDestroy() {
        super.onDestroy()
        countDownTimer?.cancel()
        Log.d(TAG, "Service destroyed.")
    }
}


// ---
// 2. MAIN ACTIVITY CLASS (The UI and Communication Handler)
// ---

class MainActivity : Activity() {

    private val TAG = "MainActivity"
    private lateinit var timerText: TextView
    private lateinit var startStopButton: ImageButton
//    private lateinit var restartButton: Button

    private var timerService: TimerService? = null
    private var isBound = false
    // CHANGE THIS LINE: Match the service time (1 hour + 15 minutes)
    private val INITIAL_TIME_MS = TimeUnit.HOURS.toMillis(1) + TimeUnit.MINUTES.toMillis(15)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as TimerService.LocalBinder
            timerService = binder.getService()
            isBound = true
            updateUI()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            isBound = false
            timerService = null
        }
    }

    private val timerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == TimerService.TIMER_BR) {
                val timeRemaining = intent.getLongExtra(TimerService.TIME_LEFT_KEY, 0)
                updateTimerDisplay(timeRemaining)
                updateButtonStates(timeRemaining)
            }
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        timerText = findViewById(R.id.timer_text)
        startStopButton = findViewById(R.id.btnCigarette)

        // Bind to the service
        val serviceIntent = Intent(this, TimerService::class.java)
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)

        // FIX: Register the Broadcast Receiver with the required export flag for Android 13+
        val intentFilter = IntentFilter(TimerService.TIMER_BR)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(timerReceiver, intentFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(timerReceiver, intentFilter)
        }

        startStopButton.setOnClickListener { restartTimer() }
//        restartButton.setOnClickListener { restartTimer() }
    }

    private fun toggleTimer() {
        if (!isBound || timerService == null) return

        val intent = Intent(this, TimerService::class.java)
        if (timerService!!.isTimerRunning()) {
            intent.action = TimerService.ACTION_STOP
        } else {
            intent.action = TimerService.ACTION_START
        }
        ContextCompat.startForegroundService(this, intent)
        updateUI()
    }

    private fun restartTimer() {
        if (!isBound || timerService == null) return

        val intent = Intent(this, TimerService::class.java).apply {
            action = TimerService.ACTION_RESTART
        }
        ContextCompat.startForegroundService(this, intent)
        updateUI()
    }

    private fun updateUI() {
        if (!isBound || timerService == null) return
        val timeRemaining = timerService!!.getTimeRemaining()
        updateTimerDisplay(timeRemaining)
        updateButtonStates(timeRemaining)
    }

// In MainActivity class

    private fun updateTimerDisplay(timeMs: Long) {
        val totalSeconds = timeMs / 1000

        // Calculate hours, minutes, and seconds
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60

        val timeStr = String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds)
        timerText.text = timeStr
    }

    private fun updateButtonStates(timeRemaining: Long) {
        if (!isBound || timerService == null) return

        val running = timerService!!.isTimerRunning()

//        startStopButton.text = when {
//            running -> "PAUSE"
//            timeRemaining > 0 && timeRemaining < INITIAL_TIME_MS -> "RESUME"
//            else -> "START"
//        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(timerReceiver)
        if (isBound) {
            unbindService(serviceConnection)
        }
    }
}

// --- Persistence Constants and Functions ---
const val PREFS_NAME = "SmokedPrefs"
const val CIG_COUNT_KEY = "cig_smoked_count" // Renamed for clarity
const val CIG_LAST_RESET_TIME_KEY = "cig_last_reset_time" // Renamed for clarity
const val WEED_COUNT_KEY = "weed_smoked_count" // New key
const val WEED_LAST_RESET_TIME_KEY = "weed_last_reset_time" // New key

/**
 * Loads a specific smoked count from SharedPreferences.
 */
fun loadCount(context: Context, key: String): Int {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    return prefs.getInt(key, 0)
}

/**
 * Loads a specific last reset time (timestamp in milliseconds) from SharedPreferences.
 */
fun loadLastResetTime(context: Context, key: String): Long {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    return prefs.getLong(key, 0L)
}

/**
 * Saves a specific smoked count and its corresponding last action time to SharedPreferences.
 */
fun saveSmokedData(context: Context, countKey: String, count: Int, timeKey: String, currentTime: Long) {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    prefs.edit()
        .putInt(countKey, count)
        .putLong(timeKey, currentTime)
        .apply()
}
// --- End Persistence Functions ---

