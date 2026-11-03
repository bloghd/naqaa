package com.downloader.video_downloader_app

import android.util.Log
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import org.json.JSONObject
import java.io.File
import kotlin.concurrent.thread

// "Download without music": downloads the audio, removes the music on-device (AI model),
// then saves the voice-only result as an M4A file.
object NoMusicPipeline {
    private const val TAG = "NoMusic"

    @Volatile private var running = false

    private fun errorJson(code: String): String =
        JSONObject().put("success", false).put("error", code).toString()

    private fun emit(activity: MainActivity, phase: String, percent: Double) {
        val payload = JSONObject()
            .put("percent", percent.coerceIn(0.0, 100.0))
            .put("eta", 0)
            .put("phase", phase)
            .toString()
        activity.mainHandler.post { activity.eventSink?.success(payload) }
    }

    fun start(activity: MainActivity, call: MethodCall, result: MethodChannel.Result) {
        val url = call.argument<String>("url")?.trim()
        val title = call.argument<String>("title") ?: ""
        val thumbnail = call.argument<String>("thumbnail") ?: ""
        val uploader = call.argument<String>("uploader") ?: ""
        val context = activity.applicationContext

        if (url.isNullOrEmpty()) {
            result.success(errorJson("EMPTY_URL"))
            return
        }
        val model = ModelChannel.modelFile(context)
        if (!model.exists()) {
            result.success(errorJson("MODEL_MISSING"))
            return
        }
        if (running) {
            result.success(errorJson("BUSY"))
            return
        }
        running = true

        thread {
            DownloadService.start(context, title)
            var success = false
            var response = errorJson("UNKNOWN")
            val workDir = File(context.cacheDir, "nomusic").apply { mkdirs() }
            val base = "nm_${System.currentTimeMillis()}"
            try {
                activity.ensureEngine()

                // 1) download the audio as 44.1 kHz stereo WAV
                emit(activity, "download", 0.0)
                val request = YoutubeDLRequest(url)
                request.addOption("-o", File(workDir, "$base.%(ext)s").absolutePath)
                request.addOption("-f", "bestaudio/best")
                request.addOption("-x")
                request.addOption("--audio-format", "wav")
                request.addOption("--postprocessor-args", "ExtractAudio:-ar 44100 -ac 2")
                YoutubeDL.getInstance().execute(request, base) { progress, _, _ ->
                    emit(activity, "download", progress.toDouble())
                    DownloadService.updateProgress(context, title, progress.toInt().coerceIn(0, 100))
                }
                val wavIn = File(workDir, "$base.wav")
                if (!wavIn.exists()) throw IllegalStateException("AUDIO_NOT_FOUND")
                Log.i(TAG, "audio downloaded: ${wavIn.length()} bytes")

                // 2) remove the music
                emit(activity, "separate", 0.0)
                val voiceWav = File(workDir, "${base}_voice.wav")
                val shownTitle = "إزالة الموسيقى: $title"
                var lastPercent = -1
                AudioSeparator.separateVocals(model, wavIn, voiceWav) { percent ->
                    emit(activity, "separate", percent.toDouble())
                    if (percent != lastPercent) {
                        lastPercent = percent
                        DownloadService.updateProgress(context, shownTitle, percent)
                    }
                }
                wavIn.delete()

                // 3) encode to AAC (M4A) and save to the public Music folder
                emit(activity, "save", 0.0)
                val m4a = File(workDir, "${base}_voice.m4a")
                AacEncoder.encodeWav(voiceWav, m4a)
                voiceWav.delete()

                val finalTitle = (if (title.isBlank()) "audio" else title) + " (بدون موسيقى)"
                val saved = activity.saveToPublicStorage(m4a, finalTitle, true)
                if (!saved) throw IllegalStateException("SAVE_UNSUPPORTED")

                HistoryChannel.addItem(
                    context,
                    JSONObject()
                        .put("id", System.currentTimeMillis().toString())
                        .put("url", url)
                        .put("title", finalTitle)
                        .put("thumbnail", thumbnail)
                        .put("uploader", uploader)
                        .put("audio", true)
                        .put("height", 0)
                        .put("date", System.currentTimeMillis())
                )
                success = true
                response = JSONObject().put("success", true).toString()
            } catch (e: OutOfMemoryError) {
                Log.e(TAG, "out of memory", e)
                response = errorJson("OUT_OF_MEMORY")
            } catch (e: Throwable) {
                Log.e(TAG, "no-music pipeline failed", e)
                response = errorJson(e.message ?: e.toString())
            } finally {
                workDir.listFiles()?.filter { it.name.startsWith(base) }?.forEach { it.delete() }
                running = false
                DownloadService.stop(context)
                DownloadService.showResult(context, title, success)
            }
            activity.mainHandler.post { result.success(response) }
        }
    }
}
