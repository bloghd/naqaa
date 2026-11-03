package com.downloader.video_downloader_app

import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CancellationException
import kotlin.math.log10
import kotlin.math.sqrt

// Mutes the pauses between speech: after the AI model, a faint trace of music can remain
// where nobody is speaking. Works on a 16-bit stereo 44.1 kHz WAV (44-byte header).
object VoiceGate {
    private const val TAG = "VoiceGate"
    private const val WINDOW = 441               // 10 ms
    private const val HOLD_WINDOWS = 20          // stay open 200 ms after speech
    private const val RELEASE_WINDOWS = 15       // fade out over about 150 ms
    private const val OPEN_DB_BELOW_REF = 24.0   // quieter than (speech level - 24 dB) = no speech
    private const val FULL_MUTE_EXTRA_DB = 8.0   // fully muted 8 dB below that
    private const val MIN_ACTIVE_DB = -60.0

    fun applyInPlace(file: File, isCancelled: () -> Boolean = { false }) {
        val frames = (file.length() - 44) / 4
        if (frames < WINDOW * 50L) return
        val windows = ((frames + WINDOW - 1) / WINDOW).toInt()
        val chunk = ByteArray(WINDOW * 4)

        // Pass 1: loudness of every 10 ms window (in dB)
        val levels = FloatArray(windows)
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(44)
            for (w in 0 until windows) {
                if (isCancelled()) throw CancellationException()
                val count = minOf(WINDOW.toLong(), frames - w.toLong() * WINDOW).toInt()
                raf.readFully(chunk, 0, count * 4)
                val bb = ByteBuffer.wrap(chunk, 0, count * 4).order(ByteOrder.LITTLE_ENDIAN)
                var sum = 0.0
                for (i in 0 until count) {
                    val l = bb.getShort() / 32768.0
                    val r = bb.getShort() / 32768.0
                    sum += (l * l + r * r) / 2.0
                }
                levels[w] = (20.0 * log10(sqrt(sum / count) + 1e-9)).toFloat()
            }
        }

        // Speech level = the 90th percentile of the non-silent windows
        val active = levels.filter { it > MIN_ACTIVE_DB }.sorted()
        if (active.size < 50) {
            Log.i(TAG, "skipped: not enough audio")
            return
        }
        val ref = active[(active.size * 0.9).toInt().coerceAtMost(active.size - 1)].toDouble()
        val threshold = ref - OPEN_DB_BELOW_REF
        val floor = threshold - FULL_MUTE_EXTRA_DB

        // Gate decision per window, with hold time
        val target = FloatArray(windows)
        var hold = 0
        for (w in 0 until windows) {
            val lv = levels[w]
            target[w] = when {
                lv >= threshold -> {
                    hold = HOLD_WINDOWS
                    1f
                }
                hold > 0 -> {
                    hold--
                    1f
                }
                lv <= floor -> 0f
                else -> ((lv - floor) / (threshold - floor)).toFloat()
            }
        }

        // Look ahead 20 ms so word beginnings are never clipped; open instantly, close smoothly
        val gains = FloatArray(windows)
        var g = 1f
        for (w in 0 until windows) {
            var t = target[w]
            for (k in 1..2) {
                if (w + k < windows && target[w + k] > t) t = target[w + k]
            }
            g = if (t > g) t else g + (t - g) / RELEASE_WINDOWS
            gains[w] = g
        }

        // Pass 2: apply the gain (interpolated between windows) and write a new file
        val temp = File(file.parentFile, file.name + ".gate")
        RandomAccessFile(file, "r").use { input ->
            RandomAccessFile(temp, "rw").use { out ->
                out.setLength(0)
                out.write(WavFile.header(frames * 4))
                input.seek(44)
                val outBuf = ByteBuffer.allocate(WINDOW * 4).order(ByteOrder.LITTLE_ENDIAN)
                for (w in 0 until windows) {
                    if (isCancelled()) throw CancellationException()
                    val count = minOf(WINDOW.toLong(), frames - w.toLong() * WINDOW).toInt()
                    input.readFully(chunk, 0, count * 4)
                    val src = ByteBuffer.wrap(chunk, 0, count * 4).order(ByteOrder.LITTLE_ENDIAN)
                    outBuf.clear()
                    val g0 = gains[w]
                    val g1 = gains[minOf(w + 1, windows - 1)]
                    for (i in 0 until count) {
                        val gain = g0 + (g1 - g0) * (i.toFloat() / WINDOW)
                        for (c in 0 until 2) {
                            val v = Math.round(src.getShort() * gain).coerceIn(-32768, 32767)
                            outBuf.putShort(v.toShort())
                        }
                    }
                    out.write(outBuf.array(), 0, count * 4)
                }
            }
        }
        file.delete()
        temp.renameTo(file)

        val mutedPercent = gains.count { it < 0.1f } * 100.0 / windows
        Log.i(
            TAG,
            "ref=${"%.1f".format(ref)} dB threshold=${"%.1f".format(threshold)} dB " +
                "muted=${"%.1f".format(mutedPercent)}% windows=$windows"
        )
    }
}
