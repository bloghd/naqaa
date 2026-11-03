package com.downloader.video_downloader_app

import android.content.ActivityNotFoundException
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

// Actions on saved files: open, share, delete.
object FilesChannel {
    private const val CHANNEL = "com.downloader/files"

    private fun exists(context: Context, uri: Uri): Boolean = try {
        context.contentResolver
            .query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)
            ?.use { it.count > 0 } ?: false
    } catch (e: Throwable) {
        false
    }

    // Older history entries have no uri: find the file by its name in the app's folder
    private fun lookup(activity: MainActivity, title: String, audio: Boolean): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val collection = if (audio) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val path = if (audio) "Music/VideoDownloader/" else "Movies/VideoDownloader/"
        val name = activity.sanitizeName(title)
        activity.applicationContext.contentResolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?",
            arrayOf("$path%", "$name.%"),
            "${MediaStore.MediaColumns.DATE_ADDED} DESC"
        )?.use { c ->
            if (c.moveToFirst()) return ContentUris.withAppendedId(collection, c.getLong(0))
        }
        return null
    }

    private fun resolve(activity: MainActivity, uriStr: String, title: String, audio: Boolean): Uri? {
        if (uriStr.isNotEmpty()) {
            val uri = Uri.parse(uriStr)
            return if (exists(activity.applicationContext, uri)) uri else null
        }
        return lookup(activity, title, audio)
    }

    fun register(activity: MainActivity, engine: FlutterEngine) {
        val context = activity.applicationContext
        MethodChannel(engine.dartExecutor.binaryMessenger, CHANNEL)
            .setMethodCallHandler { call, result ->
                val uriStr = call.argument<String>("uri") ?: ""
                val title = call.argument<String>("title") ?: ""
                val audio = call.argument<Boolean>("audio") ?: false

                val uri = resolve(activity, uriStr, title, audio)
                if (uri == null) {
                    result.success("MISSING")
                    return@setMethodCallHandler
                }
                val mime = context.contentResolver.getType(uri) ?: if (audio) "audio/*" else "video/*"

                try {
                    when (call.method) {
                        "open" -> {
                            val intent = Intent(Intent.ACTION_VIEW)
                                .setDataAndType(uri, mime)
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            activity.startActivity(intent)
                            result.success("OK")
                        }

                        "share" -> {
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = mime
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            activity.startActivity(Intent.createChooser(send, "مشاركة"))
                            result.success("OK")
                        }

                        "delete" -> {
                            val rows = context.contentResolver.delete(uri, null, null)
                            result.success(if (rows > 0) "OK" else "MISSING")
                        }

                        else -> result.notImplemented()
                    }
                } catch (e: ActivityNotFoundException) {
                    result.success("NO_APP")
                } catch (e: SecurityException) {
                    result.success("DENIED")
                } catch (e: Throwable) {
                    result.success("ERROR")
                }
            }
    }
}
