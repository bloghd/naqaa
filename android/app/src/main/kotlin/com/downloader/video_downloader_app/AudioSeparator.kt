package com.downloader.video_downloader_app

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.ceil
import kotlin.math.sqrt

// Removes music from a WAV file on-device: keeps only the "vocals" stem of the HT-Demucs model.
// Works segment by segment (the model has a fixed 7.8 s window) and crossfades the overlaps.
object AudioSeparator {
    private const val TAG = "AudioSeparator"
    private const val SAMPLE_RATE = 44100
    private const val SEGMENT = 343980          // fixed by the model (7.8 s at 44.1 kHz)
    private const val OVERLAP = SEGMENT / 4     // frames crossfaded between neighbouring segments
    private const val STEP = SEGMENT - OVERLAP
    private const val VOCALS_INDEX = 3          // stems order: drums, bass, other, vocals

    private class WavInfo(
        val dataOffset: Long,
        val dataBytes: Long,
        val channels: Int,
        val bits: Int,
        val isFloat: Boolean,
        val sampleRate: Int
    )

    private fun readWavInfo(f: RandomAccessFile): WavInfo {
        val head = ByteArray(12)
        f.readFully(head)
        if (String(head, 0, 4, Charsets.US_ASCII) != "RIFF" ||
            String(head, 8, 4, Charsets.US_ASCII) != "WAVE"
        ) throw IllegalStateException("NOT_WAV")

        var channels = 0
        var bits = 0
        var rate = 0
        var isFloat = false
        while (true) {
            val h = ByteArray(8)
            if (f.read(h) < 8) throw IllegalStateException("NO_DATA_CHUNK")
            val id = String(h, 0, 4, Charsets.US_ASCII)
            val size = ByteBuffer.wrap(h, 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt().toLong() and 0xFFFFFFFFL
            val pos = f.filePointer
            if (id == "fmt ") {
                val fmt = ByteArray(minOf(size, 40L).toInt())
                f.readFully(fmt)
                val bb = ByteBuffer.wrap(fmt).order(ByteOrder.LITTLE_ENDIAN)
                var tag = bb.getShort().toInt() and 0xFFFF
                channels = bb.getShort().toInt()
                rate = bb.getInt()
                bb.getInt()    // byte rate
                bb.getShort()  // block align
                bits = bb.getShort().toInt()
                if (tag == 0xFFFE && fmt.size >= 26) {
                    tag = (fmt[24].toInt() and 0xFF) or ((fmt[25].toInt() and 0xFF) shl 8)
                }
                isFloat = tag == 3
            } else if (id == "data") {
                var bytes = size
                val remaining = f.length() - pos
                if (bytes == 0L || bytes > remaining) bytes = remaining
                return WavInfo(pos, bytes, channels, bits, isFloat, rate)
            }
            f.seek(pos + size + (size and 1L))
        }
    }

    // Reads `count` frames starting at frame `start` into planar floats [left..., right...].
    // Anything past the end of the file is zero-padded. Returns the number of real frames read.
    private fun readFrames(f: RandomAccessFile, info: WavInfo, start: Long, count: Int, out: FloatArray): Int {
        val frameBytes = (info.bits / 8) * info.channels
        val totalFrames = info.dataBytes / frameBytes
        val avail = (totalFrames - start).coerceIn(0L, count.toLong()).toInt()
        java.util.Arrays.fill(out, 0f)
        if (avail <= 0) return 0

        val buf = ByteArray(avail * frameBytes)
        f.seek(info.dataOffset + start * frameBytes)
        f.readFully(buf)
        val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until avail) {
            var left = 0f
            var right = 0f
            for (c in 0 until info.channels) {
                val v = when {
                    info.isFloat && info.bits == 32 -> bb.getFloat()
                    info.bits == 16 -> bb.getShort() / 32768f
                    else -> throw IllegalStateException("UNSUPPORTED_WAV")
                }
                if (c == 0) left = v
                if (c == 1) right = v
            }
            if (info.channels == 1) right = left
            out[i] = left
            out[count + i] = right
        }
        return avail
    }

    private fun toPcm16(v: Float): Short =
        Math.round(v.coerceIn(-1f, 1f) * 32767f).toShort()

    private fun wavHeader(dataBytes: Long): ByteArray {
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray(Charsets.US_ASCII))
        h.putInt((36 + dataBytes).toInt())
        h.put("WAVE".toByteArray(Charsets.US_ASCII))
        h.put("fmt ".toByteArray(Charsets.US_ASCII))
        h.putInt(16)
        h.putShort(1)
        h.putShort(2)
        h.putInt(SAMPLE_RATE)
        h.putInt(SAMPLE_RATE * 4)
        h.putShort(4)
        h.putShort(16)
        h.put("data".toByteArray(Charsets.US_ASCII))
        h.putInt(dataBytes.toInt())
        return h.array()
    }

    /** Reads a 44.1 kHz WAV, writes a 44.1 kHz stereo 16-bit WAV that contains only the voice. */
    fun separateVocals(modelFile: File, input: File, output: File, isCancelled: () -> Boolean = { false }, onProgress: (Int) -> Unit) {
        val env = OrtEnvironment.getEnvironment()
        val startAll = System.currentTimeMillis()

        OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(2, 4))
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)

            val loadStart = System.currentTimeMillis()
            env.createSession(modelFile.absolutePath, options).use { session ->
                Log.i(TAG, "model loaded in ${System.currentTimeMillis() - loadStart} ms")

                RandomAccessFile(input, "r").use { raf ->
                    val info = readWavInfo(raf)
                    if (info.sampleRate != SAMPLE_RATE) throw IllegalStateException("BAD_RATE ${info.sampleRate}")
                    if (info.channels !in 1..2) throw IllegalStateException("BAD_CHANNELS ${info.channels}")

                    val frameBytes = (info.bits / 8) * info.channels
                    val totalFrames = info.dataBytes / frameBytes
                    if (totalFrames <= 0) throw IllegalStateException("EMPTY_AUDIO")
                    val chunkCount = if (totalFrames <= SEGMENT) 1
                    else 1 + ceil((totalFrames - SEGMENT).toDouble() / STEP).toInt()
                    Log.i(TAG, "frames=$totalFrames chunks=$chunkCount")

                    val planar = FloatArray(2 * SEGMENT)
                    val tail = FloatArray(2 * OVERLAP)
                    var written = 0L
                    var sumIn = 0.0
                    var sumOut = 0.0

                    RandomAccessFile(output, "rw").use { out ->
                        out.setLength(0)
                        out.write(ByteArray(44)) // header, patched at the end

                        for (i in 0 until chunkCount) {
                            val chunkStart = System.currentTimeMillis()
                            if (isCancelled()) throw java.util.concurrent.CancellationException()
                            val avail = readFrames(raf, info, i.toLong() * STEP, SEGMENT, planar)
                            val isFirst = i == 0
                            val isLast = i == chunkCount - 1
                            val emitFrames = if (isLast) avail else STEP

                            OnnxTensor.createTensor(
                                env, FloatBuffer.wrap(planar), longArrayOf(1, 2, SEGMENT.toLong())
                            ).use { tensor ->
                                session.run(mapOf("mix" to tensor)).use { result ->
                                    val stems = (result.get(0) as OnnxTensor).floatBuffer
                                    if (stems.remaining() < 8 * SEGMENT) {
                                        throw IllegalStateException("BAD_MODEL_OUTPUT")
                                    }
                                    val pcm = ByteBuffer.allocate(emitFrames * 4).order(ByteOrder.LITTLE_ENDIAN)
                                    val limit = if (isLast) emitFrames else SEGMENT
                                    val leftBase = (VOCALS_INDEX * 2) * SEGMENT
                                    val rightBase = (VOCALS_INDEX * 2 + 1) * SEGMENT

                                    for (t in 0 until limit) {
                                        var w = 1f
                                        if (!isFirst && t < OVERLAP) w = t.toFloat() / OVERLAP
                                        if (!isLast && t >= STEP) w = (SEGMENT - t).toFloat() / OVERLAP
                                        val l = stems.get(leftBase + t) * w
                                        val r = stems.get(rightBase + t) * w

                                        if (!isLast && t >= STEP) {
                                            // not written yet: kept to be blended into the next segment
                                            tail[t - STEP] = l
                                            tail[OVERLAP + (t - STEP)] = r
                                            continue
                                        }
                                        var outL = l
                                        var outR = r
                                        if (!isFirst && t < OVERLAP) {
                                            outL += tail[t]
                                            outR += tail[OVERLAP + t]
                                        }
                                        val inL = planar[t]
                                        val inR = planar[SEGMENT + t]
                                        sumIn += (inL * inL + inR * inR).toDouble()
                                        sumOut += (outL * outL + outR * outR).toDouble()
                                        pcm.putShort(toPcm16(outL))
                                        pcm.putShort(toPcm16(outR))
                                    }
                                    out.write(pcm.array(), 0, emitFrames * 4)
                                    written += emitFrames
                                }
                            }
                            Log.i(TAG, "chunk ${i + 1}/$chunkCount took ${System.currentTimeMillis() - chunkStart} ms")
                            onProgress(((i + 1) * 100) / chunkCount)
                        }

                        out.seek(0)
                        out.write(wavHeader(written * 4))
                    }

                    val rmsIn = sqrt(sumIn / (2.0 * maxOf(written, 1L)))
                    val rmsOut = sqrt(sumOut / (2.0 * maxOf(written, 1L)))
                    Log.i(TAG, "done: frames=$written rmsIn=$rmsIn rmsOut=$rmsOut totalMs=${System.currentTimeMillis() - startAll}")
                }
            }
        }
    }
}
