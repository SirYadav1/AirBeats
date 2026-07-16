package com.darkxvenom.airbeats.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

class CloudBackupClient {
    private val client =
        OkHttpClient
            .Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

    // Create a safe folder name from the email
    private fun getEmailFolder(email: String): String {
        return email.replace("@", "_at_").replace(".", "_dot_")
    }

    suspend fun checkBackupExists(email: String): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val folder = getEmailFolder(email)
                val fileName = "airbeats/backups/$folder/details.json"
                val request =
                    Request
                        .Builder()
                        .url("$BASE_URL/read?file=$fileName&_t=${System.currentTimeMillis()}")
                        .header("Cache-Control", "no-cache")
                        .header("Pragma", "no-cache")
                        .get()
                        .build()
                client.newCall(request).execute().use { response ->
                    response.isSuccessful
                }
            }.getOrDefault(false)
        }

    suspend fun uploadBackup(email: String, name: String, backupFile: File): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val folder = getEmailFolder(email)
                
                // 1. Upload the details.json
                val detailsJson = JSONObject()
                    .put("email", email)
                    .put("name", name)
                    .put("lastBackupAt", System.currentTimeMillis())

                val detailsRequest = Request.Builder()
                    .url("$BASE_URL/write?file=airbeats/backups/$folder/details.json")
                    .addHeader("X-API-Key", API_KEY)
                    .post(detailsJson.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()
                
                val detailsSuccess = client.newCall(detailsRequest).execute().use { it.isSuccessful }
                if (!detailsSuccess) return@runCatching false

                // 2. Upload the actual backup zip file
                val backupRequest = Request.Builder()
                    .url("$BASE_URL/write?file=airbeats/backups/$folder/airbeats_backup.backup")
                    .addHeader("X-API-Key", API_KEY)
                    .post(backupFile.asRequestBody("application/octet-stream".toMediaType()))
                    .build()

                client.newCall(backupRequest).execute().use { it.isSuccessful }
            }.getOrDefault(false)
        }

    suspend fun downloadBackup(email: String, destFile: File): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val folder = getEmailFolder(email)
                val fileName = "airbeats/backups/$folder/airbeats_backup.backup"
                val request = Request.Builder()
                    .url("$BASE_URL/read?file=$fileName&_t=${System.currentTimeMillis()}")
                    .header("Cache-Control", "no-cache")
                    .get()
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@runCatching false
                    
                    val body = response.body ?: return@runCatching false
                    FileOutputStream(destFile).use { fos ->
                        body.byteStream().use { stream ->
                            stream.copyTo(fos)
                        }
                    }
                    true
                }
            }.getOrDefault(false)
        }

    private companion object {
        val BASE_URL = com.darkxvenom.airbeats.BuildConfig.STATS_BASE_URL
        val API_KEY = com.darkxvenom.airbeats.BuildConfig.STATS_API_KEY
    }
}
