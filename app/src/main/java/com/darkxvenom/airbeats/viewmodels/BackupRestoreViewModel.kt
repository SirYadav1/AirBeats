package com.darkxvenom.airbeats.viewmodels

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.darkxvenom.airbeats.MainActivity
import com.darkxvenom.airbeats.R
import com.darkxvenom.airbeats.db.InternalDatabase
import com.darkxvenom.airbeats.db.MusicDatabase
import com.darkxvenom.airbeats.db.entities.ArtistEntity
import com.darkxvenom.airbeats.db.entities.Event
import com.darkxvenom.airbeats.db.entities.FormatEntity
import com.darkxvenom.airbeats.db.entities.Song
import com.darkxvenom.airbeats.db.entities.SongEntity
import com.darkxvenom.airbeats.models.MediaMetadata
import java.time.LocalDateTime
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import com.darkxvenom.airbeats.extensions.div
import com.darkxvenom.airbeats.extensions.tryOrNull
import com.darkxvenom.airbeats.extensions.zipInputStream
import com.darkxvenom.airbeats.extensions.zipOutputStream
import com.darkxvenom.airbeats.playback.MusicService
import com.darkxvenom.airbeats.playback.MusicService.Companion.PERSISTENT_QUEUE_FILE
import com.darkxvenom.airbeats.ui.component.NamePreferenceManager
import com.darkxvenom.airbeats.utils.reportException
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import javax.inject.Inject
import kotlin.system.exitProcess
import timber.log.Timber
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.darkxvenom.airbeats.utils.AirBeatsStatsCloudSync
import com.darkxvenom.airbeats.utils.AutoBackupManager

@HiltViewModel
class BackupRestoreViewModel @Inject constructor(
    val database: MusicDatabase,
) : ViewModel() {


    private val _lastOsBackupTime = MutableStateFlow(0L)
    val lastOsBackupTime: StateFlow<Long> = _lastOsBackupTime.asStateFlow()

    private val _isBackingUp = MutableStateFlow(false)
    val isBackingUp: StateFlow<Boolean> = _isBackingUp.asStateFlow()

    private val _isRestoring = MutableStateFlow(false)
    val isRestoring: StateFlow<Boolean> = _isRestoring.asStateFlow()

    private val _backupSizeString = MutableStateFlow("~0 KB")
    val backupSizeString: StateFlow<String> = _backupSizeString.asStateFlow()

    private val _isAutoBackupToStorage = MutableStateFlow(false)
    val isAutoBackupToStorage: StateFlow<Boolean> = _isAutoBackupToStorage.asStateFlow()

    fun loadOsBackupState(context: Context) {
        _lastOsBackupTime.value = AutoBackupManager.getLastBackupTime(context)
        _isAutoBackupToStorage.value = AutoBackupManager.isAutoBackupToStorageEnabled(context)
        updateBackupSize(context)
    }

    fun setAutoBackupToStorage(context: Context, enabled: Boolean) {
        AutoBackupManager.setAutoBackupToStorageEnabled(context, enabled)
        _isAutoBackupToStorage.value = enabled
    }

    fun backupToStorageNow(context: Context, onResult: (Boolean) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val success = AutoBackupManager.saveBackupToStorage(context, database)
            withContext(Dispatchers.Main) {
                onResult(success)
            }
        }
    }

    fun restoreFromStorageNow(context: Context, onResult: (Boolean) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val success = AutoBackupManager.restoreFromStorageBackup(context, shouldRestart = true)
            withContext(Dispatchers.Main) {
                onResult(success)
            }
        }
    }

    fun updateBackupSize(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            val totalBytes = AutoBackupManager.getBackupSize(context)
            val kb = totalBytes / 1024
            _backupSizeString.value = if (kb > 1024) String.format(java.util.Locale.US, "%.1f MB", kb / 1024f) else "$kb KB"
        }
    }

    fun createBackupZip(context: Context, rawStream: java.io.OutputStream) {
        rawStream.buffered().zipOutputStream().use { outputStream ->
            val settingsFile = context.filesDir / "datastore" / SETTINGS_FILENAME
            if (settingsFile.exists()) {
                settingsFile.inputStream().buffered().use { inputStream ->
                    outputStream.putNextEntry(ZipEntry(SETTINGS_FILENAME))
                    inputStream.copyTo(outputStream)
                }
            }

            val namePrefsFile = context.filesDir / "datastore" / "user_name_preferences.preferences_pb"
            if (namePrefsFile.exists()) {
                namePrefsFile.inputStream().buffered().use { inputStream ->
                    outputStream.putNextEntry(ZipEntry("user_name_preferences.preferences_pb"))
                    inputStream.copyTo(outputStream)
                }
            }

            val accountEmail = runBlocking { NamePreferenceManager(context).accountEmail.first() }
            if (accountEmail.isNotBlank()) {
                outputStream.putNextEntry(ZipEntry(GOOGLE_ACCOUNT_FILENAME))
                outputStream.write(
                    JSONObject()
                        .put("email", accountEmail)
                        .put("previouslyLoggedIn", true)
                        .toString()
                        .toByteArray()
                )
            }

            val currentUid = context.getSharedPreferences(AirBeatsStatsCloudSync.PREFERENCES_NAME, Context.MODE_PRIVATE)
                .getString(AirBeatsStatsCloudSync.KEY_USER_ID, null)
                ?: runCatching {
                    val f = File(context.filesDir, AutoBackupManager.STATS_IDENTITY_FILENAME)
                    if (f.exists()) JSONObject(f.readText()).optString("userId").takeIf { it.isNotBlank() } else null
                }.getOrNull()
            val currentName = runCatching {
                runBlocking { NamePreferenceManager(context).userName.first() }
            }.getOrDefault("")

            val currentUserNum = AirBeatsStatsCloudSync.getUserNumber(context)

            if (!currentUid.isNullOrBlank() || !currentUserNum.isNullOrBlank()) {
                outputStream.putNextEntry(ZipEntry(AutoBackupManager.STATS_IDENTITY_FILENAME))
                outputStream.write(
                    JSONObject()
                        .put("userId", currentUid ?: "")
                        .put("userNumber", currentUserNum ?: "")
                        .put("name", currentName)
                        .put("email", accountEmail)
                        .toString()
                        .toByteArray(Charsets.UTF_8)
                )
            }

            val parentFile = context.filesDir.parentFile
            if (parentFile != null) {
                val statsPrefsFile = parentFile / "shared_prefs" / "airbeats_global_stats.xml"
                if (statsPrefsFile.exists()) {
                    statsPrefsFile.inputStream().buffered().use { inputStream ->
                        outputStream.putNextEntry(ZipEntry("airbeats_global_stats.xml"))
                        inputStream.copyTo(outputStream)
                    }
                } else if (!currentUid.isNullOrBlank() || !currentUserNum.isNullOrBlank()) {
                    val xmlFallback = """<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    ${if (!currentUid.isNullOrBlank()) "<string name=\"${AirBeatsStatsCloudSync.KEY_USER_ID}\">$currentUid</string>" else ""}
    ${if (!currentUserNum.isNullOrBlank()) "<string name=\"${AirBeatsStatsCloudSync.KEY_USER_NUMBER}\">$currentUserNum</string>" else ""}
</map>""".trimIndent()
                    outputStream.putNextEntry(ZipEntry("airbeats_global_stats.xml"))
                    outputStream.write(xmlFallback.toByteArray(Charsets.UTF_8))
                }

                val playlistImagesPrefs = parentFile / "shared_prefs" / "playlist_images.xml"
                if (playlistImagesPrefs.exists()) {
                    playlistImagesPrefs.inputStream().buffered().use { inputStream ->
                        outputStream.putNextEntry(ZipEntry("playlist_images.xml"))
                        inputStream.copyTo(outputStream)
                    }
                }
            }

            val playlistImagesDir = context.filesDir / "playlist_images"
            if (playlistImagesDir.exists() && playlistImagesDir.isDirectory) {
                playlistImagesDir.listFiles()?.forEach { imgFile ->
                    if (imgFile.isFile) {
                        imgFile.inputStream().buffered().use { inputStream ->
                            outputStream.putNextEntry(ZipEntry("playlist_images/${imgFile.name}"))
                            inputStream.copyTo(outputStream)
                        }
                    }
                }
            }

            runCatching {
                runBlocking(Dispatchers.IO) {
                    database.checkpoint()
                }
            }
            val dbFile = context.getDatabasePath(InternalDatabase.DB_NAME)
            if (dbFile.exists()) {
                FileInputStream(dbFile).use { inputStream ->
                    outputStream.putNextEntry(ZipEntry(InternalDatabase.DB_NAME))
                    inputStream.copyTo(outputStream)
                }
            }
            val walFile = context.getDatabasePath("${InternalDatabase.DB_NAME}-wal")
            if (walFile.exists() && walFile.length() > 0) {
                FileInputStream(walFile).use { inputStream ->
                    outputStream.putNextEntry(ZipEntry("${InternalDatabase.DB_NAME}-wal"))
                    inputStream.copyTo(outputStream)
                }
            }
            val shmFile = context.getDatabasePath("${InternalDatabase.DB_NAME}-shm")
            if (shmFile.exists() && shmFile.length() > 0) {
                FileInputStream(shmFile).use { inputStream ->
                    outputStream.putNextEntry(ZipEntry("${InternalDatabase.DB_NAME}-shm"))
                    inputStream.copyTo(outputStream)
                }
            }
        }
    }

    fun backup(context: Context, uri: Uri) {
        runCatching {
            context.applicationContext.contentResolver.openOutputStream(uri)?.use { outputStream ->
                createBackupZip(context, outputStream)
            }
        }.onSuccess {
            Toast.makeText(context, R.string.backup_create_success, Toast.LENGTH_SHORT).show()
        }.onFailure {
            reportException(it)
            Toast.makeText(context, R.string.backup_create_failed, Toast.LENGTH_SHORT).show()
        }
    }

    fun backupNow(context: Context, onComplete: ((Boolean) -> Unit)? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            _isBackingUp.value = true
            try {
                val success = AutoBackupManager.createAutoBackup(context, database, notifyBackupManager = true)
                if (success) {
                    val backupFile = AutoBackupManager.getAutoBackupFile(context)
                    val cloudSuccess = AutoBackupManager.uploadToCloud(context, backupFile)
                    val now = System.currentTimeMillis()
                    _lastOsBackupTime.value = now
                    updateBackupSize(context)
                    withContext(Dispatchers.Main) {
                        val message = if (cloudSuccess) {
                            "Cloud backup saved successfully!"
                        } else {
                            context.getString(R.string.backup_now_success)
                        }
                        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                        onComplete?.invoke(true)
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, R.string.backup_create_failed, Toast.LENGTH_SHORT).show()
                        onComplete?.invoke(false)
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "backupNow failed")
                reportException(e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, R.string.backup_create_failed, Toast.LENGTH_SHORT).show()
                    onComplete?.invoke(false)
                }
            } finally {
                _isBackingUp.value = false
            }
        }
    }

    fun restoreFromLatestBackup(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            _isRestoring.value = true
            try {
                var success = AutoBackupManager.restoreAutoBackup(context, shouldRestart = true)
                if (!success) {
                    // Try fetching device cloud backup from server
                    success = AutoBackupManager.checkAndRestoreDeviceCloudBackup(context)
                }
                if (!success) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, R.string.backup_no_snapshot, Toast.LENGTH_LONG).show()
                    }
                }
            } finally {
                _isRestoring.value = false
            }
        }
    }

    fun deleteBackup(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                AutoBackupManager.deleteBackup(context)
                AutoBackupManager.deleteFromCloud(context)
                _lastOsBackupTime.value = 0L
                updateBackupSize(context)
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Cloud and device backup deleted", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Timber.e(e, "deleteBackup failed")
            }
        }
    }

    fun openDeviceBackupSettings(context: Context) {
        val intentList = listOf(
            Intent("android.settings.BACKUP_SETTINGS"),
            Intent("com.google.android.gms.BACKUP"),
            Intent(android.provider.Settings.ACTION_PRIVACY_SETTINGS),
            Intent(android.provider.Settings.ACTION_SETTINGS)
        )
        for (intentListEntry in intentList) {
            try {
                intentListEntry.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intentListEntry)
                return
            } catch (_: Exception) {}
        }
        Toast.makeText(context, "Could not open system backup settings", Toast.LENGTH_SHORT).show()
    }

    fun restore(context: Context, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                Timber.d("Starting local restore from Uri: $uri")
                val targetFile = AutoBackupManager.getAutoBackupFile(context)
                context.applicationContext.contentResolver.openInputStream(uri)?.use { stream ->
                    FileOutputStream(targetFile).use { fos ->
                        stream.copyTo(fos)
                    }
                }
                if (targetFile.exists() && targetFile.length() > 0) {
                    // Promote manually restored backup to cloud device backup immediately
                    launch(Dispatchers.IO) {
                        AutoBackupManager.uploadToCloud(context, targetFile)
                    }
                    runCatching {
                        FileInputStream(targetFile).use { stream ->
                            AutoBackupManager.restoreFromInputStream(context, stream, shouldRestart = true)
                        }
                    }
                }
            }.onFailure {
                Timber.e(it, "Local restore failed")
                reportException(it)
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, R.string.restore_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun restoreFromFile(context: Context, file: java.io.File) {
        runCatching {
            Timber.d("Starting restore from file: ${file.absolutePath}")
            file.inputStream().buffered().use { stream ->
                restoreFromInputStream(context, stream)
            }
        }.onFailure {
            Timber.e(it, "File restore failed")
            reportException(it)
            Toast.makeText(context, R.string.restore_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun restoreFromInputStream(context: Context, rawStream: java.io.InputStream) {
        rawStream.zipInputStream().use { inputStream ->
            var entry = tryOrNull { inputStream.nextEntry } // prevent ZipException
            while (entry != null) {
                Timber.d("Restore processing entry: ${entry.name}")
                when (entry.name) {
                    SETTINGS_FILENAME -> {
                        val destFile = context.filesDir / "datastore" / SETTINGS_FILENAME
                        destFile.parentFile?.mkdirs()
                        destFile.outputStream().use { outputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    }

                    "user_name_preferences.preferences_pb" -> {
                        val destFile = context.filesDir / "datastore" / "user_name_preferences.preferences_pb"
                        destFile.parentFile?.mkdirs()
                        destFile.outputStream().use { outputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    }

                    AutoBackupManager.STATS_IDENTITY_FILENAME -> {
                        val content = inputStream.readBytes().toString(Charsets.UTF_8)
                        runCatching {
                            val json = JSONObject(content)
                            val uid = json.optString("userId").trim()
                            if (uid.isNotBlank()) {
                                AirBeatsStatsCloudSync.persistUserId(
                                    context,
                                    context.getSharedPreferences(AirBeatsStatsCloudSync.PREFERENCES_NAME, Context.MODE_PRIVATE),
                                    uid
                                )
                                Timber.i("BackupRestoreViewModel: Restored stats userId from identity json: $uid")
                            }
                            val userNum = json.optString("userNumber").trim()
                            if (userNum.isNotBlank()) {
                                AirBeatsStatsCloudSync.persistUserNumber(context, userNum)
                                Timber.i("BackupRestoreViewModel: Restored stats userNumber from identity json: $userNum")
                            }
                        }
                    }

                    "airbeats_global_stats.xml" -> {
                        val bytes = inputStream.readBytes()
                        val xmlStr = bytes.toString(Charsets.UTF_8)
                        val uidRegex = """<string name="${AirBeatsStatsCloudSync.KEY_USER_ID}">([^<]+)</string>""".toRegex()
                        val match = uidRegex.find(xmlStr)
                        val extractedUid = match?.groupValues?.get(1)?.trim()
                        if (!extractedUid.isNullOrBlank()) {
                            AirBeatsStatsCloudSync.persistUserId(
                                context,
                                context.getSharedPreferences(AirBeatsStatsCloudSync.PREFERENCES_NAME, Context.MODE_PRIVATE),
                                extractedUid
                            )
                            Timber.i("BackupRestoreViewModel: Restored stats userId from XML: $extractedUid")
                        }
                        val numRegex = """<string name="${AirBeatsStatsCloudSync.KEY_USER_NUMBER}">([^<]+)</string>""".toRegex()
                        val numMatch = numRegex.find(xmlStr)
                        val extractedNum = numMatch?.groupValues?.get(1)?.trim()
                        if (!extractedNum.isNullOrBlank()) {
                            AirBeatsStatsCloudSync.persistUserNumber(context, extractedNum)
                            Timber.i("BackupRestoreViewModel: Restored stats userNumber from XML: $extractedNum")
                        }
                        val parentFile = context.filesDir.parentFile
                        if (parentFile != null) {
                            val destFile = parentFile / "shared_prefs" / "airbeats_global_stats.xml"
                            destFile.parentFile?.mkdirs()
                            destFile.outputStream().use { outputStream ->
                                outputStream.write(bytes)
                            }
                        }
                    }

                    GOOGLE_ACCOUNT_FILENAME -> {
                        val email = inputStream.readBytes()
                            .toString(Charsets.UTF_8)
                            .let { JSONObject(it).optString("email") }
                            .trim()
                        if (email.isNotBlank()) {
                            runBlocking {
                                NamePreferenceManager(context).rememberGoogleLoginEmail(email)
                            }
                        }
                    }

                    InternalDatabase.DB_NAME -> {
                        runCatching {
                            runBlocking(Dispatchers.IO) {
                                database.checkpoint()
                            }
                        }
                        database.close()
                        val dbFile = context.getDatabasePath(InternalDatabase.DB_NAME)
                        dbFile.parentFile?.mkdirs()
                        FileOutputStream(dbFile).use { outputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    }

                    "${InternalDatabase.DB_NAME}-wal" -> {
                        val walFile = context.getDatabasePath("${InternalDatabase.DB_NAME}-wal")
                        walFile.parentFile?.mkdirs()
                        FileOutputStream(walFile).use { outputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    }

                    "${InternalDatabase.DB_NAME}-shm" -> {
                        val shmFile = context.getDatabasePath("${InternalDatabase.DB_NAME}-shm")
                        shmFile.parentFile?.mkdirs()
                        FileOutputStream(shmFile).use { outputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    }

                    "playlist_images.xml" -> {
                        val parentFile = context.filesDir.parentFile
                        if (parentFile != null) {
                            val destFile = parentFile / "shared_prefs" / "playlist_images.xml"
                            destFile.parentFile?.mkdirs()
                            destFile.outputStream().use { outputStream ->
                                inputStream.copyTo(outputStream)
                            }
                        }
                    }

                    else -> {
                        if (entry.name.startsWith("playlist_images/")) {
                            val relName = entry.name.removePrefix("playlist_images/")
                            if (relName.isNotBlank()) {
                                val destFile = context.filesDir / "playlist_images" / relName
                                destFile.parentFile?.mkdirs()
                                destFile.outputStream().use { outputStream ->
                                    inputStream.copyTo(outputStream)
                                }
                            }
                        }
                    }
                }
                entry = tryOrNull { inputStream.nextEntry } // prevent ZipException
            }
        }
        context.filesDir.resolve(PERSISTENT_QUEUE_FILE).delete()
        Timber.d("Restore finished successfully, restarting app")
        restartApp(context)
    }


    fun importPlaylistFromCsv(context: Context, uri: Uri): ArrayList<Song> {
        val songs = arrayListOf<Song>()
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val lines = stream.bufferedReader().readLines()
                lines.forEachIndexed { _, line ->
                    val parts = line.split(",").map { it.trim() }
                    val title = parts[0]
                    val artistStr = parts[1]

                    val artists = artistStr.split(";").map { it.trim() }.map {
                        ArtistEntity(
                            id = "",
                            name = it,
                        )
                    }
                    val mockSong = Song(
                        song = SongEntity(
                            id = "",
                            title = title,
                        ),
                        artists = artists,
                    )
                    songs.add(mockSong)
                }
            }
        }

        if (songs.isEmpty()) {
            Toast.makeText(
                context,
                "No songs found. Invalid file, or perhaps no song matches were found.",
                Toast.LENGTH_SHORT
            ).show()
        }
        return songs
    }

    fun loadM3UOnline(
        context: Context,
        uri: Uri,
    ): ArrayList<Song> {
        val songs = ArrayList<Song>()

        runCatching {
            context.applicationContext.contentResolver.openInputStream(uri)?.use { stream ->
                val lines = stream.bufferedReader().readLines()
                if (lines.firstOrNull()?.startsWith("#EXTM3U") == true) {
                    lines.forEachIndexed { _, rawLine ->
                        if (rawLine.startsWith("#EXTINF:")) {
                            // maybe later write this to be more efficient
                            val artists =
                                rawLine.substringAfter("#EXTINF:").substringAfter(',').substringBefore(" - ").split(';')
                            val title = rawLine.substringAfter("#EXTINF:").substringAfter(',').substringAfter(" - ")

                            val mockSong = Song(
                                song = SongEntity(
                                    id = "",
                                    title = title,
                                ),
                                artists = artists.map { ArtistEntity("", it) },
                            )
                            songs.add(mockSong)

                        }
                    }
                }
            }
        }

        if (songs.isEmpty()) {
            Toast.makeText(
                context,
                "No songs found. Invalid file, or perhaps no song matches were found.",
                Toast.LENGTH_SHORT
            ).show()
        }
        return songs
    }

    fun resetVisitorData(context: Context) {
        runCatching {
            // Implementa aquí cómo borras VISITOR_DATA, por ejemplo, desde DataStore
            val visitorDataFile = context.filesDir / "datastore" / SETTINGS_FILENAME
            if (visitorDataFile.exists()) {
                // Borra solo la parte de VISITOR_DATA si es posible, o reinicia el archivo
                visitorDataFile.delete()
            }

            Toast.makeText(
                context,
                "VISITOR_DATA reseteado. La aplicación se reiniciará.",
                Toast.LENGTH_SHORT
            ).show()

            context.stopService(Intent(context, MusicService::class.java))
            context.filesDir.resolve(PERSISTENT_QUEUE_FILE).delete()
            context.startActivity(
                Intent(
                    context,
                    MainActivity::class.java
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            exitProcess(0)
        }.onFailure {
            reportException(it)
            Toast.makeText(context, "Error al resetear VISITOR_DATA", Toast.LENGTH_SHORT).show()
        }
    }

    fun restartApp(context: Context) {
        try {
            context.stopService(Intent(context, MusicService::class.java))
        } catch (_: Exception) {}

        val packageManager = context.packageManager
        val intent = packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }

        if (intent != null) {
            try {
                val pendingIntent = PendingIntent.getActivity(
                    context,
                    24601,
                    intent,
                    PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
                alarmManager?.set(
                    AlarmManager.RTC,
                    System.currentTimeMillis() + 400,
                    pendingIntent
                )
            } catch (_: Exception) {}
            try {
                context.startActivity(intent)
            } catch (_: Exception) {}
        }

        try {
            Thread.sleep(350)
        } catch (_: InterruptedException) {}

        android.os.Process.killProcess(android.os.Process.myPid())
        exitProcess(0)
    }

    fun backupCache(context: Context, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                tryOrNull { database.checkpoint() }

                context.applicationContext.contentResolver.openOutputStream(uri)?.use { stream ->
                    stream.buffered().zipOutputStream().use { zipOut ->
                        // 1. Backup exoplayer cache chunks
                        val exoDir = context.filesDir.resolve("exoplayer")
                        if (exoDir.exists() && exoDir.isDirectory) {
                            exoDir.walkTopDown().forEach { file ->
                                if (file.isFile) {
                                    tryOrNull {
                                        val relPath = "exoplayer/" + file.relativeTo(exoDir).path.replace('\\', '/')
                                        zipOut.putNextEntry(ZipEntry(relPath))
                                        file.inputStream().buffered().use { it.copyTo(zipOut) }
                                    }
                                }
                            }
                        }

                        // 2. Backup download cache if present
                        val dlDir = context.filesDir.resolve("download")
                        if (dlDir.exists() && dlDir.isDirectory) {
                            dlDir.walkTopDown().forEach { file ->
                                if (file.isFile) {
                                    tryOrNull {
                                        val relPath = "download/" + file.relativeTo(dlDir).path.replace('\\', '/')
                                        zipOut.putNextEntry(ZipEntry(relPath))
                                        file.inputStream().buffered().use { it.copyTo(zipOut) }
                                    }
                                }
                            }
                        }

                        // 3. Backup exoplayer internal database
                        val exoDb = context.getDatabasePath("exoplayer_internal.db")
                        if (exoDb.exists() && exoDb.isFile) {
                            tryOrNull {
                                SQLiteDatabase.openDatabase(exoDb.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                                    db.rawQuery("PRAGMA wal_checkpoint(FULL)", null).use { it.moveToFirst() }
                                }
                            }
                            tryOrNull {
                                zipOut.putNextEntry(ZipEntry("exoplayer_internal.db"))
                                exoDb.inputStream().buffered().use { it.copyTo(zipOut) }
                            }
                        }
                        val exoDbWal = context.getDatabasePath("exoplayer_internal.db-wal")
                        if (exoDbWal.exists() && exoDbWal.isFile) {
                            tryOrNull {
                                zipOut.putNextEntry(ZipEntry("exoplayer_internal.db-wal"))
                                exoDbWal.inputStream().buffered().use { it.copyTo(zipOut) }
                            }
                        }

                        // Determine actual cached song IDs from exoplayer_internal.db and restored_cache_ids.json
                        val actualCachedIds = mutableSetOf<String>()
                        val exoDbPath = context.getDatabasePath("exoplayer_internal.db")
                        if (exoDbPath.exists() && exoDbPath.isFile) {
                            tryOrNull {
                                SQLiteDatabase.openDatabase(exoDbPath.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                                    val indexTables = mutableListOf<String>()
                                    db.rawQuery(
                                        "SELECT name FROM sqlite_master WHERE type='table' AND name LIKE 'ExoPlayerCacheIndex%'",
                                        null
                                    ).use { c ->
                                        while (c.moveToNext()) {
                                            indexTables.add(c.getString(0))
                                        }
                                    }
                                    for (tbl in indexTables) {
                                        tryOrNull {
                                            db.rawQuery("SELECT `key` FROM `$tbl`", null).use { c ->
                                                val keyIdx = c.getColumnIndex("key")
                                                if (keyIdx != -1) {
                                                    while (c.moveToNext()) {
                                                        val k = c.getString(keyIdx)
                                                        if (!k.isNullOrBlank()) {
                                                            actualCachedIds.add(k)
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Also include any verified IDs from restored_cache_ids.json
                        val restoredFile = context.filesDir.resolve("restored_cache_ids.json")
                        if (restoredFile.exists() && restoredFile.isFile) {
                            tryOrNull {
                                val arr = JSONArray(restoredFile.readText())
                                for (i in 0 until arr.length()) {
                                    val rId = arr.getString(i)
                                    if (!rId.isNullOrBlank()) {
                                        actualCachedIds.add(rId)
                                    }
                                }
                            }
                        }

                        // Backup restored_cache_ids.json containing ONLY actual cached songs
                        if (actualCachedIds.isNotEmpty()) {
                            tryOrNull {
                                zipOut.putNextEntry(ZipEntry("restored_cache_ids.json"))
                                zipOut.write(JSONArray(actualCachedIds.toList()).toString().toByteArray(Charsets.UTF_8))
                            }
                        }

                        // 4. Backup ONLY cached song metadata and formats (do NOT backup full playback history)
                        tryOrNull {
                            val formatsMap = mutableMapOf<String, FormatEntity>()
                            tryOrNull {
                                database.openHelper.readableDatabase.query(
                                    "SELECT id, itag, mimeType, codecs, bitrate, sampleRate, contentLength, loudnessDb, playbackUrl FROM format"
                                ).use { cursor ->
                                    val idCol = cursor.getColumnIndex("id")
                                    val itagCol = cursor.getColumnIndex("itag")
                                    val mimeCol = cursor.getColumnIndex("mimeType")
                                    val codecsCol = cursor.getColumnIndex("codecs")
                                    val bitrateCol = cursor.getColumnIndex("bitrate")
                                    val sampleCol = cursor.getColumnIndex("sampleRate")
                                    val lenCol = cursor.getColumnIndex("contentLength")
                                    val loudCol = cursor.getColumnIndex("loudnessDb")
                                    val urlCol = cursor.getColumnIndex("playbackUrl")
                                    while (cursor.moveToNext()) {
                                        val sId = cursor.getString(idCol)
                                        if (sId in actualCachedIds) {
                                            formatsMap[sId] = FormatEntity(
                                                id = sId,
                                                itag = cursor.getInt(itagCol),
                                                mimeType = cursor.getString(mimeCol),
                                                codecs = cursor.getString(codecsCol),
                                                bitrate = cursor.getInt(bitrateCol),
                                                sampleRate = if (!cursor.isNull(sampleCol)) cursor.getInt(sampleCol) else null,
                                                contentLength = cursor.getLong(lenCol),
                                                loudnessDb = if (!cursor.isNull(loudCol)) cursor.getDouble(loudCol) else null,
                                                playbackUrl = if (!cursor.isNull(urlCol)) cursor.getString(urlCol) else null
                                            )
                                        }
                                    }
                                }
                            }

                            val songsList = mutableListOf<SongEntity>()
                            tryOrNull {
                                database.openHelper.readableDatabase.query(
                                    "SELECT id, title, duration, thumbnailUrl FROM song"
                                ).use { cursor ->
                                    val idCol = cursor.getColumnIndex("id")
                                    val titleCol = cursor.getColumnIndex("title")
                                    val durCol = cursor.getColumnIndex("duration")
                                    val thumbCol = cursor.getColumnIndex("thumbnailUrl")
                                    while (cursor.moveToNext()) {
                                        val sId = cursor.getString(idCol)
                                        if (sId in actualCachedIds) {
                                            songsList.add(
                                                SongEntity(
                                                    id = sId,
                                                    title = cursor.getString(titleCol),
                                                    duration = cursor.getInt(durCol),
                                                    thumbnailUrl = if (!cursor.isNull(thumbCol)) cursor.getString(thumbCol) else null
                                                )
                                            )
                                        }
                                    }
                                }
                            }

                            val artistMap = mutableMapOf<String, MutableList<String>>()
                            tryOrNull {
                                database.openHelper.readableDatabase.query(
                                    "SELECT song_artist_map.songId, artist.name FROM song_artist_map JOIN artist ON song_artist_map.artistId = artist.id"
                                ).use { cursor ->
                                    val songIdCol = cursor.getColumnIndex("songId")
                                    val nameCol = cursor.getColumnIndex("name")
                                    while (cursor.moveToNext()) {
                                        val sId = cursor.getString(songIdCol)
                                        if (sId in actualCachedIds) {
                                            val name = cursor.getString(nameCol)
                                            artistMap.getOrPut(sId) { mutableListOf() }.add(name)
                                        }
                                    }
                                }
                            }

                            val songJsonArray = JSONArray()
                            for (song in songsList) {
                                val format = formatsMap[song.id]
                                val artists = artistMap[song.id] ?: emptyList()
                                val obj = JSONObject().apply {
                                    put("id", song.id)
                                    put("title", song.title)
                                    put("duration", song.duration)
                                    put("thumbnailUrl", song.thumbnailUrl)
                                    put("artists", JSONArray(artists))
                                    if (format != null) {
                                        put("itag", format.itag)
                                        put("mimeType", format.mimeType)
                                        put("codecs", format.codecs)
                                        put("bitrate", format.bitrate)
                                        put("sampleRate", format.sampleRate)
                                        put("contentLength", format.contentLength)
                                        if (format.loudnessDb != null) put("loudnessDb", format.loudnessDb)
                                        if (format.playbackUrl != null) put("playbackUrl", format.playbackUrl)
                                    }
                                }
                                songJsonArray.put(obj)
                            }
                            zipOut.putNextEntry(ZipEntry("cached_songs_metadata.json"))
                            zipOut.write(songJsonArray.toString().toByteArray(Charsets.UTF_8))
                        }
                    }
                }
            }.onSuccess {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, R.string.backup_cache_success, Toast.LENGTH_SHORT).show()
                }
            }.onFailure {
                reportException(it)
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, R.string.backup_cache_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun restoreCache(context: Context, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                // Stop playback service first so files and databases are released
                withContext(Dispatchers.Main) {
                    try {
                        context.stopService(Intent(context, MusicService::class.java))
                    } catch (_: Exception) {}
                }

                val tempDir = java.io.File(context.cacheDir, "cache_restore_${System.currentTimeMillis()}").apply { mkdirs() }

                try {
                    context.applicationContext.contentResolver.openInputStream(uri)?.use { inputStream ->
                        java.util.zip.ZipInputStream(inputStream.buffered()).use { zipIn ->
                            var entry = tryOrNull { zipIn.nextEntry }
                            while (entry != null) {
                                if (!entry.isDirectory) {
                                    val normName = entry.name.replace('\\', '/').trimStart('/')
                                    val destFile = java.io.File(tempDir, normName)
                                    if (destFile.canonicalPath.startsWith(tempDir.canonicalPath)) {
                                        destFile.parentFile?.mkdirs()
                                        destFile.outputStream().buffered().use { zipIn.copyTo(it) }
                                    }
                                }
                                zipIn.closeEntry()
                                entry = tryOrNull { zipIn.nextEntry }
                            }
                        }
                    }

                    // 1. Process cached songs metadata and formats into Room DB
                    val metadataFile = tempDir.walkTopDown().firstOrNull {
                        it.isFile && (it.name == "cached_songs_metadata.json" || it.name == "metadata.json")
                    }
                    val restoredSongIds = mutableListOf<String>()
                    val songOrderFromMetadata = mutableListOf<String>()

                    if (metadataFile != null && metadataFile.exists()) {
                        tryOrNull {
                            val jsonStr = metadataFile.readText(Charsets.UTF_8)
                            val array = JSONArray(jsonStr)
                            for (i in 0 until array.length()) {
                                val obj = array.getJSONObject(i)
                                val id = obj.getString("id")
                                val title = obj.getString("title")
                                val duration = obj.optInt("duration", -1)
                                val thumbnailUrl = if (obj.has("thumbnailUrl") && !obj.isNull("thumbnailUrl")) obj.getString("thumbnailUrl") else null
                                val artistsArray = obj.optJSONArray("artists")
                                val artists = mutableListOf<String>()
                                if (artistsArray != null) {
                                    for (j in 0 until artistsArray.length()) {
                                        artists.add(artistsArray.getString(j))
                                    }
                                }
                                restoredSongIds.add(id)
                                songOrderFromMetadata.add(id)

                                val mediaMetadata = MediaMetadata(
                                    id = id,
                                    title = title,
                                    artists = artists.map { MediaMetadata.Artist(id = null, name = it) },
                                    duration = duration,
                                    thumbnailUrl = thumbnailUrl
                                )
                                database.query {
                                    insert(mediaMetadata)
                                    val existing = getSongById(id)
                                    if (existing != null) {
                                        update(existing.song.copy(
                                            title = title,
                                            duration = if (duration != -1) duration else existing.song.duration,
                                            thumbnailUrl = thumbnailUrl ?: existing.song.thumbnailUrl
                                        ))
                                    }
                                }

                                if (obj.has("itag")) {
                                    database.query {
                                        upsert(
                                            FormatEntity(
                                                id = id,
                                                itag = obj.getInt("itag"),
                                                mimeType = obj.getString("mimeType"),
                                                codecs = obj.getString("codecs"),
                                                bitrate = obj.getInt("bitrate"),
                                                sampleRate = if (obj.has("sampleRate") && !obj.isNull("sampleRate")) obj.getInt("sampleRate") else null,
                                                contentLength = obj.getLong("contentLength"),
                                                loudnessDb = if (obj.has("loudnessDb") && !obj.isNull("loudnessDb")) obj.getDouble("loudnessDb") else null,
                                                playbackUrl = if (obj.has("playbackUrl") && !obj.isNull("playbackUrl")) obj.getString("playbackUrl") else null
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // 2. Target directories on device
                    val targetExoDir = context.filesDir.resolve("exoplayer").apply { mkdirs() }
                    val targetDlDir = context.filesDir.resolve("download").apply { mkdirs() }
                    val targetDbFile = context.getDatabasePath("exoplayer_internal.db").apply { parentFile?.mkdirs() }

                    // 3. Preserve device's active UIDs (NEVER delete or change existing .uid files!)
                    val existingExoUidFiles = targetExoDir.listFiles { _, name -> name.endsWith(".uid") } ?: emptyArray()
                    val backupExoUidFile = tempDir.walkTopDown().firstOrNull {
                        it.isFile && it.name.endsWith(".uid") && (it.parentFile?.name == "exoplayer" || it.parentFile == tempDir)
                    }
                    val activeExoUid = if (existingExoUidFiles.isNotEmpty()) {
                        existingExoUidFiles.first().name.removeSuffix(".uid")
                    } else if (backupExoUidFile != null) {
                        val bUid = backupExoUidFile.name.removeSuffix(".uid")
                        targetExoDir.resolve("$bUid.uid").createNewFile()
                        bUid
                    } else {
                        val newUid = java.lang.Long.toHexString(java.security.SecureRandom().nextLong())
                        targetExoDir.resolve("$newUid.uid").createNewFile()
                        newUid
                    }

                    targetExoDir.listFiles { _, name -> name.endsWith(".uid") }?.forEach { f ->
                        if (!f.name.equals("$activeExoUid.uid", ignoreCase = true)) {
                            f.delete()
                        }
                    }

                    val existingDlUidFiles = targetDlDir.listFiles { _, name -> name.endsWith(".uid") } ?: emptyArray()
                    val backupDlUidFile = tempDir.walkTopDown().firstOrNull {
                        it.isFile && it.name.endsWith(".uid") && it.parentFile?.name == "download"
                    }
                    val activeDlUid = if (existingDlUidFiles.isNotEmpty()) {
                        existingDlUidFiles.first().name.removeSuffix(".uid")
                    } else if (backupDlUidFile != null) {
                        val bUid = backupDlUidFile.name.removeSuffix(".uid")
                        targetDlDir.resolve("$bUid.uid").createNewFile()
                        bUid
                    } else {
                        val newUid = java.lang.Long.toHexString(java.security.SecureRandom().nextLong())
                        targetDlDir.resolve("$newUid.uid").createNewFile()
                        newUid
                    }

                    targetDlDir.listFiles { _, name -> name.endsWith(".uid") }?.forEach { f ->
                        if (!f.name.equals("$activeDlUid.uid", ignoreCase = true)) {
                            f.delete()
                        }
                    }

                    // 4. Open device's exoplayer_internal.db
                    SQLiteDatabase.openOrCreateDatabase(targetDbFile.path, null).use { db ->
                        tryOrNull {
                            db.rawQuery("PRAGMA wal_checkpoint(FULL)", null).use { it.moveToFirst() }
                        }

                        // Register active UIDs in ExoPlayerVersions
                        db.execSQL(
                            "CREATE TABLE IF NOT EXISTS ExoPlayerVersions (" +
                            "feature INTEGER NOT NULL, " +
                            "instance_uid TEXT NOT NULL, " +
                            "version INTEGER NOT NULL, " +
                            "PRIMARY KEY (feature, instance_uid))"
                        )
                        db.execSQL("INSERT OR REPLACE INTO ExoPlayerVersions (feature, instance_uid, version) VALUES (1, '$activeExoUid', 1)")
                        db.execSQL("INSERT OR REPLACE INTO ExoPlayerVersions (feature, instance_uid, version) VALUES (1, '$activeDlUid', 1)")

                        // Inspect backup database in tempDir
                        val backupDbFile = tempDir.walkTopDown().firstOrNull { it.isFile && it.name == "exoplayer_internal.db" }
                        val backupExoEntries = mutableMapOf<Int, Pair<String, ByteArray>>() // backupChunkId -> (key, metadata)
                        val backupDlEntries = mutableMapOf<Int, Pair<String, ByteArray>>()

                        if (backupDbFile != null && backupDbFile.exists()) {
                            tryOrNull {
                                SQLiteDatabase.openDatabase(backupDbFile.path, null, SQLiteDatabase.OPEN_READONLY).use { bDb ->
                                    val tables = mutableListOf<String>()
                                    bDb.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name LIKE 'ExoPlayerCacheIndex%'", null).use { c ->
                                        while (c.moveToNext()) tables.add(c.getString(0))
                                    }
                                    for (table in tables) {
                                        val isDlTable = backupDlUidFile != null && table.contains(backupDlUidFile.name.removeSuffix(".uid"))
                                        bDb.rawQuery("SELECT id, key, metadata FROM $table", null).use { c ->
                                            while (c.moveToNext()) {
                                                val id = c.getInt(0)
                                                val key = c.getString(1)
                                                val metadata = c.getBlob(2)
                                                if (isDlTable) {
                                                    backupDlEntries[id] = Pair(key, metadata)
                                                } else {
                                                    backupExoEntries[id] = Pair(key, metadata)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        val emptyMetadata = byteArrayOf(0, 0, 0, 0)

                        // 5. Merge exoplayer cache entries
                        val targetExoTable = "ExoPlayerCacheIndex$activeExoUid"
                        db.execSQL(
                            "CREATE TABLE IF NOT EXISTS $targetExoTable (" +
                            "id INTEGER PRIMARY KEY NOT NULL, " +
                            "key TEXT NOT NULL, " +
                            "metadata BLOB NOT NULL)"
                        )

                        val existingExoKeyToId = mutableMapOf<String, Int>()
                        val existingExoIds = mutableSetOf<Int>()
                        db.rawQuery("SELECT id, key FROM $targetExoTable", null).use { cursor ->
                            while (cursor.moveToNext()) {
                                val id = cursor.getInt(0)
                                val key = cursor.getString(1)
                                existingExoKeyToId[key] = id
                                existingExoIds.add(id)
                            }
                        }
                        targetExoDir.walkTopDown().forEach { file ->
                            if (file.isFile && file.name.endsWith(".exo")) {
                                file.name.substringBefore('.').toIntOrNull()?.let { existingExoIds.add(it) }
                            }
                        }
                        var maxExoId = existingExoIds.maxOrNull() ?: -1

                        val backupExoFiles = tempDir.walkTopDown().filter { file ->
                            file.isFile && file.name.endsWith(".exo") && !file.path.replace('\\', '/').contains("/download/")
                        }.toList()

                        val exoFilesByBackupId = backupExoFiles.groupBy { it.name.substringBefore('.').toIntOrNull() }
                        for ((backupId, files) in exoFilesByBackupId) {
                            if (backupId == null) continue
                            val entry = backupExoEntries[backupId]
                            val songKey = entry?.first
                                ?: songOrderFromMetadata.getOrNull(backupId)
                                ?: restoredSongIds.getOrNull(backupId)
                                ?: "restored_$backupId"
                            val metadata = entry?.second ?: emptyMetadata

                            val targetId = existingExoKeyToId.getOrPut(songKey) {
                                val newId = ++maxExoId
                                tryOrNull {
                                    val stmt = db.compileStatement(
                                        "INSERT OR REPLACE INTO $targetExoTable (id, key, metadata) VALUES (?, ?, ?)"
                                    )
                                    stmt.bindLong(1, newId.toLong())
                                    stmt.bindString(2, songKey)
                                    stmt.bindBlob(3, metadata)
                                    stmt.executeInsert()
                                }
                                newId
                            }

                            for (chunkFile in files) {
                                val newFileName = "$targetId." + chunkFile.name.substringAfter('.')
                                val targetFile = targetExoDir.resolve(newFileName)
                                if (!targetFile.exists()) {
                                    chunkFile.copyTo(targetFile, overwrite = false)
                                }
                            }
                        }

                        // 6. Merge download cache entries
                        val targetDlTable = "ExoPlayerCacheIndex$activeDlUid"
                        db.execSQL(
                            "CREATE TABLE IF NOT EXISTS $targetDlTable (" +
                            "id INTEGER PRIMARY KEY NOT NULL, " +
                            "key TEXT NOT NULL, " +
                            "metadata BLOB NOT NULL)"
                        )

                        val existingDlKeyToId = mutableMapOf<String, Int>()
                        val existingDlIds = mutableSetOf<Int>()
                        db.rawQuery("SELECT id, key FROM $targetDlTable", null).use { cursor ->
                            while (cursor.moveToNext()) {
                                val id = cursor.getInt(0)
                                val key = cursor.getString(1)
                                existingDlKeyToId[key] = id
                                existingDlIds.add(id)
                            }
                        }
                        targetDlDir.walkTopDown().forEach { file ->
                            if (file.isFile && file.name.endsWith(".exo")) {
                                file.name.substringBefore('.').toIntOrNull()?.let { existingDlIds.add(it) }
                            }
                        }
                        var maxDlId = existingDlIds.maxOrNull() ?: -1

                        val backupDlFiles = tempDir.walkTopDown().filter { file ->
                            file.isFile && file.name.endsWith(".exo") && file.path.replace('\\', '/').contains("/download/")
                        }.toList()

                        val dlFilesByBackupId = backupDlFiles.groupBy { it.name.substringBefore('.').toIntOrNull() }
                        for ((backupId, files) in dlFilesByBackupId) {
                            if (backupId == null) continue
                            val entry = backupDlEntries[backupId] ?: backupExoEntries[backupId]
                            val songKey = entry?.first
                                ?: songOrderFromMetadata.getOrNull(backupId)
                                ?: restoredSongIds.getOrNull(backupId)
                                ?: "restored_$backupId"
                            val metadata = entry?.second ?: emptyMetadata

                            val targetId = existingDlKeyToId.getOrPut(songKey) {
                                val newId = ++maxDlId
                                tryOrNull {
                                    val stmt = db.compileStatement(
                                        "INSERT OR REPLACE INTO $targetDlTable (id, key, metadata) VALUES (?, ?, ?)"
                                    )
                                    stmt.bindLong(1, newId.toLong())
                                    stmt.bindString(2, songKey)
                                    stmt.bindBlob(3, metadata)
                                    stmt.executeInsert()
                                }
                                newId
                            }

                            for (chunkFile in files) {
                                val newFileName = "$targetId." + chunkFile.name.substringAfter('.')
                                val targetFile = targetDlDir.resolve(newFileName)
                                if (!targetFile.exists()) {
                                    chunkFile.copyTo(targetFile, overwrite = false)
                                }
                            }
                        }

                        tryOrNull {
                            db.rawQuery("PRAGMA wal_checkpoint(FULL)", null).use { it.moveToFirst() }
                        }

                        // 7. Merge restoredSongIds and existing cached song IDs into restored_cache_ids.json
                        val restoredFile = context.filesDir.resolve("restored_cache_ids.json")
                        val mergedIds = mutableSetOf<String>()
                        if (restoredFile.exists()) {
                            tryOrNull {
                                val arr = JSONArray(restoredFile.readText())
                                for (i in 0 until arr.length()) mergedIds.add(arr.getString(i))
                            }
                        }
                        mergedIds.addAll(restoredSongIds)
                        mergedIds.addAll(existingExoKeyToId.keys)
                        mergedIds.addAll(existingDlKeyToId.keys)
                        tryOrNull {
                            val backupRestoredFile = tempDir.walkTopDown().firstOrNull { it.isFile && it.name == "restored_cache_ids.json" }
                            if (backupRestoredFile != null && backupRestoredFile.exists()) {
                                val arr = JSONArray(backupRestoredFile.readText())
                                for (i in 0 until arr.length()) mergedIds.add(arr.getString(i))
                            }
                        }
                        restoredFile.writeText(JSONArray(mergedIds.toList()).toString())
                    }
                } finally {
                    tempDir.deleteRecursively()
                }

                // Checkpoint database to flush restored Room entries
                tryOrNull { database.checkpoint() }
            }.onSuccess {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, R.string.restore_cache_success, Toast.LENGTH_SHORT).show()
                    restartApp(context)
                }
            }.onFailure {
                reportException(it)
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, R.string.restore_cache_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    companion object {
        const val SETTINGS_FILENAME = "settings.preferences_pb"
        const val GOOGLE_ACCOUNT_FILENAME = "google_account.json"
        const val OS_BACKUP_PREFS = "os_backup_prefs"
        const val KEY_LAST_OS_BACKUP_TIME = "last_android_os_backup_time"
        const val OS_BACKUP_DIR = "os_backup"
        const val OS_BACKUP_FILENAME = "latest.backup"
    }
}

