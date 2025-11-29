package com.example.mywatchtimerv2application.presentation

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
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
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit

// ---
// 1. TIMER SERVICE CLASS (Unchanged)
// ---
class TimerService : Service() {
    private val TAG = "TimerService"

    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val ACTION_RESTART = "ACTION_RESTART"
        const val TIMER_BR = "com.example.myapplication.presentation.timer_broadcast"
        const val TIME_LEFT_KEY = "time_left"
        const val IS_OVERTIME_KEY = "is_overtime"

        val INITIAL_TIME_MS = TimeUnit.HOURS.toMillis(1) + TimeUnit.MINUTES.toMillis(15)
        private const val NOTIFICATION_ID = 101
        private const val CHANNEL_ID = "TimerChannel"
    }

    private var mainTimer: CountDownTimer? = null
    private var overtimeTimer: CountDownTimer? = null
    private var timeRemaining: Long = INITIAL_TIME_MS
    private var overtimeCounter: Long = 0
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

    fun startTimer() {
        if (isRunning) return
        isRunning = true

        if (isOvertime) {
            startOvertimeTimer()
        } else {
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
                    overtimeCounter = 0
                    startOvertimeTimer()
                }
            }.start()
        }
        updateNotification(if (isOvertime) overtimeCounter else timeRemaining, "Running...")
        sendUpdateBroadcast()
    }

    private fun startOvertimeTimer() {
        overtimeTimer?.cancel()
        overtimeTimer = object : CountDownTimer(Long.MAX_VALUE, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                overtimeCounter += 1000
                updateNotification(overtimeCounter, "Overtime...")
                sendUpdateBroadcast()
            }
            override fun onFinish() { /* Unreachable */ }
        }.start()
    }

    fun pauseTimer() {
        isRunning = false
        mainTimer?.cancel()
        overtimeTimer?.cancel()
        updateNotification(if (isOvertime) overtimeCounter else timeRemaining, "Paused")
        sendUpdateBroadcast()
    }

    fun restartTimer() {
        pauseTimer()
        timeRemaining = INITIAL_TIME_MS
        isOvertime = false
        overtimeCounter = 0
        startTimer()
    }

    fun isTimerRunning(): Boolean = isRunning
    fun getTimeRemaining(): Long = timeRemaining
    fun getOvertime(): Long = overtimeCounter
    fun isOvertime(): Boolean = isOvertime

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
        val prefix = if (isNegative) "-" else ""
        return String.format("%s%02d:%02d:%02d", prefix, hours, minutes, seconds)
    }

    private fun updateNotification(time: Long, status: String) {
        val notification = buildNotification(time, status)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(time: Long, status: String): Notification {
        val timeStr = formatTime(time, isOvertime)
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE)

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Timer")
            .setContentText("$timeStr | $status")
            .setSmallIcon(R.drawable.cigarette_icon)
            .setOngoing(true)
            .setContentIntent(pendingIntent)

        if (isRunning) {
            builder.addAction(0, "Pause", getPendingIntent(ACTION_STOP))
        } else {
            builder.addAction(0, "Resume", getPendingIntent(ACTION_START))
        }
        builder.addAction(0, "Restart", getPendingIntent(ACTION_RESTART))

        return builder.build()
    }

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
    // UI
    private lateinit var timerText: TextView
    private lateinit var tvEndTime: TextView
    private lateinit var btnCigarette: ImageButton
    private lateinit var btnWeed: ImageButton
    private lateinit var btnReset: ImageButton
    private lateinit var tvCigCount: TextView
    private lateinit var tvWeedCount: TextView
    private lateinit var tvCigEntries: TextView
    private lateinit var tvWeedEntries: TextView

    // State
    private var cigaretteCount: Int = 0
    private var weedCount: Int = 0
    private var cigEntries = mutableListOf<String>()
    private var weedEntries = mutableListOf<String>()
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

    private val timerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == TimerService.TIMER_BR) {
                val time = intent.getLongExtra(TimerService.TIME_LEFT_KEY, 0)
                val isOvertime = intent.getBooleanExtra(TimerService.IS_OVERTIME_KEY, false)
                updateTimerDisplay(time, isOvertime)
                updateButtonStates()
            }
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize UI components
        timerText = findViewById(R.id.timer_text)
        tvEndTime = findViewById(R.id.tvEndTime)
        btnCigarette = findViewById(R.id.btnCigarette)
        btnWeed = findViewById(R.id.btnWeed)
        btnReset = findViewById(R.id.btnReset)
        tvCigCount = findViewById(R.id.tvCigCount)
        tvWeedCount = findViewById(R.id.tvWeedCount)
        tvCigEntries = findViewById(R.id.tvCigEntries)
        tvWeedEntries = findViewById(R.id.tvWeedEntries)

        // Load persistent data
        loadAllData()

        checkAndResetCountersIfNeeded()
        updateCounterUI()
        updateEntriesUI()

        // Bind to the timer service
        val serviceIntent = Intent(this, TimerService::class.java)
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)

        // Register Broadcast Receiver
        ContextCompat.registerReceiver(this, timerReceiver, IntentFilter(TimerService.TIMER_BR), ContextCompat.RECEIVER_NOT_EXPORTED)

        // Setup Click Listeners
        btnCigarette.setOnClickListener {
            val currentTime = System.currentTimeMillis()
            cigaretteCount++
            cigEntries.add(formatTimestamp(currentTime))
            saveEntryData(this, CIG_COUNT_KEY, cigaretteCount, CIG_ENTRIES_KEY, cigEntries.toSet())
            updateCounterUI()
            updateEntriesUI()
            restartTimer()
        }

        btnWeed.setOnClickListener {
            val currentTime = System.currentTimeMillis()
            weedCount++
            weedEntries.add(formatTimestamp(currentTime))
            saveEntryData(this, WEED_COUNT_KEY, weedCount, WEED_ENTRIES_KEY, weedEntries.toSet())
            updateCounterUI()
            updateEntriesUI()
            restartTimer()
        }

        btnReset.setOnClickListener {
            showResetConfirmationDialog()
        }
    }

    private fun checkAndResetCountersIfNeeded() {
        val lastResetTime = loadLastResetTime(this, LAST_APP_OPEN_KEY)
        val now = Calendar.getInstance()
        val lastResetCal = Calendar.getInstance().apply { timeInMillis = lastResetTime }

        val isNewDay = now.get(Calendar.DAY_OF_YEAR) != lastResetCal.get(Calendar.DAY_OF_YEAR) ||
                now.get(Calendar.YEAR) != lastResetCal.get(Calendar.YEAR)

        if (isNewDay && now.get(Calendar.HOUR_OF_DAY) >= 9) {
            performReset()
            saveLastResetTime(this, LAST_APP_OPEN_KEY, now.timeInMillis)
        }
    }

    private fun showResetConfirmationDialog() {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Reset Counters")
            .setMessage("Are you sure you want to reset all counts and entries?")
            .setPositiveButton("Reset") { _, _ ->
                performReset()
            }
            .setNegativeButton("Cancel", null)
            .setIcon(R.drawable.baseline_settings_backup_restore_24)
            .show()
    }

    private fun performReset() {
        cigaretteCount = 0
        weedCount = 0
        cigEntries.clear()
        weedEntries.clear()
        saveEntryData(this, CIG_COUNT_KEY, cigaretteCount, CIG_ENTRIES_KEY, cigEntries.toSet())
        saveEntryData(this, WEED_COUNT_KEY, weedCount, WEED_ENTRIES_KEY, weedEntries.toSet())
        updateCounterUI()
        updateEntriesUI()
    }

    private fun updateCounterUI() {
        tvCigCount.text = "Cigarettes: $cigaretteCount"
        tvWeedCount.text = "Weed: $weedCount"
    }

    private fun updateEntriesUI() {
        tvCigEntries.text = if (cigEntries.isNotEmpty()) "Cigarettes:\n" + cigEntries.joinToString("\n") else ""
        tvWeedEntries.text = if (weedEntries.isNotEmpty()) "Weed:\n" + weedEntries.joinToString("\n") else ""
    }

    private fun restartTimer() {
        if (!isBound || timerService == null) return
        val intent = Intent(this, TimerService::class.java).apply {
            action = TimerService.ACTION_RESTART
        }
        ContextCompat.startForegroundService(this, intent)
        updateUI()
        updateEndTimeDisplay()
    }

    private fun updateUI() {
        if (!isBound || timerService == null) return
        val isOvertime = timerService!!.isOvertime()
        val time = if (isOvertime) timerService!!.getOvertime() else timerService!!.getTimeRemaining()
        updateTimerDisplay(time, isOvertime)
        updateButtonStates()
    }

    private fun updateTimerDisplay(timeMs: Long, isOvertime: Boolean) {
        val totalSeconds = timeMs / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        val prefix = if (isOvertime) "-" else ""
        timerText.text = String.format(Locale.getDefault(), "%s%02d:%02d:%02d", prefix, hours, minutes, seconds)

        // MODIFIED: This 'if' block has been removed to keep the text visible.
        // if (isOvertime) {
        //     tvEndTime.text = ""
        // }
    }

    private fun updateEndTimeDisplay() {
        val now = System.currentTimeMillis()
        val endTimeMillis = now + TimerService.INITIAL_TIME_MS
        val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
        val endTimeString = sdf.format(endTimeMillis)
        tvEndTime.text = "Ends at $endTimeString"
    }

    private fun updateButtonStates() {
        if (!isBound || timerService == null) return
    }

    private fun formatTimestamp(millis: Long): String {
        val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
        return sdf.format(millis)
    }

    private fun loadAllData() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        cigaretteCount = prefs.getInt(CIG_COUNT_KEY, 0)
        weedCount = prefs.getInt(WEED_COUNT_KEY, 0)
        cigEntries = prefs.getStringSet(CIG_ENTRIES_KEY, emptySet())?.toMutableList() ?: mutableListOf()
        weedEntries = prefs.getStringSet(WEED_ENTRIES_KEY, emptySet())?.toMutableList() ?: mutableListOf()
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
const val WEED_COUNT_KEY = "weed_smoked_count"
const val LAST_APP_OPEN_KEY = "last_app_open_time"
const val CIG_ENTRIES_KEY = "cig_entries_list"
const val WEED_ENTRIES_KEY = "weed_entries_list"

fun loadLastResetTime(context: Context, key: String): Long {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    return prefs.getLong(key, 0L)
}

fun saveLastResetTime(context: Context, key: String, currentTime: Long) {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    prefs.edit().putLong(key, currentTime).apply()
}

fun saveEntryData(context: Context, countKey: String, count: Int, entriesKey: String, entries: Set<String>) {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    prefs.edit()
        .putInt(countKey, count)
        .putStringSet(entriesKey, entries)
        .apply()
}
