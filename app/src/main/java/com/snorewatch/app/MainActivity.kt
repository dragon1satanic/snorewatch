package com.snorewatch.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.snorewatch.app.databinding.ActivityMainBinding
import java.util.ArrayDeque
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_PERMISSIONS = 1001
        private const val MAX_EVENTS = 30

        /** Slider value (0..100, higher = more sensitive) -> probability threshold. */
        private fun sliderToThreshold(value: Float): Float =
            0.55f - (value / 100f) * 0.45f // 0 -> 0.55, 100 -> 0.10
    }

    private lateinit var binding: ActivityMainBinding

    private var monitoring = false
    private var alarmActive = false
    private var pendingStart = false
    private val events = ArrayDeque<String>()

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val state = intent.getStringExtra(SnoreDetectionService.EXTRA_STATE) ?: return
            val count = intent.getIntExtra(SnoreDetectionService.EXTRA_SNORE_COUNT, 0)
            binding.snoreCountText.text = getString(R.string.label_snore_count, count)

            when (state) {
                SnoreDetectionService.STATE_MONITORING -> {
                    monitoring = true
                    alarmActive = false
                    val message = intent.getStringExtra(SnoreDetectionService.EXTRA_MESSAGE)
                    if (message != null) binding.statusText.text = message
                    val probability =
                        intent.getFloatExtra(SnoreDetectionService.EXTRA_PROBABILITY, 0f)
                    updateProbability(probability)
                    renderButtons()
                }
                SnoreDetectionService.STATE_ALARM -> {
                    monitoring = true
                    alarmActive = true
                    binding.statusText.text = getString(R.string.status_alarm)
                    intent.getStringExtra(SnoreDetectionService.EXTRA_EVENT_TIME)?.let {
                        addEvent(it)
                    }
                    renderButtons()
                }
                SnoreDetectionService.STATE_STOPPED -> {
                    monitoring = false
                    alarmActive = false
                    binding.statusText.text =
                        intent.getStringExtra(SnoreDetectionService.EXTRA_MESSAGE)
                            ?: getString(R.string.status_stopped)
                    updateProbability(0f)
                    renderButtons()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        updateSensitivityLabel()
        updateSnoresToTriggerLabel()
        renderButtons()

        binding.sensitivitySlider.addOnChangeListener { _, _, _ -> updateSensitivityLabel() }
        binding.snoresToTriggerSlider.addOnChangeListener { _, _, _ -> updateSnoresToTriggerLabel() }

        binding.toggleButton.setOnClickListener {
            if (monitoring) stopMonitoring() else startMonitoringWithPermissions()
        }
        binding.stopAlarmButton.setOnClickListener {
            startService(
                Intent(this, SnoreDetectionService::class.java)
                    .setAction(SnoreDetectionService.ACTION_STOP_ALARM)
            )
            binding.stopAlarmButton.visibility = View.GONE
        }
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            this,
            statusReceiver,
            IntentFilter(SnoreDetectionService.BROADCAST_STATUS),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStop() {
        super.onStop()
        unregisterReceiver(statusReceiver)
    }

    private fun startMonitoringWithPermissions() {
        val missing = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            startMonitoring()
        } else {
            pendingStart = true
            binding.statusText.text = getString(R.string.status_waiting_permissions)
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQUEST_PERMISSIONS)
        }
    }

    private fun requiredPermissions(): List<String> = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_PERMISSIONS) return

        val micGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (micGranted && pendingStart) {
            startMonitoring()
        } else if (!micGranted) {
            Toast.makeText(this, R.string.error_mic_permission, Toast.LENGTH_LONG).show()
            binding.statusText.text = getString(R.string.status_idle)
        }
        pendingStart = false
    }

    private fun startMonitoring() {
        val threshold = sliderToThreshold(binding.sensitivitySlider.value)
        val snoresToTrigger = binding.snoresToTriggerSlider.value.roundToInt()
        val intent = Intent(this, SnoreDetectionService::class.java).apply {
            action = SnoreDetectionService.ACTION_START
            putExtra(SnoreDetectionService.EXTRA_THRESHOLD, threshold)
            putExtra(SnoreDetectionService.EXTRA_SNORES_TO_TRIGGER, snoresToTrigger)
        }
        ContextCompat.startForegroundService(this, intent)
        monitoring = true
        binding.statusText.text = getString(R.string.status_monitoring)
        renderButtons()
    }

    private fun stopMonitoring() {
        startService(
            Intent(this, SnoreDetectionService::class.java)
                .setAction(SnoreDetectionService.ACTION_STOP)
        )
        monitoring = false
        binding.statusText.text = getString(R.string.status_stopped)
        updateProbability(0f)
        renderButtons()
    }

    private fun updateProbability(probability: Float) {
        val percent = (probability.coerceIn(0f, 1f) * 100f).roundToInt()
        binding.probabilityBar.progress = percent
        binding.probabilityText.text = getString(R.string.label_live_probability, percent)
    }

    private fun renderButtons() {
        binding.toggleButton.text = getString(
            if (monitoring) R.string.button_stop else R.string.button_start
        )
        binding.sensitivitySlider.isEnabled = !monitoring
        binding.snoresToTriggerSlider.isEnabled = !monitoring
        binding.stopAlarmButton.visibility =
            if (alarmActive && monitoring) View.VISIBLE else View.GONE
    }

    private fun updateSensitivityLabel() {
        val value = binding.sensitivitySlider.value
        val level = when {
            value >= 66f -> getString(R.string.sensitivity_high)
            value >= 33f -> getString(R.string.sensitivity_medium)
            else -> getString(R.string.sensitivity_low)
        }
        binding.sensitivityLabel.text = getString(R.string.label_sensitivity, level)
    }

    private fun updateSnoresToTriggerLabel() {
        val count = binding.snoresToTriggerSlider.value.roundToInt()
        binding.snoresToTriggerLabel.text = getString(R.string.label_snores_to_trigger, count)
    }

    private fun addEvent(time: String) {
        events.addFirst(getString(R.string.status_alarm) + " — " + time)
        while (events.size > MAX_EVENTS) events.removeLast()
        binding.eventsText.text = events.joinToString("\n")
    }
}
