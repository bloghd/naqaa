package com.downloader.video_downloader_app

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.CancellationException

object VideoMuxer {
    private fun findTrack(extractor: MediaExtractor, prefix: String): Int {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: ""
            if (mime.startsWith(prefix)) return i
        }
        return -1
    }

    private fun sizeHint(f: MediaFormat): Int =
        if (f.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) f.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 0

    private fun rotationOf(context: Context, uri: Uri): Int {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        } catch (e: Throwable) {
            0
        } finally {
            try { retriever.release() } catch (_: Throwable) {}
        }
    }

    /** Copies the video track of [videoUri] (no re-encoding) and adds [audioFile] (AAC in M4A) as audio. */
    fun mux(context: Context, videoUri: Uri, audioFile: File, output: File, isCancelled: () -> Boolean) {
        val vEx = MediaExtractor()
        val aEx = MediaExtractor()
        var muxerRef: MediaMuxer? = null
        var started = false
        try {
            vEx.setDataSource(context, videoUri, null)
            aEx.setDataSource(audioFile.absolutePath)
            val vTrack = findTrack(vEx, "video/")
            val aTrack = findTrack(aEx, "audio/")
            if (vTrack < 0) throw IllegalStateException("NO_VIDEO_TRACK")
            if (aTrack < 0) throw IllegalStateException("NO_AUDIO_TRACK")
            vEx.selectTrack(vTrack)
            aEx.selectTrack(aTrack)
            val vFormat = vEx.getTrackFormat(vTrack)
            val aFormat = aEx.getTrackFormat(aTrack)

            val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxerRef = muxer
            val rotation = rotationOf(context, videoUri)
            if (rotation == 0 || rotation == 90 || rotation == 180 || rotation == 270) {
                muxer.setOrientationHint(rotation)
            }
            val vOut = muxer.addTrack(vFormat)
            val aOut = muxer.addTrack(aFormat)
            muxer.start()
            started = true

            val vBuf = ByteBuffer.allocate(maxOf(sizeHint(vFormat), 4 * 1024 * 1024))
            val aBuf = ByteBuffer.allocate(maxOf(sizeHint(aFormat), 256 * 1024))
            val info = MediaCodec.BufferInfo()

            while (true) {
                if (isCancelled()) throw CancellationException()
                val vt = vEx.sampleTime
                val at = aEx.sampleTime
                if (vt < 0 && at < 0) break

                // write whichever track is behind, so the file stays interleaved
                val useVideo = vt >= 0 && (at < 0 || vt <= at)
                val ex = if (useVideo) vEx else aEx
                val buf = if (useVideo) vBuf else aBuf
                buf.clear()
                val size = ex.readSampleData(buf, 0)
                if (size < 0) {
                    ex.advance()
                    continue
                }
                val flags =
                    if ((ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                info.set(0, size, ex.sampleTime, flags)
                muxer.writeSampleData(if (useVideo) vOut else aOut, buf, info)
                ex.advance()
            }
        } finally {
            val m = muxerRef
            if (m != null) {
                if (started) {
                    try { m.stop() } catch (_: Throwable) {}
                }
                try { m.release() } catch (_: Throwable) {}
            }
            vEx.release()
            aEx.release()
        }
    }
}
