package com.downloader.video_downloader_app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

// Download queue: jobs run one after another in the background, with pause / resume / cancel
// and an optional "Wi-Fi only" mode (jobs wait until Wi-Fi is available).
object DownloadQueue {
    private const val METHOD_CHANNEL = "com.downloader/queue"
    private const val EVENT_CHANNEL = "com.downloader/queue_events"
    private const val TAG = "DownloadQueue"

    class Job(
        val id: String,
        val url: String,
        val title: String,
        val thumbnail: String,
        val uploader: String,
        val audio: Boolean,
        val height: Int
    ) {
        @Volatile var state = "queued"   // queued | running | waiting | paused | done | error
        @Volatile var percent = 0.0
        @Volatile var eta = 0L
        @Volatile var error: String? = null
        @Volatile var pausedByUser = false
        @Volatile var cancelledByUser = false
        @Volatile var waitingWifi = false
        val fileBase: String get() = "dl_$id"
    }

    private val jobs = CopyOnWriteArrayList<Job>()
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var sink: EventChannel.EventSink? = null
    private var activity: MainActivity? = null
    private var serviceOn = false
    private var lastPush = 0L
    private var idCounter = 0
    private var resultCounter = 0
    private var watcherRegistered = false

    fun hasRunning(): Boolean = jobs.any { it.state == "running" }

    private fun snapshot(): String {
        val array = JSONArray()
        for (j in jobs) {
            array.put(
                JSONObject()
                    .put("id", j.id)
                    .put("url", j.url)
                    .put("title", j.title)
                    .put("thumbnail", j.thumbnail)
                    .put("uploader", j.uploader)
                    .put("audio", j.audio)
                    .put("height", j.height)
                    .put("state", j.state)
                    .put("percent", j.percent)
                    .put("eta", j.eta)
                    .put("error", j.error ?: JSONObject.NULL)
            )
        }
        return array.toString()
    }

    // Sends the whole list to the UI (progress updates are throttled unless forced)
    private fun push(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastPush < 400) return
        lastPush = now
        val payload = snapshot()
        mainHandler.post {
            try {
                sink?.success(payload)
            } catch (e: Throwable) {
                Log.w(TAG, "push failed", e)
            }
        }
    }

    // The foreground service runs only while something is queued, running or waiting for Wi-Fi
    @Synchronized
    private fun updateService(context: Context) {
        val pending = jobs.count { it.state == "queued" || it.state == "running" || it.state == "waiting" }
        if (pending > 0 && !serviceOn) {
            serviceOn = true
            DownloadService.start(context, "قائمة التحميل")
        } else if (pending == 0 && serviceOn) {
            serviceOn = false
            DownloadService.stop(context)
        }
    }

    private fun deleteFiles(context: Context, job: Job) {
        File(context.cacheDir, "downloads").listFiles()
            ?.filter { it.name.startsWith(job.fileBase) }
            ?.forEach { it.delete() }
    }

    private fun submit(job: Job) {
        executor.execute { runJob(job) }
    }

    private fun notifyProgress(context: Context, job: Job) {
        val pending = jobs.count { it.state == "queued" || it.state == "running" || it.state == "waiting" }
        val heading = when {
            job.state == "waiting" -> "بانتظار الاتصال بشبكة Wi-Fi"
            pending > 1 -> "جاري التحميل... (متبقي $pending)"
            else -> DownloadService.HEADING_DOWNLOAD
        }
        DownloadService.updateProgress(context, job.title, job.percent.toInt(), heading)
    }

    // ---- Wi-Fi only -------------------------------------------------------------------

    private fun isWifiOk(context: Context): Boolean {
        if (!SettingsChannel.wifiOnly(context)) return true
        return try {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        } catch (e: Throwable) {
            true
        }
    }

    // Called when the network changes or the Wi-Fi-only setting is toggled
    private fun onNetworkChanged(context: Context) {
        if (isWifiOk(context)) {
            val waiting = jobs.filter { it.state == "waiting" }
            for (j in waiting) {
                j.waitingWifi = false
                j.state = "queued"
                submit(j)
            }
            if (waiting.isNotEmpty()) {
                push(true)
                updateService(context)
            }
        } else {
            for (j in jobs.filter { it.state == "running" }) {
                j.waitingWifi = true
                YoutubeDL.getInstance().destroyProcessById(j.id)
            }
        }
    }

    fun onSettingsChanged(context: Context) = onNetworkChanged(context)

    private fun registerNetworkWatcher(context: Context) {
        if (watcherRegistered || Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        try {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    onNetworkChanged(context)
                }

                override fun onLost(network: Network) {
                    onNetworkChanged(context)
                }
            })
            watcherRegistered = true
        } catch (e: Throwable) {
            Log.w(TAG, "network watcher failed", e)
        }
    }

    // ---- running a job ---------------------------------------------------------------

    private fun runJob(job: Job) {
        val act = activity ?: return
        if (job.state != "queued") return // paused or cancelled while waiting
        val context = act.applicationContext

        if (!isWifiOk(context)) {
            job.waitingWifi = true
            job.state = "waiting"
            push(true)
            notifyProgress(context, job)
            return
        }

        job.waitingWifi = false
        job.state = "running"
        job.error = null
        push(true)
        notifyProgress(context, job)

        var lastPercent = -1
        try {
            act.ensureEngine()
            if (job.cancelledByUser || job.pausedByUser) throw CancellationException()

            val dir = File(context.cacheDir, "downloads").apply { mkdirs() }
            val request = YoutubeDLRequest(job.url)
            request.addOption("-o", File(dir, "${job.fileBase}.%(ext)s").absolutePath)
            if (job.audio) {
                request.addOption("-f", "bestaudio/best")
                request.addOption("-x")
                request.addOption("--audio-format", "mp3")
            } else {
                request.addOption("-f", act.videoFormatSelector(job.height))
                request.addOption("--merge-output-format", "mp4")
            }

            YoutubeDL.getInstance().execute(request, job.id) { progress, eta, _ ->
                job.percent = progress.toDouble().coerceIn(0.0, 100.0)
                job.eta = eta
                push(false)
                val p = job.percent.toInt()
                if (p != lastPercent) {
                    lastPercent = p
                    notifyProgress(context, job)
                }
            }

            val output = dir.listFiles()
                ?.filter {
                    it.name.startsWith(job.fileBase) &&
                        !it.name.endsWith(".part") &&
                        !it.name.endsWith(".ytdl")
                }
                ?.maxByOrNull { it.length() }
            if (output == null) throw IllegalStateException("OUTPUT_NOT_FOUND")

            val saved = act.saveToPublicStorage(output, job.title, job.audio)
            val savedUri = act.savedUri.get() ?: ""
            output.delete()
            if (!saved) throw IllegalStateException("SAVE_UNSUPPORTED")

            HistoryChannel.addItem(
                context,
                JSONObject()
                    .put("id", job.id)
                    .put("uri", savedUri)
                    .put("url", job.url)
                    .put("title", job.title)
                    .put("thumbnail", job.thumbnail)
                    .put("uploader", job.uploader)
                    .put("audio", job.audio)
                    .put("height", if (job.audio) 0 else job.height)
                    .put("date", System.currentTimeMillis())
            )
            job.percent = 100.0
            job.state = "done"
            DownloadService.showResult(
                context, job.title, true, notificationId = 3000 + (resultCounter++ % 50)
            )
        } catch (e: Throwable) {
            when {
                job.cancelledByUser -> {
                    jobs.remove(job)
                    deleteFiles(context, job)
                }
                job.pausedByUser -> job.state = "paused"
                job.waitingWifi -> job.state = "waiting"
                else -> {
                    Log.e(TAG, "job failed: ${job.title}", e)
                    job.state = "error"
                    job.error = e.message ?: e.toString()
                    DownloadService.showResult(
                        context, job.title, false, notificationId = 3000 + (resultCounter++ % 50)
                    )
                }
            }
        } finally {
            push(true)
            updateService(context)
            // only waiting jobs are left: make the notification say so
            val stillBusy = jobs.any { it.state == "running" || it.state == "queued" }
            if (!stillBusy) {
                jobs.firstOrNull { it.state == "waiting" }?.let { notifyProgress(context, it) }
            }
        }
    }

    fun register(act: MainActivity, engine: FlutterEngine) {
        activity = act
        val context = act.applicationContext
        registerNetworkWatcher(context)

        EventChannel(engine.dartExecutor.binaryMessenger, EVENT_CHANNEL)
            .setStreamHandler(object : EventChannel.StreamHandler {
                private var mine: EventChannel.EventSink? = null

                override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                    mine = events
                    sink = events
                    push(true)
                }

                override fun onCancel(arguments: Any?) {
                    if (sink === mine) sink = null
                    mine = null
                }
            })

        MethodChannel(engine.dartExecutor.binaryMessenger, METHOD_CHANNEL)
            .setMethodCallHandler { call, result ->
                val job = call.argument<String>("id")?.let { id -> jobs.firstOrNull { it.id == id } }
                when (call.method) {
                    "enqueue" -> {
                        val url = call.argument<String>("url")?.trim()
                        if (url.isNullOrEmpty()) {
                            result.success(null)
                        } else {
                            val id = System.currentTimeMillis().toString() +
                                (idCounter++ % 100).toString().padStart(2, '0')
                            val newJob = Job(
                                id = id,
                                url = url,
                                title = call.argument<String>("title") ?: "",
                                thumbnail = call.argument<String>("thumbnail") ?: "",
                                uploader = call.argument<String>("uploader") ?: "",
                                audio = call.argument<Boolean>("audio") ?: false,
                                height = call.argument<Int>("height") ?: 0
                            )
                            jobs.add(newJob)
                            push(true)
                            updateService(context)
                            submit(newJob)
                            result.success(id)
                        }
                    }

                    "list" -> result.success(snapshot())

                    "pause" -> {
                        if (job != null) {
                            when (job.state) {
                                "running" -> {
                                    job.pausedByUser = true
                                    YoutubeDL.getInstance().destroyProcessById(job.id)
                                }
                                "queued", "waiting" -> {
                                    job.state = "paused"
                                    push(true)
                                    updateService(context)
                                }
                            }
                        }
                        result.success(true)
                    }

                    "resume" -> {
                        if (job != null && (job.state == "paused" || job.state == "error")) {
                            job.pausedByUser = false
                            job.cancelledByUser = false
                            job.waitingWifi = false
                            job.error = null
                            job.state = "queued"
                            push(true)
                            updateService(context)
                            submit(job)
                        }
                        result.success(true)
                    }

                    "cancel" -> {
                        if (job != null) {
                            if (job.state == "running") {
                                job.cancelledByUser = true
                                YoutubeDL.getInstance().destroyProcessById(job.id)
                            } else {
                                jobs.remove(job)
                                deleteFiles(context, job)
                                push(true)
                                updateService(context)
                            }
                        }
                        result.success(true)
                    }

                    "clearDone" -> {
                        jobs.removeAll { it.state == "done" }
                        push(true)
                        result.success(true)
                    }

                    else -> result.notImplemented()
                }
            }
    }
}
