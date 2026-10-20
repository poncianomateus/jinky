package com.tclstream.tv

import android.os.Build
import android.util.Log
import android.view.InputDevice
import android.view.View
import java.lang.reflect.Method

/**
 * By default Android batches joystick motion and hands it to the app once per display frame,
 * which delayed every stick movement by ~16 ms (measured on the Fire TV Stick). This asks for
 * each event to be delivered as soon as it arrives.
 */
object UnbufferedInput {
    private const val TAG = "Jinky"

    fun enable(view: View) {
        if (Build.VERSION.SDK_INT >= 30) {
            view.requestUnbufferedDispatch(InputDevice.SOURCE_CLASS_JOYSTICK or InputDevice.SOURCE_CLASS_BUTTON)
            return
        }
        // Android 9/10 (Fire OS 7) only offer this for touch gestures. ViewRootImpl keeps a flag
        // that, once set, is cleared only when a touch gesture ends — a TV has no touch screen, so
        // setting it once keeps every gamepad event unbatched.
        try {
            allowHiddenApis()
            val root = View::class.java.getDeclaredMethod("getViewRootImpl").invoke(view) ?: return
            val flag = root.javaClass.getDeclaredField("mUnbufferedInputDispatch")
            flag.isAccessible = true
            if (!flag.getBoolean(root)) {
                flag.setBoolean(root, true)
                Log.i(TAG, "input: entrega imediata ligada (Android ${Build.VERSION.SDK_INT})")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "input: entrega imediata indisponível: $t")
        }
    }

    private var hiddenApisAllowed = false

    /** Android 9/10: lifts the restriction on framework internals for this app (no effect on 11+). */
    private fun allowHiddenApis() {
        if (hiddenApisAllowed || Build.VERSION.SDK_INT < 28) return
        val forName = Class::class.java.getDeclaredMethod("forName", String::class.java)
        val getDeclaredMethod = Class::class.java.getDeclaredMethod(
            "getDeclaredMethod", String::class.java, arrayOf<Class<*>>()::class.java)
        val vmRuntime = forName.invoke(null, "dalvik.system.VMRuntime") as Class<*>
        val getRuntime = getDeclaredMethod.invoke(vmRuntime, "getRuntime", null) as Method
        val setExemptions = getDeclaredMethod.invoke(
            vmRuntime, "setHiddenApiExemptions", arrayOf<Class<*>>(Array<String>::class.java)) as Method
        setExemptions.invoke(getRuntime.invoke(null), arrayOf("L") as Any)
        hiddenApisAllowed = true
    }
}
