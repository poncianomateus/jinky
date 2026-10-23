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

    /** Games pinned to the front of the home row, in the order they were pinned. */
    var pinned: List<Int>
        get() = prefs.getString("pinned", "")!!.split(',').mapNotNull { it.toIntOrNull() }
        set(v) = prefs.edit().putString("pinned", v.joinToString(",")).apply()

    /** Pins [id] (at the end), or unpins it. Returns true if it is pinned now. */
    fun togglePin(id: Int): Boolean {
        val now = pinned
        pinned = if (id in now) now - id else now + id
        return id !in now
    }

    /** Achievements of [gameId] the user follows (shown in the menu over the game), at most 5. */
    fun tracked(gameId: Int): List<String> =
        prefs.getString("track_$gameId", "")!!.split('\u0001').filter { it.isNotEmpty() }

    /** Follows or stops following [id]. Returns true if it is followed now (false when already 5). */
    fun toggleTrack(gameId: Int, id: String): Boolean {
        val now = tracked(gameId)
        val next = if (id in now) now - id else if (now.size >= MAX_TRACKED) return false else now + id
        prefs.edit().putString("track_$gameId", next.joinToString("\u0001")).apply()
        return id in next
    }

    companion object {
        val BITRATES = listOf(0, 15, 20, 30, 40, 50, 60)
        const val MAX_TRACKED = 5
    }
}
