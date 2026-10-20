package com.tclstream.tv

import android.content.Context
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * PCs this TV has seen (name, last address, network adapter MAC), so the home screen can show a
 * PC that is off and wake it with Wake-on-LAN.
 */
class HostCache(context: Context) {
    private val prefs = context.getSharedPreferences("jinky_hosts", Context.MODE_PRIVATE)

    data class Entry(val id: Long, val name: String, val ip: String, val mac: ByteArray?)

    fun all(): List<Entry> = prefs.all.mapNotNull { (k, v) ->
        val parts = (v as? String)?.split('|') ?: return@mapNotNull null
        if (parts.size < 3) return@mapNotNull null
        Entry(java.lang.Long.parseUnsignedLong(k, 16), parts[0], parts[1], parseMac(parts[2]))
    }

    fun remember(id: Long, name: String, ip: String, macHex: String) {
        val value = "$name|$ip|$macHex"
        val key = java.lang.Long.toHexString(id)
        if (prefs.getString(key, null) != value) prefs.edit().putString(key, value).apply()
    }

    companion object {
        fun parseMac(hex: String): ByteArray? {
            if (hex.length != 12) return null
            val b = ByteArray(6) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
            return if (b.all { it == 0.toByte() }) null else b
        }

        /**
         * Wake-on-LAN "magic packet" (6 x 0xFF + 16 x MAC) to the broadcast addresses, a few times.
         * Works when the PC's network adapter has wake enabled; over Wi-Fi usually only from sleep.
         */
        fun wake(mac: ByteArray, lastIp: String) {
            Thread {
                val packet = ByteArray(6) { 0xFF.toByte() } + ByteArray(16 * 6) { mac[it % 6] }
                val targets = buildList {
                    add("255.255.255.255")
                    if (lastIp.count { it == '.' } == 3) add(lastIp.substringBeforeLast('.') + ".255")  // home /24
                }
                try {
                    DatagramSocket().use { s ->
                        s.broadcast = true
                        repeat(3) {
                            for (t in targets) for (port in listOf(9, 7)) {
                                s.send(DatagramPacket(packet, packet.size, InetAddress.getByName(t), port))
                            }
                            Thread.sleep(200)
                        }
                    }
                    Log.i("Jinky", "wake-on-lan sent to ${targets.joinToString()}")
                } catch (e: Exception) {
                    Log.w("Jinky", "wake-on-lan failed: ${e.message}")
                }
            }.start()
        }
    }
}
