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
    // A sixth of the app's memory (Fire TV: ~32 MB, TVs with more: up to 64 MB).
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 6).toInt().coerceIn(16 shl 20, 64 shl 20)) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val loading = HashSet<String>()
    private val failed = HashSet<String>()

    /** [done] runs on the main thread with the image, or null if the PC has none. */
    fun load(pcIp: String, port: Int, id: Int, kind: String, sample: Int, done: (Bitmap?) -> Unit) =
        loadUrl("http://$pcIp:$port/library/art?id=$id&kind=$kind", sample, done)

    /** Any image URL (store screenshots, trailer thumbnails), same cache. */
    fun loadUrl(url: String, sample: Int, done: (Bitmap?) -> Unit) {
        val key = "$url#$sample"
        cache.get(key)?.let { done(it); return }
        if (synchronized(failed) { key in failed }) { done(null); return }
        pool.execute {
            val bmp = try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 4000
                c.readTimeout = 8000
                val bytes = if (c.responseCode == 200) c.inputStream.use { it.readBytes() } else null
                c.disconnect()
                bytes?.let {
                    // Covers, backgrounds and photos have no transparency: 16-bit colour takes
                    // half the memory (twice as many fit in the cache) and draws faster. Logos
                    // keep their transparency.
                    val opaque = !url.contains("kind=logo") && !url.endsWith(".png")
                    BitmapFactory.decodeByteArray(it, 0, it.size, BitmapFactory.Options().apply {
                        inSampleSize = sample
                        inPreferredConfig = if (opaque) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888
                        inDither = opaque
                    })
                }
            } catch (e: Exception) {
                null
            }
            if (bmp != null) cache.put(key, bmp) else synchronized(failed) { failed += key }
            main.post { done(bmp) }
        }
    }

    /** Fetches an image into the cache ahead of time (the next game's background), if not there yet. */
    fun prefetch(pcIp: String, port: Int, id: Int, kind: String, sample: Int) {
        val url = "http://$pcIp:$port/library/art?id=$id&kind=$kind"
        val key = "$url#$sample"
        if (cache.get(key) != null || synchronized(failed) { key in failed }) return
        synchronized(loading) { if (!loading.add(key)) return }
        loadUrl(url, sample) { synchronized(loading) { loading.remove(key) } }
    }

    /** Forget missing-art answers (e.g. after the PC was updated). */
    fun reset() = synchronized(failed) { failed.clear() }
}
