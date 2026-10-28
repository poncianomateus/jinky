package com.tclstream.tv

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.input.InputManager
import android.os.Build
import android.os.CombinedVibration
import android.os.Handler
import android.os.HandlerThread
import android.os.VibrationEffect
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Turns the TV's Bluetooth/USB gamepads into Xbox-style states for the PC, and plays the PC's
 * vibration requests on them. The TV remote is not a gamepad and is left alone.
 *
 * Each pad's full state is sent on every change and repeated every 50 ms, so a lost datagram
 * never leaves a button "stuck" on the PC.
 */
class GamepadInput(
    private val context: Context,
    private val receiver: NativeReceiver,
    /** Called on the input thread when a pad holds View + Menu for a second. */
    private val onMenuCombo: () -> Unit,
    /** A controller connected (true) or went away (false): its player number (1..4) and name. */
    private val onPlayer: (player: Int, name: String, joined: Boolean) -> Unit = { _, _, _ -> },
) : InputManager.InputDeviceListener {

    /**
     * While the TV menu is open, gamepad events drive the menu (Android focus navigation) and the PC
     * sees all pads released, so the game doesn't receive stray input.
     */
    @Volatile var menuOpen = false
        set(value) {
            field = value
            if (value) synchronized(pads) { pads.values.forEach { it.release(); send(it) } }
        }

    /**
     * "Controle PlayStation no PC": PlayStation pads (Sony) are plugged on the PC as a DualShock 4
     * and get their touchpad and motion there too.
     */
    @Volatile var playStationMode = false
        set(value) {
            if (field == value) return
            field = value
            handler.post { synchronized(pads) { pads.values.forEach { updateSensors(it); sendExtras(it, force = true) } } }
        }

    private class Pad(val index: Int, val deviceId: Int, val name: String, val ps: Boolean) {
        // PlayStation extras (DualShock 4 units): touch points, touchpad click, motion.
        val touchId = IntArray(2)
        val touchDown = BooleanArray(2)
        val touchX = IntArray(2)
        val touchY = IntArray(2)
        var touchClick = false
        val gyro = IntArray(3)
        val accel = IntArray(3)
        var extrasSeq = 0
        var extrasSentAt = 0L
        var sensorListener: SensorEventListener? = null
        var buttons = 0
        var lt = 0
        var rt = 0
        var lx = 0
        var ly = 0
        var rx = 0
        var ry = 0
        var dpadFromHat = 0
        var seq = 0
        var rumbleActive = false
        var comboSinceMs = 0L
        // View/Menu are held back briefly so the View+Menu menu shortcut never reaches the PC.
        var viewDownAt = 0L
        var menuDownAt = 0L
        var comboActive = false

        fun release() {
            buttons = 0; lt = 0; rt = 0; lx = 0; ly = 0; rx = 0; ry = 0; dpadFromHat = 0
        }
    }

    private val pads = HashMap<Int, Pad>()  // by InputDevice id
    private val inputManager = context.getSystemService(Context.INPUT_SERVICE) as InputManager
    private val thread = HandlerThread("tcl-input").apply { start() }
    private val handler = Handler(thread.looper)
    @Volatile var summary = "nenhum controle"; private set

    // How long Android held each gamepad event before handing it to us (diagnostics).
    private var lagCount = 0
    private var lagSumMs = 0L
    private var lagMaxMs = 0L
    private var lagBatched = 0
    private var lagSince = 0L
    @Volatile var lagSummary = ""; private set

    private fun noteLag(eventTimeMs: Long, batched: Boolean) {
        val now = android.os.SystemClock.uptimeMillis()
        val lag = now - eventTimeMs
        lagCount++; lagSumMs += lag; if (lag > lagMaxMs) lagMaxMs = lag
        if (batched) lagBatched++
        if (lagSince == 0L) lagSince = now
        if (now - lagSince >= 5000 && lagCount > 0) {
            lagSummary = "entrada: Android segura %.1f ms em média, máx %d ms (%d eventos, %d em lote)"
                .format(lagSumMs.toDouble() / lagCount, lagMaxMs, lagCount, lagBatched)
            Log.i(TAG, lagSummary)
            lagCount = 0; lagSumMs = 0; lagMaxMs = 0; lagBatched = 0; lagSince = now
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            val now = android.os.SystemClock.uptimeMillis()
            synchronized(pads) {
                pads.values.forEach {
                    send(it)
                    if (it.ps && playStationMode) sendExtras(it, force = true)  // repeated like the buttons
                    // View + Menu held for a second opens the TV menu.
                    if (!menuOpen && (it.buttons and COMBO) == COMBO) {
                        if (it.comboSinceMs == 0L) it.comboSinceMs = now
                        else if (now - it.comboSinceMs >= COMBO_HOLD_MS) {
                            it.comboSinceMs = 0L
                            onMenuCombo()
                        }
                    } else {
                        it.comboSinceMs = 0L
                    }
                }
            }
            handler.postDelayed(this, REPEAT_MS)
        }
    }
    private val rumblePoll = object : Runnable {
        override fun run() {
            while (true) {
                val r = receiver.pollRumble()
                if (r < 0) break
                applyRumble(r and 0xFF, (r shr 8) and 0xFF, (r shr 16) and 0xFF)
            }
            handler.postDelayed(this, RUMBLE_POLL_MS)
        }
    }

    fun start() {
        inputManager.registerInputDeviceListener(this, handler)
        for (id in inputManager.inputDeviceIds) onInputDeviceAdded(id)
        handler.post(tick)
        handler.post(rumblePoll)
    }

    fun stop() {
        inputManager.unregisterInputDeviceListener(this)
        handler.removeCallbacksAndMessages(null)
        synchronized(pads) {
            pads.values.forEach {
                stopVibration(it)
                stopSensors(it)
                receiver.sendInput(it.index, false, ++it.seq, 0, 0, 0, 0, 0, 0, 0)
            }
            pads.clear()
        }
        thread.quitSafely()
    }

    // --- device tracking ---

    override fun onInputDeviceAdded(deviceId: Int) {
        val dev = InputDevice.getDevice(deviceId) ?: return
        if (!isGamepad(dev)) return
        synchronized(pads) {
            if (pads.containsKey(deviceId)) return
            val used = pads.values.map { it.index }.toSet()
            val index = (0 until MAX_PADS).firstOrNull { it !in used } ?: return
            pads[deviceId] = Pad(index, deviceId, dev.name, dev.vendorId == SONY_VENDOR).also { updateSensors(it) }
            updateSummary()
            onPlayer(index + 1, dev.name, true)
        }
        Log.i(TAG, "gamepad connected: ${dev.name} (vendor ${dev.vendorId} product ${dev.productId}) axes=" +
            dev.motionRanges.joinToString { MotionEvent.axisToString(it.axis) })
    }

    override fun onInputDeviceRemoved(deviceId: Int) {
        synchronized(pads) {
            val pad = pads.remove(deviceId) ?: return
            stopSensors(pad)
            receiver.sendInput(pad.index, false, ++pad.seq, 0, 0, 0, 0, 0, 0, 0)
            updateSummary()
            onPlayer(pad.index + 1, pad.name, false)
            Log.i(TAG, "gamepad disconnected: ${pad.name}")
        }
    }

    override fun onInputDeviceChanged(deviceId: Int) {}

    private fun isGamepad(dev: InputDevice): Boolean {
        val s = dev.sources
        val claimsGamepad = (s and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
            (s and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
        if (dev.isVirtual || !claimsGamepad) return false
        // Some TVs expose helper devices that claim to be gamepads (the TCL has Google's
        // "virtual-search", vendor 0x18D1, which even reports an A button). Real controllers report
        // at least one axis (sticks, triggers or a hat D-pad); requiring one keeps such devices
        // from taking player 1.
        return dev.motionRanges.isNotEmpty()
    }

    private fun updateSummary() {
        summary = if (pads.isEmpty()) "nenhum controle"
        else pads.values.sortedBy { it.index }.joinToString { "${it.index + 1}: ${it.name}" }
    }

    // --- events (main thread) ---

    /** Returns true if the event came from a gamepad and was consumed. */
    fun onKeyEvent(e: KeyEvent): Boolean {
        // In the menu: let Android handle it (A -> select, B -> back, D-pad/stick -> focus).
        if (menuOpen) return false
        val pad = synchronized(pads) { pads[e.deviceId] } ?: run {
            // A gamepad we missed (e.g. connected before start); pick it up now.
            onInputDeviceAdded(e.deviceId)
            synchronized(pads) { pads[e.deviceId] }
        } ?: return false
        val bit = buttonFor(e.keyCode)
        val down = e.action == KeyEvent.ACTION_DOWN
        synchronized(pads) {  // the 50 ms repeat runs on another thread
        if (e.repeatCount == 0) noteLag(e.eventTime, false)
        val now = android.os.SystemClock.uptimeMillis()
        if (bit and COMBO != 0 && e.repeatCount == 0) {
            if (down) {
                if (bit == BTN_VIEW) pad.viewDownAt = now else pad.menuDownAt = now
            } else if (!pad.comboActive && now - (if (bit == BTN_VIEW) pad.viewDownAt else pad.menuDownAt) < HOLDBACK_MS) {
                // A quick tap released before it was forwarded: deliver it as a short press.
                send(pad, extra = bit)
            }
        }
        when {
            bit != 0 -> pad.buttons = if (down) pad.buttons or bit else pad.buttons and bit.inv()
            // Digital-only triggers (no analog axis): full press.
            e.keyCode == KeyEvent.KEYCODE_BUTTON_L2 -> if (!hasAnalogTriggers(e.device)) pad.lt = if (down) 255 else 0
            e.keyCode == KeyEvent.KEYCODE_BUTTON_R2 -> if (!hasAnalogTriggers(e.device)) pad.rt = if (down) 255 else 0
            else -> return true  // other gamepad keys: swallow so they never act as TV navigation
        }
        if (e.repeatCount == 0) send(pad)
        }
        return true
    }

    fun onMotionEvent(e: MotionEvent): Boolean {
        if (menuOpen) return false
        if ((e.source and InputDevice.SOURCE_JOYSTICK) != InputDevice.SOURCE_JOYSTICK) return false
        val pad = synchronized(pads) { pads[e.deviceId] } ?: return false
        val dev = e.device ?: return false
        synchronized(pads) {
            noteLag(if (e.historySize > 0) e.getHistoricalEventTime(0) else e.eventTime, e.historySize > 0)
            applyMotion(pad, e, dev)
        }
        return true
    }

    private fun applyMotion(pad: Pad, e: MotionEvent, dev: InputDevice) {
        pad.lx = stick(e, dev, MotionEvent.AXIS_X)
        pad.ly = -stick(e, dev, MotionEvent.AXIS_Y)  // Android: down is +; XInput: up is +
        pad.rx = stick(e, dev, MotionEvent.AXIS_Z)
        pad.ry = -stick(e, dev, MotionEvent.AXIS_RZ)
        if (hasAnalogTriggers(dev)) {
            pad.lt = trigger(maxOf(e.getAxisValue(MotionEvent.AXIS_LTRIGGER), e.getAxisValue(MotionEvent.AXIS_BRAKE)))
            pad.rt = trigger(maxOf(e.getAxisValue(MotionEvent.AXIS_RTRIGGER), e.getAxisValue(MotionEvent.AXIS_GAS)))
        }
        // Some pads report the D-pad as a hat axis instead of keys.
        val hx = e.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hy = e.getAxisValue(MotionEvent.AXIS_HAT_Y)
        var hat = 0
        if (hx < -0.5f) hat = hat or BTN_LEFT
        if (hx > 0.5f) hat = hat or BTN_RIGHT
        if (hy < -0.5f) hat = hat or BTN_UP
        if (hy > 0.5f) hat = hat or BTN_DOWN
        pad.buttons = (pad.buttons and pad.dpadFromHat.inv()) or hat
        pad.dpadFromHat = hat
        send(pad)
    }

    private fun send(p: Pad, extra: Int = 0) {
        receiver.sendInput(p.index, true, ++p.seq, outgoingButtons(p) or extra, p.lt, p.rt, p.lx, p.ly, p.rx, p.ry)
    }

    /** Buttons as the PC should see them: View/Menu only after [HOLDBACK_MS], never as a pair. */
    private fun outgoingButtons(p: Pad): Int {
        val held = p.buttons and COMBO
        if (held == COMBO) p.comboActive = true
        if (held == 0) p.comboActive = false
        var out = p.buttons and COMBO.inv()
        if (!p.comboActive) {
            val now = android.os.SystemClock.uptimeMillis()
            if (held and BTN_VIEW != 0 && now - p.viewDownAt >= HOLDBACK_MS) out = out or BTN_VIEW
            if (held and BTN_MENU != 0 && now - p.menuDownAt >= HOLDBACK_MS) out = out or BTN_MENU
        }
        return out
    }

    private fun stick(e: MotionEvent, dev: InputDevice, axis: Int): Int {
        val range = dev.getMotionRange(axis, e.source) ?: return 0
        val v = e.getAxisValue(axis)
        if (abs(v) <= range.flat) return 0  // the pad's own reported dead zone
        return (v * 32767f).roundToInt().coerceIn(-32768, 32767)
    }

    private fun trigger(v: Float) = (v * 255f).roundToInt().coerceIn(0, 255)

    private fun hasAnalogTriggers(dev: InputDevice?): Boolean =
        dev != null && (dev.getMotionRange(MotionEvent.AXIS_LTRIGGER) != null ||
            dev.getMotionRange(MotionEvent.AXIS_BRAKE) != null)

    // --- PlayStation extras: motion (the pad's own sensors) and touchpad ---

    private fun updateSensors(p: Pad) {
        if (!p.ps || !playStationMode) { stopSensors(p); return }
        if (p.sensorListener != null || Build.VERSION.SDK_INT < 31) return
        val sm = InputDevice.getDevice(p.deviceId)?.sensorManager ?: return
        val gyro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        val accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (gyro == null && accel == null) return
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                // Android: x right, y away from the player, z up out of the pad. DualShock 4: x
                // right, y up, z toward the player. Gyro rad/s -> 16 per degree/s; accel m/s2 -> 8192 per g.
                val sx = e.values[0]
                val sy = e.values[1]
                val sz = e.values[2]
                synchronized(pads) {
                    if (e.sensor.type == Sensor.TYPE_GYROSCOPE) {
                        val k = 57.29578f * 16f
                        p.gyro[0] = (sx * k).roundToInt(); p.gyro[1] = (sz * k).roundToInt(); p.gyro[2] = (-sy * k).roundToInt()
                    } else {
                        val k = 8192f / 9.80665f
                        p.accel[0] = (sx * k).roundToInt(); p.accel[1] = (sz * k).roundToInt(); p.accel[2] = (-sy * k).roundToInt()
                    }
                    sendExtras(p)
                }
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        gyro?.let { sm.registerListener(listener, it, android.hardware.SensorManager.SENSOR_DELAY_GAME, handler) }
        accel?.let { sm.registerListener(listener, it, android.hardware.SensorManager.SENSOR_DELAY_GAME, handler) }
        p.sensorListener = listener
        Log.i(TAG, "${p.name}: motion on (gyro=${gyro != null} accel=${accel != null})")
    }

    private fun stopSensors(p: Pad) {
        val l = p.sensorListener ?: return
        if (Build.VERSION.SDK_INT >= 31) InputDevice.getDevice(p.deviceId)?.sensorManager?.unregisterListener(l)
        p.sensorListener = null
    }

    /** The PlayStation pad that owns the touchpad: the first one (the touchpad is its own device). */
    private fun touchpadPad(): Pad? = pads.values.filter { it.ps }.minByOrNull { it.index }

    /**
     * Touchpad events (captured pointer, see MainActivity): up to two fingers, scaled to the
     * DualShock 4 touchpad (1920 x 943), and the touchpad click. True if used.
     */
    fun onTouchpad(e: MotionEvent): Boolean {
        if (!playStationMode || menuOpen) return false
        synchronized(pads) {
            val p = touchpadPad() ?: return false
            val dev = e.device
            val rx = dev?.getMotionRange(MotionEvent.AXIS_X, e.source)
            val ry = dev?.getMotionRange(MotionEvent.AXIS_Y, e.source)
            val up = e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL
            for (slot in 0..1) p.touchDown[slot] = false
            if (!up) for (i in 0 until minOf(e.pointerCount, 2)) {
                if (e.actionMasked == MotionEvent.ACTION_POINTER_UP && i == e.actionIndex) continue
                val x = e.getX(i)
                val y = e.getY(i)
                p.touchDown[i] = true
                p.touchId[i] = e.getPointerId(i) and 0x7F
                p.touchX[i] = if (rx != null && rx.range > 0) (((x - rx.min) / rx.range) * 1919).roundToInt().coerceIn(0, 1919) else x.roundToInt().coerceIn(0, 1919)
                p.touchY[i] = if (ry != null && ry.range > 0) (((y - ry.min) / ry.range) * 942).roundToInt().coerceIn(0, 942) else y.roundToInt().coerceIn(0, 942)
            }
            when (e.actionMasked) {
                MotionEvent.ACTION_BUTTON_PRESS -> p.touchClick = true
                MotionEvent.ACTION_BUTTON_RELEASE -> p.touchClick = false
                else -> if (e.buttonState and MotionEvent.BUTTON_PRIMARY == 0) p.touchClick = false
            }
            sendExtras(p, force = true)
        }
        return true
    }

    private fun sendExtras(p: Pad, force: Boolean = false) {
        if (!p.ps) return
        val now = android.os.SystemClock.uptimeMillis()
        if (!force && now - p.extrasSentAt < 4) return  // motion comes fast; ~250 a second is plenty
        p.extrasSentAt = now
        val on = playStationMode
        val live = on && !menuOpen
        val flags = (if (on) 1 else 0) or (if (live && p.touchClick) 2 else 0)
        receiver.sendExtras(intArrayOf(
            p.index, flags, ++p.extrasSeq,
            p.touchId[0], if (live && p.touchDown[0]) 1 else 0, p.touchX[0], p.touchY[0],
            p.touchId[1], if (live && p.touchDown[1]) 1 else 0, p.touchX[1], p.touchY[1],
            if (live) p.gyro[0] else 0, if (live) p.gyro[1] else 0, if (live) p.gyro[2] else 0,
            if (live) p.accel[0] else 0, if (live) p.accel[1] else 8192, if (live) p.accel[2] else 0,
        ))
    }

    /** A PlayStation pad is connected (for the touchpad capture). */
    fun hasPlayStationPad(): Boolean = synchronized(pads) { pads.values.any { it.ps } }

    // --- vibration ---

    private fun applyRumble(index: Int, large: Int, small: Int) {
        val pad = synchronized(pads) { pads.values.firstOrNull { it.index == index } } ?: return
        if (large == 0 && small == 0) {
            stopVibration(pad)
            return
        }
        if (Build.VERSION.SDK_INT < 31) {
            // Android 9-11 (Fire OS 7/8): one vibrator per pad, amplitude only.
            @Suppress("DEPRECATION")
            val v = InputDevice.getDevice(pad.deviceId)?.vibrator ?: return
            if (!v.hasVibrator()) return
            val level = maxOf(large, small).coerceIn(1, 255)
            v.vibrate(if (v.hasAmplitudeControl()) VibrationEffect.createOneShot(RUMBLE_HOLD_MS, level)
                      else VibrationEffect.createOneShot(RUMBLE_HOLD_MS, VibrationEffect.DEFAULT_AMPLITUDE))
            pad.rumbleActive = true
            return
        }
        val dev = InputDevice.getDevice(pad.deviceId) ?: return
        val vm = dev.vibratorManager
        val ids = vm.vibratorIds
        if (ids.isEmpty()) return
        // Games send motor levels and expect them to hold until changed; long one-shots, refreshed
        // by every new request from the PC, approximate that.
        val combo = CombinedVibration.startParallel()
        fun effect(level: Int) = VibrationEffect.createOneShot(RUMBLE_HOLD_MS, level.coerceIn(1, 255))
        if (ids.size >= 2) {
            if (large > 0) combo.addVibrator(ids[0], effect(large))
            if (small > 0) combo.addVibrator(ids[1], effect(small))
        } else {
            combo.addVibrator(ids[0], effect(maxOf(large, small)))
        }
        vm.vibrate(combo.combine())
        pad.rumbleActive = true
    }

    private fun stopVibration(pad: Pad) {
        if (!pad.rumbleActive) return
        if (Build.VERSION.SDK_INT >= 31) InputDevice.getDevice(pad.deviceId)?.vibratorManager?.cancel()
        else @Suppress("DEPRECATION") InputDevice.getDevice(pad.deviceId)?.vibrator?.cancel()
        pad.rumbleActive = false
    }

    companion object {
        private const val TAG = "Jinky"
        private const val MAX_PADS = 4
        private const val SONY_VENDOR = 0x054C
        private const val REPEAT_MS = 50L
        private const val RUMBLE_POLL_MS = 10L
        private const val RUMBLE_HOLD_MS = 2000L
        private const val BTN_MENU = 0x0010  // Start
        private const val BTN_VIEW = 0x0020  // Back
        private const val COMBO = BTN_MENU or BTN_VIEW
        private const val HOLDBACK_MS = 100L
        private const val COMBO_HOLD_MS = 1000L

        // XInput button bits (must match tclproto::GamepadButtons)
        private const val BTN_UP = 0x0001
        private const val BTN_DOWN = 0x0002
        private const val BTN_LEFT = 0x0004
        private const val BTN_RIGHT = 0x0008

        private fun buttonFor(keyCode: Int): Int = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> BTN_UP
            KeyEvent.KEYCODE_DPAD_DOWN -> BTN_DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> BTN_LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> BTN_RIGHT
            KeyEvent.KEYCODE_BUTTON_START -> 0x0010
            KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_BACK -> 0x0020
            KeyEvent.KEYCODE_BUTTON_THUMBL -> 0x0040
            KeyEvent.KEYCODE_BUTTON_THUMBR -> 0x0080
            KeyEvent.KEYCODE_BUTTON_L1 -> 0x0100
            KeyEvent.KEYCODE_BUTTON_R1 -> 0x0200
            KeyEvent.KEYCODE_BUTTON_MODE -> 0x0400
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER -> 0x1000
            KeyEvent.KEYCODE_BUTTON_B -> 0x2000
            KeyEvent.KEYCODE_BUTTON_X -> 0x4000
            KeyEvent.KEYCODE_BUTTON_Y -> 0x8000
            else -> 0
        }
    }
}
