package com.visionnav.platform.power

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.visionnav.platform.common.RefCounter
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Performance budget for on-device inference.
 * @param inferenceThrottleFactor multiply the nominal inference rate by this (1.0 = full rate).
 */
enum class PerformanceMode(val inferenceThrottleFactor: Float) {
    NORMAL(1.0f),
    REDUCED(0.6f),
    MINIMAL(0.3f),
}

/** Raw power inputs; null = unknown. */
data class PowerSnapshot(
    /** `PowerManager.THERMAL_STATUS_*` (API 29+), null below API 29 or before the first report. */
    val thermalStatus: Int?,
    val batteryPercent: Int?,
    val charging: Boolean,
    val powerSaveMode: Boolean,
)

/** Pure policy mapping a [PowerSnapshot] to a [PerformanceMode] (JVM-testable). */
object PowerPolicy {
    // PowerManager.THERMAL_STATUS_* values, duplicated to keep this object JVM-pure.
    const val THERMAL_NONE = 0
    const val THERMAL_LIGHT = 1
    const val THERMAL_MODERATE = 2
    const val THERMAL_SEVERE = 3

    const val CRITICAL_BATTERY_PERCENT = 10
    const val LOW_BATTERY_PERCENT = 20

    /**
     * - Thermal ≥ SEVERE, or battery ≤ 10 % and not charging → MINIMAL.
     * - Thermal MODERATE, battery ≤ 20 % and not charging, or battery-saver on → REDUCED.
     * - Otherwise NORMAL. The most restrictive applicable mode wins.
     */
    fun decide(s: PowerSnapshot): PerformanceMode {
        val thermal = s.thermalStatus ?: THERMAL_NONE
        val battery = s.batteryPercent
        return when {
            thermal >= THERMAL_SEVERE -> PerformanceMode.MINIMAL
            battery != null && !s.charging && battery <= CRITICAL_BATTERY_PERCENT -> PerformanceMode.MINIMAL
            thermal >= THERMAL_MODERATE -> PerformanceMode.REDUCED
            battery != null && !s.charging && battery <= LOW_BATTERY_PERCENT -> PerformanceMode.REDUCED
            s.powerSaveMode -> PerformanceMode.REDUCED
            else -> PerformanceMode.NORMAL
        }
    }
}

/**
 * Observes thermal status (API 29+ listener), battery level/charging and battery-saver, and
 * publishes a [PerformanceMode] for the perception pipeline (PLAN Phase 8, §6). Consumers should
 * announce degraded mode when [mode] leaves NORMAL.
 *
 * Ref-counted [start]/[stop]; while stopped [mode] keeps its last value.
 */
@Singleton
class PowerGovernor @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager

    private val _snapshot = MutableStateFlow(
        PowerSnapshot(thermalStatus = null, batteryPercent = null, charging = false, powerSaveMode = false),
    )

    /** Latest raw inputs (for diagnostics / debug overlay). */
    val snapshot: StateFlow<PowerSnapshot> = _snapshot.asStateFlow()

    private val _mode = MutableStateFlow(PerformanceMode.NORMAL)

    /** Current performance mode. */
    val mode: StateFlow<PerformanceMode> = _mode.asStateFlow()

    /** Convenience: current [PerformanceMode.inferenceThrottleFactor]. */
    val inferenceThrottleFactor: Float get() = _mode.value.inferenceThrottleFactor

    private var thermalListener: Any? = null // PowerManager.OnThermalStatusChangedListener (API 29+)

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_BATTERY_CHANGED -> updateBattery(intent)
                PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> update {
                    it.copy(powerSaveMode = powerManager?.isPowerSaveMode == true)
                }
            }
        }
    }

    private val refCounter = RefCounter(onFirstAcquire = ::open, onLastRelease = ::close)

    fun start() = refCounter.acquire()

    fun stop() {
        refCounter.release()
    }

    private fun open() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        }
        // ACTION_BATTERY_CHANGED is sticky: the registration returns the current value.
        val sticky = ContextCompat.registerReceiver(
            context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        update { it.copy(powerSaveMode = powerManager?.isPowerSaveMode == true) }
        sticky?.let(::updateBattery)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) registerThermal()
    }

    private fun close() {
        runCatching { context.unregisterReceiver(receiver) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) unregisterThermal()
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun registerThermal() {
        val pm = powerManager ?: return
        val listener = PowerManager.OnThermalStatusChangedListener { status ->
            update { it.copy(thermalStatus = status) }
        }
        update { it.copy(thermalStatus = pm.currentThermalStatus) }
        pm.addThermalStatusListener(ContextCompat.getMainExecutor(context), listener)
        thermalListener = listener
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun unregisterThermal() {
        val listener = thermalListener as? PowerManager.OnThermalStatusChangedListener ?: return
        powerManager?.removeThermalStatusListener(listener)
        thermalListener = null
    }

    private fun updateBattery(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val percent = if (level >= 0 && scale > 0) (level * 100 / scale) else null
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        update { it.copy(batteryPercent = percent, charging = charging) }
    }

    private fun update(transform: (PowerSnapshot) -> PowerSnapshot) {
        val next = synchronized(_snapshot) {
            transform(_snapshot.value).also { _snapshot.value = it }
        }
        _mode.value = PowerPolicy.decide(next)
    }
}
