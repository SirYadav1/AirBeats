package com.darkxvenom.airbeats.utils

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import androidx.core.content.getSystemService
import com.darkxvenom.airbeats.constants.AudioQuality
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

object RingtoneHelper {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun getStreamUrl(context: Context, songId: String): String? = withContext(Dispatchers.IO) {
        try {
            // 1. Check if audio is already cached locally
            val cached = SaveToStorageUtil.getCachedAudioBytes(context, songId)
            if (cached != null && cached.first.size > 200_000) {
                val previewFile = File(context.cacheDir, "temp_ringtone_preview_${songId.replace(Regex("[^a-zA-Z0-9_-]"), "_")}.${cached.second}")
                if (!previewFile.exists() || previewFile.length() != cached.first.size.toLong()) {
                    previewFile.writeBytes(cached.first)
                }
                return@withContext Uri.fromFile(previewFile).toString()
            }

            // 2. Resolve via YTPlayerUtils
            val connectivityManager = context.getSystemService<ConnectivityManager>()
            if (connectivityManager != null) {
                val result = YTPlayerUtils.playerResponseForPlayback(
                    videoId = songId,
                    audioQuality = AudioQuality.AUTO,
                    connectivityManager = connectivityManager,
                )
                val url = result.getOrNull()?.streamUrl
                if (!url.isNullOrBlank()) return@withContext url
            }

            null
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    suspend fun downloadAndTrimAsRingtone(
        context: Context,
        songId: String,
        title: String,
        artist: String,
        startMs: Long,
        endMs: Long,
        onProgress: (Float, String) -> Unit,
        onComplete: (Boolean, String, Uri?) -> Unit
    ) = withContext(Dispatchers.IO) {
        val safeId = songId.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val tempFile = File(context.cacheDir, "temp_ringtone_source_$safeId")
        val trimmedFile = File(context.cacheDir, "trimmed_ringtone_$safeId.m4a")

        try {
            onProgress(0.05f, "Getting audio stream...")

            // Check cached audio bytes first
            val cached = SaveToStorageUtil.getCachedAudioBytes(context, songId)
            if (cached != null && cached.first.size > 200_000) {
                onProgress(0.3f, "Using cached audio...")
                tempFile.writeBytes(cached.first)
            } else {
                val streamUrl = getStreamUrl(context, songId)
                if (streamUrl == null) {
                    withContext(Dispatchers.Main) {
                        onComplete(false, "Failed to get audio stream", null)
                    }
                    return@withContext
                }

                if (streamUrl.startsWith("file://")) {
                    val localSource = File(Uri.parse(streamUrl).path ?: "")
                    if (localSource.exists()) {
                        localSource.copyTo(tempFile, overwrite = true)
                    }
                } else {
                    onProgress(0.1f, "Fetching audio...")
                    val request = Request.Builder()
                        .url(streamUrl)
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                        .build()

                    val response = httpClient.newCall(request).execute()
                    val body = response.body
                    if (!response.isSuccessful || body == null) {
                        withContext(Dispatchers.Main) {
                            onComplete(false, "Failed to download audio stream", null)
                        }
                        return@withContext
                    }

                    val contentLength = body.contentLength()
                    body.byteStream().use { input ->
                        tempFile.outputStream().use { output ->
                            val buffer = ByteArray(8192)
                            var bytesRead: Int
                            var totalBytesRead = 0L

                            while (input.read(buffer).also { bytesRead = it } != -1) {
                                output.write(buffer, 0, bytesRead)
                                totalBytesRead += bytesRead

                                if (contentLength > 0) {
                                    val progress = 0.1f + (totalBytesRead.toFloat() / contentLength) * 0.4f
                                    withContext(Dispatchers.Main) {
                                        onProgress(progress, "Downloading... ${(progress * 100).toInt()}%")
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (!tempFile.exists() || tempFile.length() == 0L) {
                withContext(Dispatchers.Main) {
                    onComplete(false, "Failed to prepare source file", null)
                }
                return@withContext
            }

            onProgress(0.6f, "Processing audio...")

            if (trimmedFile.exists()) trimmedFile.delete()

            // Perform studio-quality PCM decoding and AAC encoding
            var success = false
            try {
                success = AudioTrimmerUtil.trimAudio(tempFile, trimmedFile, startMs, endMs)
            } catch (e: Exception) {
                e.printStackTrace()
            }

            // Fallback: If trimming failed, copy source directly
            if (!success || !trimmedFile.exists() || trimmedFile.length() == 0L) {
                tempFile.copyTo(trimmedFile, overwrite = true)
                success = trimmedFile.exists() && trimmedFile.length() > 0L
            }

            if (!success || !trimmedFile.exists() || trimmedFile.length() == 0L) {
                withContext(Dispatchers.Main) {
                    onComplete(false, "Failed to process audio or output is empty", null)
                }
                return@withContext
            }

            onProgress(0.85f, "Saving ringtone...")

            val cleanTitle = title.replace(Regex("[^a-zA-Z0-9\\s]"), "").trim().ifBlank { "Song" }
            val fileName = "${cleanTitle}_ringtone_$safeId.m4a"

            val ringtoneUri: Uri = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val contentValues = ContentValues().apply {
                        put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
                        put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
                        put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_RINGTONES)
                        put(MediaStore.Audio.Media.IS_RINGTONE, true)
                        put(MediaStore.Audio.Media.IS_NOTIFICATION, true)
                        put(MediaStore.Audio.Media.IS_ALARM, true)
                        put(MediaStore.Audio.Media.TITLE, "$title (Ringtone)")
                        put(MediaStore.Audio.Media.ARTIST, artist)
                        put(MediaStore.Audio.Media.IS_PENDING, 1)
                    }

                    val uri = context.contentResolver.insert(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        contentValues
                    ) ?: throw Exception("Failed to create MediaStore entry")

                    context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                        trimmedFile.inputStream().use { inputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    }

                    contentValues.clear()
                    contentValues.put(MediaStore.Audio.Media.IS_PENDING, 0)
                    context.contentResolver.update(uri, contentValues, null, null)
                    uri
                } else {
                    val ringtonesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_RINGTONES)
                    if (!ringtonesDir.exists()) ringtonesDir.mkdirs()

                    val file = File(ringtonesDir, fileName)
                    trimmedFile.copyTo(file, overwrite = true)

                    val contentValues = ContentValues().apply {
                        put(MediaStore.Audio.Media.DATA, file.absolutePath)
                        put(MediaStore.Audio.Media.TITLE, "$title (Ringtone)")
                        put(MediaStore.Audio.Media.ARTIST, artist)
                        put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
                        put(MediaStore.Audio.Media.IS_RINGTONE, true)
                        put(MediaStore.Audio.Media.IS_NOTIFICATION, true)
                        put(MediaStore.Audio.Media.IS_ALARM, true)
                    }

                    context.contentResolver.insert(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        contentValues
                    ) ?: Uri.fromFile(file)
                }
            } finally {
                tempFile.delete()
                trimmedFile.delete()
            }

            onProgress(0.95f, "Song saved to Ringtones...")

            withContext(Dispatchers.Main) {
                onProgress(1f, "Done!")
                onComplete(true, "\"$title\" added to system ringtones. Please select it from settings.", ringtoneUri)
            }

        } catch (e: Exception) {
            e.printStackTrace()
            withContext(Dispatchers.Main) {
                onComplete(false, "Error: ${e.message}", null)
            }
        } finally {
            if (tempFile.exists()) tempFile.delete()
            if (trimmedFile.exists()) trimmedFile.delete()
        }
    }

    fun openRingtoneSettings(context: Context, ringtoneUri: Uri? = null) {
        try {
            val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_RINGTONE)
                putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Select Ringtone")
                if (ringtoneUri != null) {
                    putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, ringtoneUri)
                }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            val intent = Intent(Settings.ACTION_SOUND_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    fun hasSettingsPermission(context: Context): Boolean {
        return true
    }

    fun requestSettingsPermission(context: Context) {
        // No additional permission required for MediaStore Environment.DIRECTORY_RINGTONES
    }
}
