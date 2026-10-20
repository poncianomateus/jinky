package com.tclstream.tv

import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * "Enviar diagnóstico ao PC": the TV has nowhere handy to save a file, so its report (state,
 * hardware, this app's recent log) goes to the Jinky on the PC, which keeps it with its own logs.
 * Pairing keys are never logged, so the report carries no secrets.
 */
object Diagnostics {
    private const val TAG = "Jinky"

    /** Runs in the background; [done] gets a message for the user (on that background thread). */
    fun send(pcIp: String, port: Int, tvName: String, state: String, done: (String) -> Unit) {
        Thread {
            done(try {
                val body = (report(tvName, state)).toByteArray(Charsets.UTF_8)
                val url = URL("http://$pcIp:$port/tv/diagnostics?tv=" + URLEncoder.encode(tvName, "UTF-8"))
                val c = url.openConnection() as HttpURLConnection
                c.requestMethod = "POST"
                c.doOutput = true
                c.connectTimeout = 5000
                c.readTimeout = 15000
                c.setFixedLengthStreamingMode(body.size)
                c.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
                c.outputStream.use { it.write(body) }
                val ok = c.responseCode == 200
                c.disconnect()
                if (ok) "Diagnóstico enviado. No PC: Jinky → Abrir pasta de diagnóstico."
                else "O PC recusou o diagnóstico (código ${c.responseCode}). Atualize o Jinky no PC."
            } catch (e: Exception) {
                Log.w(TAG, "diagnostics: $e")
                "Não foi possível enviar ao PC: ${e.message}"
            })
        }.start()
    }

    private fun report(tvName: String, state: String) = buildString {
        appendLine("Jinky TV — diagnóstico")
        appendLine("TV: $tvName · versão ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine("data: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())}")
        appendLine()
        appendLine(state)
        appendLine()
        appendLine("--- registro do app (mais recente por último) ---")
        try {
            val p = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-v", "time", "-t", "4000",
                "--pid", android.os.Process.myPid().toString()))
            append(p.inputStream.bufferedReader().readText())
        } catch (e: Exception) {
            appendLine("registro indisponível: $e")
        }
    }
}
