package com.tclstream.tv

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build

/** What this TV / Fire Stick can actually decode, read from MediaCodec rather than assumed. */
object HardwareInfo {
    data class Decoder(
        val name: String,
        val mime: String,
        val hardware: Boolean,
        val lowLatency: Boolean,
        val maxWidth: Int,
        val maxHeight: Int,
        val supports1080p60: Boolean,
        val supports4k60: Boolean,
    )

    fun device(): String =
        "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})" +
            " · SoC ${socName()}"

    private fun socName(): String =
        if (Build.VERSION.SDK_INT >= 31) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else Build.HARDWARE

    fun isHardware(info: MediaCodecInfo): Boolean =
        if (Build.VERSION.SDK_INT >= 29) {
            info.isHardwareAccelerated
        } else {
            val n = info.name.lowercase()
            !(n.startsWith("omx.google.") || n.startsWith("c2.android."))
        }

    fun decoders(mime: String): List<Decoder> =
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { !it.isEncoder && it.supportedTypes.any { t -> t.equals(mime, ignoreCase = true) } }
            .map { info ->
                val caps = info.getCapabilitiesForType(mime)
                val video = caps.videoCapabilities
                Decoder(
                    name = info.name,
                    mime = mime,
                    hardware = isHardware(info),
                    lowLatency = Build.VERSION.SDK_INT >= 30 &&
                        caps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency),
                    maxWidth = video.supportedWidths.upper,
                    maxHeight = video.supportedHeights.upper,
                    supports1080p60 = video.areSizeAndRateSupported(1920, 1080, 60.0),
                    supports4k60 = video.areSizeAndRateSupported(3840, 2160, 60.0),
                )
            }

    /** First hardware decoder for [mime], or null if the device only has software decoders. */
    fun bestDecoder(mime: String): Decoder? = decoders(mime).firstOrNull { it.hardware }

    fun report(): String = buildString {
        appendLine(device())
        for (mime in listOf("video/avc", "video/hevc", "video/av01")) {
            val list = decoders(mime)
            if (list.isEmpty()) {
                appendLine("$mime: nenhum decodificador")
                continue
            }
            for (d in list) {
                append("$mime: ${d.name} ")
                append(if (d.hardware) "[HW]" else "[SW]")
                append(" máx ${d.maxWidth}x${d.maxHeight}")
                append(" 1080p60=${if (d.supports1080p60) "sim" else "não"}")
                append(" 4K60=${if (d.supports4k60) "sim" else "não"}")
                appendLine(" baixa-latência=${if (d.lowLatency) "sim" else "não"}")
            }
        }
    }.trimEnd()
}
