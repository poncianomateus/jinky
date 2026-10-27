package com.tclstream.tv

import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import com.tclstream.tv.StartScreen.Step as S

/**
 * Proof-of-life screen: full-screen video plus a diagnostic overlay that shows which milestone
 * the stream has reached. OK toggles the overlay, Back opens the menu. Not the final Jinky UI.
 */
class MainActivity : Activity(), SurfaceHolder.Callback {
    private lateinit var surfaceView: SurfaceView
    private lateinit var statusText: TextView
    private lateinit var perfHud: PerfHud
    private lateinit var keyboard: KeyboardPanel
    private lateinit var flash: View  // the white blink of "Capturar tela"
    private var mouseMode = false  // menu "Mouse": the controller drives the PC's mouse
    private var statsMode = "off"  // performance on screen during this game: off, compact, full
    private var lastPresented = 0L
    private var lastPresentedAt = 0L
    private var shownFps = Double.NaN
    private lateinit var startScreen: StartScreen
    private lateinit var homeScreen: HomeScreen
    private lateinit var settingsScreen: SettingsScreen
    private lateinit var helpScreen: HelpScreen
    private lateinit var pcPicker: PcPickerScreen
    private lateinit var libraryScreen: LibraryScreen
    private lateinit var gameScreen: GameScreen
    private lateinit var achievementsScreen: AchievementsScreen
    private lateinit var profileScreen: ProfileScreen
    private lateinit var friendsScreen: FriendsScreen
    private lateinit var capturesScreen: CapturesScreen
    private lateinit var toast: AchievementToast
    private var bootSplash: BootSplash? = null
    private var sessionGame: LibraryGame? = null  // the game played from the TV's library, if any
    private var achievementsOverGame = false        // the achievements page opened from the menu
    private var unlocksAfter = -1L                  // PC clock of the last unlock check (-1 = learn it)
    private var unlockPolling = false
    private var lastUnlockPoll = 0L
    private var friendsOrigin = Page.HOME  // where Back returns from Amigos
    private var gameOrigin = Page.HOME  // where Back returns from a game's page
    private lateinit var launchScreen: LaunchScreen
    private lateinit var hint: TextView
    private var launching: LibraryGame? = null  // a game starting on the PC, picture not revealed yet
    private var launchedAt = 0L
    private var pcGames: List<LibraryGame> = emptyList()  // the PC's games (the profile opens them by id)
    private var gamesFrom: String? = null  // PC whose games the home row shows ("ip:port")
    private val settings by lazy { TvSettings(this) }

    /** Which of the TV's own screens is up while no game is on. */
    private enum class Page { HOME, SETTINGS, HELP, LIBRARY, GAME, ACHIEVEMENTS, PROFILE, PCS, FRIENDS, CAPTURES }
    private var page = Page.HOME
    private var homeHost: HomeHost? = null  // the PC the home screen shows
    private var askedForPc = false  // "Escolher PC" was shown by itself (several PCs, none chosen yet)
    private var pairingPc = 0L  // a PC picked in "Parear um PC": shown until its code is confirmed
    private var pairingOnly = false  // connected only to pair (no session): leave once paired
    private var wasInSession = false
    private val hostCache by lazy { HostCache(this) }
    private var wakingUntil = 0L
    private val updater by lazy { Updater(this) }
    private var searchStartMs = 0L
    private var lastPictureMs = 0L  // last time this session showed a picture (0 = not yet)
    private val pairingStore by lazy { PairingStore(this) }
    private var pairing: PairingSession? = null
    private var pairingCode: String? = null
    private var receiver: NativeReceiver? = null
    private var pipeline: VideoPipeline? = null
    private var audio: AudioPlayer? = null
    private var gamepads: GamepadInput? = null
    private lateinit var menu: GameMenu
    private var sessionTitle = ""      // what is playing, for the menu
    private var sessionDesktop = false
    private var startError: String? = null
    private val hardwareReport by lazy { HardwareInfo.report() }

    // Test switches: adb shell am start -n com.tclstream.tv/.MainActivity --ez vendor_ll false
    //   --ez pacing false --ei display_hz 120 --ez surface_rate true
    private val tuning by lazy {
        VideoPipeline.Tuning(
            vendorLowLatency = intent.getBooleanExtra("vendor_ll", true),
            pacing = intent.getBooleanExtra("pacing", true),
            vsyncAlign = intent.getBooleanExtra("vsync", true),
            appendAud = intent.getBooleanExtra("aud", false),
            extraFormat = intent.getStringExtra("fmt") ?: "",
        )
    }
    private val displayHz by lazy { intent.getIntExtra("display_hz", 0) }
    private val surfaceRate by lazy { intent.getBooleanExtra("surface_rate", false) }
    private val ui = Handler(Looper.getMainLooper())
    private var ticks = 0

    private val refresh = object : Runnable {
        override fun run() {
            val inSession = receiver?.inSession() == true
            if (inSession != wasInSession) {
                wasInSession = inSession
                // A game starts: the chosen panel setting applies; it ends: back to a clean screen.
                statsMode = if (inSession) settings.statsMode else "off"
                mouseMode = false  // every session starts with the controller in the game
                if (!inSession) receiver?.setCodecSupport(helloCapabilities())  // a game's "Ajuste" was for that game only
                if (!inSession && keyboard.isOpen) keyboard.close()
                applyStatsMode()
                if (!inSession) gamesFrom = null  // "last played" changed: reload the home row
                if (inSession) unlocksAfter = -1L
                else {
                    achievementsOverGame = false
                    sessionGame = null
                }
            }
            if (inSession) {
                checkPadBattery()
                // (the achievements page may be open over the game, from the menu)
                if (!achievementsOverGame) showPage(null)
                updateStartScreen()
                pollUnlocks()
            } else {
                // (the session starts a moment after the request; don't cancel in between)
                if (launching != null && android.os.SystemClock.elapsedRealtime() - launchedAt > 3000) stopLaunching()
                if (android.os.SystemClock.elapsedRealtime() - launchedAt > 3000) installing = false
                updateHome()
            }
            updateUiInput()
            if (menu.isOpen) menu.updateStream(streamLine())
            val text = buildOverlay()
            statusText.text = text
            if (perfHud.visibility == View.VISIBLE) updateHud()
            updatePointerCapture()
            // Same diagnostics in logcat every 5 s, for remote debugging over adb.
            if (++ticks % 10 == 0) Log.i(TAG, "status: " + text.lines().filter { it.startsWith("áudio") }.joinToString(" | "))
            ui.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The chosen look and sounds, before any screen is built.
        settings.applyLook()
        UiSounds.init(this)
        UiSounds.mode = settings.sounds
        UiSounds.volume = settings.soundVolume
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Ask the TV for its low-latency mode (HDMI ALLM / Game Mode): skips picture post-processing.
        if (Build.VERSION.SDK_INT >= 30) {
            window.attributes = window.attributes.also { it.preferMinimalPostProcessing = true }
        }

        surfaceView = SurfaceView(this).also { it.holder.addCallback(this) }
        if (Build.VERSION.SDK_INT >= 26) surfaceView.setOnCapturedPointerListener { _, e -> gamepads?.onTouchpad(e) == true }
        statusText = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(0xB0101418.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = android.graphics.Typeface.MONOSPACE
            val pad = (24 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            visibility = View.GONE  // technical panel: opened from the menu ("Mostrar desempenho")
        }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(surfaceView, FrameLayout.LayoutParams(-1, -1))
            startScreen = StartScreen(this@MainActivity)
            addView(startScreen, FrameLayout.LayoutParams(-1, -1))
            homeScreen = HomeScreen(this@MainActivity, settings,
                onPlayGame = { host, game -> playGame(host, game) },
                onOpenGame = { _, game -> openGame(game, Page.HOME) },
                onPlay = { host, desktop ->
                    sessionTitle = when {
                        host.status == HomeHost.Status.BUSY -> "Jogo em andamento"
                        desktop -> "Área de trabalho"
                        else -> "Steam Big Picture"
                    }
                    sessionDesktop = desktop
                    sessionGame = null
                    receiver?.connect(host.ip, host.port, desktop)
                    homeScreen.visibility = View.GONE
                    startScreen.setShown(true)
                },
                onWake = { host ->
                    host.mac?.let { HostCache.wake(it, host.ip) }
                    wakingUntil = android.os.SystemClock.elapsedRealtime() + 60_000
                },
                onUpdate = { host -> updater.update(host.ip, host.updatePort) },
                onSettings = { showPage(Page.SETTINGS) },
                onLibrary = { host, search ->
                    showPage(Page.LIBRARY)
                    libraryScreen.load(host.ip, host.updatePort, search)
                },
                onProfile = {
                    homeHost?.let { h ->
                        showPage(Page.PROFILE)
                        profileScreen.show(h.ip, h.updatePort)
                    }
                },
                onFriends = ::openFriends,
                onChoosePc = { showPage(Page.PCS) })
            updater.register()
            addView(homeScreen, FrameLayout.LayoutParams(-1, -1))
            settingsScreen = SettingsScreen(this@MainActivity, settings,
                onChanged = {
                    receiver?.setCodecSupport(helloCapabilities())
                    audio?.delayMs = settings.audioDelayMs
                    gamepads?.playStationMode = settings.psPad
                },
                onSendDiagnostics = ::sendDiagnostics,
                onForgetPc = ::forgetPc,
                onHelp = { showPage(Page.HELP) },
                onLookChanged = ::lookChanged,
                onTestToast = {
                    toast.show(AchievementUnlock(0, 0, "Jinky", "teste", "Conquista de exemplo",
                        "Assim aparece uma conquista durante o jogo.", "", 4.2, 12, 46))
                },
                pcAddress = { homeHost?.takeIf { it.updatePort > 0 }?.let { it.ip to it.updatePort } },
                currentPc = { homeHost },
                onPower = ::requestPower,
                onAddRemotePc = ::askRemotePc).apply {
                    visibility = View.GONE
                    pausedNames = { pcGames.filter { it.paused }.map { it.name } }
                }
            addView(settingsScreen, FrameLayout.LayoutParams(-1, -1))
            helpScreen = HelpScreen(this@MainActivity) { showPage(Page.SETTINGS) }.apply { visibility = View.GONE }
            addView(helpScreen, FrameLayout.LayoutParams(-1, -1))
            pcPicker = PcPickerScreen(this@MainActivity,
                onChoose = { h ->
                    settings.preferredPc = h.id
                    gamesFrom = null  // load the chosen PC's library
                    showPage(Page.HOME)
                    if (!isPaired(h) && h.id !in pairingStore.hosts().keys) {
                        // "Parear um PC": the code right away (no game starts); once the PC
                        // accepts, the TV keeps this PC until someone chooses another.
                        pairingPc = h.id
                        pairingOnly = true
                        homeScreen.visibility = View.GONE
                        startScreen.setShown(true)
                        receiver?.connect(h.ip, h.port, false, pairOnly = true)
                    } else updateHome()
                },
                onBack = { showPage(Page.HOME) }).apply { visibility = View.GONE }
            addView(pcPicker, FrameLayout.LayoutParams(-1, -1))
            libraryScreen = LibraryScreen(this@MainActivity, settings,
                onOpen = { game -> openGame(game, Page.LIBRARY) },
                onPlay = { game -> homeHost?.let { playGame(it, game) } },
                onBack = { showPage(Page.HOME) }).apply { visibility = View.GONE }
            addView(libraryScreen, FrameLayout.LayoutParams(-1, -1))
            gameScreen = GameScreen(this@MainActivity, settings,
                onPlay = { game -> homeHost?.let { playGame(it, game) } },
                onInstall = { game -> homeHost?.let { installGame(it, game) } },
                onAchievements = { game -> openAchievements(game, overGame = false) }).apply { visibility = View.GONE }
            addView(gameScreen, FrameLayout.LayoutParams(-1, -1))
            achievementsScreen = AchievementsScreen(this@MainActivity, settings) { closeAchievements() }.apply { visibility = View.GONE }
            addView(achievementsScreen, FrameLayout.LayoutParams(-1, -1))
            profileScreen = ProfileScreen(this@MainActivity,
                onBack = { showPage(Page.HOME) },
                onOpenGame = { id -> pcGames.firstOrNull { it.id == id }?.let { openGame(it, Page.PROFILE) } },
                onOpenAchievements = { id, achievement, lockedOnly ->
                    pcGames.firstOrNull { it.id == id }?.let { g ->
                        // Through the game's page, so Back walks back: achievements, game, profile.
                        openGame(g, Page.PROFILE)
                        openAchievements(g, overGame = false, focusId = achievement, lockedOnly = lockedOnly)
                    }
                },
                onChoosePc = { showPage(Page.PCS) },
                onSettings = { showPage(Page.SETTINGS) },
                onFriends = ::openFriends,
                onCaptures = ::openCaptures).apply { visibility = View.GONE }
            addView(profileScreen, FrameLayout.LayoutParams(-1, -1))
            capturesScreen = CapturesScreen(this@MainActivity) { showPage(Page.PROFILE) }.apply { visibility = View.GONE }
            addView(capturesScreen, FrameLayout.LayoutParams(-1, -1))
            friendsScreen = FriendsScreen(this@MainActivity,
                ownedGame = { id -> pcGames.firstOrNull { it.id == id } },
                onOpenGame = { g -> openGame(g, Page.FRIENDS) },
                onPlayGame = { g -> homeHost?.let { playGame(it, g) } }).apply { visibility = View.GONE }
            addView(friendsScreen, FrameLayout.LayoutParams(-1, -1))
            launchScreen = LaunchScreen(this@MainActivity).apply { visibility = View.GONE }
            addView(launchScreen, FrameLayout.LayoutParams(-1, -1))
            hint = TextView(this@MainActivity).apply {
                visibility = View.GONE
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                val d = resources.displayMetrics.density
                setPadding((22 * d).toInt(), (12 * d).toInt(), (22 * d).toInt(), (12 * d).toInt())
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(0xE60D131D.toInt()); cornerRadius = 99 * d
                }
            }
            addView(hint, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = (48 * resources.displayMetrics.density).toInt()
            })
            // 5% margins keep text inside the TV's safe area.
            val margin = (resources.displayMetrics.widthPixels * 0.05).toInt()
            val statusParams = FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START)
            statusParams.setMargins(margin, margin, margin, margin)
            addView(statusText, statusParams)
            keyboard = KeyboardPanel(this@MainActivity,
                onType = { text, enter -> receiver?.typeText(text, enter) },
                onBackspace = { receiver?.backspace() },
                onClose = { keyboard.close(); updateUiInput() })
            addView(keyboard, FrameLayout.LayoutParams(-1, -1))
            flash = View(this@MainActivity).apply { setBackgroundColor(Color.WHITE); alpha = 0f; visibility = View.GONE }
            addView(flash, FrameLayout.LayoutParams(-1, -1))
            perfHud = PerfHud(this@MainActivity).apply { visibility = View.GONE }
            addView(perfHud, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
                val m = (resources.displayMetrics.heightPixels * 0.04).toInt()
                setMargins(m, m, (resources.displayMetrics.widthPixels * 0.03).toInt(), m)
            })
            menu = GameMenu(this@MainActivity,
                onResume = { hideMenu() },
                onCloseGame = {
                    // The PC closes the game in front; a game session then ends by itself.
                    receiver?.closeGame()
                    hideMenu()
                },
                onScreenshot = {
                    hideMenu()
                    takeScreenshot()
                },
                onKeyboard = {
                    hideMenu()
                    keyboard.open()
                    updateUiInput()
                },
                onToggleMouse = {
                    mouseMode = !mouseMode
                    receiver?.setMouseMode(mouseMode)
                    hideMenu()
                    showHint(if (mouseMode)
                        "Mouse ligado:  analógico esquerdo move  ·  A clica  ·  X botão direito  ·  B Esc  ·  Start Enter.  Para voltar ao jogo: menu  →  Mouse."
                        else "Controle de volta ao jogo.")
                },
                onToggleStats = {
                    // Off -> compact -> detailed -> off, for this game.
                    statsMode = when (statsMode) { "off" -> "compact"; "compact" -> "full"; else -> "off" }
                    applyStatsMode()
                    hideMenu()
                },
                onHome = {
                    // Ends the session (the PC goes back to normal) and shows the home screen. A game
                    // from the library is frozen on the PC first, to continue it later ("Continuar").
                    val game = sessionGame
                    if (game != null && settings.pauseGames) {
                        receiver?.pauseGame()
                        showHint("${game.name} pausado. Continue de onde parou pelo Início.")
                        ui.postDelayed(hideHint, 5000)
                        // The PC freezes it a moment after: list the games again to show it paused.
                        ui.postDelayed({ gamesFrom = null }, 2500)
                    }
                    stopLaunching()
                    sessionGame = null
                    // A short wait so the pause request reaches the PC before the session ends.
                    if (game != null && settings.pauseGames) ui.postDelayed({ receiver?.disconnect() }, 300)
                    else receiver?.disconnect()
                    hideMenu()
                },
                onExit = { finish() },
                onAchievements = {
                    hideMenu()
                    sessionGame?.let { openAchievements(it, overGame = true) }
                })
            addView(menu, FrameLayout.LayoutParams(-1, -1))
            toast = AchievementToast(this@MainActivity)
            addView(toast, FrameLayout.LayoutParams(-1, -1))
            // The opening, like a console's boot (not when the screens were just rebuilt for a new look).
            if (savedInstanceState == null && settings.boot != "off") {
                val splash = BootSplash(this@MainActivity)
                bootSplash = splash
                addView(splash, FrameLayout.LayoutParams(-1, -1))
                splash.play(fast = settings.boot == "fast") { removeView(splash); bootSplash = null }
            }
        })
        // A new look rebuilt the screens: back to where the user was choosing it.
        if (savedInstanceState?.getBoolean(KEY_LOOK) == true) {
            page = Page.SETTINGS
            showPage(Page.SETTINGS)
            // After the first layout, or the default focus would land on the first category.
            settingsScreen.post { settingsScreen.openLook() }
        }
        // A soft tick whenever the focus moves on the TV's own screens.
        window.decorView.viewTreeObserver.addOnGlobalFocusChangeListener { old, new ->
            if (old != null && new != null && uiShown()) UiSounds.play(UiSounds.Sound.MOVE)
        }
        Log.i(TAG, "hardware:\n$hardwareReport")
        Log.i(TAG, "tuning: $tuning displayHz=$displayHz surfaceRate=$surfaceRate")
        if (displayHz > 0) selectRefreshRate(displayHz)
    }

    /** Asks for a display mode at [hz] with the current resolution, if the TV offers one. */
    private fun selectRefreshRate(hz: Int) {
        @Suppress("DEPRECATION") val display = windowManager.defaultDisplay
        val current = display.mode
        val mode = display.supportedModes
            .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
            .minByOrNull { kotlin.math.abs(it.refreshRate - hz) } ?: return
        Log.i(TAG, "requesting display mode ${mode.modeId} (${mode.refreshRate} Hz)")
        window.attributes = window.attributes.also { it.preferredDisplayModeId = mode.modeId }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) UnbufferedInput.enable(window.decorView)
    }

    // Wi-Fi power saving lets the radio doze between packets and deliver them in bursts, which
    // showed up as audio dropouts with no packet lost. Keep it awake while Jinky is on screen.
    private val wifiLock by lazy {
        val wifi = applicationContext.getSystemService(android.net.wifi.WifiManager::class.java)
        @Suppress("DEPRECATION")
        val mode = if (Build.VERSION.SDK_INT >= 29) android.net.wifi.WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                   else android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF
        wifi?.createWifiLock(mode, "jinky:stream")?.apply { setReferenceCounted(false) }
    }

    override fun onResume() {
        super.onResume()
        runCatching { wifiLock?.acquire() }.onFailure { Log.w(TAG, "wifi lock: $it") }
        startReceiver()
    }

    /** What the TV asks of the PC in every Hello: decodable codecs and the chosen quality. */
    /** What this TV asks of the PC; [game]'s "Ajuste" (its page) overrides quality and resolution. */
    private fun helloCapabilities(game: LibraryGame? = null): Int {
        // Codecs this TV decodes in hardware at 1080p60 (the PC prefers HEVC).
        var codecs = 0
        if (HardwareInfo.bestDecoder(VideoPipeline.MIME_AVC) != null) codecs = codecs or 1
        if (!settings.forceH264 && HardwareInfo.bestDecoder(VideoPipeline.MIME_HEVC)?.supports1080p60 == true) codecs = codecs or 2
        var mbps = settings.bitrateMbps
        var size = settings.resolution
        when (game?.let { settings.gamePreset(it.id) } ?: 0) {
            1 -> { mbps = 0; size = 0 }   // Nítido
            2 -> { mbps = 20; size = 1 }  // Leve
            3 -> { mbps = 20; size = 0 }  // Estável
        }
        // HDR10: only with HEVC, and only if this TV shows HDR10 (the PC decides if its screen can).
        val hdr = if (settings.hdr && codecs and 2 != 0 && HardwareInfo.hdr10(this)) HELLO_HDR else 0
        // Quick Resume: continuing a paused game (no Big Picture to wait for), and letting the PC
        // pause the game itself if this TV vanishes (Wi-Fi gone, switched off).
        val resume = if (game?.paused == true) HELLO_RESUME else 0
        val autoPause = if (game != null && settings.pauseGames) HELLO_AUTO_PAUSE else 0
        return codecs or (mbps shl HELLO_BITRATE_SHIFT) or (size shl HELLO_SIZE_SHIFT) or hdr or resume or autoPause
    }

    private fun startReceiver() {
        val r = NativeReceiver()
        r.setCodecSupport(helloCapabilities())
        r.setDeviceName(deviceName())
        r.setIdentity(pairingStore.deviceId())
        pairingStore.hosts().forEach { (host, key) -> r.addPairedHost(host, key) }
        hostCache.all().forEach { r.addKnownAddress(it.ip) }
        settings.remotePcs.forEach { r.addKnownAddress(it) }  // "PC pela internet" (Tailscale)
        // Android emulator on the PC (for trying the screens there): the PC is 10.0.2.2, which
        // broadcast discovery can't reach.
        if (Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish") r.addKnownAddress("10.0.2.2")
        pairing = null
        pairingCode = null
        startError = r.start(HOST_PORT)
        searchStartMs = android.os.SystemClock.elapsedRealtime()
        receiver = r
        audio = AudioPlayer(r).also { it.delayMs = settings.audioDelayMs; it.start() }
        gamepads = GamepadInput(this, r) { ui.post { showMenu() } }.also { it.playStationMode = settings.psPad; it.start() }
        if (surfaceView.holder.surface?.isValid == true) startPipeline(surfaceView.holder)
        ui.post(refresh)
    }

    override fun onPause() {
        runCatching { wifiLock?.release() }
        // Leaving the app in the middle of a game (Home on the remote, the TV switched off): the
        // PC pauses the game, like a console, instead of leaving it running (and heard) there.
        if (sessionGame != null && launching == null && settings.pauseGames && receiver?.inSession() == true) {
            receiver?.pauseGame()
            sessionGame = null
            gamesFrom = null  // list the games again on return: it shows under PAUSADOS
        }
        // TV going to standby / user leaving: release decoder and network, start clean on resume.
        stopReceiver()
        super.onPause()
    }

    private fun stopReceiver() {
        ui.removeCallbacks(refresh)
        stopPipeline()
        audio?.stop()
        audio = null
        gamepads?.stop()  // tells the PC to unplug the virtual pads
        gamepads = null
        receiver?.stop()
        receiver?.close()
        receiver = null
    }

    // --- the TV's own screens ---

    /** Shows home, settings or help; null hides them all (a game is on). */
    private fun showPage(p: Page?) {
        val before = when {
            homeScreen.visibility == View.VISIBLE -> Page.HOME
            settingsScreen.visibility == View.VISIBLE -> Page.SETTINGS
            helpScreen.visibility == View.VISIBLE -> Page.HELP
            libraryScreen.visibility == View.VISIBLE -> Page.LIBRARY
            gameScreen.visibility == View.VISIBLE -> Page.GAME
            achievementsScreen.visibility == View.VISIBLE -> Page.ACHIEVEMENTS
            profileScreen.visibility == View.VISIBLE -> Page.PROFILE
            pcPicker.visibility == View.VISIBLE -> Page.PCS
            friendsScreen.visibility == View.VISIBLE -> Page.FRIENDS
            capturesScreen.visibility == View.VISIBLE -> Page.CAPTURES
            else -> null
        }
        if (p != null) page = p
        homeScreen.visibility = if (p == Page.HOME) View.VISIBLE else View.GONE
        settingsScreen.visibility = if (p == Page.SETTINGS) View.VISIBLE else View.GONE
        helpScreen.visibility = if (p == Page.HELP) View.VISIBLE else View.GONE
        libraryScreen.visibility = if (p == Page.LIBRARY) View.VISIBLE else View.GONE
        gameScreen.visibility = if (p == Page.GAME) View.VISIBLE else View.GONE
        achievementsScreen.visibility = if (p == Page.ACHIEVEMENTS) View.VISIBLE else View.GONE
        profileScreen.visibility = if (p == Page.PROFILE) View.VISIBLE else View.GONE
        pcPicker.visibility = if (p == Page.PCS) View.VISIBLE else View.GONE
        friendsScreen.visibility = if (p == Page.FRIENDS) View.VISIBLE else View.GONE
        capturesScreen.visibility = if (p == Page.CAPTURES) View.VISIBLE else View.GONE
        if (before == Page.FRIENDS && p != Page.FRIENDS) friendsScreen.hidden()
        if (p == Page.FRIENDS && before != Page.FRIENDS) friendsScreen.resumed()  // back from a game's page
        if (p == Page.PCS && before != Page.PCS) {
            val pcs = knownPcs()
            pcPicker.show(pcs, homeHost?.id, pcs.filter(::isPaired).map { it.id }.toSet())
        }
        if (p != Page.GAME) gameScreen.closeViewer()
        if (p == Page.HELP) helpScreen.show(homeHost, decoderSummary())
        if (p != null && p != before) {
            // Screens fade in instead of popping up.
            val view = when (p) {
                Page.HOME -> homeScreen
                Page.SETTINGS -> settingsScreen
                Page.HELP -> helpScreen
                Page.LIBRARY -> libraryScreen
                Page.GAME -> gameScreen
                Page.ACHIEVEMENTS -> achievementsScreen
                Page.PROFILE -> profileScreen
                Page.PCS -> pcPicker
                Page.FRIENDS -> friendsScreen
                Page.CAPTURES -> capturesScreen
            }
            view.alpha = 0f
            view.animate().alpha(1f).setDuration(220).start()
            if (p == Page.HOME) {
                homeScreen.refreshRow()  // pins may have changed in the Biblioteca
                homeScreen.animateIn()
            }
        }
        if (p != null && p != before) when (p) {
            Page.HOME -> homeScreen.focusFirst()
            Page.SETTINGS -> settingsScreen.focusFirst()
            Page.HELP -> helpScreen.focusFirst()
            Page.LIBRARY -> libraryScreen.focusFirst()
            Page.GAME -> gameScreen.focusFirst()
            Page.ACHIEVEMENTS -> achievementsScreen.focusFirst()
            Page.PROFILE -> profileScreen.focusFirst()
            Page.PCS -> pcPicker.focusFirst()
            Page.FRIENDS -> friendsScreen.focusFirst()
            Page.CAPTURES -> capturesScreen.focusFirst()
        }
        updateUiInput()
    }

    /** A game's page (artwork, details, screenshots, trailers; Jogar / Instalar from there). */
    private fun openGame(game: LibraryGame, from: Page) {
        val host = homeHost ?: return
        if (host.status != HomeHost.Status.READY) return
        gameOrigin = from
        showPage(Page.GAME)
        gameScreen.show(game, host.ip, host.updatePort, when (from) {
            Page.HOME -> "Início"
            Page.PROFILE -> "Perfil"
            Page.FRIENDS -> "Amigos"
            else -> "Biblioteca"
        })
        gameScreen.focusFirst()
    }

    private var friendsPolledAt = 0L

    /** How many friends are playing now, for the badge on Home's Amigos tab (once a minute). */
    private fun pollFriends(host: HomeHost) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (homeScreen.visibility != View.VISIBLE || host.status == HomeHost.Status.OFFLINE || host.updatePort <= 0) return
        if (friendsPolledAt != 0L && now - friendsPolledAt < 60_000) return
        friendsPolledAt = now
        Thread {
            val playing = try { SteamFriend.fetch(host.ip, host.updatePort).first?.count { it.playing } } catch (e: Exception) { null }
            ui.post { homeScreen.setFriendsPlaying(playing ?: 0) }
        }.start()
    }

    /**
     * "Suspender/Desligar o PC" (Configurações > Sistema): an authenticated request to the PC
     * (its Jinky checks this TV's pairing key), then the TV lets go.
     */
    private fun requestPower(off: Boolean) {
        val h = homeHost ?: return
        receiver?.requestPower(h.ip, h.port, off)
        settingsScreen.setNote(if (off) "Pedido enviado: o PC ${h.name} vai desligar."
            else "Pedido enviado: o PC ${h.name} vai dormir.")
        ui.postDelayed({ if (receiver?.inSession() != true) receiver?.disconnect() }, 4000)
    }

    private var lastShotAt = 0L

    /**
     * "Controle PlayStation no PC": while playing, the DualSense touchpad is captured (raw touches
     * for the PC's DualShock 4) instead of moving a pointer on the TV.
     */
    private fun updatePointerCapture() {
        val want = settings.psPad && receiver?.inSession() == true && !uiShown() && gamepads?.hasPlayStationPad() == true
        if (Build.VERSION.SDK_INT < 26) return
        if (want && !surfaceView.hasPointerCapture()) {
            surfaceView.isFocusable = true
            surfaceView.requestFocus()
            surfaceView.requestPointerCapture()
        } else if (!want && surfaceView.hasPointerCapture()) {
            surfaceView.releasePointerCapture()
        }
    }
    private var padBatteryCheckedAt = 0L
    private var padBatteryWarnedAt = 101  // the level last warned about (warn again only lower)

    /** While playing: a note when a controller's battery gets low (15%, then 5%). */
    private fun checkPadBattery() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - padBatteryCheckedAt < 30_000) return
        padBatteryCheckedAt = now
        val pct = PadBattery.lowest() ?: return
        if (PadBattery.charging()) { padBatteryWarnedAt = 101; return }
        val step = when { pct <= 5 -> 5; pct <= 15 -> 15; else -> return }
        if (step >= padBatteryWarnedAt) return
        padBatteryWarnedAt = step
        showHint("Bateria do controle baixa: $pct%. Bom ligar no carregador.")
        ui.postDelayed(hideHint, 6000)
    }

    /** "Capturar tela": the PC saves what is on screen; the TV blinks and says where it went. */
    private fun takeScreenshot() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastShotAt < 1500) return
        lastShotAt = now
        // After the menu has slid away (it isn't in the PC's picture anyway, but the blink reads better).
        ui.postDelayed({
            receiver?.screenshot()
            UiSounds.play(UiSounds.Sound.SELECT)
            flash.visibility = View.VISIBLE
            flash.alpha = 0.7f
            flash.animate().alpha(0f).setDuration(450).withEndAction { flash.visibility = View.GONE }.start()
            showHint("Captura salva no PC, em Imagens › Jinky. Veja em Perfil › Capturas.")
            ui.postDelayed(hideHint, 3500)
        }, 250)
    }

    /** Capturas (from the Perfil): the screenshots taken from the TV, kept on the PC. */
    private fun openCaptures() {
        val host = homeHost ?: return
        if (host.status == HomeHost.Status.OFFLINE || host.updatePort <= 0) return
        showPage(Page.CAPTURES)
        capturesScreen.show(host.ip, host.updatePort)
    }

    /**
     * "PC pela internet": the PC's Tailscale address (100.x.y.z) or name. The TV then looks for the
     * PC there too; a PC paired at home is recognised by its key and plays from anywhere.
     */
    private fun askRemotePc() {
        val field = android.widget.EditText(this).apply {
            hint = "100.x.y.z ou nome-do-pc"
            setText(settings.remotePcs.firstOrNull() ?: "")
            isSingleLine = true
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("PC pela internet")
            .setMessage("Com o Tailscale no PC e nesta TV (mesma conta), digite o endereço que o Jinky do PC mostra em \"Jogar fora de casa\". Deixe vazio para parar de procurar.")
            .setView(field)
            .setPositiveButton("Salvar") { _, _ ->
                val text = field.text.toString().trim()
                if (text.isEmpty()) {
                    settings.remotePcs = emptySet()
                    settingsScreen.setNote("PC pela internet: desligado.")
                    return@setPositiveButton
                }
                Thread {
                    // A Tailscale name (MagicDNS) becomes its address; an address stays as it is.
                    val ip = try { java.net.InetAddress.getByName(text).hostAddress } catch (e: Exception) { null }
                    ui.post {
                        if (ip == null || ip.contains(':')) {
                            settingsScreen.setNote("Não encontrei \"$text\". Confira o endereço e se o Tailscale está ligado na TV.")
                        } else {
                            settings.remotePcs = setOf(ip)
                            receiver?.addKnownAddress(ip)
                            settingsScreen.setNote("Procurando o PC em $ip. Ele aparece em Escolher PC quando responder.")
                        }
                    }
                }.start()
            }
            .setNegativeButton("Cancelar", null)
            .setNeutralButton(if (tailscaleInstalled()) "Abrir o Tailscale" else "Instalar o Tailscale") { _, _ -> openTailscale() }
            .show()
    }

    private fun tailscaleInstalled(): Boolean = try {
        packageManager.getPackageInfo(TAILSCALE, 0)
        true
    } catch (e: Exception) {
        false
    }

    /** Tailscale on this TV: open it (to sign in), or its page in the TV's store to install it. */
    private fun openTailscale() {
        if (tailscaleInstalled()) {
            packageManager.getLeanbackLaunchIntentForPackage(TAILSCALE)?.let { startActivity(it); return }
            packageManager.getLaunchIntentForPackage(TAILSCALE)?.let { startActivity(it); return }
        }
        // market:// opens Google Play on Google TV and the Amazon Appstore on Fire TV.
        for (uri in listOf("market://details?id=$TAILSCALE", "https://play.google.com/store/apps/details?id=$TAILSCALE")) {
            try {
                startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(uri))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (e: Exception) {
            }
        }
        settingsScreen.setNote("Não achei a loja nesta TV. Instale o Tailscale pela loja de apps da TV.")
    }

    /** "Para abrir X, feche um jogo pausado": the choice, then the game. */
    private fun askWhichPausedToClose(host: HomeHost, game: LibraryGame, paused: List<LibraryGame>) {
        val names = paused.map { "Fechar ${it.name}" }.toTypedArray()
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Dois jogos já estão pausados")
            .setItems(names) { _, which ->
                val close = paused[which]
                Thread {
                    LibraryScreen.closePaused(host.ip, host.updatePort, close.id)
                    ui.post {
                        pcGames = pcGames.map { if (it.id == close.id) it.copy(paused = false) else it }
                        playGame(host, game)
                    }
                }.start()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /** Amigos: the Steam friends list, from Home or the Perfil (Back returns there). */
    private fun openFriends() {
        val host = homeHost ?: return
        if (host.status == HomeHost.Status.OFFLINE || host.updatePort <= 0) return
        friendsOrigin = if (profileScreen.visibility == View.VISIBLE) Page.PROFILE else Page.HOME
        showPage(Page.FRIENDS)
        friendsScreen.show(host.ip, host.updatePort)
    }

    /** A game's achievements: from its page, or over the game from the menu ([overGame]). */
    private fun openAchievements(game: LibraryGame, overGame: Boolean, focusId: String? = null, lockedOnly: Boolean = false) {
        val host = homeHost ?: return
        achievementsOverGame = overGame
        showPage(Page.ACHIEVEMENTS)
        achievementsScreen.show(game, host.ip, host.updatePort, focusId, lockedOnly)
    }

    private fun closeAchievements() {
        if (achievementsOverGame) {
            achievementsOverGame = false
            showPage(null)  // back to the game
        } else {
            showPage(Page.GAME)
        }
    }

    /** While playing: asks the PC every few seconds for achievements just unlocked, and shows them. */
    private fun pollUnlocks() {
        val host = homeHost ?: return
        val now = android.os.SystemClock.elapsedRealtime()
        if (unlockPolling || now - lastUnlockPoll < 3000 || host.updatePort == 0) return
        lastUnlockPoll = now
        unlockPolling = true
        val after = unlocksAfter
        Thread {
            val result = try { AchievementsApi.unlocks(host.ip, host.updatePort, after) } catch (e: Exception) { null }
            ui.post {
                unlockPolling = false
                if (result == null) return@post
                unlocksAfter = result.first
                if (settings.toasts) result.second.forEach { toast.show(it) }
            }
        }.start()
    }

    /**
     * A game from the TV's own library: a console session without Big Picture. The TV shows the
     * game's artwork until the PC says the game is on screen; closing the game brings the TV back
     * to its home screen.
     */
    private fun playGame(host: HomeHost, game: LibraryGame) {
        if (host.status != HomeHost.Status.READY) return
        // Two games are already paused: one has to close to make room (the PC's video memory).
        val paused = pcGames.filter { it.paused && it.id != game.id }
        if (!game.paused && paused.size >= 2) {
            askWhichPausedToClose(host, game, paused)
            return
        }
        launching = game
        launchedAt = android.os.SystemClock.elapsedRealtime()
        sessionTitle = game.name
        sessionGame = game
        sessionDesktop = false
        showPage(Page.HOME)
        homeScreen.visibility = View.GONE
        launchScreen.show(game, host.ip, host.updatePort)
        audio?.muted = true  // Big Picture opens behind the launch screen: not a sound of it
        receiver?.setCodecSupport(helloCapabilities(game))  // the game's own "Ajuste", if any
        receiver?.connect(host.ip, host.port, false, game = true)
        // The PC starts the game as soon as this session begins.
        Thread {
            LibraryScreen.launch(host.ip, host.updatePort, game)?.let { Log.w(TAG, "launch ${game.name}: $it") }
        }.start()
    }

    /**
     * Installing: Steam only installs after the user confirms in its own window, so this opens a
     * console session (Big Picture) where Steam's install prompt appears, to confirm with A. The
     * download then continues on the PC and shows on the game's cover in the Biblioteca.
     */
    private fun installGame(host: HomeHost, game: LibraryGame) {
        if (host.status != HomeHost.Status.READY) return
        installing = true
        launchedAt = android.os.SystemClock.elapsedRealtime()
        sessionTitle = "Instalando ${game.name}"
        sessionDesktop = false
        showPage(Page.HOME)
        homeScreen.visibility = View.GONE
        startScreen.setShown(true)
        receiver?.connect(host.ip, host.port, false)
        Thread {
            LibraryScreen.install(host.ip, host.updatePort, game)?.let { Log.w(TAG, "install ${game.name}: $it") }
        }.start()
        showHint("Confirme a instalação com A.  Depois: Voltar  →  Voltar ao início.", delayMs = 2500)
    }

    private var installing = false

    /** A short note at the bottom of the screen (over the game), gone after a few seconds. */
    private fun showHint(text: String, delayMs: Long = 0) {
        hint.text = text
        hint.visibility = View.VISIBLE
        hint.alpha = 0f
        hint.animate().alpha(1f).setStartDelay(delayMs).setDuration(300).start()
        ui.removeCallbacks(hideHint)
        ui.postDelayed(hideHint, 14_000)
    }

    private val hideHint = Runnable { hint.animate().alpha(0f).setStartDelay(0).setDuration(300).withEndAction { hint.visibility = View.GONE }.start() }

    private fun stopLaunching() {
        audio?.muted = false
        launching = null
        launchScreen.hide()
    }

    /** The home row shows the PC's games; fetched once per PC (and again after each session). */
    private fun loadGames(host: HomeHost) {
        val key = "${host.ip}:${host.updatePort}"
        if (host.status != HomeHost.Status.READY || host.updatePort == 0 || gamesFrom == key) return
        gamesFrom = key
        Thread {
            val games = try {
                LibraryScreen.fetchGames(host.ip, host.updatePort)
            } catch (e: Exception) {
                Log.w(TAG, "home games: $e")
                null
            }
            ui.post {
                pcGames = games.orEmpty()
                homeScreen.setGames(games, host.ip, host.updatePort)
            }
            // The Steam account for the profile button (the PC caches it).
            val profile = try { SteamProfileData.fetch(host.ip, host.updatePort).first } catch (e: Exception) { null }
            if (profile != null) ui.post { homeScreen.setProfile(profile.name, profile.avatar) }
        }.start()
    }

    private fun decoderSummary(): String {
        val hevc = !settings.forceH264 && HardwareInfo.bestDecoder(VideoPipeline.MIME_HEVC)?.supports1080p60 == true
        val d = HardwareInfo.bestDecoder(if (hevc) VideoPipeline.MIME_HEVC else VideoPipeline.MIME_AVC)
        return d?.let { "${if (hevc) "HEVC" else "H.264"} por hardware (${it.name})" } ?: "nenhum por hardware"
    }

    private fun sendDiagnostics() {
        val pc = homeHost
        if (pc == null || pc.status == HomeHost.Status.OFFLINE || pc.updatePort == 0) {
            settingsScreen.setNote("O PC precisa estar ligado e com o Jinky aberto para receber o diagnóstico.")
            return
        }
        settingsScreen.setNote("Enviando diagnóstico ao PC…")
        Diagnostics.send(pc.ip, pc.updatePort, deviceName(), buildOverlay()) { msg -> ui.post { settingsScreen.setNote(msg) } }
    }

    private fun forgetPc() {
        // Only the PC in use (others, e.g. another Windows user's Jinky, stay paired).
        val id = homeHost?.id
        if (id == null || id !in pairingStore.hosts().keys) {
            settingsScreen.setNote("Esta TV não está pareada com este PC.")
            return
        }
        pairingStore.remove(id)
        // The native side still holds the old key: start it again without it.
        stopReceiver()
        startReceiver()
        settingsScreen.setNote("Pronto: este PC foi esquecido. Na próxima conexão, a TV pede um novo pareamento.")
    }

    override fun surfaceCreated(holder: SurfaceHolder) = startPipeline(holder)

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) = stopPipeline()

    private fun startPipeline(holder: SurfaceHolder) {
        val r = receiver ?: return
        if (pipeline != null) return
        if (surfaceRate && Build.VERSION.SDK_INT >= 30) {
            holder.surface.setFrameRate(60f, android.view.Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE)
        }
        @Suppress("DEPRECATION") val hz = windowManager.defaultDisplay.refreshRate
        pipeline = VideoPipeline(r, holder.surface, tuning, hz).also { it.start() }
    }

    private fun stopPipeline() {
        pipeline?.stop()
        pipeline = null
    }

    // Gamepad input goes to the PC; everything else (the TV remote) keeps working normally.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // While the opening plays, any button skips it (and nothing moves behind it).
        bootSplash?.let { if (event.action == KeyEvent.ACTION_DOWN) it.skip(); return true }
        // The controller's Share/Capture button (Xbox Series, some others) takes a screenshot while playing.
        if (event.keyCode == KeyEvent.KEYCODE_MEDIA_RECORD && receiver?.inSession() == true && !uiShown()) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) takeScreenshot()
            return true
        }
        val ok = event.action == KeyEvent.ACTION_DOWN &&
            event.keyCode in listOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BUTTON_A)
        // Session on another TV: OK on the remote or A on a controller brings it here.
        if (ok && receiver?.state() == STATE_DISPLACED) {
            receiver?.takeover()
            return true
        }
        // Pairing was refused: OK tries again with a fresh key pair.
        if (ok && receiver?.state() == STATE_PAIRING_REJECTED) {
            pairing = null
            pairingCode = null
            receiver?.retryPairing()
            return true
        }
        if (gamepads?.onKeyEvent(event) == true) return true
        // Interface sounds on the TV's own screens: OK confirms, Back goes back.
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && uiShown()) when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BUTTON_A -> UiSounds.play(UiSounds.Sound.SELECT)
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_ESCAPE -> UiSounds.play(UiSounds.Sound.BACK)
        }
        // On the TV's own screens (home, menu) the controller's A works as OK.
        if (event.keyCode == KeyEvent.KEYCODE_BUTTON_A) {
            return super.dispatchKeyEvent(KeyEvent(event.downTime, event.eventTime, event.action,
                KeyEvent.KEYCODE_DPAD_CENTER, event.repeatCount, event.metaState, event.deviceId,
                event.scanCode, event.flags, event.source))
        }
        return super.dispatchKeyEvent(event)
    }

    /** The name the user gave this TV (e.g. "Fire TV da sala"), or its model. */
    private fun deviceName(): String =
        android.provider.Settings.Global.getString(contentResolver, "device_name")
            // Some devices (the Android emulator) pad the name with invisible direction marks.
            ?.filter { Character.getType(it) != Character.FORMAT.toInt() }?.trim()?.takeIf { it.isNotBlank() }
            ?: "${Build.MANUFACTURER} ${Build.MODEL}"

    override fun dispatchGenericMotionEvent(event: android.view.MotionEvent): Boolean =
        gamepads?.onMotionEvent(event) == true || super.dispatchGenericMotionEvent(event)

    // --- in-stream menu: View + Menu held on the controller, or Back on the TV remote ---

    /** PCs answering discovery right now. One PC may run several Jinkys (one per Windows user). */
    private fun onlinePcs(): List<HomeHost> = receiver?.hosts().orEmpty().lines().mapNotNull { line ->
        val p = line.split('|')
        if (p.size < 7) return@mapNotNull null
        val id = java.lang.Long.parseUnsignedLong(p[0], 16)
        val age = p[4].toLongOrNull() ?: Long.MAX_VALUE
        hostCache.remember(id, p[5], p[1], p[3])
        if (age > 6000) return@mapNotNull null  // not answering any more
        val flags = p.getOrNull(10)?.toIntOrNull() ?: 0
        val known = flags and 1 != 0
        val pairedHere = flags and 2 != 0
        // The PC removed this TV (its "Remover" in TVs pareadas): forget it here too.
        if (known && !pairedHere && id in pairingStore.hosts().keys) {
            Log.i(TAG, "PC ${p[5]} no longer has this TV paired: forgetting it")
            pairingStore.remove(id)
            if (settings.preferredPc == id) settings.preferredPc = 0L
        }
        HomeHost(id, p[5], p[1], if (p[2] == "1") HomeHost.Status.BUSY else HomeHost.Status.READY, p[6],
            HostCache.parseMac(p[3]), p.getOrNull(7)?.toIntOrNull() ?: 0, p.getOrNull(8)?.toIntOrNull() ?: 0,
            p.getOrNull(9)?.toIntOrNull() ?: 0, known, pairedHere)
    }

    /** Paired with this TV: as the PC itself says (newer PCs), else as this TV remembers. */
    private fun isPaired(h: HomeHost) = if (h.pairingKnown) h.pairedHere else h.id in pairingStore.hosts().keys

    /** For "Escolher PC": the ones online, then the others this TV has seen. */
    private fun knownPcs(): List<HomeHost> {
        val online = onlinePcs()
        return online + hostCache.all().filter { c -> online.none { it.id == c.id } }
            .map { HomeHost(it.id, it.name, it.ip, HomeHost.Status.OFFLINE, "", it.mac) }
    }

    /** Home screen: the PC this TV knows, live from discovery, else from the cache (to wake it). */
    private fun updateHome() {
        val now = android.os.SystemClock.elapsedRealtime()
        startScreen.setShown(false)
        showPage(page)
        val online = onlinePcs()
        // The PC chosen in "Escolher PC": shown even when it is off (this TV belongs to it; it must
        // not land on another Windows user's Jinky by itself). No choice yet: the only PC there is,
        // and with more than one, ask (once) instead of guessing.
        val preferred = settings.preferredPc
        val chosenOffline = if (preferred == 0L || online.any { it.id == preferred }) null
            else hostCache.all().firstOrNull { it.id == preferred }?.let { HomeHost(it.id, it.name, it.ip, HomeHost.Status.OFFLINE, "", it.mac) }
        // The base rule: a PC shows up only once it is paired with this TV (the code confirmed on
        // the PC), or while the user is pairing it (chose it in "Parear um PC"). Never another
        // Windows user's Jinky on the same PC just because it answers.
        val paired = pairingStore.hosts().keys
        fun allowed(h: HomeHost) = isPaired(h) || h.id in paired || h.id == pairingPc
        val candidates = online.filter(::isPaired)
        val host = (online.firstOrNull { it.id == preferred } ?: chosenOffline)?.takeIf(::allowed)
            ?: candidates.minByOrNull { if (it.status == HomeHost.Status.READY) 0 else 1 }
            ?: hostCache.all().firstOrNull { it.id in paired }?.let { HomeHost(it.id, it.name, it.ip, HomeHost.Status.OFFLINE, "", it.mac) }
            ?: online.firstOrNull { it.id == pairingPc }
        // Several paired PCs and none chosen: ask. None paired yet but PCs around: "Parear um PC".
        if (!askedForPc && page == Page.HOME &&
            ((preferred == 0L && candidates.size > 1) || (host == null && online.isNotEmpty()))) {
            askedForPc = true
            ui.post { showPage(Page.PCS) }
        }
        if (host?.status != HomeHost.Status.OFFLINE) wakingUntil = 0L
        homeHost = host
        lastPictureMs = 0L
        host?.let(::loadGames)
        host?.let(::pollFriends)
        homeScreen.show(host, searching = true, waking = now < wakingUntil, updateStatus = updater.status,
            unpairedAround = if (host == null) online.count { !isPaired(it) } else 0)
    }

    /** One of the TV's own screens (or the menu) is in front, not the game. */
    private fun uiShown() = homeScreen.visibility == View.VISIBLE || settingsScreen.visibility == View.VISIBLE ||
        helpScreen.visibility == View.VISIBLE || libraryScreen.visibility == View.VISIBLE ||
        gameScreen.visibility == View.VISIBLE || achievementsScreen.visibility == View.VISIBLE ||
        profileScreen.visibility == View.VISIBLE || pcPicker.visibility == View.VISIBLE ||
        friendsScreen.visibility == View.VISIBLE || capturesScreen.visibility == View.VISIBLE || menu.isOpen || keyboard.isOpen

    /** The controller drives the TV's own screens (home, menu); during play it drives the PC. */
    private fun updateUiInput() {
        val ui = uiShown()
        if (gamepads?.menuOpen != ui) gamepads?.menuOpen = ui
    }

    /** A new theme or old-TV lines: rebuild every screen with the new look. */
    private var lookPending = false
    private fun lookChanged() {
        settings.applyLook()
        lookPending = true
        recreate()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (lookPending) outState.putBoolean(KEY_LOOK, true)
    }

    /** Shows the Jinky start screen until the TV confirms a picture; explains each step meanwhile. */
    private fun updateStartScreen() {
        val now = android.os.SystemClock.elapsedRealtime()
        val r = receiver
        // Persist pairing changes the native side reports.
        r?.takeNewPairing()?.let { b ->
            val host = java.nio.ByteBuffer.wrap(b, 0, 8).order(java.nio.ByteOrder.LITTLE_ENDIAN).long
            pairingStore.save(host, b.copyOfRange(8, 40))
            pairing = null
            pairingCode = null
            if (pairingOnly) {
                // Paired: back to the home screen, now on this PC (nothing started on it).
                pairingOnly = false
                pairingPc = 0L
                settings.preferredPc = host
                r.disconnect()
                startScreen.setShown(false)
                showPage(Page.HOME)
                return
            }
        }
        r?.takeForgottenHost()?.takeIf { it != 0L }?.let { pairingStore.remove(it) }

        when (r?.state()) {
            STATE_NEED_PAIRING, STATE_PAIRING_CODE -> {
                // First time with this PC: ECDH, then show the code to confirm on the PC.
                val session = pairing ?: PairingSession().also { pairing = it; r.startPairing(it.publicKey) }
                if (pairingCode == null) {
                    r.pairingChallenge()?.let { pc -> pairingCode = r.setPairSecret(session.secretWith(pc), session.publicKey, pc) }
                }
                startScreen.setShown(true)
                val code = pairingCode
                startScreen.show(
                    if (code == null) StartScreen.Model("Conectando a um PC novo", "Preparando o pareamento…", emptyList())
                    else StartScreen.Model(
                        "Parear com o PC: ${code.substring(0, 3)} ${code.substring(3)}",
                        "No computador, o Jinky está mostrando um código. Se for o mesmo, clique em Permitir. Isso só é preciso uma vez.",
                        emptyList()))
                return
            }
            STATE_PAIRING_REJECTED -> {
                startScreen.setShown(true)
                startScreen.show(StartScreen.Model(
                    "Pareamento recusado", "O computador não permitiu esta TV. Aperte OK para tentar de novo.",
                    emptyList(), warning = true))
                return
            }
            STATE_ENDED -> {
                if (r.endReason() == END_GAME_CLOSED) {
                    // The game was closed: back to the home screen, like a console.
                    stopLaunching()
                    r.disconnect()
                    return
                }
                if (installing) {
                    // Closing Big Picture after confirming an install: back to the home screen.
                    installing = false
                    r.disconnect()
                    return
                }
                // Big Picture was closed on the PC: the session is over, like turning a console off.
                finish()
                return
            }
            STATE_DISPLACED -> {
                startScreen.setShown(true)
                startScreen.show(StartScreen.Model(
                    "Jogando em outra TV",
                    "O jogo continua em outra TV da casa. Aperte OK para trazer para cá.",
                    emptyList()))
                return
            }
        }
        val p = pipeline
        val host = r?.hostAddress()
        val picture = p != null && p.lastPresentMs != 0L && now - p.lastPresentMs < 3000
        if (picture) lastPictureMs = now
        if (launching != null) {
            // The game's own screen stays up until the game is in front on the PC.
            startScreen.setShown(false)
            val ready = r?.gameReady() ?: -1
            if (picture && ready >= 0) {
                stopLaunching()
                // A launcher or Steam prompt is in front of the game: say why it's showing.
                if (ready == GAME_NEEDS_ATTENTION) showHint("Uma janela pede sua resposta: o analógico move o mouse, A clica, B cancela, Start confirma.")
            } else {
                launchScreen.setStatus(when {
                    host == null -> "Conectando ao PC…"
                    launching?.paused == true -> "Continuando de onde parou…"
                    else -> "Abrindo o jogo…"
                })
            }
            return
        }
        if (picture) {
            startScreen.setShown(false)
            return
        }
        startScreen.setShown(true)
        val model = when {
            startError != null -> StartScreen.Model(
                "Sem acesso à rede", "A TV não conseguiu usar a rede: $startError",
                listOf("Rede da TV" to S.ACTIVE), warning = true)
            lastPictureMs != 0L && p?.stage != VideoPipeline.Stage.STALLED && p?.stage != VideoPipeline.Stage.ERROR -> StartScreen.Model(
                // The picture was there and stopped: almost always the Wi-Fi. The game keeps running
                // on the PC (console mode waits for this TV), and the picture comes back by itself.
                "Reconectando", "A conexão com o PC oscilou. O jogo continua lá; a imagem volta sozinha assim que a rede normalizar.",
                listOf("PC encontrado" to S.DONE, "Recebendo o vídeo" to S.ACTIVE, "Mostrando a imagem" to S.PENDING),
                warning = now - lastPictureMs > 20_000)
            host == null -> {
                val long = now - searchStartMs > 15_000
                StartScreen.Model(
                    "Procurando seu PC",
                    if (!long) "Abra o Jinky no computador. A TV e o PC precisam estar na mesma rede de casa."
                    else "Ainda não encontramos o PC. Confira se o Jinky está aberto no computador (ícone perto do relógio) e se o Firewall do Windows permitiu o Jinky.",
                    listOf("Procurando o PC na rede" to S.ACTIVE, "Recebendo o vídeo" to S.PENDING, "Mostrando a imagem" to S.PENDING),
                    warning = long)
            }
            p != null && (p.stage == VideoPipeline.Stage.STALLED || p.stage == VideoPipeline.Stage.ERROR) -> StartScreen.Model(
                "Recuperando a imagem", p.lastError ?: "A imagem parou. Reiniciando o vídeo automaticamente…",
                listOf("PC encontrado" to S.DONE, "Recebendo o vídeo" to S.DONE, "Mostrando a imagem" to S.ACTIVE), warning = true)
            else -> {
                val receiving = (p?.framesQueued?.get() ?: 0L) > 0
                StartScreen.Model(
                    "Conectando ao seu PC", "PC encontrado em $host. Preparando a imagem…",
                    listOf("PC encontrado" to S.DONE,
                        "Recebendo o vídeo" to if (receiving) S.DONE else S.ACTIVE,
                        "Mostrando a imagem" to if (receiving) S.ACTIVE else S.PENDING))
            }
        }
        startScreen.show(model)
    }

    private fun applyStatsMode() {
        statusText.visibility = if (statsMode == "full") View.VISIBLE else View.GONE
        perfHud.visibility = if (statsMode == "compact") View.VISIBLE else View.GONE
        if (statsMode == "compact") { lastPresentedAt = 0L; shownFps = Double.NaN; updateHud() }
    }

    /** The compact corner pill: frames shown per second (over the last tick), latency, quality, ping. */
    private fun updateHud() {
        val p = pipeline
        val now = android.os.SystemClock.elapsedRealtime()
        val presented = p?.framesPresented?.get() ?: 0L
        if (lastPresentedAt > 0 && now > lastPresentedAt && presented >= lastPresented) {
            val fps = (presented - lastPresented) * 1000.0 / (now - lastPresentedAt)
            // A little smoothing so the number doesn't flicker between 59 and 61.
            shownFps = if (shownFps.isNaN()) fps else shownFps * 0.6 + fps * 0.4
        }
        lastPresented = presented
        lastPresentedAt = now
        val r = receiver
        r?.clock(clock)
        val rtt = if (r != null && clock[0] == 1L) clock[2] / 1000.0 else Double.NaN
        val latency = p?.latency?.last?.totalAvgMs ?: Double.NaN
        perfHud.update(shownFps, latency, (r?.videoKbps() ?: 0) / 1000.0, rtt)
    }

    /** Live line for the menu: latency, codec and quality of the stream. */
    private fun streamLine(): String {
        val p = pipeline
        val latency = p?.latency?.last?.totalAvgMs?.takeIf { !it.isNaN() }?.let { "%.0f ms".format(it) } ?: "— ms"
        val codec = p?.decoderName?.substringBefore(" · ")?.takeIf { it == "HEVC" || it == "H.264" } ?: "vídeo"
        // What actually arrives: the PC lowers it by itself when the Wi-Fi struggles.
        val kbps = receiver?.videoKbps() ?: 0
        val quality = if (kbps > 0) "%.0f Mbit/s (automático)".format(kbps / 1000.0) else "qualidade automática"
        return "Latência $latency  ·  1080p60  ·  $codec  ·  $quality"
    }

    private fun showMenu() {
        if (menu.isOpen) return
        val game = sessionGame
        val host = homeHost
        menu.open(sessionTitle.ifEmpty { "Jinky" }, streamLine(), canClose = !sessionDesktop,
            stats = statsMode, mouse = mouseMode, game = game, art = host?.let { it.ip to it.updatePort })
        gamepads?.menuOpen = true
        // The achievements the user follows in this game, once the PC answers.
        if (game != null && host != null && settings.tracked(game.id).isNotEmpty()) Thread {
            val list = try { AchievementsApi.fetch(host.ip, host.updatePort, game.id) } catch (e: Exception) { null }
            val ids = settings.tracked(game.id)
            val items = list?.items.orEmpty().filter { it.id in ids }
            ui.post { if (menu.isOpen) menu.showTracked(items) }
        }.start()
    }

    private fun hideMenu() {
        menu.close()
        updateUiInput()
    }

    @Deprecated("Back opens/closes the menu instead of leaving")
    override fun onBackPressed() {
        // Pairing a PC ("Parear um PC"): Back gives up and returns to the home screen.
        if (pairingOnly) {
            pairingOnly = false
            pairingPc = 0L
            receiver?.disconnect()
            startScreen.setShown(false)
            showPage(Page.HOME)
            return
        }
        // The keyboard panel (game menu "Teclado"): Back closes it (the TV keyboard closes first).
        if (keyboard.isOpen) {
            keyboard.close()
            updateUiInput()
            return
        }
        // On the home screen Back leaves the app; settings/help go back one step; while playing it
        // opens/closes the menu.
        if (capturesScreen.visibility == View.VISIBLE) { if (!capturesScreen.closeViewer()) showPage(Page.PROFILE) }
        else if (friendsScreen.visibility == View.VISIBLE) { if (!friendsScreen.backToList()) showPage(friendsOrigin) }
        else if (profileScreen.visibility == View.VISIBLE || pcPicker.visibility == View.VISIBLE) showPage(Page.HOME)
        else if (achievementsScreen.visibility == View.VISIBLE) { if (!achievementsScreen.closeDetail()) closeAchievements() }
        else if (gameScreen.visibility == View.VISIBLE) { if (!gameScreen.closeViewer()) showPage(gameOrigin) }
        else if (helpScreen.visibility == View.VISIBLE) showPage(Page.SETTINGS)
        else if (libraryScreen.visibility == View.VISIBLE) showPage(Page.HOME)
        else if (settingsScreen.visibility == View.VISIBLE) showPage(Page.HOME)
        else if (homeScreen.visibility == View.VISIBLE) finish()
        else if (menu.isOpen) hideMenu() else showMenu()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // The Fire TV remote has a Menu (☰) key; it opens the Jinky menu like Back does.
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            if (menu.isOpen) hideMenu() else showMenu()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun buildOverlay(): String = buildString {
        appendLine("Jinky — estatísticas (Voltar ou View+Menu no controle abre o menu)")
        appendLine()
        startError?.let { appendLine("ERRO de rede: $it") }
        receiver?.let { appendLine(it.status()) }
        val p = pipeline
        if (p == null) {
            appendLine("decodificador: aguardando superfície de vídeo")
        } else {
            appendLine()
            appendLine("etapa: ${stageText(p.stage)}")
            appendLine("decodificador: ${p.decoderName}")
            appendLine(latencyText(p.latency.last))
            gamepads?.let {
                appendLine("controles: ${it.summary}")
                if (it.lagSummary.isNotEmpty()) appendLine(it.lagSummary)
            }
            audio?.let { a ->
                val out = if (a.outputLatencyMs.isNaN()) "?" else "%.0f".format(a.outputLatencyMs)
                appendLine("áudio: saída da TV $out ms · falhas na saída ${a.deviceUnderruns}" + (a.error?.let { " · $it" } ?: ""))
            }
            appendLine(
                "quadros  enviados ao decodificador: ${p.framesQueued.get()}  " +
                    "decodificados: ${p.framesDecoded.get()}  pulados (atrasados): ${p.framesSkipped.get()}  " +
                    "exibidos: ${p.framesPresented.get()}"
            )
            if (p.recoveries > 0) appendLine("recuperações: ${p.recoveries}")
            p.lastError?.let { appendLine("último erro: $it") }
        }
        appendLine()
        append(hardwareReport)
    }

    private val clock = LongArray(3)

    private fun latencyText(s: LatencyTracker.Summary?): String {
        val r = receiver
        r?.clock(clock)
        val sync = if (r != null && clock[0] == 1L) "rede ida+volta %.1f ms".format(clock[2] / 1000.0) else "relógio não sincronizado"
        if (s == null || s.totalAvgMs.isNaN()) return "latência PC→TV: indisponível ($sync)"
        return "latência PC→TV: média %.1f ms (mín %.1f, máx %.1f) · decodificação+exibição %.1f ms · %s"
            .format(s.totalAvgMs, s.totalMinMs, s.totalMaxMs, s.localAvgMs, sync)
    }

    private fun stageText(s: VideoPipeline.Stage) = when (s) {
        VideoPipeline.Stage.NO_DECODER -> "sem decodificador"
        VideoPipeline.Stage.WAITING_FOR_VIDEO -> "aguardando vídeo do PC"
        VideoPipeline.Stage.DECODING -> "decodificando (exibição ainda não confirmada)"
        VideoPipeline.Stage.PRESENTING -> "✔ imagem sendo exibida"
        VideoPipeline.Stage.STALLED -> "⚠ travado — recuperando"
        VideoPipeline.Stage.ERROR -> "✖ erro"
    }

    companion object {
        private const val KEY_LOOK = "look_changed"
        private const val TAG = "Jinky"
        private const val HOST_PORT = 47100
        private const val HELLO_BITRATE_SHIFT = 16  // tclproto::kHelloBitrateShift
        private const val HELLO_SIZE_SHIFT = 24     // tclproto::kHelloSizeShift
        private const val HELLO_HDR = 1 shl 14       // tclproto::kHelloHdr
        private const val HELLO_RESUME = 1 shl 15    // tclproto::kHelloResume
        private const val HELLO_AUTO_PAUSE = 1 shl 26  // tclproto::kHelloAutoPause
        private const val TAILSCALE = "com.tailscale.ipn"
        private const val STATE_DISPLACED = 1
        private const val STATE_ENDED = 2
        private const val STATE_NEED_PAIRING = 3
        private const val STATE_PAIRING_CODE = 4
        private const val STATE_PAIRING_REJECTED = 5
        private const val END_GAME_CLOSED = 1  // tclproto::kEndGameClosed
        private const val GAME_NEEDS_ATTENTION = 1  // tclproto::kGameNeedsAttention
    }
}
