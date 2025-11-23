package com.example.mywatchtimerv2application.presentation

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.IBinder
import android.util.Log
import android.widget.ImageButton
import android.widget.TextView
import androidx.compose.ui.semantics.text
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.mywatchtimerv2application.R
import java.util.Locale
import java.util.concurrent.TimeUnit

// ---
// 1. TIMER SERVICE CLASS (Refactored to allow negative counting)
// ---

class TimerService : Service() {
    private val TAG = "TimerService"

    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val ACTION_RESTART = "ACTION_RESTART"
        const val TIMER_BR = "com.example.myapplication.presentation.timer_broadcast"
        const val TIME_LEFT_KEY = "time_left"
        const val IS_OVERTIME_KEY = "is_overtime" // Key to broadcast overtime state

        private val INITIAL_TIME_MS = TimeUnit.HOURS.toMillis(1) + TimeUnit.MINUTES.toMillis(15)
        private const val NOTIFICATION_ID = 101
        private const val CHANNEL_ID = "TimerChannel"
    }

    private var mainTimer: CountDownTimer? = null
    private var overtimeTimer: CountDownTimer? = null // Timer for counting up
    private var timeRemaining: Long = INITIAL_TIME_MS
    private var overtimeCounter: Long = 0 // Stores the overtime in ms
    private var isRunning: Boolean = false
    private var isOvertime: Boolean = false

    private val binder = LocalBinder()
    inner class LocalBinder : Binder() {
        fun getService(): TimerService = this@TimerService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification(timeRemaining, "Paused"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startTimer()
            ACTION_STOP -> pauseTimer()
            ACTION_RESTART -> restartTimer()
        }
        return START_STICKY
    }

    // --- Service Control Methods ---
    fun startTimer() {
        if (isRunning) return
        isRunning = true

        if (isOvertime) {
            // If resuming from a paused overtime state, restart the overtime timer
            startOvertimeTimer()
        } else {
            // Otherwise, start the main countdown
            mainTimer?.cancel()
            mainTimer = object : CountDownTimer(timeRemaining, 1000) {
                override fun onTick(millisUntilFinished: Long) {
                    timeRemaining = millisUntilFinished
                    updateNotification(timeRemaining, "Running...")
                    sendUpdateBroadcast()
                }

                override fun onFinish() {
                    isOvertime = true
                    timeRemaining = 0
                    overtimeCounter = 0 // Reset overtime
                    startOvertimeTimer() // Start counting up
                }
            }.start()
        }

        updateNotification(timeRemaining, "Running...")
        sendUpdateBroadcast()
    }

    private fun startOvertimeTimer() {
        overtimeTimer?.cancel()
        overtimeTimer = object : CountDownTimer(Long.MAX_VALUE, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                overtimeCounter += 1000 // Increment by one second
                updateNotification(overtimeCounter, "Overtime...")
                sendUpdateBroadcast()
            }

            override fun onFinish() { /* Will not be called */ }
        }.start()
    }

    fun pauseTimer() {
        isRunning = false
        mainTimer?.cancel()
        overtimeTimer?.cancel() // Pause both timers
        updateNotification(if (isOvertime) overtimeCounter else timeRemaining, "Paused")
        sendUpdateBroadcast()
    }

    fun restartTimer() {
        pauseTimer() // Stop everything first
        timeRemaining = INITIAL_TIME_MS
        isOvertime = false
        overtimeCounter = 0
        startTimer() // Start the main countdown fresh
    }

    fun isTimerRunning(): Boolean = isRunning
    fun getTimeRemaining(): Long = timeRemaining
    fun getOvertime(): Long = overtimeCounter
    fun isOvertime(): Boolean = isOvertime

    // --- Notification & Broadcast Methods ---
    private fun sendUpdateBroadcast() {
        val intent = Intent(TIMER_BR).setPackage(packageName).apply {
            putExtra(TIME_LEFT_KEY, if (isOvertime) overtimeCounter else timeRemaining)
            putExtra(IS_OVERTIME_KEY, isOvertime)
        }
        sendBroadcast(intent)
    }

    private fun formatTime(timeMs: Long, isNegative: Boolean): String {
        val totalSeconds = timeMs / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60

        // Add a negative sign if in overtime
        val prefix = if (isNegative) "-" else ""
        return String.format("%s%02d:%02d:%02d", prefix, hours, minutes, seconds)
    }

    private fun updateNotification(time: Long, status: String) {
        val notification = buildNotification(time, status)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(time: Long, status: String): Notification {
        // Use the new formatTime function
        val timeStr = formatTime(time, isOvertime)
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE)

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Timer")
            .setContentText("$timeStr | $status")
            .setSmallIcon(R.drawable.cigarette_icon) // Using the app icon
            .setOngoing(true)
            .setContentIntent(pendingIntent)

        // Action buttons logic can remain the same
        if (isRunning) {
            builder.addAction(0, "Pause", getPendingIntent(ACTION_STOP))
        } else {
            builder.addAction(0, "Resume", getPendingIntent(ACTION_START))
        }
        builder.addAction(0, "Restart", getPendingIntent(ACTION_RESTART))

        return builder.build()
    }

    // Unchanged methods: createNotificationChannel, getPendingIntent
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(CHANNEL_ID, "Timer Service Channel", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun getPendingIntent(action: String): PendingIntent {
        val intent = Intent(this, TimerService::class.java).apply { this.action = action }
        return PendingIntent.getService(this, action.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    override fun onDestroy() {
        super.onDestroy()
        mainTimer?.cancel()
        overtimeTimer?.cancel()
        Log.d(TAG, "Service destroyed.")
    }
}

// ---
// 2. MAIN ACTIVITY CLASS (The UI and Communication Handler)
// ---

class MainActivity : Activity() {

    private val TAG = "MainActivity"
    // Timer UI
    private lateinit var timerText: TextView
    private lateinit var btnCigarette: ImageButton
    private lateinit var btnWeed: ImageButton

    // Counter UI
    private lateinit var tvCigCount: TextView
    private lateinit var tvWeedCount: TextView

    // Counter State
    private var cigaretteCount: Int = 0
    private var weedCount: Int = 0

    // Timer Service
    private var timerService: TimerService? = null
    private var isBound = false

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

    // MODIFIED: Handle overtime state from broadcast
    private val timerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == TimerService.TIMER_BR) {
                val time = intent.getLongExtra(TimerService.TIME_LEFT_KEY, 0)
                val isOvertime = intent.getBooleanExtra(TimerService.IS_OVERTIME_KEY, false)
                updateTimerDisplay(time, isOvertime) // Pass overtime state to UI update
                updateButtonStates()
            }
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // --- Initialize UI components ---
        timerText = findViewById(R.id.timer_text)
        btnCigarette = findViewById(R.id.btnCigarette)
        btnWeed = findViewById(R.id.btnWeed)
        tvCigCount = findViewById(R.id.tvCigCount)
        tvWeedCount = findViewById(R.id.tvWeedCount)

        // --- Load persistent counts and update UI ---
        cigaretteCount = loadCount(this, CIG_COUNT_KEY)
        weedCount = loadCount(this, WEED_COUNT_KEY)
        updateCounterUI()

        // --- Bind to the timer service ---
        val serviceIntent = Intent(this, TimerService::class.java)
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)

        // --- Register Broadcast Receiver ---
        val intentFilter = IntentFilter(TimerService.TIMER_BR)
        ContextCompat.registerReceiver(this, timerReceiver, intentFilter, ContextCompat.RECEIVER_NOT_EXPORTED)

        // --- Setup Click Listeners ---
        btnCigarette.setOnClickListener {
            cigaretteCount++
            saveSmokedData(this, CIG_COUNT_KEY, cigaretteCount, CIG_LAST_RESET_TIME_KEY, System.currentTimeMillis())
            updateCounterUI()
            restartTimer()
        }

        btnWeed.setOnClickListener {
            weedCount++
            saveSmokedData(this, WEED_COUNT_KEY, weedCount, WEED_LAST_RESET_TIME_KEY, System.currentTimeMillis())
            updateCounterUI()
            restartTimer()
        }
    }

    private fun updateCounterUI() {
        tvCigCount.text = "Cigarettes: $cigaretteCount"
        tvWeedCount.text = "Weed: $weedCount"
    }

    private fun restartTimer() {
        if (!isBound || timerService == null) return
        val intent = Intent(this, TimerService::class.java).apply {
            action = TimerService.ACTION_RESTART
        }
        ContextCompat.startForegroundService(this, intent)
        updateUI()
    }

    // Update UI based on service state
    private fun updateUI() {
        if (!isBound || timerService == null) return

        val isOvertime = timerService!!.isOvertime()
        val time = if (isOvertime) timerService!!.getOvertime() else timerService!!.getTimeRemaining()

        updateTimerDisplay(time, isOvertime)
        updateButtonStates()
    }

    // MODIFIED: Accept overtime state to format text correctly
    private fun updateTimerDisplay(timeMs: Long, isOvertime: Boolean) {
        val totalSeconds = timeMs / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60

        val prefix = if (isOvertime) "-" else ""
        timerText.text = String.format(Locale.getDefault(), "%s%02d:%02d:%02d", prefix, hours, minutes, seconds)
    }

    private fun updateButtonStates() {
        if (!isBound || timerService == null) return
        val running = timerService!!.isTimerRunning()
        // You can add visual feedback for running/paused state here if you wish
        // e.g., btnCigarette.alpha = if(running) 1.0f else 0.5f
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(timerReceiver)
        if (isBound) {
            unbindService(serviceConnection)
        }
    }
}

// --- Persistence Constants and Functions (Unchanged) ---
const val PREFS_NAME = "SmokedPrefs"
const val CIG_COUNT_KEY = "cig_smoked_count"
const val CIG_LAST_RESET_TIME_KEY = "cig_last_reset_time"
const val WEED_COUNT_KEY = "weed_smoked_count"
const val WEED_LAST_RESET_TIME_KEY = "weed_last_reset_time"

fun loadCount(context: Context, key: String): Int {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    return prefs.getInt(key, 0)
}

fun loadLastResetTime(context: Context, key: String): Long {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    return prefs.getLong(key, 0L)
}

fun saveSmokedData(context: Context, countKey: String, count: Int, timeKey: String, currentTime: Long) {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    prefs.edit()
        .putInt(countKey, count)
        .putLong(timeKey, currentTime)
        .apply()
}
