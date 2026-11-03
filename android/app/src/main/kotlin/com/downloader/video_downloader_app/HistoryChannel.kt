package com.downloader.video_downloader_app

import android.content.Context
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import org.json.JSONArray
import org.json.JSONObject

// Stores the downloads history (a JSON array) in the app's private SharedPreferences.
object HistoryChannel {
    private const val CHANNEL = "com.downloader/history"
    private const val PREFS = "download_history"
    private const val KEY = "items_v1"
    private const val MAX_ITEMS = 200

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // Adds one finished download at the top of the history (safe to call from any thread)
    @Synchronized
    fun addItem(context: Context, item: JSONObject) {
        val p = prefs(context)
        val old = try {
            JSONArray(p.getString(KEY, null) ?: "[]")
        } catch (e: Exception) {
            JSONArray()
        }
        val merged = JSONArray()
        merged.put(item)
        for (i in 0 until minOf(old.length(), MAX_ITEMS - 1)) {
            merged.put(old.get(i))
        }
        p.edit().putString(KEY, merged.toString()).commit()
    }

    fun register(engine: FlutterEngine, context: Context) {
        MethodChannel(engine.dartExecutor.binaryMessenger, CHANNEL)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "load" -> result.success(prefs(context).getString(KEY, null))
                    "save" -> {
                        val json = call.argument<String>("json") ?: "[]"
                        synchronized(this) {
                            prefs(context).edit().putString(KEY, json).commit()
                        }
                        result.success(true)
                    }
                    else -> result.notImplemented()
                }
            }
    }
}
