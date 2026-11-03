package com.downloader.video_downloader_app

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.concurrent.thread

class MainActivity : FlutterActivity() {
    private val methodChannelName = "com.downloader/engine"
    private val eventChannelName = "com.downloader/progress"
    private val tag = "VideoEngine"
    val mainHandler = Handler(Looper.getMainLooper())
    val savedUri = ThreadLocal<String?>()
    private var engineReady = false
    var eventSink: EventChannel.EventSink? = null
    private var methodChannel: MethodChannel? = null
    private var sharedText: String? = null

    // Initializes yt-dlp + ffmpeg once, then updates yt-dlp to the latest stable build.
    // Synchronized so any request waits until the engine is fully ready.
    @Synchronized
    fun ensureEngine() {
        if (engineReady) return
        YoutubeDL.getInstance().init(applicationContext)
        FFmpeg.getInstance().init(applicationContext)
        engineReady = true

        try {
            val status = YoutubeDL.getInstance()
                .updateYoutubeDL(applicationContext, YoutubeDL.UpdateChannel.STABLE)
            val version = YoutubeDL.getInstance().versionName(applicationContext)
            Log.i(tag, "yt-dlp update status=$status version=$version")
        } catch (e: Throwable) {
            Log.e(tag, "yt-dlp update failed", e)
        }
    }

    private fun errorJson(message: String): String =
        JSONObject().put("success", false).put("error", message).toString()

    // Text shared from other apps (Share -> this app)
    private fun extractSharedText(intent: Intent?): String? {
        if (intent?.action != Intent.ACTION_SEND) return null
        if (intent.type?.startsWith("text/") != true) return null
        return intent.getStringExtra(Intent.EXTRA_TEXT)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sharedText = extractSharedText(intent)

        // Android 13+: ask for permission to show the download notifications
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2001)
        }
    }

    // App is already open and the user shares another link to it
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        ConvertChannel.onActivityResult(requestCode, resultCode, data)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val text = extractSharedText(intent) ?: return
        methodChannel?.invokeMethod("onSharedText", text)
    }

    // Makes a safe file name out of the video title
    fun sanitizeName(raw: String?): String {
        val cleaned = (raw ?: "")
            .replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(80)
            .trim()
            .trim('.')
        return if (cleaned.isEmpty()) "video" else cleaned
    }

    // Builds the yt-dlp format selector for the requested max height (0 = best available)
    fun videoFormatSelector(height: Int): String {
        return if (height > 0) {
            "bv*[height<=$height][ext=mp4]+ba[ext=m4a]/b[height<=$height][ext=mp4]/" +
                "bv*[height<=$height]+ba/b[height<=$height]/b"
        } else {
            "bv*[ext=mp4]+ba[ext=m4a]/b[ext=mp4]/b"
        }
    }

    // Copies the downloaded file to public storage (Movies / Music) using MediaStore.
    // Returns false when the device is older than Android 10 (handled in a later step).
    fun saveToPublicStorage(source: File, title: String?, audio: Boolean): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false

        val ext = source.extension.lowercase()
        val mime = when (ext) {
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            "webm" -> if (audio) "audio/webm" else "video/webm"
            "mkv" -> "video/x-matroska"
            else -> if (audio) "audio/mpeg" else "video/mp4"
        }
        val collection = if (audio) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val baseDir = if (audio) Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_MOVIES

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "${sanitizeName(title)}.$ext")
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "$baseDir/VideoDownloader")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val resolver = applicationContext.contentResolver
        val uri = resolver.insert(collection, values) ?: return false
        try {
            val out = resolver.openOutputStream(uri)
                ?: throw IllegalStateException("Could not open output stream")
            out.use { stream -> source.inputStream().use { it.copyTo(stream) } }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            Log.i(tag, "saved to public storage: $uri")
            savedUri.set(uri.toString())
            return true
        } catch (e: Throwable) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        DownloadQueue.register(this@MainActivity, flutterEngine)
        SettingsChannel.register(this@MainActivity, flutterEngine)
        FilesChannel.register(this@MainActivity, flutterEngine)
        ConvertChannel.register(this@MainActivity, flutterEngine)
        ModelChannel.register(flutterEngine, applicationContext)
        HistoryChannel.register(flutterEngine, applicationContext)

        // Warm up the engine in the background so the first request is fast
        thread {
            try {
                ensureEngine()
            } catch (e: Throwable) {
                Log.e(tag, "engine warm-up failed", e)
            }
        }

        EventChannel(flutterEngine.dartExecutor.binaryMessenger, eventChannelName)
            .setStreamHandler(object : EventChannel.StreamHandler {
                override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                    eventSink = events
                }

                override fun onCancel(arguments: Any?) {
                    eventSink = null
                }
            })

        val channel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, methodChannelName)
        methodChannel = channel
        channel.setMethodCallHandler { call, result ->
            when (call.method) {
                "getSharedText" -> {
                    val text = sharedText
                    sharedText = null
                    result.success(text)
                }

                "analyzeUrl" -> {
                    val url = call.argument<String>("url")?.trim()
                    if (url.isNullOrEmpty()) {
                        result.success(errorJson("EMPTY_URL"))
                        return@setMethodCallHandler
                    }
                    thread {
                        try {
                            ensureEngine()
                            val info = YoutubeDL.getInstance().getInfo(url)

                            // Available video heights (e.g. 1080, 720, 480), highest first
                            val heights = (info.formats ?: arrayListOf())
                                .filter { (it.vcodec ?: "none") != "none" }
                                .map { it.height ?: 0 }
                                .filter { it >= 144 }
                                .distinct()
                                .sortedDescending()
                                .take(6)
                            val heightsJson = JSONArray()
                            heights.forEach { heightsJson.put(it) }

                            val json = JSONObject().apply {
                                put("success", true)
                                put("url", url)
                                put("title", info.title ?: "")
                                put("thumbnail", info.thumbnail ?: "")
                                put("uploader", info.uploader ?: "")
                                put("duration", info.duration?.toInt() ?: 0)
                                put("heights", heightsJson)
                            }
                            mainHandler.post { result.success(json.toString()) }
                        } catch (e: Throwable) {
                            Log.e(tag, "analyzeUrl failed", e)
                            val message = e.message ?: e.toString()
                            mainHandler.post { result.success(errorJson(message)) }
                        }
                    }
                }

                "startNoMusic" -> {
                    NoMusicPipeline.start(this@MainActivity, call, result)
                }

                "startDownload" -> {
                    val url = call.argument<String>("url")?.trim()
                    val audioOnly = call.argument<Boolean>("audio") ?: false
                    val title = call.argument<String>("title")
                    val height = call.argument<Int>("height") ?: 0
                    val thumbnail = call.argument<String>("thumbnail") ?: ""
                    val uploader = call.argument<String>("uploader") ?: ""
                    if (url.isNullOrEmpty()) {
                        result.success(errorJson("EMPTY_URL"))
                        return@setMethodCallHandler
                    }
                    thread {
                        // Foreground service keeps the download alive in the background
                        DownloadService.start(applicationContext, title)
                        var success = false
                        var lastPercent = -1
                        try {
                            ensureEngine()

                            val downloadsDir = File(applicationContext.cacheDir, "downloads")
                            downloadsDir.mkdirs()
                            val fileName = "dl_${System.currentTimeMillis()}"

                            val request = YoutubeDLRequest(url)
                            request.addOption("-o", File(downloadsDir, "$fileName.%(ext)s").absolutePath)
                            if (audioOnly) {
                                request.addOption("-f", "bestaudio/best")
                                request.addOption("-x")
                                request.addOption("--audio-format", "mp3")
                            } else {
                                request.addOption("-f", videoFormatSelector(height))
                                request.addOption("--merge-output-format", "mp4")
                            }

                            YoutubeDL.getInstance().execute(request, fileName) { progress, eta, _ ->
                                val percent = progress.toInt().coerceIn(0, 100)
                                if (percent != lastPercent) {
                                    lastPercent = percent
                                    DownloadService.updateProgress(applicationContext, title, percent)
                                }
                                val payload = JSONObject()
                                    .put("percent", progress.toDouble().coerceIn(0.0, 100.0))
                                    .put("eta", eta)
                                    .toString()
                                mainHandler.post { eventSink?.success(payload) }
                            }

                            val output = downloadsDir.listFiles()
                                ?.filter {
                                    it.name.startsWith(fileName) &&
                                        !it.name.endsWith(".part") &&
                                        !it.name.endsWith(".ytdl")
                                }
                                ?.maxByOrNull { it.length() }

                            if (output == null) {
                                mainHandler.post { result.success(errorJson("OUTPUT_NOT_FOUND")) }
                            } else {
                                Log.i(tag, "download finished: ${output.absolutePath} (${output.length()} bytes)")
                                val saved = saveToPublicStorage(output, title, audioOnly)
                                output.delete()
                                if (saved) {
                                    success = true
                                    HistoryChannel.addItem(
                                        applicationContext,
                                        JSONObject()
                                            .put("id", System.currentTimeMillis().toString())
                                            .put("url", url)
                                            .put("title", title ?: "")
                                            .put("thumbnail", thumbnail)
                                            .put("uploader", uploader)
                                            .put("audio", audioOnly)
                                            .put("height", if (audioOnly) 0 else height)
                                            .put("date", System.currentTimeMillis())
                                    )
                                    val json = JSONObject().put("success", true).toString()
                                    mainHandler.post { result.success(json) }
                                } else {
                                    mainHandler.post { result.success(errorJson("SAVE_UNSUPPORTED")) }
                                }
                            }
                        } catch (e: Throwable) {
                            Log.e(tag, "startDownload failed", e)
                            val message = e.message ?: e.toString()
                            mainHandler.post { result.success(errorJson(message)) }
                        } finally {
                            DownloadService.stop(applicationContext)
                            DownloadService.showResult(applicationContext, title, success)
                        }
                    }
                }

                else -> result.notImplemented()
            }
        }
    }
}
