package com.tclstream.tv

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Updates the TV app from the PC: the Jinky PC app serves the TV app of its own version on the home
 * network. Downloads it, then hands it to Android's installer, which shows its own confirmation
 * (unavoidable for apps not installed from a store). Android only accepts it over this app if it is
 * signed with the same key, so a tampered file can't replace Jinky.
 */
class Updater(private val activity: Activity) {
    @Volatile var status: String? = null; private set
    @Volatile var busy = false; private set

    private val action = "${activity.packageName}.INSTALL_RESULT"
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    // Android's confirmation screen ("Install update?").
                    @Suppress("DEPRECATION")
                    (intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT))?.let {
                        it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        activity.startActivity(it)
                    }
                    status = "Confirme a atualização na tela"
                }
                PackageInstaller.STATUS_SUCCESS -> status = "Atualizado"  // the app restarts on its own
                else -> {
                    busy = false
                    status = "A atualização não foi instalada (${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "cancelada"})"
                }
            }
        }
    }

    fun register() {
        val filter = IntentFilter(action)
        if (Build.VERSION.SDK_INT >= 33) activity.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("UnspecifiedRegisterReceiverFlag") activity.registerReceiver(receiver, filter)
    }

    fun unregister() = runCatching { activity.unregisterReceiver(receiver) }

    /** Downloads the TV app from the PC at [ip]:[port] and starts the install. */
    fun update(ip: String, port: Int) {
        if (busy) return
        // Android 8+: the user must allow Jinky to install apps (once). Send them to that setting.
        if (Build.VERSION.SDK_INT >= 26 && !activity.packageManager.canRequestPackageInstalls()) {
            status = "Permita que o Jinky instale atualizações e aperte Atualizar de novo"
            activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")))
            return
        }
        busy = true
        status = "Baixando a atualização do PC…"
        Thread {
            try {
                val file = File(activity.cacheDir, "jinky-update.apk")
                val conn = URL("http://$ip:$port/tv/jinky.apk").openConnection() as HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 15000
                val total = conn.contentLengthLong
                conn.inputStream.use { input ->
                    file.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (total > 0) status = "Baixando a atualização do PC… ${done * 100 / total}%"
                        }
                    }
                }
                install(file)
            } catch (e: Exception) {
                Log.w("Jinky", "update failed", e)
                status = "Não foi possível baixar a atualização: ${e.message}"
                busy = false
            }
        }.start()
    }

    private fun install(apk: File) {
        status = "Instalando…"
        val installer = activity.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("jinky.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pi = PendingIntent.getBroadcast(activity, id, Intent(action).setPackage(activity.packageName), flags)
            session.commit(pi.intentSender)
        }
    }
}
