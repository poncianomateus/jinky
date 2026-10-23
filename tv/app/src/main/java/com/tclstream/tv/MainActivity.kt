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
    private lateinit var startScreen: StartScreen
    private lateinit var homeScreen: HomeScreen
    private lateinit var settingsScreen: SettingsScreen
    private lateinit var helpScreen: HelpScreen
    private lateinit var libraryScreen: LibraryScreen
    private lateinit var gameScreen: GameScreen
    private var gameOrigin = Page.HOME  // where Back returns from a game's page
    private lateinit var launchScreen: LaunchScreen
    private lateinit var hint: TextView
    private var launching: LibraryGame? = null  // a game starting on the PC, picture not revealed yet
    private var launchedAt = 0L
    private var gamesFrom: String? = null  // PC whose games the home row shows ("ip:port")
    private val settings by lazy { TvSettings(this) }

    /** Which of the TV's own screens is up while no game is on. */
    private enum class Page { HOME, SETTINGS, HELP, LIBRARY, GAME }
    private var page = Page.HOME
    private var homeHost: HomeHost? = null  // the PC the home screen shows
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
                statusText.visibility = if (inSession && settings.showStats) View.VISIBLE else View.GONE
                if (!inSession) gamesFrom = null  // "last played" changed: reload the home row
            }
            if (inSession) {
                showPage(null)
                updateStartScreen()
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
            // Same diagnostics in logcat every 5 s, for remote debugging over adb.
            if (++ticks % 10 == 0) Log.i(TAG, "status: " + text.lines().filter { it.startsWith("áudio") }.joinToString(" | "))
            ui.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Ask the TV for its low-latency mode (HDMI ALLM / Game Mode): skips picture post-processing.
        if (Build.VERSION.SDK_INT >= 30) {
            window.attributes = window.attributes.also { it.preferMinimalPostProcessing = true }
        }

        surfaceView = SurfaceView(this).also { it.holder.addCallback(this) }
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
            homeScreen = HomeScreen(this@MainActivity,
                onPlayGame = { _, game -> openGame(game, Page.HOME) },
                onPlay = { host, desktop ->
                    sessionTitle = when {
                        host.status == HomeHost.Status.BUSY -> "Jogo em andamento"
                        desktop -> "Área de trabalho"
                        else -> "Steam Big Picture"
                    }
                    sessionDesktop = desktop
                    receiver?.connect(host.ip, desktop)
                    homeScreen.visibility = View.GONE
                    startScreen.setShown(true)
                },
                onWake = { host ->
                    host.mac?.let { HostCache.wake(it, host.ip) }
                    wakingUntil = android.os.SystemClock.elapsedRealtime() + 60_000
                },
                onUpdate = { host -> updater.update(host.ip, host.updatePort) },
                onSettings = { showPage(Page.SETTINGS) },
                onLibrary = { host ->
                    showPage(Page.LIBRARY)
                    libraryScreen.load(host.ip, host.updatePort)
                })
            updater.register()
            addView(homeScreen, FrameLayout.LayoutParams(-1, -1))
            settingsScreen = SettingsScreen(this@MainActivity, settings,
                onChanged = { receiver?.setCodecSupport(helloCapabilities()) },
                onSendDiagnostics = ::sendDiagnostics,
                onForgetPc = ::forgetPc,
                onHelp = { showPage(Page.HELP) }).apply { visibility = View.GONE }
            addView(settingsScreen, FrameLayout.LayoutParams(-1, -1))
            helpScreen = HelpScreen(this@MainActivity) { showPage(Page.SETTINGS) }.apply { visibility = View.GONE }
            addView(helpScreen, FrameLayout.LayoutParams(-1, -1))
            libraryScreen = LibraryScreen(this@MainActivity,
                onPlay = { game -> openGame(game, Page.LIBRARY) },
                onInstall = { game -> openGame(game, Page.LIBRARY) },
                onBack = { showPage(Page.HOME) }).apply { visibility = View.GONE }
            addView(libraryScreen, FrameLayout.LayoutParams(-1, -1))
            gameScreen = GameScreen(this@MainActivity,
                onPlay = { game -> homeHost?.let { playGame(it, game) } },
                onInstall = { game -> homeHost?.let { installGame(it, game) } }).apply { visibility = View.GONE }
            addView(gameScreen, FrameLayout.LayoutParams(-1, -1))
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
            menu = GameMenu(this@MainActivity,
                onResume = { hideMenu() },
                onCloseGame = {
                    // The PC closes the game in front; a game session then ends by itself.
                    receiver?.closeGame()
                    hideMenu()
                },
                onToggleStats = {
                    statusText.visibility = if (statusText.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                    hideMenu()
                },
                onHome = {
                    // Ends the session (the PC goes back to normal) and shows the home screen.
                    stopLaunching()
                    receiver?.disconnect()
                    hideMenu()
                },
                onExit = { finish() })
            addView(menu, FrameLayout.LayoutParams(-1, -1))
        })
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
    private fun helloCapabilities(): Int {
        // Codecs this TV decodes in hardware at 1080p60 (the PC prefers HEVC).
        var codecs = 0
        if (HardwareInfo.bestDecoder(VideoPipeline.MIME_AVC) != null) codecs = codecs or 1
        if (!settings.forceH264 && HardwareInfo.bestDecoder(VideoPipeline.MIME_HEVC)?.supports1080p60 == true) codecs = codecs or 2
        return codecs or (settings.bitrateMbps shl HELLO_BITRATE_SHIFT)
    }

    private fun startReceiver() {
        val r = NativeReceiver()
        r.setCodecSupport(helloCapabilities())
        r.setDeviceName(deviceName())
        r.setIdentity(pairingStore.deviceId())
        pairingStore.hosts().forEach { (host, key) -> r.addPairedHost(host, key) }
        hostCache.all().forEach { r.addKnownAddress(it.ip) }
        // Android emulator on the PC (for trying the screens there): the PC is 10.0.2.2, which
        // broadcast discovery can't reach.
        if (Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish") r.addKnownAddress("10.0.2.2")
        pairing = null
        pairingCode = null
        startError = r.start(HOST_PORT)
        searchStartMs = android.os.SystemClock.elapsedRealtime()
        receiver = r
        audio = AudioPlayer(r).also { it.start() }
        gamepads = GamepadInput(this, r) { ui.post { showMenu() } }.also { it.start() }
        if (surfaceView.holder.surface?.isValid == true) startPipeline(surfaceView.holder)
        ui.post(refresh)
    }

    override fun onPause() {
        runCatching { wifiLock?.release() }
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
            else -> null
        }
        if (p != null) page = p
        homeScreen.visibility = if (p == Page.HOME) View.VISIBLE else View.GONE
        settingsScreen.visibility = if (p == Page.SETTINGS) View.VISIBLE else View.GONE
        helpScreen.visibility = if (p == Page.HELP) View.VISIBLE else View.GONE
        libraryScreen.visibility = if (p == Page.LIBRARY) View.VISIBLE else View.GONE
        gameScreen.visibility = if (p == Page.GAME) View.VISIBLE else View.GONE
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
            }
            view.alpha = 0f
            view.animate().alpha(1f).setDuration(220).start()
            if (p == Page.HOME) homeScreen.animateIn()
        }
        if (p != null && p != before) when (p) {
            Page.HOME -> homeScreen.focusFirst()
            Page.SETTINGS -> settingsScreen.focusFirst()
            Page.HELP -> helpScreen.focusFirst()
            Page.LIBRARY -> libraryScreen.focusFirst()
            Page.GAME -> gameScreen.focusFirst()
        }
        updateUiInput()
    }

    /** A game's page (artwork, details, screenshots, trailers; Jogar / Instalar from there). */
    private fun openGame(game: LibraryGame, from: Page) {
        val host = homeHost ?: return
        if (host.status != HomeHost.Status.READY) return
        gameOrigin = from
        showPage(Page.GAME)
        gameScreen.show(game, host.ip, host.updatePort)
        gameScreen.focusFirst()
    }

    /**
     * A game from the TV's own library: a console session without Big Picture. The TV shows the
     * game's artwork until the PC says the game is on screen; closing the game brings the TV back
     * to its home screen.
     */
    private fun playGame(host: HomeHost, game: LibraryGame) {
        if (host.status != HomeHost.Status.READY) return
        launching = game
        launchedAt = android.os.SystemClock.elapsedRealtime()
        sessionTitle = game.name
        sessionDesktop = false
        showPage(Page.HOME)
        homeScreen.visibility = View.GONE
        launchScreen.show(game, host.ip, host.updatePort)
        audio?.muted = true  // Big Picture opens behind the launch screen: not a sound of it
        receiver?.connect(host.ip, false, game = true)
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
        receiver?.connect(host.ip, false)
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
            ui.post { homeScreen.setGames(games, host.ip, host.updatePort) }
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
        val ids = pairingStore.hosts().keys
        if (ids.isEmpty()) {
            settingsScreen.setNote("Esta TV não está pareada com nenhum PC.")
            return
        }
        ids.forEach { pairingStore.remove(it) }
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
        android.provider.Settings.Global.getString(contentResolver, "device_name")?.takeIf { it.isNotBlank() }
            ?: "${Build.MANUFACTURER} ${Build.MODEL}"

    override fun dispatchGenericMotionEvent(event: android.view.MotionEvent): Boolean =
        gamepads?.onMotionEvent(event) == true || super.dispatchGenericMotionEvent(event)

    // --- in-stream menu: View + Menu held on the controller, or Back on the TV remote ---

    /** Home screen: the PC this TV knows, live from discovery, else from the cache (to wake it). */
    private fun updateHome() {
        val now = android.os.SystemClock.elapsedRealtime()
        startScreen.setShown(false)
        showPage(page)
        val online = receiver?.hosts().orEmpty().lines().mapNotNull { line ->
            val p = line.split('|')
            if (p.size < 7) return@mapNotNull null
            val id = java.lang.Long.parseUnsignedLong(p[0], 16)
            val age = p[4].toLongOrNull() ?: Long.MAX_VALUE
            hostCache.remember(id, p[5], p[1], p[3])
            if (age > 6000) return@mapNotNull null  // not answering any more
            HomeHost(id, p[5], p[1], if (p[2] == "1") HomeHost.Status.BUSY else HomeHost.Status.READY, p[6],
                HostCache.parseMac(p[3]), p.getOrNull(7)?.toIntOrNull() ?: 0, p.getOrNull(8)?.toIntOrNull() ?: 0)
        }
        val host = online.minByOrNull { if (it.status == HomeHost.Status.READY) 0 else 1 }
            ?: hostCache.all().firstOrNull()?.let { HomeHost(it.id, it.name, it.ip, HomeHost.Status.OFFLINE, "", it.mac) }
        if (host?.status != HomeHost.Status.OFFLINE) wakingUntil = 0L
        homeHost = host
        lastPictureMs = 0L
        host?.let(::loadGames)
        homeScreen.show(host, searching = true, waking = now < wakingUntil, updateStatus = updater.status)
    }

    /** The controller drives the TV's own screens (home, menu); during play it drives the PC. */
    private fun updateUiInput() {
        val ui = homeScreen.visibility == View.VISIBLE || settingsScreen.visibility == View.VISIBLE ||
            helpScreen.visibility == View.VISIBLE || libraryScreen.visibility == View.VISIBLE ||
            gameScreen.visibility == View.VISIBLE ||
            menu.isOpen
        if (gamepads?.menuOpen != ui) gamepads?.menuOpen = ui
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
                if (ready == GAME_NEEDS_ATTENTION) showHint("O jogo pediu uma confirmação: use o controle.")
            } else {
                launchScreen.setStatus(if (host == null) "Conectando ao PC…" else "Abrindo o jogo…")
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
        menu.open(sessionTitle.ifEmpty { "Jinky" }, homeHost?.name ?: (receiver?.hostAddress() ?: ""), streamLine(),
            canClose = !sessionDesktop, statsShown = statusText.visibility == View.VISIBLE)
        gamepads?.menuOpen = true
    }

    private fun hideMenu() {
        menu.close()
        updateUiInput()
    }

    @Deprecated("Back opens/closes the menu instead of leaving")
    override fun onBackPressed() {
        // On the home screen Back leaves the app; settings/help go back one step; while playing it
        // opens/closes the menu.
        if (gameScreen.visibility == View.VISIBLE) { if (!gameScreen.closeViewer()) showPage(gameOrigin) }
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
        private const val TAG = "Jinky"
        private const val HOST_PORT = 47100
        private const val HELLO_BITRATE_SHIFT = 16  // tclproto::kHelloBitrateShift
        private const val STATE_DISPLACED = 1
        private const val STATE_ENDED = 2
        private const val STATE_NEED_PAIRING = 3
        private const val STATE_PAIRING_CODE = 4
        private const val STATE_PAIRING_REJECTED = 5
        private const val END_GAME_CLOSED = 1  // tclproto::kEndGameClosed
        private const val GAME_NEEDS_ATTENTION = 1  // tclproto::kGameNeedsAttention
    }
}
