package com.tclstream.tv

import android.os.Build
import android.view.InputDevice

/**
 * Battery of the controllers connected to this TV. Android reports it from Android 12 on (the
 * TCL); older ones (the Fire TV Stick Lite on Android 9) don't, and then nothing is shown.
 */
object PadBattery {
    /** The lowest battery among the connected controllers, 0..100, or null when none reports one. */
    fun lowest(): Int? {
        if (Build.VERSION.SDK_INT < 31) return null
        var low: Int? = null
        for (id in InputDevice.getDeviceIds()) {
            val d = InputDevice.getDevice(id) ?: continue
            if (d.isVirtual || (d.sources and InputDevice.SOURCE_GAMEPAD) != InputDevice.SOURCE_GAMEPAD) continue
            val b = try { d.batteryState } catch (e: Exception) { null } ?: continue
            if (!b.isPresent) continue
            val capacity = b.capacity
            if (capacity.isNaN() || capacity < 0f) continue
            val pct = (capacity * 100).toInt().coerceIn(0, 100)
            if (low == null || pct < low) low = pct
        }
        return low
    }

    /** Charging right now (any controller). */
    fun charging(): Boolean {
        if (Build.VERSION.SDK_INT < 31) return false
        return InputDevice.getDeviceIds().any { id ->
            val d = InputDevice.getDevice(id) ?: return@any false
            (d.sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD &&
                (try { d.batteryState.status } catch (e: Exception) { 0 }) == android.hardware.BatteryState.STATUS_CHARGING
        }
    }
}
