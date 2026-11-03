package com.downloader.video_downloader_app

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.yausername.youtubedl_android.YoutubeDL
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import org.json.JSONObject
import kotlin.concurrent.thread

// App settings (default quality, Wi-Fi only) and the manual download-tool update.
object SettingsChannel {
    private const val CHANNEL = "com.downloader/settings"
    private const val PREFS = "app_settings"
    private val mainHandler = Handler(Looper.getMainLooper())

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun wifiOnly(context: Context): Boolean = prefs(context).getBoolean("wifi_only", false)

    fun defaultHeight(context: Context): Int = prefs(context).getInt("default_height", 0)

    fun register(activity: MainActivity, engine: FlutterEngine) {
        val context = activity.applicationContext
        MethodChannel(engine.dartExecutor.binaryMessenger, CHANNEL)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "get" -> {
                        val json = JSONObject()
                            .put("defaultHeight", defaultHeight(context))
                            .put("wifiOnly", wifiOnly(context))
                        result.success(json.toString())
                    }

                    "setDefaultHeight" -> {
                        prefs(context).edit()
                            .putInt("default_height", call.argument<Int>("value") ?: 0)
                            .apply()
                        result.success(true)
                    }

                    "setWifiOnly" -> {
                        prefs(context).edit()
                            .putBoolean("wifi_only", call.argument<Boolean>("value") ?: false)
                            .apply()
                        thread { DownloadQueue.onSettingsChanged(context) }
                        result.success(true)
                    }

                    "engineVersion" -> thread {
                        val version = try {
                            activity.ensureEngine()
                            YoutubeDL.getInstance().versionName(context) ?: ""
                        } catch (e: Throwable) {
                            ""
                        }
                        mainHandler.post { result.success(version) }
                    }

                    "updateEngine" -> {
                        if (DownloadQueue.hasRunning()) {
                            result.success(JSONObject().put("ok", false).put("busy", true).toString())
                        } else {
                            thread {
                                val json = JSONObject()
                                try {
                                    activity.ensureEngine()
                                    val status = YoutubeDL.getInstance()
                                        .updateYoutubeDL(context, YoutubeDL.UpdateChannel.STABLE)
                                    json.put("ok", true)
                                        .put("status", status?.toString() ?: "")
                                        .put("version", YoutubeDL.getInstance().versionName(context) ?: "")
                                } catch (e: Throwable) {
                                    json.put("ok", false)
                                }
                                mainHandler.post { result.success(json.toString()) }
                            }
                        }
                    }

                    else -> result.notImplemented()
                }
            }
    }
}
