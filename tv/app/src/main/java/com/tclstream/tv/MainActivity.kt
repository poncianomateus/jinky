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
    private val hostCache by lazy { HostCache(this) }
    private var wakingUntil = 0L
    private val updater by lazy { Updater(this) }
    private var searchStartMs = 0L
    private val pairingStore by lazy { PairingStore(this) }
    private var pairing: PairingSession? = null
    private var pairingCode: String? = null
    private var receiver: NativeReceiver? = null
    private var pipeline: VideoPipeline? = null
    private var audio: AudioPlayer? = null
    private var gamepads: GamepadInput? = null
    private lateinit var menu: android.widget.LinearLayout
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
            if (receiver?.inSession() == true) {
                homeScreen.visibility = View.GONE
                updateStartScreen()
            } else {
                updateHome()
            }
            updateUiInput()
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
                onPlay = { host, desktop ->
                    receiver?.connect(host.ip, desktop)
                    homeScreen.visibility = View.GONE
                    startScreen.setShown(true)
                },
                onWake = { host ->
                    host.mac?.let { HostCache.wake(it, host.ip) }
                    wakingUntil = android.os.SystemClock.elapsedRealtime() + 60_000
                },
                onUpdate = { host -> updater.update(host.ip, host.updatePort) })
            updater.register()
            addView(homeScreen, FrameLayout.LayoutParams(-1, -1))
            // 5% margins keep text inside the TV's safe area.
            val margin = (resources.displayMetrics.widthPixels * 0.05).toInt()
            val statusParams = FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START)
            statusParams.setMargins(margin, margin, margin, margin)
            addView(statusText, statusParams)
            menu = buildMenu()
            addView(menu, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
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

    override fun onResume() {
        super.onResume()
        val r = NativeReceiver()
        // Tell the PC which codecs this TV decodes in hardware at 1080p60 (it prefers HEVC).
        var codecs = 0
        if (HardwareInfo.bestDecoder(VideoPipeline.MIME_AVC) != null) codecs = codecs or 1
        if (HardwareInfo.bestDecoder(VideoPipeline.MIME_HEVC)?.supports1080p60 == true) codecs = codecs or 2
        r.setCodecSupport(codecs)
        r.setDeviceName(deviceName())
        r.setIdentity(pairingStore.deviceId())
        pairingStore.hosts().forEach { (host, key) -> r.addPairedHost(host, key) }
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
        // TV going to standby / user leaving: release decoder and network, start clean on resume.
        ui.removeCallbacks(refresh)
        stopPipeline()
        audio?.stop()
        audio = null
        gamepads?.stop()  // tells the PC to unplug the virtual pads
        gamepads = null
        receiver?.stop()
        receiver?.close()
        receiver = null
        super.onPause()
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
        return gamepads?.onKeyEvent(event) == true || super.dispatchKeyEvent(event)
    }

    /** The name the user gave this TV (e.g. "Fire TV da sala"), or its model. */
    private fun deviceName(): String =
        android.provider.Settings.Global.getString(contentResolver, "device_name")?.takeIf { it.isNotBlank() }
            ?: "${Build.MANUFACTURER} ${Build.MODEL}"

    override fun dispatchGenericMotionEvent(event: android.view.MotionEvent): Boolean =
        gamepads?.onMotionEvent(event) == true || super.dispatchGenericMotionEvent(event)

    // --- in-stream menu: View + Menu held on the controller, or Back on the TV remote ---

    private fun buildMenu(): android.widget.LinearLayout {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        return android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(32), dp(24), dp(32), dp(24))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xF00B0F17.toInt())
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), 0x402F7BFF)
            }
            addView(TextView(context).apply {
                text = "Jinky"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                setPadding(0, 0, 0, dp(16))
            })
            fun item(label: String, action: () -> Unit) = addView(android.widget.Button(context).apply {
                text = label
                isAllCaps = false
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                setTextColor(Color.WHITE)
                minWidth = dp(320)
                background = android.graphics.drawable.StateListDrawable().apply {
                    val focused = android.graphics.drawable.GradientDrawable().apply {
                        setColor(0xFF2F7BFF.toInt()); cornerRadius = dp(10).toFloat()
                    }
                    val normal = android.graphics.drawable.GradientDrawable().apply {
                        setColor(0x22FFFFFF); cornerRadius = dp(10).toFloat()
                    }
                    addState(intArrayOf(android.R.attr.state_focused), focused)
                    addState(intArrayOf(), normal)
                }
                setOnClickListener { action() }
            }, android.widget.LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            item("Continuar jogando") { hideMenu() }
            item("Voltar ao início") {
                // Ends the session (the PC goes back to normal) and shows the home screen.
                receiver?.disconnect()
                hideMenu()
            }
            item("Mostrar/ocultar desempenho") {
                statusText.visibility = if (statusText.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                hideMenu()
            }
            item("Sair do Jinky") { finish() }
        }
    }

    /** Home screen: the PC this TV knows, live from discovery, else from the cache (to wake it). */
    private fun updateHome() {
        val now = android.os.SystemClock.elapsedRealtime()
        startScreen.setShown(false)
        homeScreen.visibility = View.VISIBLE
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
        homeScreen.show(host, searching = true, waking = now < wakingUntil, updateStatus = updater.status)
    }

    /** The controller drives the TV's own screens (home, menu); during play it drives the PC. */
    private fun updateUiInput() {
        val ui = homeScreen.visibility == View.VISIBLE || menu.visibility == View.VISIBLE
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
        if (picture) {
            startScreen.setShown(false)
            return
        }
        startScreen.setShown(true)
        val model = when {
            startError != null -> StartScreen.Model(
                "Sem acesso à rede", "A TV não conseguiu usar a rede: $startError",
                listOf("Rede da TV" to S.ACTIVE), warning = true)
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

    private fun showMenu() {
        if (menu.visibility == View.VISIBLE) return
        gamepads?.menuOpen = true
        menu.visibility = View.VISIBLE
        menu.getChildAt(1).requestFocus()
    }

    private fun hideMenu() {
        menu.visibility = View.GONE
        updateUiInput()
    }

    @Deprecated("Back opens/closes the menu instead of leaving")
    override fun onBackPressed() {
        // On the home screen Back leaves the app; while playing it opens/closes the menu.
        if (homeScreen.visibility == View.VISIBLE) finish()
        else if (menu.visibility == View.VISIBLE) hideMenu() else showMenu()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // The Fire TV remote has a Menu (☰) key; it opens the Jinky menu like Back does.
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            if (menu.visibility == View.VISIBLE) hideMenu() else showMenu()
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER ||
            keyCode == KeyEvent.KEYCODE_BUTTON_A
        ) {
            statusText.visibility = if (statusText.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun buildOverlay(): String = buildString {
        appendLine("Jinky — estatísticas (OK oculta · Voltar ou View+Menu no controle abre o menu)")
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
            gamepads?.let { appendLine("controles: ${it.summary}") }
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
        private const val STATE_DISPLACED = 1
        private const val STATE_ENDED = 2
        private const val STATE_NEED_PAIRING = 3
        private const val STATE_PAIRING_CODE = 4
        private const val STATE_PAIRING_REJECTED = 5
    }
}
