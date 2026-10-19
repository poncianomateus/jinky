package com.tclstream.tv

/**
 * Collects per-frame latency samples and summarises them once per second.
 *
 * total  = PC presented the image on the desktop → this TV composited the decoded frame.
 * local  = frame handed to the decoder on the TV → composited (decode + display pipeline).
 * Neither includes the TV panel's own processing; that is why Game Mode matters.
 */
class LatencyTracker {
    data class Summary(
        val totalAvgMs: Double,
        val totalMinMs: Double,
        val totalMaxMs: Double,
        val localAvgMs: Double,
        val samples: Int,
    )

    private val total = ArrayList<Long>()
    private val local = ArrayList<Long>()
    @Volatile var last: Summary? = null; private set

    @Synchronized
    fun add(totalUs: Long?, localUs: Long) {
        // Discard nonsense from clock sync not yet settled (negative or > 2 s).
        if (totalUs != null && totalUs in 0..2_000_000) total.add(totalUs)
        if (localUs in 0..2_000_000) local.add(localUs)
    }

    /** Called once per second; returns the window's summary (null if nothing was measured). */
    @Synchronized
    fun roll(): Summary? {
        if (total.isEmpty() && local.isEmpty()) return null
        val s = Summary(
            totalAvgMs = if (total.isEmpty()) Double.NaN else total.average() / 1000.0,
            totalMinMs = if (total.isEmpty()) Double.NaN else total.min() / 1000.0,
            totalMaxMs = if (total.isEmpty()) Double.NaN else total.max() / 1000.0,
            localAvgMs = if (local.isEmpty()) Double.NaN else local.average() / 1000.0,
            samples = total.size,
        )
        total.clear()
        local.clear()
        last = s
        return s
    }
}
