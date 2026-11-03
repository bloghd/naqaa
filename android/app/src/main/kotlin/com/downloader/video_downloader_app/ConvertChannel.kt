package com.downloader.video_downloader_app

import android.app.Activity
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CancellationException
import kotlin.concurrent.thread

// "Remove music" tab: pick a file (device or app downloads), convert it in the background,
// save the result automatically and notify the user when it is done.
object ConvertChannel {
    private const val METHOD_CHANNEL = "com.downloader/convert"
    private const val EVENT_CHANNEL = "com.downloader/convert_progress"
    private const val TAG = "Convert"
    private const val REQUEST_PICK = 4101
    private const val RESULT_NOTIFICATION_ID = 1003

    private val mainHandler = Handler(Looper.getMainLooper())
    private var sink: EventChannel.EventSink? = null
    private var pendingPick: MethodChannel.Result? = null
    private var activityRef: MainActivity? = null

    @Volatile private var running = false
    @Volatile private var cancelled = false
    @Volatile private var phase = "prepare"
    @Volatile private var percent = 0
    @Volatile private var runningName = ""
    @Volatile private var lastResult: String? = null

    private fun emit(json: JSONObject) {
        val payload = json.toString()
        mainHandler.post {
            try {
                sink?.success(payload)
            } catch (e: Throwable) {
                Log.w(TAG, "event send failed", e)
            }
        }
    }

    private fun report(context: Context, newPhase: String, p: Int) {
        val value = p.coerceIn(0, 100)
        if (newPhase == phase && value == percent) return
        phase = newPhase
        percent = value
        emit(
            JSONObject().put("type", "progress").put("phase", phase)
                .put("percent", percent).put("name", runningName)
        )
        DownloadService.updateProgress(context, runningName, percent, DownloadService.HEADING_CONVERT)
    }

    private fun describe(context: Context, uri: Uri): JSONObject {
        var name = "ملف"
        var size = 0L
        context.contentResolver.query(
            uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null
        )?.use { c ->
            if (c.moveToFirst()) {
                val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (n >= 0 && !c.isNull(n)) name = c.getString(n)
                val s = c.getColumnIndex(OpenableColumns.SIZE)
                if (s >= 0 && !c.isNull(s)) size = c.getLong(s)
            }
        }
        val mime = context.contentResolver.getType(uri) ?: ""
        var durationMs = 0L
        var hasVideo = mime.startsWith("video/")
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            hasVideo = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
        } catch (e: Throwable) {
            Log.w(TAG, "metadata failed", e)
        } finally {
            try { retriever.release() } catch (_: Throwable) {}
        }
        return JSONObject().put("uri", uri.toString()).put("name", name).put("size", size)
            .put("durationMs", durationMs).put("isVideo", hasVideo)
    }

    // Files saved by this app (Movies/VideoDownloader and Music/VideoDownloader), newest first
    private fun library(context: Context): JSONArray {
        val found = mutableListOf<Pair<Long, JSONObject>>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val sources = listOf(
                Triple(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), "Movies/VideoDownloader/%", true),
                Triple(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), "Music/VideoDownloader/%", false)
            )
            for ((collection, path, isVideo) in sources) {
                val projection = arrayOf(
                    MediaStore.MediaColumns._ID,
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.SIZE,
                    MediaStore.MediaColumns.DURATION,
                    MediaStore.MediaColumns.DATE_ADDED
                )
                try {
                    context.contentResolver.query(
                        collection, projection,
                        "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?", arrayOf(path),
                        "${MediaStore.MediaColumns.DATE_ADDED} DESC"
                    )?.use { c ->
                        while (c.moveToNext()) {
                            val uri = ContentUris.withAppendedId(collection, c.getLong(0))
                            val json = JSONObject()
                                .put("uri", uri.toString())
                                .put("name", c.getString(1) ?: "ملف")
                                .put("size", c.getLong(2))
                                .put("durationMs", c.getLong(3))
                                .put("isVideo", isVideo)
                            found.add(Pair(c.getLong(4), json))
                        }
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "library query failed", e)
                }
            }
        }
        val array = JSONArray()
        found.sortedByDescending { it.first }.forEach { array.put(it.second) }
        return array
    }

    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != REQUEST_PICK) return
        val callback = pendingPick
        pendingPick = null
        val activity = activityRef
        val uri = data?.data
        if (callback == null) return
        if (resultCode != Activity.RESULT_OK || uri == null || activity == null) {
            callback.success(null)
            return
        }
        try {
            activity.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: Throwable) {
            Log.w(TAG, "persist permission failed", e)
        }
        thread {
            val json = describe(activity.applicationContext, uri).toString()
            mainHandler.post { callback.success(json) }
        }
    }

    private fun runConversion(
        activity: MainActivity,
        uri: Uri,
        displayName: String,
        isVideo: Boolean,
        outputVideo: Boolean,
        strong: Boolean,
        model: File
    ) {
        val context = activity.applicationContext
        DownloadService.start(context, displayName, DownloadService.HEADING_CONVERT)
        val work = File(context.cacheDir, "convert").apply { mkdirs() }
        val base = "cv_${System.currentTimeMillis()}"
        val startedAt = System.currentTimeMillis()
        val fmtTime = { ms: Long -> val s = ms / 1000; if (s >= 60) "${s / 60} د ${s % 60} ث" else "$s ث" }
        val shortName = displayName.substringBeforeLast('.', displayName).ifBlank { "audio" }
        val finalTitle = "$shortName (بدون موسيقى)"
        var success = false
        var notify = true
        var finalName = displayName
        try {
            report(context, "prepare", 0)
            val wavIn = File(work, "$base.wav")
            val decodedFrames = MediaDecoder.decodeToWav(context, uri, wavIn, { cancelled }) { p ->
                report(context, "prepare", p / 10)
            }
            if (cancelled) throw CancellationException()

            val voiceWav = File(work, "${base}_voice.wav")
            AudioSeparator.separateVocals(model, wavIn, voiceWav, { cancelled }) { p ->
                report(context, "separate", 10 + p * (if (strong) 40 else 80) / 100)
            }
            wavIn.delete()
            if (strong) {
                val refined = File(work, "${base}_voice2.wav")
                AudioSeparator.separateVocals(model, voiceWav, refined, { cancelled }) { p ->
                    report(context, "refine", 50 + p * 40 / 100)
                }
                voiceWav.delete()
                refined.renameTo(voiceWav)
            }
            VoiceGate.applyInPlace(voiceWav) { cancelled }

            report(context, "save", 90)
            val m4a = File(work, "${base}_voice.m4a")
            AacEncoder.encodeWav(voiceWav, m4a)
            voiceWav.delete()

            var kind = "audio"
            val savedOk: Boolean
            if (isVideo && outputVideo) {
                val mp4 = File(work, "${base}_out.mp4")
                var ok: Boolean
                try {
                    VideoMuxer.mux(context, uri, m4a, mp4) { cancelled }
                    kind = "video"
                    ok = activity.saveToPublicStorage(mp4, finalTitle, false)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    Log.w(TAG, "video mux failed, saving the audio only", e)
                    kind = "audio_fallback"
                    ok = activity.saveToPublicStorage(m4a, finalTitle, true)
                }
                savedOk = ok
            } else {
                savedOk = activity.saveToPublicStorage(m4a, finalTitle, true)
            }
            if (!savedOk) throw IllegalStateException("SAVE_UNSUPPORTED")
            val savedUri = activity.savedUri.get() ?: ""

            HistoryChannel.addItem(
                context,
                JSONObject()
                    .put("id", System.currentTimeMillis().toString())
                    .put("uri", savedUri)
                    .put("url", "")
                    .put("title", finalTitle)
                    .put("thumbnail", "")
                    .put("uploader", "")
                    .put("audio", kind != "video")
                    .put("height", 0)
                    .put("date", System.currentTimeMillis())
            )

            finalName = finalTitle
            success = true
            report(context, "save", 100)
            val done = JSONObject().put("type", "done").put("output", kind).put("name", finalTitle + "\n" + "استغرقت المعالجة " + fmtTime(System.currentTimeMillis() - startedAt) + " لمقطع مدته " + fmtTime(decodedFrames * 1000 / 44100))
            lastResult = done.toString()
            emit(done)
        } catch (e: CancellationException) {
            notify = false
            lastResult = null
            emit(JSONObject().put("type", "cancelled"))
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "out of memory", e)
            val err = JSONObject().put("type", "error").put("error", "OUT_OF_MEMORY")
            lastResult = err.toString()
            emit(err)
        } catch (e: Throwable) {
            Log.e(TAG, "conversion failed", e)
            val err = JSONObject().put("type", "error").put("error", e.message ?: e.toString())
            lastResult = err.toString()
            emit(err)
        } finally {
            work.listFiles()?.filter { it.name.startsWith(base) }?.forEach { it.delete() }
            running = false
            DownloadService.stop(context)
            if (notify) {
                DownloadService.showResult(
                    context, finalName, success,
                    successText = "تمت إزالة الموسيقى",
                    failText = "تعذّرت إزالة الموسيقى",
                    notificationId = RESULT_NOTIFICATION_ID
                )
            }
        }
    }

    fun register(activity: MainActivity, engine: FlutterEngine) {
        activityRef = activity
        val context = activity.applicationContext

        EventChannel(engine.dartExecutor.binaryMessenger, EVENT_CHANNEL)
            .setStreamHandler(object : EventChannel.StreamHandler {
                private var mine: EventChannel.EventSink? = null

                override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                    mine = events
                    sink = events
                }

                override fun onCancel(arguments: Any?) {
                    if (sink === mine) sink = null
                    mine = null
                }
            })

        MethodChannel(engine.dartExecutor.binaryMessenger, METHOD_CHANNEL)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "pick" -> {
                        if (pendingPick != null) {
                            result.success(null)
                        } else {
                            pendingPick = result
                            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                                addCategory(Intent.CATEGORY_OPENABLE)
                                type = "*/*"
                                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("video/*", "audio/*"))
                            }
                            activity.startActivityForResult(intent, REQUEST_PICK)
                        }
                    }

                    "library" -> thread {
                        val json = library(context).toString()
                        mainHandler.post { result.success(json) }
                    }

                    "start" -> {
                        val uriStr = call.argument<String>("uri")
                        val name = call.argument<String>("name") ?: "ملف"
                        val isVideo = call.argument<Boolean>("isVideo") ?: false
                        val outputVideo = call.argument<Boolean>("outputVideo") ?: true
                        val strong = call.argument<Boolean>("strong") ?: false
                        val model = ModelChannel.modelFile(context)
                        when {
                            uriStr.isNullOrEmpty() ->
                                result.success(JSONObject().put("started", false).put("error", "EMPTY_URI").toString())
                            !model.exists() ->
                                result.success(JSONObject().put("started", false).put("error", "MODEL_MISSING").toString())
                            running ->
                                result.success(JSONObject().put("started", false).put("error", "BUSY").toString())
                            else -> {
                                running = true
                                cancelled = false
                                lastResult = null
                                phase = "prepare"
                                percent = -1
                                runningName = name
                                result.success(JSONObject().put("started", true).toString())
                                thread {
                                    runConversion(activity, Uri.parse(uriStr), name, isVideo, outputVideo, strong, model)
                                }
                            }
                        }
                    }

                    "status" -> {
                        val json = JSONObject()
                            .put("running", running)
                            .put("phase", phase)
                            .put("percent", percent.coerceAtLeast(0))
                            .put("name", runningName)
                            .put("result", lastResult?.let { JSONObject(it) } ?: JSONObject.NULL)
                        result.success(json.toString())
                    }

                    "ack" -> {
                        lastResult = null
                        result.success(true)
                    }

                    "cancel" -> {
                        cancelled = true
                        result.success(true)
                    }

                    else -> result.notImplemented()
                }
            }
    }
}
