Kotlin


package com.custom.headphonealarm

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.media.*
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.view.KeyEvent
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.media.VolumeProviderCompat
import java.util.Calendar

class MainActivity : AppCompatActivity() {
    private var selectedUri: Uri? = null
    private var hour = 7
    private var minute = 0

    private val pickAudio = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            selectedUri = it
            getSharedPreferences("alarm_prefs", MODE_PRIVATE).edit().putString("uri", it.toString()).apply()
            Toast.makeText(this, "Music selected!", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        val prefs = getSharedPreferences("alarm_prefs", MODE_PRIVATE)
        prefs.getString("uri", null)?.let { selectedUri = Uri.parse(it) }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }

        val statusText = TextView(this).apply {
            text = "Alarm set for 07:00"
            textSize = 20f
            setPadding(0, 0, 0, 32)
        }

        val timeBtn = Button(this).apply {
            text = "1. Pick Alarm Time"
            setOnClickListener {
                TimePickerDialog(this@MainActivity, { _, h, m ->
                    hour = h
                    minute = m
                    statusText.text = String.format("Alarm set for %02d:%02d", hour, minute)
                }, hour, minute, true).show()
            }
        }

        val musicBtn = Button(this).apply {
            text = "2. Pick Alarm MP3"
            setOnClickListener { pickAudio.launch(arrayOf("audio/*")) }
        }

        val setBtn = Button(this).apply {
            text = "3. Turn Alarm ON"
            setOnClickListener {
                if (selectedUri == null) {
                    Toast.makeText(this@MainActivity, "Pick an MP3 file first!", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val am = getSystemService(ALARM_SERVICE) as AlarmManager
                if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                    startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM))
                    return@setOnClickListener
                }
                scheduleAlarm(hour, minute)
                statusText.text = String.format("ACTIVE: %02d:%02d (Plug in earphones)", hour, minute)
                Toast.makeText(this@MainActivity, "Alarm Scheduled!", Toast.LENGTH_SHORT).show()
            }
        }

        val stopBtn = Button(this).apply {
            text = "Stop / Cancel Alarm"
            setOnClickListener {
                stopService(Intent(this@MainActivity, AlarmService::class.java))
                val am = getSystemService(ALARM_SERVICE) as AlarmManager
                val pi = PendingIntent.getBroadcast(this@MainActivity, 0, Intent(this@MainActivity, AlarmReceiver::class.java), PendingIntent.FLAG_IMMUTABLE)
                am.cancel(pi)
                statusText.text = "Alarm OFF"
            }
        }

        layout.addView(statusText)
        layout.addView(timeBtn)
        layout.addView(musicBtn)
        layout.addView(setBtn)
        layout.addView(stopBtn)
        setContentView(layout)
    }

    private fun scheduleAlarm(h: Int, m: Int) {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, h)
            set(Calendar.MINUTE, m)
            set(Calendar.SECOND, 0)
            if (before(Calendar.getInstance())) add(Calendar.DAY_OF_YEAR, 1)
        }
        val am = getSystemService(ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getBroadcast(this, 0, Intent(this, AlarmReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val serviceIntent = Intent(context, AlarmService::class.java)
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(serviceIntent)
        else context.startService(serviceIntent)
    }
}

class AlarmService : Service() {
    private var player: MediaPlayer? = null
    private var mediaSession: MediaSessionCompat? = null
    private val handler = Handler(Looper.getMainLooper())
    private var currentVol = 0.05f

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "SNOOZE") {
            triggerSnooze()
            return START_NOT_STICKY
        }

        startForegroundNotification()
        setupVolumeButtonSnooze()
        startGradualAlarm()
        return START_STICKY
    }

    private fun startForegroundNotification() {
        val channelId = "headphone_alarm_channel"
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(channelId, "Headphone Alarm", NotificationManager.IMPORTANCE_HIGH))
        }
        val snoozeIntent = PendingIntent.getService(this, 1, Intent(this, AlarmService::class.java).apply { action = "SNOOZE" }, PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Headphone Alarm Ringing")
            .setContentText("Press Volume button to Snooze (9 min) & resume Hemi-Sync")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .addAction(android.R.drawable.ic_media_pause, "Snooze 9m", snoozeIntent)
            .build()
        startForeground(101, notification)
    }

    private fun startGradualAlarm() {
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        if (Build.VERSION.SDK_INT >= 26) {
            am.requestAudioFocus(AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).build())
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        }

        val uriStr = getSharedPreferences("alarm_prefs", MODE_PRIVATE).getString("uri", null) ?: return
        player = MediaPlayer().apply {
            setDataSource(applicationContext, Uri.parse(uriStr))
            setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            isLooping = true
            setVolume(currentVol, currentVol)
            prepare()
            start()
        }

        val fadeRunnable = object : Runnable {
            override fun run() {
                if (player != null && currentVol < 1.0f) {
                    currentVol = (currentVol + 0.05f).coerceAtMost(1.0f)
                    player?.setVolume(currentVol, currentVol)
                    handler.postDelayed(this, 2500)
                }
            }
        }
        handler.postDelayed(fadeRunnable, 2500)
    }

    private fun setupVolumeButtonSnooze() {
        mediaSession = MediaSessionCompat(this, "HeadphoneAlarmSession").apply {
            setPlaybackState(PlaybackStateCompat.Builder().setState(PlaybackStateCompat.STATE_PLAYING, 0, 1f).build())
            setPlaybackToRemote(object : VolumeProviderCompat(VOLUME_CONTROL_RELATIVE, 100, 50) {
                override fun onAdjustVolume(direction: Int) {
                    if (direction != 0) triggerSnooze()
                }
            })
            isActive = true
        }
    }

    private fun triggerSnooze() {
        handler.removeCallbacksAndMessages(null)
        player?.stop()
        player?.release()
        player = null
        mediaSession?.release()

        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY))

        val snoozeTime = System.currentTimeMillis() + 9 * 60 * 1000
        val alarmMgr = getSystemService(ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getBroadcast(this, 0, Intent(this, AlarmReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        alarmMgr.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, snoozeTime, pi)

        Toast.makeText(this, "Snoozed 9 mins — Resuming Hemi-Sync", Toast.LENGTH_LONG).show()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        player?.release()
        mediaSession?.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null
}
