package com.gpsbrowser

import android.app.AlertDialog
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class UpdateChecker(private val context: Context) {

    companion object {
        private const val TAG = "UpdateChecker"
        private const val VERSION_URL = "http://31.97.189.245/dl/gpsbrowser_version.json"
        private const val APK_FILENAME = "GPSBrowser.apk"
    }

    fun checkForUpdate(silent: Boolean = false) {
        thread {
            try {
                val conn = URL(VERSION_URL).openConnection() as HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.setRequestProperty("X-GPSBrowser-Bypass", "1")
                if (conn.responseCode != 200) return@thread

                val text = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(text)
                val remoteVersionCode = json.getInt("versionCode")
                val remoteVersionName = json.getString("version")
                val apkUrl = json.getString("url")
                val changelog = json.optString("changelog", "")

                val localVersionCode = context.packageManager
                    .getPackageInfo(context.packageName, 0).longVersionCode.toInt()

                Log.i(TAG, "Local: $localVersionCode, Remote: $remoteVersionCode")

                if (remoteVersionCode > localVersionCode) {
                    (context as? MainActivity)?.runOnUiThread {
                        showUpdateDialog(remoteVersionName, changelog, apkUrl)
                    }
                } else if (!silent) {
                    (context as? MainActivity)?.runOnUiThread {
                        Toast.makeText(context, "Đã là bản mới nhất ($remoteVersionName)", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Update check failed", e)
            }
        }
    }

    private fun showUpdateDialog(version: String, changelog: String, apkUrl: String) {
        AlertDialog.Builder(context)
            .setTitle("Có phiên bản mới: $version")
            .setMessage(if (changelog.isNotEmpty()) changelog else "Cập nhật GPSBrowser?")
            .setPositiveButton("Tải & Cài") { _, _ ->
                downloadApk(apkUrl)
            }
            .setNegativeButton("Để sau", null)
            .show()
    }

    private fun downloadApk(apkUrl: String) {
        try {
            // Xoa file cu neu co
            val downloadDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            val oldFile = File(downloadDir, APK_FILENAME)
            if (oldFile.exists()) oldFile.delete()

            val request = DownloadManager.Request(Uri.parse(apkUrl))
                .setTitle("GPSBrowser Update")
                .setDescription("Đang tải bản cập nhật...")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, APK_FILENAME)
                .setMimeType("application/vnd.android.package-archive")

            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val downloadId = dm.enqueue(request)
            Toast.makeText(context, "Đang tải bản mới...", Toast.LENGTH_SHORT).show()

            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                    if (id == downloadId) {
                        try { context.unregisterReceiver(this) } catch (e: Exception) {}
                        installApk(File(downloadDir, APK_FILENAME))
                    }
                }
            }
            val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(receiver, filter)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Download failed", e)
            Toast.makeText(context, "Lỗi tải: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun installApk(apkFile: File) {
        try {
            if (!apkFile.exists()) {
                Toast.makeText(context, "File APK không tồn tại", Toast.LENGTH_LONG).show()
                return
            }
            // Android 8+ can permission INSTALL_UNKNOWN_APPS
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!context.packageManager.canRequestPackageInstalls()) {
                    Toast.makeText(context, "Hãy bật quyền cài đặt từ nguồn không xác định", Toast.LENGTH_LONG).show()
                    val intent = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                        .setData(Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    return
                }
            }

            val uri = FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", apkFile
            )
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Install failed", e)
            Toast.makeText(context, "Lỗi cài: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
