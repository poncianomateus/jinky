package com.tclstream.tv

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import kotlin.concurrent.thread
import kotlin.math.abs

/**
 * "Testar a rede": how good the path between this TV and the PC is for streaming, the question
 * Moonlight/Steam Link users keep asking. Measures the round trip to the PC 40 times (ping, its
 * variation = jitter, and losses), then how fast the PC can send data (downloads the TV app the PC
 * offers for 3 s), and reads how this TV is connected (cable or Wi-Fi band, signal, link speed).
 * Ends with a verdict and the quality ("Qualidade máxima") that fits.
 */
object NetworkTest {
    data class Link(val wired: Boolean, val wifiBand: String?, val signalDbm: Int?, val linkMbps: Int?)
    data class Result(
        val pingMs: Double, val jitterMs: Double, val lossPct: Double, val mbps: Double?, val link: Link?,
        val grade: Grade, val advice: String, val suggestedMbps: Int,
    )
    enum class Grade(val label: String, val color: Int) {
        GREAT("Excelente", Jinky.GREEN), GOOD("Boa", Jinky.GREEN), FAIR("Razoável", Jinky.AMBER), POOR("Fraca", Jinky.RED)
    }

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var running = false

    /** [progress] gets 0..1 and a line of what is being measured; [done] gets the result or an error. */
    fun run(context: Context, ip: String, port: Int, progress: (Float, String) -> Unit, done: (Result?, String?) -> Unit) {
        if (running) return
        running = true
        val link = link(context)
        thread(name = "network-test", isDaemon = true) {
            try {
                // 1. Round trips: a TCP handshake with the PC is answered by its network stack
                // right away, so it measures the network, not the PC's load.
                val samples = ArrayList<Double>()
                var lost = 0
                val total = 40
                for (i in 0 until total) {
                    val t0 = System.nanoTime()
                    try {
                        Socket().use { s -> s.tcpNoDelay = true; s.connect(InetSocketAddress(ip, port), 800) }
                        val ms = (System.nanoTime() - t0) / 1e6
                        // A handshake retried by the system (>~700 ms) means the first try was lost.
                        if (ms > 700) lost++ else samples += ms
                    } catch (e: Exception) {
                        lost++
                    }
                    if (i % 4 == 0) main.post { progress(i / total.toFloat() * 0.6f, "Medindo o ping… ${i + 1} de $total") }
                    Thread.sleep(60)
                }
                if (samples.isEmpty()) {
                    main.post { done(null, "o PC não respondeu. Ele está ligado e com o Jinky aberto?") }
                    return@thread
                }
                val sorted = samples.sorted()
                val ping = sorted[sorted.size / 2]  // median: one slow answer doesn't skew it
                val jitter = samples.zipWithNext { a, b -> abs(a - b) }.average().takeIf { !it.isNaN() } ?: 0.0
                val loss = lost * 100.0 / total

                // 2. Throughput: the PC sends the TV app; 3 s of it tells how fast the path is.
                main.post { progress(0.65f, "Medindo a velocidade…") }
                val mbps = try { download(ip, port) { f -> main.post { progress(0.65f + f * 0.35f, "Medindo a velocidade…") } } } catch (e: Exception) { null }

                val result = judge(ping, jitter, loss, mbps, link)
                main.post { done(result, null) }
            } finally {
                running = false
            }
        }
    }

    private fun download(ip: String, port: Int, progress: (Float) -> Unit): Double? {
        val c = URL("http://$ip:$port/tv/jinky.apk").openConnection() as HttpURLConnection
        c.connectTimeout = 3000
        c.readTimeout = 4000
        c.useCaches = false
        try {
            if (c.responseCode != 200) return null
            val buf = ByteArray(64 * 1024)
            var bytes = 0L
            val start = System.nanoTime()
            var firstAt = 0L
            c.inputStream.use { input ->
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (firstAt == 0L) firstAt = System.nanoTime()  // from the first byte: leaves the request time out
                    bytes += n
                    val elapsed = (System.nanoTime() - start) / 1e9
                    progress((elapsed / 3.0).toFloat().coerceAtMost(1f))
                    if (elapsed >= 3.0) break
                }
            }
            val seconds = (System.nanoTime() - firstAt) / 1e9
            if (bytes < 256 * 1024 || seconds <= 0) return null  // too little to say
            return bytes * 8 / seconds / 1e6
        } finally {
            c.disconnect()
        }
    }

    @Suppress("DEPRECATION")
    fun link(context: Context): Link? = try {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        when {
            caps == null -> null
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Link(true, null, null, null)
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
                val info = (if (Build.VERSION.SDK_INT >= 31) caps.transportInfo as? WifiInfo else null)
                    ?: context.applicationContext.getSystemService(WifiManager::class.java)?.connectionInfo
                val band = info?.frequency?.let { f ->
                    when {
                        f >= 5925 -> "6 GHz"
                        f >= 4900 -> "5 GHz"
                        f in 2400..2500 -> "2,4 GHz"
                        else -> null
                    }
                }
                Link(false, band, info?.rssi?.takeIf { it in -110..-1 }, info?.linkSpeed?.takeIf { it > 0 })
            }
            else -> null
        }
    } catch (e: Exception) {
        null
    }

    /** The verdict and the advice that helps most, in plain words. */
    private fun judge(ping: Double, jitter: Double, loss: Double, mbps: Double?, link: Link?): Result {
        val grade = when {
            loss >= 5 || ping > 40 || jitter > 15 || (mbps != null && mbps < 15) -> Grade.POOR
            loss >= 1 || ping > 15 || jitter > 6 || (mbps != null && mbps < 30) -> Grade.FAIR
            ping <= 5 && jitter <= 2 && (mbps == null || mbps >= 80) -> Grade.GREAT
            else -> Grade.GOOD
        }
        // Leave room: about 60% of what the path carries, among the TV's options.
        val fit = mbps?.let { it * 0.6 } ?: 30.0
        val suggested = TvSettings.BITRATES.filter { it > 0 && it <= fit }.maxOrNull() ?: TvSettings.BITRATES.first { it > 0 }
        val advice = when {
            link?.wifiBand == "2,4 GHz" ->
                "A TV está no Wi-Fi de 2,4 GHz, que é lento e sofre interferência. Conecte-a à rede de 5 GHz do roteador (ou use cabo)."
            link?.signalDbm != null && link.signalDbm < -70 ->
                "O sinal do Wi-Fi está fraco aqui (${link.signalDbm} dBm). Aproximar o roteador ou usar cabo deixa o jogo mais estável."
            loss >= 1 -> "Alguns pacotes se perderam: isso vira travadinhas. Cabo de rede na TV ou no PC resolve na maioria das vezes."
            jitter > 6 -> "O tempo de resposta varia muito (outros aparelhos usando a rede, ou o Wi-Fi do PC). Cabo no PC ajuda bastante."
            ping > 15 -> "A resposta está lenta para jogar. Verifique se o PC está no cabo e se a TV está perto do roteador."
            mbps != null && mbps < 30 -> "A velocidade até o PC está baixa. Use ${suggested} Mbit/s para não travar."
            grade == Grade.GREAT -> "Rede ótima para jogar. Pode deixar a qualidade no máximo."
            else -> "Rede boa para jogar. Se notar travadinhas, baixe a qualidade para ${suggested} Mbit/s."
        }
        return Result(ping, jitter, loss, mbps, link, grade, advice, suggested)
    }
}
