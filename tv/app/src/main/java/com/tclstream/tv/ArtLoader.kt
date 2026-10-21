package com.tclstream.tv

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Game artwork from the PC (covers, backgrounds, logos), fetched in the background and kept in a
 * small memory cache so moving back and forth along the home row doesn't reload anything.
 */
object ArtLoader {
    private val main = Handler(Looper.getMainLooper())
    private val pool = Executors.newFixedThreadPool(3)
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val failed = HashSet<String>()

    /** [done] runs on the main thread with the image, or null if the PC has none. */
    fun load(pcIp: String, port: Int, id: Int, kind: String, sample: Int, done: (Bitmap?) -> Unit) {
        val key = "$pcIp/$id/$kind/$sample"
        cache.get(key)?.let { done(it); return }
        if (synchronized(failed) { key in failed }) { done(null); return }
        pool.execute {
            val bmp = try {
                val c = URL("http://$pcIp:$port/library/art?id=$id&kind=$kind").openConnection() as HttpURLConnection
                c.connectTimeout = 4000
                c.readTimeout = 8000
                val bytes = if (c.responseCode == 200) c.inputStream.use { it.readBytes() } else null
                c.disconnect()
                bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size, BitmapFactory.Options().apply { inSampleSize = sample }) }
            } catch (e: Exception) {
                null
            }
            if (bmp != null) cache.put(key, bmp) else synchronized(failed) { failed += key }
            main.post { done(bmp) }
        }
    }

    /** Forget missing-art answers (e.g. after the PC was updated). */
    fun reset() = synchronized(failed) { failed.clear() }
}
