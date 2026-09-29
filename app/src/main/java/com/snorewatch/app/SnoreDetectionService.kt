package com.snorewatch.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.tensorflow.lite.task.audio.classifier.AudioClassifier
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground service that continuously classifies microphone audio with the
 * on-device YAMNet model and sounds an alarm as soon as snoring is detected.
 */
class SnoreDetectionService : Service() {

    companion object {
        const val ACTION_START = "com.snorewatch.app.action.START"
        const val ACTION_STOP = "com.snorewatch.app.action.STOP"
        const val ACTION_STOP_ALARM = "com.snorewatch.app.action.STOP_ALARM"

        const val EXTRA_THRESHOLD = "extra_threshold"
        const val EXTRA_SNORES_TO_TRIGGER = "extra_snores_to_trigger"

        const val BROADCAST_STATUS = "com.snorewatch.app.STATUS"
        const val EXTRA_STATE = "state"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_PROBABILITY = "probability"
        const val EXTRA_SNORE_COUNT = "snore_count"
        const val EXTRA_EVENT_TIME = "event_time"

        const val STATE_MONITORING = "monitoring"
        const val STATE_ALARM = "alarm"
        const val STATE_STOPPED = "stopped"

        private const val CHANNEL_ID_MONITOR = "snore_monitor"
        private const val CHANNEL_ID_ALARM = "snore_alarm"
        private const val NOTIFICATION_ID_MONITOR = 1
        private const val NOTIFICATION_ID_ALARM = 2

        private const val MODEL_FILE = "yamnet.tflite"
        private const val SNORE_LABEL = "Snoring"
        private const val INFERENCE_INTERVAL_MS = 400L
        private const val ALARM_COOLDOWN_MS = 15_000L
        private const val WAKELOCK_TAG = "snorewatch:monitor"
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val running = AtomicBoolean(false)

    private var classifier: AudioClassifier? = null
    private var mediaPlayer: MediaPlayer? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var threshold = 0.35f
    private var snoresToTrigger = 3

    private var consecutiveSnores = 0
    private var snoreCount = 0
    private var alarmActive = false
    private var alarmStoppedAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                threshold = intent.getFloatExtra(EXTRA_THRESHOLD, threshold)
                    .coerceIn(0.05f, 0.95f)
                snoresToTrigger = intent.getIntExtra(EXTRA_SNORES_TO_TRIGGER, snoresToTrigger)
                    .coerceIn(1, 10)
                startMonitoring()
            }
            ACTION_STOP_ALARM -> stopAlarm()
            ACTION_STOP -> stopEverything()
        }
        return START_STICKY
    }


    private fun startMonitoring() {
        if (running.get()) return

        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            broadcastStatus(STATE_STOPPED, getString(R.string.error_mic_permission))
            stopSelf()
            return
        }

        createNotificationChannels()
        val notification = buildMonitorNotification(getString(R.string.notification_listening))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID_MONITOR,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID_MONITOR, notification)
        }

        acquireWakeLock()
        running.set(true)
        executor.execute { detectionLoop() }
    }

    private fun detectionLoop() {
        try {
            val options = AudioClassifier.AudioClassifierOptions.builder()
                .setMaxResults(5)
                .build()
            val clf = AudioClassifier.createFromFileAndOptions(this, MODEL_FILE, options)
            classifier = clf

            val audioRecord = clf.createAudioRecord()
            audioRecord.startRecording()
            val tensorAudio = clf.createInputTensorAudio()

            broadcastStatus(STATE_MONITORING, getString(R.string.status_monitoring))

            while (running.get()) {
                val cycleStart = System.currentTimeMillis()
                tensorAudio.load(audioRecord)
                val results = clf.classify(tensorAudio)
                val snoreProbability = results
                    .flatMap { it.categories }
                    .firstOrNull { it.label == SNORE_LABEL }
                    ?.score ?: 0f
                onSnoreProbability(snoreProbability)

                val elapsed = System.currentTimeMillis() - cycleStart
                val sleepMs = INFERENCE_INTERVAL_MS - elapsed
                if (sleepMs > 0) Thread.sleep(sleepMs)
            }

            audioRecord.stop()
        } catch (t: Throwable) {
            if (running.get()) {
                broadcastStatus(
                    STATE_STOPPED,
                    getString(R.string.error_detection, t.message ?: "unknown")
                )
            }
        }
    }

    private fun onSnoreProbability(probability: Float) {
        if (probability >= threshold) {
            val inCooldown = System.currentTimeMillis() - alarmStoppedAt < ALARM_COOLDOWN_MS
            if (!alarmActive && !inCooldown) {
                consecutiveSnores++
                if (consecutiveSnores >= snoresToTrigger) {
                    snoreCount++
                    consecutiveSnores = 0
                    triggerAlarm()
                }
            }
        } else {
            consecutiveSnores = 0
        }
        sendProbabilityUpdate(probability)
    }


    private fun sendProbabilityUpdate(probability: Float) {
        val intent = Intent(BROADCAST_STATUS).apply {
            setPackage(packageName)
            putExtra(EXTRA_STATE, if (alarmActive) STATE_ALARM else STATE_MONITORING)
            putExtra(EXTRA_PROBABILITY, probability)
            putExtra(EXTRA_SNORE_COUNT, snoreCount)
        }
        sendBroadcast(intent)
    }

    private fun broadcastStatus(state: String, message: String?) {
        val intent = Intent(BROADCAST_STATUS).apply {
            setPackage(packageName)
            putExtra(EXTRA_STATE, state)
            putExtra(EXTRA_MESSAGE, message)
            putExtra(EXTRA_SNORE_COUNT, snoreCount)
        }
        sendBroadcast(intent)
    }

    private fun triggerAlarm() {
        alarmActive = true
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val intent = Intent(BROADCAST_STATUS).apply {
            setPackage(packageName)
            putExtra(EXTRA_STATE, STATE_ALARM)
            putExtra(EXTRA_MESSAGE, getString(R.string.status_alarm))
            putExtra(EXTRA_EVENT_TIME, time)
            putExtra(EXTRA_SNORE_COUNT, snoreCount)
        }
        sendBroadcast(intent)

        startAlarmSound()
        startVibration()
        showAlarmNotification()
    }

    private fun stopAlarm() {
        alarmActive = false
        alarmStoppedAt = System.currentTimeMillis()
        consecutiveSnores = 0
        stopAlarmSound()
        stopVibration()
        notificationManager().cancel(NOTIFICATION_ID_ALARM)
        if (running.get()) {
            notificationManager().notify(
                NOTIFICATION_ID_MONITOR,
                buildMonitorNotification(getString(R.string.notification_listening))
            )
        }
        sendProbabilityUpdate(0f)
    }

    private fun stopEverything() {
        running.set(false)
        stopAlarm()
        classifier?.close()
        classifier = null
        releaseWakeLock()
        broadcastStatus(STATE_STOPPED, getString(R.string.status_stopped))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        running.set(false)
        stopAlarmSound()
        stopVibration()
        classifier?.close()
        classifier = null
        releaseWakeLock()
        executor.shutdownNow()
        super.onDestroy()
    }

    // ---- Alarm sound & vibration ----

    private fun startAlarmSound() {
        stopAlarmSound()
        val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            ?: return
        try {
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(this@SnoreDetectionService, alarmUri)
                isLooping = true
                prepare()
                start()
            }
        } catch (t: Throwable) {
            mediaPlayer = null
        }
    }

    private fun stopAlarmSound() {
        mediaPlayer?.let {
            try {
                if (it.isPlaying) it.stop()
            } catch (_: IllegalStateException) {
            }
            it.release()
        }
        mediaPlayer = null
    }

    private fun startVibration() {
        val pattern = longArrayOf(0, 600, 300)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator.vibrate(VibrationEffect.createWaveform(pattern, 0))
            } else {
                @Suppress("DEPRECATION")
                (getSystemService(VIBRATOR_SERVICE) as Vibrator)
                    .vibrate(VibrationEffect.createWaveform(pattern, 0))
            }
        } catch (_: Throwable) {
        }
    }

    private fun stopVibration() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator.cancel()
            } else {
                @Suppress("DEPRECATION")
                (getSystemService(VIBRATOR_SERVICE) as Vibrator).cancel()
            }
        } catch (_: Throwable) {
        }
    }

    // ---- Wake lock ----

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG).apply {
            acquire(10 * 60 * 60 * 1000L) // up to 10 hours, released on stop
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
    }

    // ---- Notifications ----

    private fun notificationManager(): NotificationManager =
        getSystemService(NotificationManager::class.java)

    private fun createNotificationChannels() {
        val nm = notificationManager()
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID_MONITOR,
                getString(R.string.channel_monitor_name),
                NotificationManager.IMPORTANCE_LOW
            )
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID_ALARM,
                getString(R.string.channel_alarm_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply { setBypassDnd(true) }
        )
    }

    private fun buildMonitorNotification(text: String): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 2,
            Intent(this, SnoreDetectionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID_MONITOR)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .addAction(0, getString(R.string.action_stop), stopIntent)
            .build()
    }

    private fun showAlarmNotification() {
        val fullScreenIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, SnoreDetectionService::class.java).setAction(ACTION_STOP_ALARM),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID_ALARM)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.alarm_title))
            .setContentText(getString(R.string.alarm_text))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .setFullScreenIntent(fullScreenIntent, true)
            .addAction(0, getString(R.string.action_stop_alarm), stopIntent)
            .build()
        notificationManager().notify(NOTIFICATION_ID_ALARM, notification)
    }
}
