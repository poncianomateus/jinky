package com.tclstream.tv

import android.content.Context

/** What the user chose in Configurações (kept on this TV). Applied from the next connection on. */
class TvSettings(context: Context) {
    private val prefs = context.getSharedPreferences("jinky_settings", Context.MODE_PRIVATE)

    /** Picture quality in Mbit/s; 0 = whatever the PC is set to. */
    var bitrateMbps: Int
        get() = prefs.getInt("bitrate_mbps", 0)
        set(v) = prefs.edit().putInt("bitrate_mbps", v).apply()

    /** Only H.264, even if this TV decodes HEVC (for troubleshooting picture problems). */
    var forceH264: Boolean
        get() = prefs.getBoolean("force_h264", false)
        set(v) = prefs.edit().putBoolean("force_h264", v).apply()

    /** Show the performance panel as soon as a game starts. */
    var showStats: Boolean
        get() = prefs.getBoolean("show_stats", false)
        set(v) = prefs.edit().putBoolean("show_stats", v).apply()

    companion object {
        val BITRATES = listOf(0, 15, 20, 30, 40, 50, 60)
    }
}
