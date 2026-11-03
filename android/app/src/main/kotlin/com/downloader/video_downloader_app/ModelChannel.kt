package com.downloader.video_downloader_app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CancellationException
import kotlin.concurrent.thread

// Downloads, stores and removes the on-device "remove music" AI model (resumable).
// The download keeps running while the screen is closed; the UI re-attaches via "status".
object ModelChannel {
    private const val METHOD_CHANNEL = "com.downloader/model"
    private const val EVENT_CHANNEL = "com.downloader/model_progress"
    private const val TAG = "ModelManager"
    private const val MODEL_URL =
        "https://huggingface.co/StemSplitio/htdemucs-ft-vocals-onnx/resolve/main/htdemucs_ft_vocals.onnx"
    private const val FILE_NAME = "htdemucs_ft_vocals.onnx"
    private const val NOTIFICATION_TITLE = "مكوّن إزالة الموسيقى"
    private const val SPACE_MARGIN = 100L * 1024 * 1024

    private val mainHandler = Handler(Looper.getMainLooper())
    private var sink: EventChannel.EventSink? = null

    @Volatile private var running = false
    @Volatile private var cancelled = false
    @Volatile private var lastDownloaded = 0L
    @Volatile private var lastTotal = 0L

    // The finished model file (it only exists after a complete, verified download)
    fun modelFile(context: Context): File =
        File(File(context.filesDir, "models").apply { mkdirs() }, FILE_NAME)

    private fun partFile(context: Context): File =
        File(modelFile(context).parentFile, "$FILE_NAME.part")

    // type: progress | done | cancelled | error
    private fun emit(type: String, downloaded: Long, total: Long, error: String? = null) {
        val json = JSONObject()
            .put("type", type)
            .put("downloaded", downloaded)
            .put("total", total)
        if (error != null) json.put("error", error)
        val payload = json.toString()
        mainHandler.post {
            try {
                sink?.success(payload)
            } catch (e: Throwable) {
                Log.w(TAG, "event send failed", e)
            }
        }
    }

    private fun download(context: Context) {
        val target = modelFile(context)
        val part = partFile(context)
        if (target.exists()) return

        var existing = if (part.exists()) part.length() else 0L
        val conn = URL(MODEL_URL).openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("User-Agent", "VideoDownloader/1.0")
        conn.setRequestProperty("Accept-Encoding", "identity")
        if (existing > 0) conn.setRequestProperty("Range", "bytes=$existing-")

        try {
            conn.connect()
            val code = conn.responseCode
            if (code != 200 && code != 206) throw IllegalStateException("HTTP $code")

            val append = code == 206
            if (!append) existing = 0L
            val length = conn.contentLengthLong
            val total = if (length > 0) existing + length else -1L
            lastDownloaded = existing
            lastTotal = total

            if (total > 0 && part.parentFile!!.usableSpace < (total - existing) + SPACE_MARGIN) {
                throw IllegalStateException("NO_SPACE")
            }

            conn.inputStream.use { input ->
                FileOutputStream(part, append).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var downloaded = existing
                    var lastEmit = 0L
                    while (true) {
                        if (cancelled) throw CancellationException()
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        downloaded += n
                        lastDownloaded = downloaded
                        val now = System.currentTimeMillis()
                        if (now - lastEmit > 300) {
                            lastEmit = now
                            emit("progress", downloaded, total)
                            val percent = if (total > 0) (downloaded * 100 / total).toInt() else 0
                            DownloadService.updateProgress(context, NOTIFICATION_TITLE, percent)
                        }
                    }
                }
            }

            if (total > 0 && part.length() != total) throw IllegalStateException("INCOMPLETE")
            if (!part.renameTo(target)) throw IllegalStateException("RENAME_FAILED")
            lastDownloaded = target.length()
            lastTotal = target.length()
            Log.i(TAG, "model ready: ${target.absolutePath} (${target.length()} bytes)")
        } finally {
            conn.disconnect()
        }
    }

    fun register(engine: FlutterEngine, context: Context) {
        EventChannel(engine.dartExecutor.binaryMessenger, EVENT_CHANNEL)
            .setStreamHandler(object : EventChannel.StreamHandler {
                private var mine: EventChannel.EventSink? = null

                override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                    mine = events
                    sink = events
                }

                // Only clear the shared sink if it is still ours (a newer screen may have replaced it)
                override fun onCancel(arguments: Any?) {
                    if (sink === mine) sink = null
                    mine = null
                }
            })

        MethodChannel(engine.dartExecutor.binaryMessenger, METHOD_CHANNEL)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "status" -> {
                        val file = modelFile(context)
                        val json = JSONObject()
                            .put("installed", file.exists())
                            .put("running", running)
                            .put("downloaded", lastDownloaded)
                            .put("total", lastTotal)
                        result.success(json.toString())
                    }

                    "download" -> {
                        if (running) {
                            result.success(
                                JSONObject().put("success", false).put("error", "BUSY").toString()
                            )
                            return@setMethodCallHandler
                        }
                        running = true
                        cancelled = false
                        thread {
                            DownloadService.start(context, NOTIFICATION_TITLE)
                            var ok = false
                            var showNotification = true
                            val json = JSONObject()
                            try {
                                download(context)
                                ok = true
                                json.put("success", true)
                                emit("done", lastTotal, lastTotal)
                            } catch (e: CancellationException) {
                                showNotification = false
                                json.put("success", false).put("error", "CANCELLED")
                                emit("cancelled", lastDownloaded, lastTotal)
                            } catch (e: Throwable) {
                                Log.e(TAG, "model download failed", e)
                                val message = e.message ?: e.toString()
                                json.put("success", false).put("error", message)
                                emit("error", lastDownloaded, lastTotal, message)
                            } finally {
                                running = false
                                DownloadService.stop(context)
                                if (showNotification) {
                                    DownloadService.showResult(context, NOTIFICATION_TITLE, ok)
                                }
                            }
                            mainHandler.post {
                                try {
                                    result.success(json.toString())
                                } catch (e: Throwable) {
                                    // The screen that started the download may be gone already
                                    Log.w(TAG, "result send failed", e)
                                }
                            }
                        }
                    }

                    "cancel" -> {
                        cancelled = true
                        result.success(true)
                    }

                    "delete" -> {
                        if (running) {
                            result.success(false)
                        } else {
                            modelFile(context).delete()
                            partFile(context).delete()
                            lastDownloaded = 0L
                            lastTotal = 0L
                            result.success(true)
                        }
                    }

                    else -> result.notImplemented()
                }
            }
    }
}
