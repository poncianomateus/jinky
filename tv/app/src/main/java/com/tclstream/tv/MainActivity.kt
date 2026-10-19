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

/**
 * Proof-of-life screen: full-screen video plus a diagnostic overlay that shows which milestone
 * the stream has reached. OK toggles the overlay, Back exits. Not the final TclStream UI.
 */
class MainActivity : Activity(), SurfaceHolder.Callback {
    private lateinit var surfaceView: SurfaceView
    private lateinit var statusText: TextView
    private var receiver: NativeReceiver? = null
    private var pipeline: VideoPipeline? = null
    private var startError: String? = null
    private val hardwareReport by lazy { HardwareInfo.report() }

    // Test switches: adb shell am start -n com.tclstream.tv/.MainActivity --ez vendor_ll false
    //   --ez render_now true --ei display_hz 120 --ez surface_rate true
    private val tuning by lazy {
        VideoPipeline.Tuning(
            vendorLowLatency = intent.getBooleanExtra("vendor_ll", true),
            explicitRenderTime = intent.getBooleanExtra("render_now", true),
        )
    }
    private val displayHz by lazy { intent.getIntExtra("display_hz", 0) }
    private val surfaceRate by lazy { intent.getBooleanExtra("surface_rate", false) }
    private val ui = Handler(Looper.getMainLooper())

    private val refresh = object : Runnable {
        override fun run() {
            statusText.text = buildOverlay()
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
        }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(surfaceView, FrameLayout.LayoutParams(-1, -1))
            // 5% margins keep text inside the TV's safe area.
            val margin = (resources.displayMetrics.widthPixels * 0.05).toInt()
            val statusParams = FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START)
            statusParams.setMargins(margin, margin, margin, margin)
            addView(statusText, statusParams)
        })
        Log.i(TAG, "hardware:\n$hardwareReport")
        Log.i(TAG, "tuning: $tuning displayHz=$displayHz surfaceRate=$surfaceRate")
        if (displayHz > 0) selectRefreshRate(displayHz)
    }

    /** Asks for a display mode at [hz] with the current resolution, if the TV offers one. */
    private fun selectRefreshRate(hz: Int) {
        val display = windowManager.defaultDisplay
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
        startError = r.start(HOST_PORT)
        receiver = r
        if (surfaceView.holder.surface?.isValid == true) startPipeline(surfaceView.holder)
        ui.post(refresh)
    }

    override fun onPause() {
        // TV going to standby / user leaving: release decoder and network, start clean on resume.
        ui.removeCallbacks(refresh)
        stopPipeline()
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
        pipeline = VideoPipeline(r, holder.surface, tuning).also { it.start() }
    }

    private fun stopPipeline() {
        pipeline?.stop()
        pipeline = null
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER ||
            keyCode == KeyEvent.KEYCODE_BUTTON_A
        ) {
            statusText.visibility = if (statusText.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun buildOverlay(): String = buildString {
        appendLine("TclStream — teste de transmissão (OK oculta este painel)")
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
            appendLine(
                "quadros  enviados ao decodificador: ${p.framesQueued.get()}  " +
                    "decodificados: ${p.framesDecoded.get()}  exibidos: ${p.framesPresented.get()}"
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
        private const val TAG = "TclStream"
        private const val HOST_PORT = 47100
    }
}
