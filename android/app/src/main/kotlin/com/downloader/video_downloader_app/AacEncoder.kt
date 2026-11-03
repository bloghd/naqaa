package com.downloader.video_downloader_app

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.RandomAccessFile

// Encodes a 16-bit stereo PCM WAV (44-byte header, as written by AudioSeparator) to AAC in an M4A file.
object AacEncoder {
    private const val MIME = "audio/mp4a-latm"

    fun encodeWav(wav: File, output: File, sampleRate: Int = 44100, channels: Int = 2, bitRate: Int = 192_000) {
        val format = MediaFormat.createAudioFormat(MIME, sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
        }
        val codec = MediaCodec.createEncoderByType(MIME)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        var track = -1
        var muxerStarted = false
        val info = MediaCodec.BufferInfo()
        val bytesPerFrame = channels * 2
        val chunk = ByteArray(16 * 1024)

        try {
            RandomAccessFile(wav, "r").use { raf ->
                raf.seek(44)
                var inputDone = false
                var outputDone = false
                var totalFrames = 0L

                while (!outputDone) {
                    if (!inputDone) {
                        val idx = codec.dequeueInputBuffer(10_000)
                        if (idx >= 0) {
                            val buf = codec.getInputBuffer(idx)!!
                            buf.clear()
                            val n = raf.read(chunk, 0, minOf(buf.remaining(), chunk.size))
                            val ptsUs = totalFrames * 1_000_000L / sampleRate
                            if (n <= 0) {
                                codec.queueInputBuffer(idx, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                buf.put(chunk, 0, n)
                                codec.queueInputBuffer(idx, 0, n, ptsUs, 0)
                                totalFrames += n / bytesPerFrame
                            }
                        }
                    }

                    val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                    if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        track = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    } else if (outIdx >= 0) {
                        val encoded = codec.getOutputBuffer(outIdx)!!
                        if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0
                        if (info.size > 0 && muxerStarted) {
                            encoded.position(info.offset)
                            encoded.limit(info.offset + info.size)
                            muxer.writeSampleData(track, encoded, info)
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true
                    }
                }
            }
        } finally {
            try { codec.stop() } catch (_: Throwable) {}
            codec.release()
            if (muxerStarted) {
                try { muxer.stop() } catch (_: Throwable) {}
            }
            muxer.release()
        }
    }
}
