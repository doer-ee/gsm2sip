package com.callagent.gateway.service

import android.content.Context
import android.os.Build
import android.util.Log
import com.callagent.gateway.RootShell
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** Pixel 4a charge hysteresis. Runs only on the gateway's background executor. */
class BatteryChargeGuard(private val context: Context) {
    private val worker: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "BatteryChargeGuard").apply { isDaemon = true }
    }
    private val prefs = context.getSharedPreferences("gateway", Context.MODE_PRIVATE)

    fun start() {
        worker.scheduleWithFixedDelay(::check, 0, 30, TimeUnit.SECONDS)
    }

    fun refresh() {
        worker.execute(::check)
    }

    fun stop() {
        worker.shutdownNow()
        val disablePath = controlPath()
        if (disablePath.isNotEmpty() && prefs.getBoolean(KEY_OWNED, false)) {
            if (setDisabled(false, disablePath)) {
                prefs.edit().putBoolean(KEY_OWNED, false).commit()
            }
        }
    }

    private fun check() {
        // This sysfs path is specific to the tested Pixel 4a.
        if (Build.DEVICE != "sunfish") return

        val enabled = prefs.getBoolean(KEY_ENABLED, false)
        val owned = prefs.getBoolean(KEY_OWNED, false)
        if (!enabled && !owned) return

        val disablePath = controlPath()
        if (disablePath.isEmpty()) {
            Log.w(TAG, "Charging switch unavailable; no supported charge_disable node")
            return
        }
        val current = RootShell.execForOutput("cat $disablePath 2>/dev/null").trim().toIntOrNull()
        if (current !in 0..1) {
            Log.w(TAG, "Charging switch unavailable; leaving it unchanged")
            return
        }

        if (!enabled) {
            // Release a gate that *this* feature set. Do not interfere with
            // another charge manager when this feature has never controlled it.
            if (current == 0 || setDisabled(false, disablePath)) {
                // An explicit disable means the remembered hysteresis state
                // must also be cleared. A service restart, in contrast, must
                // not clear it: if we paused at the upper limit and restart
                // at 52%, the charger should remain paused until the lower
                // limit is reached.
                prefs.edit()
                    .putBoolean(KEY_OWNED, false)
                    .putBoolean(KEY_LAST_DISABLED, false)
                    .commit()
            }
            return
        }

        val start = prefs.getInt(KEY_START, 35)
        val stop = prefs.getInt(KEY_STOP, 65)
        if (start !in 5..90 || stop !in 10..100 || stop - start < 5) {
            Log.e(TAG, "Invalid battery thresholds: $start–$stop")
            return
        }
        val capacity = RootShell.execForOutput("cat $CAPACITY 2>/dev/null")
            .trim().toIntOrNull()
        if (capacity == null || capacity !in 0..100) {
            Log.w(TAG, "Battery capacity unavailable; leaving charger unchanged")
            return
        }

        val shouldDisable = when {
            capacity >= stop -> true
            capacity <= start -> false
            else -> {
                // Migrate the old ownership flag on the first run after this
                // update. Older builds remembered that they had paused the
                // charger, but did not preserve the actual paused state
                // across a service restart.
                if (prefs.contains(KEY_LAST_DISABLED)) {
                    prefs.getBoolean(KEY_LAST_DISABLED, current == 1)
                } else {
                    prefs.getBoolean(KEY_OWNED, current == 1)
                }
            }
        }
        if (current == if (shouldDisable) 1 else 0) return
        if (setDisabled(shouldDisable, disablePath)) {
            prefs.edit()
                .putBoolean(KEY_LAST_DISABLED, shouldDisable)
                .putBoolean(KEY_OWNED, shouldDisable)
                .commit()
            Log.i(TAG, "Charge ${if (shouldDisable) "paused" else "resumed"} at $capacity% ($start–$stop%)")
        }
    }

    private fun setDisabled(disabled: Boolean, disablePath: String): Boolean {
        val value = if (disabled) 1 else 0
        val ok = RootShell.exec("echo $value > $disablePath", timeoutMs = 8000) == 0 &&
            RootShell.execForOutput("cat $disablePath 2>/dev/null").trim() == value.toString()
        if (!ok) Log.e(TAG, "Could not set charge_disable=$value at $disablePath")
        return ok
    }

    /**
     * Pixel 4a exposes the effective charger gate through the Google battery
     * driver. Older/alternate Qualcomm kernels may expose only the SMB5 node,
     * so prefer the Pixel node and retain the legacy fallback.
     */
    private fun controlPath(): String {
        val result = RootShell.execForOutput(
            "if [ -e $PRIMARY_DISABLE ]; then echo $PRIMARY_DISABLE; " +
                "elif [ -e $LEGACY_DISABLE ]; then echo $LEGACY_DISABLE; fi"
        ).trim()
        return result.takeIf { it == PRIMARY_DISABLE || it == LEGACY_DISABLE }.orEmpty()
    }

    companion object {
        private const val TAG = "BatteryChargeGuard"
        const val PRIMARY_DISABLE = "/sys/class/power_supply/sm7150_bms/charge_disable"
        const val LEGACY_DISABLE = "/sys/class/power_supply/smb5/charge_disable"
        private const val CAPACITY = "/sys/class/power_supply/battery/capacity"
        const val KEY_ENABLED = "battery_protection"
        const val KEY_START = "battery_start_threshold"
        const val KEY_STOP = "battery_stop_threshold"
        private const val KEY_LAST_DISABLED = "battery_last_disabled"
        private const val KEY_OWNED = "battery_control_owned"
    }
}
