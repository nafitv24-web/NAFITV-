package com.example.util

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

object AppUpdateHelper {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    /**
     * Converts indirect storage links (Google Drive, Dropbox) into direct binary download links
     */
    fun getDirectDownloadUrl(rawUrl: String): String {
        val trimmed = rawUrl.trim()
        if (trimmed.isBlank()) return ""

        // Convert Google Drive view/share link to direct file download
        if (trimmed.contains("drive.google.com", ignoreCase = true)) {
            val fileIdRegex = Regex("(?:/file/d/|/d/|id=)([a-zA-Z0-9_-]+)")
            val match = fileIdRegex.find(trimmed)
            if (match != null) {
                val fileId = match.groupValues[1]
                return "https://drive.google.com/uc?export=download&id=$fileId&confirm=t"
            }
        }

        // Convert Dropbox preview link to direct download
        if (trimmed.contains("dropbox.com", ignoreCase = true)) {
            return if (trimmed.contains("?dl=0")) {
                trimmed.replace("?dl=0", "?dl=1")
            } else if (!trimmed.contains("?dl=1")) {
                "$trimmed?dl=1"
            } else {
                trimmed
            }
        }

        return trimmed
    }

    /**
     * Validates if the downloaded file is a genuine binary APK (ZIP format)
     */
    fun isValidApkFile(file: File): Boolean {
        if (!file.exists() || file.length() < 500 * 1024) { // Minimum 500 KB for Android APK
            return false
        }
        return try {
            val header = ByteArray(4)
            FileInputStream(file).use { it.read(header) }
            // ZIP archive magic bytes: 'P', 'K', 0x03, 0x04 or 0x05, 0x06 (empty zip) or 0x07, 0x08 (spanned)
            header[0] == 0x50.toByte() && header[1] == 0x4B.toByte()
        } catch (e: Exception) {
            false
        }
    }

    suspend fun downloadApk(
        context: Context,
        downloadUrl: String,
        versionName: String,
        onProgress: (progress: Float, statusText: String) -> Unit,
        onSuccess: (file: File) -> Unit,
        onError: (errorMsg: String) -> Unit
    ) = withContext(Dispatchers.IO) {
        try {
            if (downloadUrl.isBlank()) {
                withContext(Dispatchers.Main) {
                    onError("ডাউনলোড লিংক পাওয়া যায়নি!")
                }
                return@withContext
            }

            val directUrl = getDirectDownloadUrl(downloadUrl)

            withContext(Dispatchers.Main) {
                onProgress(0.05f, "সার্ভারে কানেক্ট করা হচ্ছে...")
            }

            val request = Request.Builder()
                .url(directUrl)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36")
                .header("Accept", "*/*")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                withContext(Dispatchers.Main) {
                    onError("সার্ভার থেকে ফাইল পাওয়া যায়নি (Error ${response.code})")
                }
                return@withContext
            }

            val body = response.body
            if (body == null) {
                withContext(Dispatchers.Main) {
                    onError("ডাউনলোড ফাইল খালি পাওয়া গেছে!")
                }
                return@withContext
            }

            val contentLength = body.contentLength()
            val totalMb = if (contentLength > 0) String.format("%.1f MB", contentLength / (1024.0 * 1024.0)) else "ফাইল"

            // Target destination directory
            val storageDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: context.cacheDir
            if (!storageDir.exists()) storageDir.mkdirs()

            val safeVersion = versionName.replace("[^a-zA-Z0-9._-]".toRegex(), "_")
            val apkFileName = "NAFITV24_v${safeVersion}.apk"
            val apkFile = File(storageDir, apkFileName)
            if (apkFile.exists()) {
                apkFile.delete()
            }

            val inputStream = body.byteStream()
            val outputStream = FileOutputStream(apkFile)

            val buffer = ByteArray(32 * 1024)
            var bytesRead: Int
            var totalBytesRead: Long = 0
            var lastUpdateTimestamp = System.currentTimeMillis()

            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
                totalBytesRead += bytesRead

                val now = System.currentTimeMillis()
                if (now - lastUpdateTimestamp > 120 || totalBytesRead == contentLength) {
                    lastUpdateTimestamp = now
                    val progress = if (contentLength > 0) {
                        (totalBytesRead.toFloat() / contentLength.toFloat()).coerceIn(0f, 1f)
                    } else 0.5f
                    val downloadedMb = String.format("%.1f MB", totalBytesRead / (1024.0 * 1024.0))
                    val progressText = if (contentLength > 0) {
                        "$downloadedMb / $totalMb (${(progress * 100).toInt()}%)"
                    } else "$downloadedMb ডাউনলোড হয়েছে..."

                    withContext(Dispatchers.Main) {
                        onProgress(progress, progressText)
                    }
                }
            }

            outputStream.flush()
            outputStream.close()
            inputStream.close()

            // Validate the downloaded APK integrity
            if (!isValidApkFile(apkFile)) {
                apkFile.delete()
                withContext(Dispatchers.Main) {
                    onError("লিংকটি সরাসরি APK ফাইল নয় (সম্ভবত ড্রাইভ/ওয়েবপেজ লিংক)। ব্রাউজার দিয়ে ডাউনলোড করুন।")
                    openBrowserDownload(context, downloadUrl)
                }
                return@withContext
            }

            withContext(Dispatchers.Main) {
                onProgress(1f, "ডাউনলোড সম্পন্ন! ইনস্টলেশন প্রস্তুত...")
                onSuccess(apkFile)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            withContext(Dispatchers.Main) {
                onError("ডাউনলোডে সমস্যা হয়েছে: ${e.localizedMessage ?: "নেটওয়ার্ক ত্রুটি"}")
            }
        }
    }

    fun installApk(context: Context, apkFile: File) {
        try {
            if (!apkFile.exists()) {
                Toast.makeText(context, "ইনস্টলেশন ফাইল পাওয়া যায়নি!", Toast.LENGTH_SHORT).show()
                return
            }

            // Check if unknown sources permission is granted on Android 8.0+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val canInstall = context.packageManager.canRequestPackageInstalls()
                if (!canInstall) {
                    Toast.makeText(context, "ইনস্টল করার জন্য NAFI TV 24-এর পারমিশন চালু করুন", Toast.LENGTH_LONG).show()
                    val permissionIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = Uri.parse("package:${context.packageName}")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(permissionIntent)
                    return
                }
            }

            val apkUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                clipData = ClipData.newRawUri("NAFI TV 24 APK", apkUri)
            }

            // Explicitly grant URI permission to target handlers
            val resolveInfoList = context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            for (resolveInfo in resolveInfoList) {
                val pkg = resolveInfo.activityInfo.packageName
                context.grantUriPermission(pkg, apkUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            context.startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(context, "স্বয়ংক্রিয় ইনস্টল শুরু করা যায়নি: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    fun openBrowserDownload(context: Context, url: String) {
        try {
            if (url.isBlank()) {
                Toast.makeText(context, "ডাউনলোড লিংক নেই!", Toast.LENGTH_SHORT).show()
                return
            }
            val targetUri = Uri.parse(url.trim())
            val intent = Intent(Intent.ACTION_VIEW, targetUri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(context, "ব্রাউজার খোলা যায়নি: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
