package com.downloader.video_downloader_app

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CancellationException

object WavFile {
    // 44-byte header for 16-bit stereo 44.1 kHz PCM
    fun header(dataBytes: Long): ByteArray {
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray(Charsets.US_ASCII))
        h.putInt((36 + dataBytes).toInt())
        h.put("WAVE".toByteArray(Charsets.US_ASCII))
        h.put("fmt ".toByteArray(Charsets.US_ASCII))
        h.putInt(16)
        h.putShort(1)
        h.putShort(2)
        h.putInt(44100)
        h.putInt(44100 * 4)
        h.putShort(4)
        h.putShort(16)
        h.put("data".toByteArray(Charsets.US_ASCII))
        h.putInt(dataBytes.toInt())
        return h.array()
    }
}

object MediaDecoder {
    private const val TARGET_RATE = 44100

    // Streaming linear resampler that writes 16-bit stereo PCM to a WAV file
    private class Resampler(output: File) {
        private val raf = RandomAccessFile(output, "rw")
        private val buf = ByteBuffer.allocate(64 * 1024).order(ByteOrder.LITTLE_ENDIAN)
        var frames = 0L
            private set
        private var step = 1.0
        private var nextOut = 0.0
        private var n = 0L
        private var prevL = 0f
        private var prevR = 0f

        init {
            raf.setLength(0)
            raf.write(ByteArray(44))
        }

        fun setInputRate(rate: Int) {
            step = rate.toDouble() / TARGET_RATE
        }

        fun feed(l: Float, r: Float) {
            if (n == 0L) {
                put(l, r)
                nextOut += step
            } else {
                while (nextOut <= n.toDouble()) {
                    val f = (nextOut - (n - 1)).toFloat()
                    put(prevL + (l - prevL) * f, prevR + (r - prevR) * f)
                    nextOut += step
                }
            }
            prevL = l
            prevR = r
            n++
        }

        private fun pcm(v: Float): Short = Math.round(v.coerceIn(-1f, 1f) * 32767f).toShort()

        private fun put(l: Float, r: Float) {
            if (buf.remaining() < 4) flush()
            buf.putShort(pcm(l))
            buf.putShort(pcm(r))
            frames++
        }

        private fun flush() {
            raf.write(buf.array(), 0, buf.position())
            buf.clear()
        }

        fun finish() {
            flush()
            raf.seek(0)
            raf.write(WavFile.header(frames * 4))
            raf.close()
        }

        fun abort() {
            try { raf.close() } catch (_: Throwable) {}
        }
    }

    private fun feedFrame(res: Resampler, f: FloatArray, channels: Int) {
        when {
            channels <= 1 -> res.feed(f[0], f[0])
            channels >= 6 -> {
                // 5.1 order: FL FR FC LFE BL BR
                val l = 0.5f * (f[0] + 0.707f * f[2] + 0.707f * f[4])
                val r = 0.5f * (f[1] + 0.707f * f[2] + 0.707f * f[5])
                res.feed(l, r)
            }
            else -> res.feed(f[0], f[1])
        }
    }

    /** Decodes the first audio track of [uri] into a 44.1 kHz stereo 16-bit WAV. Returns the frame count. */
    fun decodeToWav(
        context: Context,
        uri: Uri,
        output: File,
        isCancelled: () -> Boolean,
        onProgress: (Int) -> Unit
    ): Long {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var resampler: Resampler? = null
        try {
            extractor.setDataSource(context, uri, null)
            var track = -1
            var found: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if ((f.getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")) {
                    track = i
                    found = f
                    break
                }
            }
            if (track < 0 || found == null) throw IllegalStateException("NO_AUDIO_TRACK")
            val format: MediaFormat = found
            extractor.selectTrack(track)
            val durationUs =
                if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L

            val decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec = decoder
            decoder.configure(format, null, null, 0)
            decoder.start()

            var inChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var pcmFloat = false
            var frame = FloatArray(maxOf(inChannels, 2))
            val res = Resampler(output)
            resampler = res
            res.setInputRate(format.getInteger(MediaFormat.KEY_SAMPLE_RATE))

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                if (isCancelled()) throw CancellationException()

                if (!inputDone) {
                    val inIdx = decoder.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val inBuf = decoder.getInputBuffer(inIdx)!!
                        val size = extractor.readSampleData(inBuf, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIdx = decoder.dequeueOutputBuffer(info, 10_000)
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val of = decoder.outputFormat
                    inChannels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    frame = FloatArray(maxOf(inChannels, 2))
                    res.setInputRate(of.getInteger(MediaFormat.KEY_SAMPLE_RATE))
                    pcmFloat = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
                        of.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        of.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                } else if (outIdx >= 0) {
                    val out = decoder.getOutputBuffer(outIdx)!!
                    if (info.size > 0) {
                        out.position(info.offset)
                        out.limit(info.offset + info.size)
                        out.order(ByteOrder.nativeOrder())
                        if (pcmFloat) {
                            val fb = out.asFloatBuffer()
                            val frames = fb.remaining() / inChannels
                            for (i in 0 until frames) {
                                for (c in 0 until inChannels) frame[c] = fb.get()
                                feedFrame(res, frame, inChannels)
                            }
                        } else {
                            val sb = out.asShortBuffer()
                            val frames = sb.remaining() / inChannels
                            for (i in 0 until frames) {
                                for (c in 0 until inChannels) frame[c] = sb.get() / 32768f
                                feedFrame(res, frame, inChannels)
                            }
                        }
                    }
                    if (durationUs > 0 && info.presentationTimeUs >= 0) {
                        onProgress(((info.presentationTimeUs * 100) / durationUs).toInt().coerceIn(0, 100))
                    }
                    decoder.releaseOutputBuffer(outIdx, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true
                }
            }
            res.finish()
            resampler = null
            return res.frames
        } finally {
            resampler?.abort()
            try { codec?.stop() } catch (_: Throwable) {}
            codec?.release()
            extractor.release()
        }
    }
}
