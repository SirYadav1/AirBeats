package com.darkxvenom.airbeats.lyrics

import android.content.Context
import com.darkxvenom.airbeats.ai.AiTranslationService
import com.darkxvenom.airbeats.constants.AiTranslationLanguages
import com.darkxvenom.airbeats.db.entities.LyricsEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import timber.log.Timber
import java.io.File

object LyricsTranslationHelper {
    sealed class TranslationStatus {
        object Idle : TranslationStatus()
        data class Translating(val logs: List<String> = emptyList()) : TranslationStatus()
        object Success : TranslationStatus()
        data class Error(val message: String) : TranslationStatus()
    }

    private val _status = MutableStateFlow<TranslationStatus>(TranslationStatus.Idle)
    val status: StateFlow<TranslationStatus> = _status.asStateFlow()

    private val _hasActiveTranslations = MutableStateFlow(false)
    val hasActiveTranslations: StateFlow<Boolean> = _hasActiveTranslations.asStateFlow()

    private val _activeSongId = MutableStateFlow<String?>(null)
    val activeSongId: StateFlow<String?> = _activeSongId.asStateFlow()

    private val _currentLanguageCode = MutableStateFlow("hi-Latn")
    val currentLanguageCode: StateFlow<String> = _currentLanguageCode.asStateFlow()

    private val _translationVersion = MutableStateFlow(0)
    val translationVersion: StateFlow<Int> = _translationVersion.asStateFlow()

    private var translationJob: Job? = null
    private val memoryCache = mutableMapOf<String, List<String>>()
    private val manuallyClearedSongIds = mutableSetOf<String>()

    fun isSongCleared(songId: String): Boolean =
        songId.isNotBlank() && manuallyClearedSongIds.contains(songId)

    fun getCacheKey(lyricsText: String, lang: String): String =
        "${lyricsText.hashCode()}_$lang"

    fun getCacheFile(context: Context, songId: String, lang: String): File {
        val dir = File(context.filesDir, "lyrics_translations")
        if (!dir.exists()) dir.mkdirs()
        val safeSongId = songId.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        return File(dir, "${safeSongId}_${lang}.json")
    }

    fun hasCachedTranslation(context: Context, songId: String, lang: String? = null): Boolean {
        if (songId.isBlank()) return false
        if (isSongCleared(songId)) return false
        if (!lang.isNullOrBlank()) {
            val file = getCacheFile(context, songId, lang)
            if (file.exists() && file.length() > 2) return true
        }
        val dir = File(context.filesDir, "lyrics_translations")
        if (dir.exists()) {
            val safeSongId = songId.replace(Regex("[^a-zA-Z0-9_-]"), "_")
            return dir.listFiles { _, name -> name.startsWith("${safeSongId}_") && name.endsWith(".json") }
                ?.any { it.length() > 2 } == true
        }
        return false
    }

    fun parseLyricsToEntries(lyrics: String?): List<LyricsEntry> {
        if (lyrics.isNullOrBlank() || lyrics == LyricsEntity.LYRICS_NOT_FOUND) return emptyList()
        return when {
            AirBeatsLyricsUtils.isTtml(lyrics) -> AirBeatsLyricsUtils.parseTtml(lyrics)
            lyrics.startsWith("[") -> AirBeatsLyricsUtils.parseLyrics(lyrics)
            else -> lyrics.lines().filter { it.isNotBlank() }.mapIndexed { index, line ->
                LyricsEntry(time = -1L, text = line.trim())
            }
        }
    }

    private var activeLyricsList: List<LyricsEntry>? = null

    fun registerLyrics(lyrics: List<LyricsEntry>) {
        activeLyricsList = lyrics
    }

    fun unregisterLyrics(lyrics: List<LyricsEntry>) {
        if (activeLyricsList === lyrics) {
            activeLyricsList = null
        }
    }

    fun isTranslating(): Boolean = _status.value is TranslationStatus.Translating

    fun cancelTranslation() {
        translationJob?.cancel()
        translationJob = null
        _status.value = TranslationStatus.Idle
    }

    fun resetStatus() {
        _status.value = TranslationStatus.Idle
    }

    fun onSongChanged(newSongId: String) {
        if (_activeSongId.value != newSongId) {
            cancelTranslation()
            _activeSongId.value = newSongId
            _hasActiveTranslations.value = false
            _status.value = TranslationStatus.Idle
            activeLyricsList?.forEach { it.translatedTextFlow.value = null }
            _translationVersion.value += 1
        }
    }

    fun clearTranslations(lyrics: List<LyricsEntry>? = null, context: Context? = null, songId: String? = null) {
        cancelTranslation()
        if (!songId.isNullOrBlank()) {
            manuallyClearedSongIds.add(songId)
        }
        val targetLyrics = lyrics ?: activeLyricsList
        targetLyrics?.forEach { it.translatedTextFlow.value = null }
        _activeSongId.value = null
        _hasActiveTranslations.value = false
        _status.value = TranslationStatus.Idle

        if (context != null && !songId.isNullOrBlank()) {
            runCatching {
                val dir = File(context.filesDir, "lyrics_translations")
                val safeSongId = songId.replace(Regex("[^a-zA-Z0-9_-]"), "_")
                dir.listFiles { _, name -> name.startsWith("${safeSongId}_") }?.forEach { it.delete() }
            }
        }
        memoryCache.clear()
        _translationVersion.value += 1
    }

    fun loadTranslationsForSong(
        lyrics: List<LyricsEntry>,
        context: Context,
        songId: String,
        preferredLanguageCode: String? = null
    ): Boolean {
        if (lyrics.isEmpty() || songId.isBlank()) return false
        if (isSongCleared(songId)) return false

        // 1. Try preferred language code
        if (!preferredLanguageCode.isNullOrBlank()) {
            if (loadTranslationsFromCache(lyrics, context, songId, preferredLanguageCode)) {
                return true
            }
        }

        // 2. Try current language code if different
        val currentLang = _currentLanguageCode.value
        if (currentLang.isNotBlank() && currentLang != preferredLanguageCode) {
            if (loadTranslationsFromCache(lyrics, context, songId, currentLang)) {
                return true
            }
        }

        // 3. Check memory cache for any key for this songId
        val memKey = memoryCache.keys.firstOrNull { it.startsWith("${songId}_") }
        if (memKey != null) {
            val lang = memKey.removePrefix("${songId}_")
            if (lang.isNotBlank() && loadTranslationsFromCache(lyrics, context, songId, lang)) {
                return true
            }
        }

        // 4. Check disk cache for ANY language file for this songId
        return runCatching {
            val dir = File(context.filesDir, "lyrics_translations")
            if (dir.exists()) {
                val safeSongId = songId.replace(Regex("[^a-zA-Z0-9_-]"), "_")
                val files = dir.listFiles { _, name -> name.startsWith("${safeSongId}_") && name.endsWith(".json") }
                val targetFile = files?.firstOrNull { it.length() > 2 }
                if (targetFile != null) {
                    val lang = targetFile.name.removePrefix("${safeSongId}_").removeSuffix(".json")
                    if (lang.isNotBlank() && loadTranslationsFromCache(lyrics, context, songId, lang)) {
                        return@runCatching true
                    }
                }
            }
            false
        }.getOrDefault(false)
    }

    fun loadTranslationsFromCache(
        lyrics: List<LyricsEntry>,
        context: Context,
        songId: String,
        targetLanguageCode: String
    ): Boolean {
        if (songId.isNotBlank() && isSongCleared(songId)) return false
        registerLyrics(lyrics)
        _currentLanguageCode.value = targetLanguageCode
        val nonEmptyEntries = lyrics.mapIndexedNotNull { index, entry ->
            if (entry.text.isNotBlank()) index to entry else null
        }
        if (nonEmptyEntries.isEmpty()) {
            _hasActiveTranslations.value = false
            return false
        }

        val fullText = nonEmptyEntries.joinToString("\n") { it.second.text }
        val memKey = getCacheKey(fullText, targetLanguageCode)

        // 1. Check memory cache (by lyrics hash or songId)
        val cachedMem = memoryCache[memKey]
            ?: if (songId.isNotBlank()) memoryCache["${songId}_$targetLanguageCode"] else null
        if (cachedMem != null && cachedMem.isNotEmpty()) {
            nonEmptyEntries.forEachIndexed { idx, (originalIndex, _) ->
                lyrics[originalIndex].translatedTextFlow.value = cachedMem.getOrNull(idx)
            }
            _activeSongId.value = songId
            _hasActiveTranslations.value = true
            return true
        }

        // 2. Check disk cache
        if (songId.isNotBlank()) {
            val file = getCacheFile(context, songId, targetLanguageCode)
            if (file.exists() && file.length() > 2) {
                val list = runCatching {
                    val jsonArray = JSONArray(file.readText())
                    (0 until jsonArray.length()).map { jsonArray.optString(it) }
                }.getOrNull()

                if (list != null && list.isNotEmpty()) {
                    memoryCache[memKey] = list
                    memoryCache["${songId}_$targetLanguageCode"] = list
                    nonEmptyEntries.forEachIndexed { idx, (originalIndex, _) ->
                        lyrics[originalIndex].translatedTextFlow.value = list.getOrNull(idx)
                    }
                    _activeSongId.value = songId
                    _hasActiveTranslations.value = true
                    return true
                }
            }
        }

        _hasActiveTranslations.value = false
        return false
    }

    fun translateLyrics(
        lyrics: List<LyricsEntry>? = null,
        rawLyrics: String? = null,
        targetLanguageCode: String,
        apiKey: String,
        baseUrl: String,
        model: String,
        mode: String = "Literal",
        customPrompt: String? = null,
        provider: String = "OpenRouter",
        context: Context,
        songId: String = "",
        scope: CoroutineScope
    ) {
        cancelTranslation()
        if (songId.isNotBlank()) {
            manuallyClearedSongIds.remove(songId)
        }
        _currentLanguageCode.value = targetLanguageCode
        _status.value = TranslationStatus.Translating(listOf("Initializing AI translation engine..."))

        val targetEntries = if (!lyrics.isNullOrEmpty()) {
            lyrics
        } else if (!activeLyricsList.isNullOrEmpty()) {
            activeLyricsList!!
        } else if (!rawLyrics.isNullOrBlank()) {
            parseLyricsToEntries(rawLyrics)
        } else {
            emptyList()
        }
        registerLyrics(targetEntries)

        val nonEmptyEntries = targetEntries.mapIndexedNotNull { index, entry ->
            if (entry.text.isNotBlank()) index to entry else null
        }

        if (nonEmptyEntries.isEmpty()) {
            _status.value = TranslationStatus.Error("No lyrics available to translate.")
            return
        }

        val linesToTranslate = nonEmptyEntries.map { it.second.text }
        val targetLanguageName = AiTranslationLanguages[targetLanguageCode] ?: targetLanguageCode

        translationJob = scope.launch(Dispatchers.IO) {
            val result = AiTranslationService.translate(
                lines = linesToTranslate,
                targetLanguageCode = targetLanguageCode,
                targetLanguageName = targetLanguageName,
                apiKey = apiKey,
                baseUrl = baseUrl,
                model = model,
                mode = mode,
                customPrompt = customPrompt,
                provider = provider,
                onLog = { logMsg ->
                    val cur = _status.value
                    if (cur is TranslationStatus.Translating) {
                        _status.value = cur.copy(logs = cur.logs + logMsg)
                    }
                }
            )

            withContext(Dispatchers.Main) {
                result.onSuccess { translatedLines ->
                    nonEmptyEntries.forEachIndexed { idx, (originalIndex, _) ->
                        if (idx < translatedLines.size && originalIndex < targetEntries.size) {
                            targetEntries[originalIndex].translatedTextFlow.value = translatedLines[idx]
                        }
                    }

                    // Save to memory cache
                    val fullText = linesToTranslate.joinToString("\n")
                    val memKey = getCacheKey(fullText, targetLanguageCode)
                    memoryCache[memKey] = translatedLines
                    if (songId.isNotBlank()) {
                        memoryCache["${songId}_$targetLanguageCode"] = translatedLines
                    }

                    // Save to disk cache
                    if (songId.isNotBlank()) {
                        runCatching {
                            val file = getCacheFile(context, songId, targetLanguageCode)
                            val jsonArray = JSONArray()
                            translatedLines.forEach { jsonArray.put(it) }
                            file.writeText(jsonArray.toString())
                        }
                    }

                    _activeSongId.value = songId
                    _hasActiveTranslations.value = true
                    _status.value = TranslationStatus.Success
                    _translationVersion.value += 1
                }.onFailure { error ->
                    Timber.e(error, "Lyrics translation failed")
                    _hasActiveTranslations.value = false
                    _status.value = TranslationStatus.Error(error.message ?: "Translation error occurred.")
                }
            }
        }
    }
}
