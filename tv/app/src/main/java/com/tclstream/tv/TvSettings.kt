package com.tclstream.tv

import android.content.Context

/** What the user chose in Configurações (kept on this TV). Applied from the next connection on. */
class TvSettings(context: Context) {
    private val prefs = context.getSharedPreferences("jinky_settings", Context.MODE_PRIVATE)

    /** The PC chosen in "Escolher PC" (host id; 0 = none yet: the first one ready). */
    var preferredPc: Long
        get() = prefs.getLong("preferred_pc", 0L)
        set(v) = prefs.edit().putLong("preferred_pc", v).apply()

    /** Picture quality in Mbit/s; 0 = whatever the PC is set to. */
    var bitrateMbps: Int
        get() = prefs.getInt("bitrate_mbps", 0)
        set(v) = prefs.edit().putInt("bitrate_mbps", v).apply()

    /** Only H.264, even if this TV decodes HEVC (for troubleshooting picture problems). */
    var forceH264: Boolean
        get() = prefs.getBoolean("force_h264", false)
        set(v) = prefs.edit().putBoolean("force_h264", v).apply()

    /**
     * Performance on screen while playing: "off", "compact" (a small pill in the corner) or
     * "full" (the technical panel). Older versions had an on/off switch for the panel.
     */
    var statsMode: String
        get() = prefs.getString("stats_mode", null) ?: if (prefs.getBoolean("show_stats", false)) "full" else "off"
        set(v) = prefs.edit().putString("stats_mode", v).apply()

    /** "PC pela internet": addresses (Tailscale) where to look for the PC besides the home network. */
    var remotePcs: Set<String>
        get() = prefs.getStringSet("remote_pcs", emptySet())!!.toSet()
        set(v) = prefs.edit().putStringSet("remote_pcs", v).apply()

    /** "Pausar ao sair": "Início" in the game menu freezes the game on the PC, to continue it later. */
    var pauseGames: Boolean
        get() = prefs.getBoolean("pause_games", true)
        set(v) = prefs.edit().putBoolean("pause_games", v).apply()

    /** "Ligar o PC ao abrir o Jinky": opening Jinky wakes the chosen PC (Wake-on-LAN) if it sleeps. */
    var wakePc: Boolean
        get() = prefs.getBoolean("wake_pc", true)
        set(v) = prefs.edit().putBoolean("wake_pc", v).apply()

    /** "Avisos do PC": downloads finished, friends online... in the corner of the screen. */
    var pcNotices: Boolean
        get() = prefs.getBoolean("pc_notices", true)
        set(v) = prefs.edit().putBoolean("pc_notices", v).apply()

    /** "Controle PlayStation no PC": a DualSense/DualShock becomes a DualShock 4 on the PC (on unless turned off). */
    var psPad: Boolean
        get() = prefs.getBoolean("ps_pad", true)
        set(v) = prefs.edit().putBoolean("ps_pad", v).apply()

    /** "HDR (experimental)": ask the PC for HDR10 (only offered when this TV can show it). */
    var hdr: Boolean
        get() = prefs.getBoolean("hdr", false)
        set(v) = prefs.edit().putBoolean("hdr", v).apply()

    /** "Resolução": 0 = 1080p (the PC's own), 1 = 720p (half the decoding work: less delay on weak sticks). */
    var resolution: Int
        get() = prefs.getInt("resolution", 0)
        set(v) = prefs.edit().putInt("resolution", v).apply()

    /**
     * "Ajuste" on a game's page: 0 = Padrão (the settings above), 1 = Nítido (1080p, the PC's top
     * quality), 2 = Leve (720p, 20 Mbit/s), 3 = Estável (1080p, 20 Mbit/s). Per game, on this TV.
     */
    fun gamePreset(gameId: Int): Int = prefs.getInt("game_preset_$gameId", 0)
    fun setGamePreset(gameId: Int, preset: Int) = prefs.edit().apply {
        if (preset == 0) remove("game_preset_$gameId") else putInt("game_preset_$gameId", preset)
    }.apply()

    /** "Sincronia do áudio": hold the sound back this long (ms) when it comes before the picture. */
    var audioDelayMs: Int
        get() = prefs.getInt("audio_delay_ms", 0)
        set(v) = prefs.edit().putInt("audio_delay_ms", v).apply()

    /** Look of the whole app: "jinky", "midnight" or "retro" (UiTheme keys). */
    var theme: String
        get() = prefs.getString("theme", "jinky")!!
        set(v) = prefs.edit().putString("theme", v).apply()

    /** Interface sounds: "theme" (the theme's own), "retro" (8-bit) or "off". */
    var sounds: String
        get() = prefs.getString("sounds", "theme")!!
        set(v) = prefs.edit().putString("sounds", v).apply()

    /** Interface sound volume, 0..100. */
    var soundVolume: Int
        get() = prefs.getInt("sound_volume", 70)
        set(v) = prefs.edit().putInt("sound_volume", v.coerceIn(0, 100)).apply()

    /** Opening animation: "theme", "fast" or "off". */
    var boot: String
        get() = prefs.getString("boot", "theme")!!
        set(v) = prefs.edit().putString("boot", v).apply()

    /** Game artwork behind the home screen and pages. */
    var art: Boolean
        get() = prefs.getBoolean("art", true)
        set(v) = prefs.edit().putBoolean("art", v).apply()

    /** Old-TV lines over artwork: 0 off, 1 subtle, 2 strong. */
    var scanlines: Int
        get() = prefs.getInt("scanlines", 1)
        set(v) = prefs.edit().putInt("scanlines", v.coerceIn(0, 2)).apply()

    /** "Conquista desbloqueada" over the game, and its sound. */
    var toasts: Boolean
        get() = prefs.getBoolean("toasts", true)
        set(v) = prefs.edit().putBoolean("toasts", v).apply()

    /** Applies the look settings to the drawing code (before building the screens). */
    fun applyLook() {
        Jinky.theme = UiTheme.of(theme)
        Jinky.artBackgrounds = art
        Jinky.scanlineLevel = scanlines
    }

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
