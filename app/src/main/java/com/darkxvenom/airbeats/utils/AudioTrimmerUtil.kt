package com.darkxvenom.airbeats.utils

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaScannerConnection
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.darkxvenom.airbeats.constants.AudioQuality
import com.darkxvenom.airbeats.innertube.YouTube
import com.darkxvenom.airbeats.innertube.models.SongItem
import com.darkxvenom.airbeats.jiosaavn.JioSaavnApi
import com.darkxvenom.airbeats.models.MediaMetadata
import com.darkxvenom.airbeats.playback.MusicService
import com.darkxvenom.airbeats.playback.PlayerConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

object AudioTrimmerUtil {
    private const val TAG = "AudioTrimmerUtil"

    sealed class AudioSourceResult {
        data class Success(val file: File, val durationMs: Long) : AudioSourceResult()
        data class Error(val message: String) : AudioSourceResult()
    }

    private data class PcmInfo(
        val sampleRate: Int,
        val channels: Int,
    )

    private val httpClient = OkHttpClient.Builder()
        .proxy(YouTube.proxy)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Resolves and downloads/prepares the source audio file with a robust multi-layer fallback strategy:
     * 1. Already downloaded snippet cache file
     * 2. Local player/download cache
     * 3. Currently active playback stream URL from MusicService or Database
     * 4. JioSaavn 320kbps high-speed CDN match
     * 5. YouTube multi-client playback stream resolution
     * 6. YouTube Song search match fallback if original ID is restricted
     */
    suspend fun prepareAudioSource(
        context: Context,
        mediaMetadata: MediaMetadata,
        playerConnection: PlayerConnection? = null,
        onProgress: (Int) -> Unit = {}
    ): AudioSourceResult = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        try {
            val safeId = mediaMetadata.id.replace(Regex("[^a-zA-Z0-9_-]"), "_")

            // 1. Check if already prepared and cached on disk
            val existingFile = appContext.cacheDir.listFiles { _, name ->
                name.startsWith("snippet_raw_${safeId}.") && !name.endsWith(".tmp")
            }?.firstOrNull { it.length() > 50_000L }
            if (existingFile != null) {
                val duration = getAudioDurationMs(existingFile) ?: (mediaMetadata.duration * 1000L).coerceAtLeast(10_000L)
                return@withContext AudioSourceResult.Success(existingFile, duration)
            }

            // 2. Check if song audio is already in player/download cache
            val cached = SaveToStorageUtil.getCachedAudioBytes(appContext, mediaMetadata.id)
            if (cached != null && cached.first.size > 200_000) {
                val (bytes, ext) = cached
                val sourceFile = File(appContext.cacheDir, "snippet_raw_${safeId}.$ext")
                if (!sourceFile.exists() || sourceFile.length() != bytes.size.toLong()) {
                    sourceFile.writeBytes(bytes)
                }
                val duration = getAudioDurationMs(sourceFile) ?: (mediaMetadata.duration * 1000L).coerceAtLeast(10_000L)
                Timber.tag(TAG).d("Reusing cached audio: ${sourceFile.name}, size=${bytes.size}, duration=${duration}ms")
                return@withContext AudioSourceResult.Success(sourceFile, duration)
            }

            onProgress(10)

            // 3. Check active playback stream URL from player service or database
            var resolvedStreamUrl: String? = null
            var resolvedExt = "m4a"

            val activeSongUrl = playerConnection?.service?.songUrlCache?.get(mediaMetadata.id)?.url
                ?: MusicService.instance?.songUrlCache?.get(mediaMetadata.id)?.url
            if (!activeSongUrl.isNullOrBlank()) {
                resolvedStreamUrl = activeSongUrl
                if (activeSongUrl.contains("opus") || activeSongUrl.contains("webm")) {
                    resolvedExt = "opus"
                }
            }

            if (resolvedStreamUrl == null) {
                val dbFormat = playerConnection?.service?.database?.format(mediaMetadata.id)?.firstOrNull()
                    ?: MusicService.instance?.database?.format(mediaMetadata.id)?.firstOrNull()
                val dbUrl = dbFormat?.playbackUrl
                if (!dbUrl.isNullOrBlank() && (dbUrl.startsWith("http://") || dbUrl.startsWith("https://"))) {
                    resolvedStreamUrl = dbUrl
                    if (dbFormat.mimeType.contains("opus") || dbFormat.mimeType.contains("webm")) {
                        resolvedExt = "opus"
                    }
                }
            }

            // 4. Resolve stream URL from provider with mutual fallback (YouTube <-> JioSaavn)
            val artistName = mediaMetadata.artists.firstOrNull()?.name.orEmpty()
            if (resolvedStreamUrl == null) {
                val isJioSaavnTrack = mediaMetadata.id.startsWith("JS:")
                val isYouTubeTrack = !isJioSaavnTrack && (mediaMetadata.id.length == 11 && !mediaMetadata.id.startsWith("sp:") && !mediaMetadata.id.startsWith("local:"))

                suspend fun tryJioSaavn(): String? {
                    return if (isJioSaavnTrack) {
                        runCatching { JioSaavnApi.getStreamUrl(mediaMetadata.id) }.getOrNull()
                    } else {
                        runCatching {
                            JioSaavnApi.findMatchAndStreamUrl(
                                title = mediaMetadata.title,
                                artist = artistName,
                                durationSec = mediaMetadata.duration
                            )
                        }.getOrNull()
                    }
                }

                suspend fun tryYouTube(): Pair<String, String>? {
                    val connectivityManager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                    var targetVideoId = mediaMetadata.id

                    if (targetVideoId.startsWith("sp:") || targetVideoId.startsWith("spotify:") || targetVideoId.length != 11) {
                        val query = "${mediaMetadata.title} $artistName".trim()
                        val searchItem = runCatching {
                            YouTube.search(query, YouTube.SearchFilter.FILTER_SONG).getOrNull()?.items?.firstOrNull() as? SongItem
                        }.getOrNull()
                        if (searchItem != null) {
                            targetVideoId = searchItem.id
                        }
                    }

                    var playbackData = runCatching {
                        YTPlayerUtils.playerResponseForPlayback(
                            videoId = targetVideoId,
                            playlistId = null,
                            audioQuality = AudioQuality.AUTO,
                            connectivityManager = connectivityManager
                        ).getOrThrow()
                    }.getOrNull()

                    if (playbackData == null) {
                        val query = "${mediaMetadata.title} $artistName".trim()
                        val altItem = runCatching {
                            YouTube.search(query, YouTube.SearchFilter.FILTER_SONG).getOrNull()?.items?.firstOrNull() as? SongItem
                        }.getOrNull()
                        if (altItem != null && altItem.id != targetVideoId) {
                            playbackData = runCatching {
                                YTPlayerUtils.playerResponseForPlayback(
                                    videoId = altItem.id,
                                    playlistId = null,
                                    audioQuality = AudioQuality.AUTO,
                                    connectivityManager = connectivityManager
                                ).getOrThrow()
                            }.getOrNull()
                        }
                    }

                    return if (playbackData != null) {
                        val mime = playbackData.format.mimeType
                        val ext = when {
                            mime.contains("opus") || mime.contains("webm") -> "opus"
                            mime.contains("mp4") || mime.contains("m4a") -> "m4a"
                            else -> "m4a"
                        }
                        Pair(playbackData.streamUrl, ext)
                    } else null
                }

                if (isJioSaavnTrack) {
                    onProgress(20)
                    resolvedStreamUrl = tryJioSaavn()
                    if (resolvedStreamUrl != null) resolvedExt = "m4a"
                    if (resolvedStreamUrl == null) {
                        onProgress(25)
                        tryYouTube()?.let {
                            resolvedStreamUrl = it.first
                            resolvedExt = it.second
                        }
                    }
                } else if (isYouTubeTrack) {
                    onProgress(20)
                    tryYouTube()?.let {
                        resolvedStreamUrl = it.first
                        resolvedExt = it.second
                    }
                    if (resolvedStreamUrl == null) {
                        onProgress(25)
                        resolvedStreamUrl = tryJioSaavn()
                        if (resolvedStreamUrl != null) resolvedExt = "m4a"
                    }
                } else {
                    onProgress(20)
                    resolvedStreamUrl = tryJioSaavn()
                    if (resolvedStreamUrl != null) {
                        resolvedExt = "m4a"
                    } else {
                        onProgress(25)
                        tryYouTube()?.let {
                            resolvedStreamUrl = it.first
                            resolvedExt = it.second
                        }
                    }
                }
            }

            if (resolvedStreamUrl.isNullOrBlank()) {
                return@withContext AudioSourceResult.Error("Unable to find working audio stream for this song")
            }

            // 6. Download the resolved stream
            val streamUrl = resolvedStreamUrl
            val isYouTube = streamUrl.contains("googlevideo.com")
            val downloadUrl = if (isYouTube && !streamUrl.contains("range=")) {
                val length = 15_000_000L
                "${streamUrl}&range=0-$length"
            } else {
                streamUrl
            }

            val sourceFile = File(appContext.cacheDir, "snippet_raw_${safeId}.$resolvedExt")
            val tempFile = File(appContext.cacheDir, "snippet_raw_${safeId}.$resolvedExt.tmp")

            val request = Request.Builder()
                .url(downloadUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .header("Accept", "*/*")
                .header("Connection", "keep-alive")
                .build()

            onProgress(35)
            val response = httpClient.newCall(request).execute()
            response.use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext AudioSourceResult.Error("HTTP ${resp.code}: Failed to download stream")
                }
                val body = resp.body ?: return@withContext AudioSourceResult.Error("Empty stream")
                val total = body.contentLength()
                var readTotal = 0L
                val buf = ByteArray(64 * 1024)
                tempFile.outputStream().use { out ->
                    body.byteStream().use { input ->
                        var read: Int
                        while (input.read(buf).also { read = it } != -1) {
                            out.write(buf, 0, read)
                            readTotal += read
                            if (total > 0) {
                                val percent = 35 + ((readTotal * 60) / total).toInt().coerceIn(0, 59)
                                onProgress(percent)
                            }
                        }
                    }
                }
            }

            if (!tempFile.exists() || tempFile.length() < 10_000L) {
                tempFile.delete()
                return@withContext AudioSourceResult.Error("Downloaded stream is too small or incomplete")
            }

            if (sourceFile.exists()) sourceFile.delete()
            tempFile.renameTo(sourceFile)
            onProgress(100)

            val duration = getAudioDurationMs(sourceFile) ?: (mediaMetadata.duration * 1000L).coerceAtLeast(10_000L)
            AudioSourceResult.Success(sourceFile, duration)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "prepareAudioSource failed")
            AudioSourceResult.Error(e.message ?: "Failed to prepare audio source")
        }
    }

    /**
     * Trims audio with studio-master quality by decoding the exact slice to pristine 16-bit PCM
     * and encoding to a high-definition 256 kbps AAC MPEG-4 container.
     */
    suspend fun trimAudio(
        sourceFile: File,
        outputFile: File,
        startMs: Long,
        endMs: Long,
    ): Boolean = withContext(Dispatchers.IO) {
        if (!sourceFile.exists() || sourceFile.length() <= 0) {
            Timber.tag(TAG).e("Source audio file does not exist")
            return@withContext false
        }

        val appContext = sourceFile.parentFile ?: File("/tmp")
        val tempPcmFile = File(appContext, "trim_pcm_${System.currentTimeMillis()}.pcm")

        try {
            // Step 1: Decode exact segment from startMs to endMs into raw PCM
            val pcmInfo = decodeToPcm(sourceFile, tempPcmFile, startMs, endMs)
            if (pcmInfo == null || !tempPcmFile.exists() || tempPcmFile.length() <= 0) {
                Timber.tag(TAG).e("Failed to decode audio segment to PCM")
                return@withContext false
            }

            // Step 2: Encode PCM to high-definition 256 kbps AAC M4A
            val success = encodePcmToM4a(
                pcmFile = tempPcmFile,
                outputFile = outputFile,
                sampleRate = pcmInfo.sampleRate,
                channels = pcmInfo.channels,
                bitrate = 256_000
            )

            Timber.tag(TAG).d("Trim complete: success=$success, size=${outputFile.length()} bytes")
            return@withContext success
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "trimAudio failed")
            return@withContext false
        } finally {
            try {
                if (tempPcmFile.exists()) tempPcmFile.delete()
            } catch (_: Exception) {}
        }
    }

    /**
     * Decodes the audio range [startMs, endMs] to standard 16-bit PCM with sample-accurate alignment.
     * Decodes sequentially without relying on container seek tables to avoid corruption or drops.
     */
    private fun decodeToPcm(
        sourceFile: File,
        pcmFile: File,
        startMs: Long,
        endMs: Long,
    ): PcmInfo? {
        var extractor: MediaExtractor? = null
        var decoder: MediaCodec? = null
        var fos: FileOutputStream? = null

        try {
            extractor = MediaExtractor().apply { setDataSource(sourceFile.absolutePath) }
            val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null

            extractor.selectTrack(trackIndex)
            val inFormat = extractor.getTrackFormat(trackIndex)
            val mime = inFormat.getString(MediaFormat.KEY_MIME) ?: return null

            var actualSampleRate = if (inFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                inFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } else 44100

            var actualChannels = if (inFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                inFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            } else 2

            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(inFormat, null, null, 0)
            decoder.start()

            if (pcmFile.exists()) pcmFile.delete()
            fos = FileOutputStream(pcmFile)

            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            val timeoutUs = 10_000L
            var tryAgainCount = 0
            var currentPcmMs = 0L

            while (!outputDone) {
                // 1. Feed compressed audio to decoder
                if (!inputDone) {
                    val inIndex = decoder.dequeueInputBuffer(timeoutUs)
                    if (inIndex >= 0) {
                        val inBuffer = decoder.getInputBuffer(inIndex)
                        if (inBuffer != null) {
                            inBuffer.clear()
                            val sampleSize = extractor.readSampleData(inBuffer, 0)
                            if (sampleSize < 0) {
                                decoder.queueInputBuffer(inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                val sampleTimeUs = extractor.sampleTime
                                decoder.queueInputBuffer(inIndex, 0, sampleSize, sampleTimeUs, extractor.sampleFlags)
                                extractor.advance()
                            }
                        }
                    }
                }

                // 2. Read decoded PCM samples and slice exact range
                val outIndex = decoder.dequeueOutputBuffer(bufferInfo, timeoutUs)
                when (outIndex) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val newFormat = decoder.outputFormat
                        if (newFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                            actualSampleRate = newFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        }
                        if (newFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                            actualChannels = newFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        }
                        Timber.tag(TAG).d("Decoder format updated: rate=$actualSampleRate, ch=$actualChannels")
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (inputDone && ++tryAgainCount > 100) {
                            outputDone = true
                        }
                    }
                    else -> {
                        if (outIndex >= 0) {
                            tryAgainCount = 0
                            val outBuffer = decoder.getOutputBuffer(outIndex)
                            if (outBuffer != null && bufferInfo.size > 0) {
                                val frameSize = actualChannels * 2
                                val samplesInChunk = bufferInfo.size / frameSize
                                val chunkDurationMs = (samplesInChunk * 1000L) / actualSampleRate
                                val chunkStartMs = currentPcmMs
                                val chunkEndMs = currentPcmMs + chunkDurationMs

                                if (chunkEndMs > startMs && chunkStartMs < endMs) {
                                    val startOffsetFrames = if (chunkStartMs < startMs) {
                                        (((startMs - chunkStartMs) * actualSampleRate) / 1000L).toInt().coerceIn(0, samplesInChunk)
                                    } else 0

                                    val endOffsetFrames = if (chunkEndMs > endMs) {
                                        (((endMs - chunkStartMs) * actualSampleRate) / 1000L).toInt().coerceIn(startOffsetFrames, samplesInChunk)
                                    } else samplesInChunk

                                    val byteStart = bufferInfo.offset + (startOffsetFrames * frameSize)
                                    val byteLength = (endOffsetFrames - startOffsetFrames) * frameSize

                                    if (byteLength > 0) {
                                        outBuffer.position(byteStart)
                                        outBuffer.limit(byteStart + byteLength)
                                        val chunk = ByteArray(byteLength)
                                        outBuffer.get(chunk)
                                        fos.write(chunk)
                                    }
                                }

                                currentPcmMs += chunkDurationMs
                                if (currentPcmMs >= endMs) {
                                    outputDone = true
                                }
                            }
                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                outputDone = true
                            }
                            decoder.releaseOutputBuffer(outIndex, false)
                        }
                    }
                }
            }

            fos.flush()
            fos.close()
            fos = null

            if (pcmFile.length() <= 0) {
                Timber.tag(TAG).w("Decoded PCM file is empty")
                return null
            }

            return PcmInfo(actualSampleRate, actualChannels)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "decodeToPcm failed")
            pcmFile.delete()
            return null
        } finally {
            try { fos?.close() } catch (_: Exception) {}
            try { decoder?.stop(); decoder?.release() } catch (_: Exception) {}
            try { extractor?.release() } catch (_: Exception) {}
        }
    }

    /**
     * Encodes a raw 16-bit PCM file into a pristine AAC MPEG-4 (.m4a) file at the specified bitrate.
     */
    private fun encodePcmToM4a(
        pcmFile: File,
        outputFile: File,
        sampleRate: Int,
        channels: Int,
        bitrate: Int = 256_000,
    ): Boolean {
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var fis: FileInputStream? = null

        try {
            if (!pcmFile.exists() || pcmFile.length() <= 0) return false
            fis = FileInputStream(pcmFile)

            val encChannels = channels.coerceIn(1, 2)
            val actualBitrate = if (encChannels == 1) 128_000 else bitrate
            val outFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, encChannels).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, actualBitrate)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
            }

            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            encoder.configure(outFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()

            if (outputFile.exists()) outputFile.delete()
            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var muxerTrackIndex = -1
            var muxerStarted = false

            val frameSize = encChannels * 2
            val inBuf = ByteArray(4096)
            val outBufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var totalSamplesRead = 0L
            val timeoutUs = 10_000L
            var tryAgainCount = 0

            while (!outputDone) {
                // 1. Feed raw PCM samples to AAC encoder
                if (!inputDone) {
                    val inIndex = encoder.dequeueInputBuffer(timeoutUs)
                    if (inIndex >= 0) {
                        val inputBuffer = encoder.getInputBuffer(inIndex)
                        if (inputBuffer != null) {
                            inputBuffer.clear()
                            val maxAllowed = (minOf(inputBuffer.remaining(), inBuf.size) / frameSize) * frameSize
                            val bytesRead = fis.read(inBuf, 0, maxAllowed)
                            val ptsUs = (totalSamplesRead * 1_000_000L) / sampleRate
                            if (bytesRead <= 0) {
                                encoder.queueInputBuffer(inIndex, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                inputBuffer.put(inBuf, 0, bytesRead)
                                encoder.queueInputBuffer(inIndex, 0, bytesRead, ptsUs, 0)
                                totalSamplesRead += bytesRead / frameSize
                            }
                        }
                    }
                }

                // 2. Read encoded AAC frames and write to MPEG-4 container
                val outIndex = encoder.dequeueOutputBuffer(outBufferInfo, timeoutUs)
                when (outIndex) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (!muxerStarted) {
                            val newFormat = encoder.outputFormat
                            muxerTrackIndex = muxer.addTrack(newFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (inputDone && ++tryAgainCount > 100) {
                            outputDone = true
                        }
                    }
                    else -> {
                        if (outIndex >= 0) {
                            tryAgainCount = 0
                            val outBuffer = encoder.getOutputBuffer(outIndex)
                            if (outBuffer != null && outBufferInfo.size > 0 && muxerStarted) {
                                if ((outBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                                    outBuffer.position(outBufferInfo.offset)
                                    outBuffer.limit(outBufferInfo.offset + outBufferInfo.size)
                                    muxer.writeSampleData(muxerTrackIndex, outBuffer, outBufferInfo)
                                }
                            }
                            if ((outBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                outputDone = true
                            }
                            encoder.releaseOutputBuffer(outIndex, false)
                        }
                    }
                }
            }

            if (muxerStarted) {
                muxer.stop()
            }
            return outputFile.exists() && outputFile.length() > 0
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "encodePcmToM4a failed")
            outputFile.delete()
            return false
        } finally {
            try { fis?.close() } catch (_: Exception) {}
            try { encoder?.stop(); encoder?.release() } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}
        }
    }

    fun getAudioDurationMs(file: File): Long? {
        if (!file.exists() || file.length() <= 0) return null
        var extractor: MediaExtractor? = null
        try {
            extractor = MediaExtractor().apply { setDataSource(file.absolutePath) }
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    if (format.containsKey(MediaFormat.KEY_DURATION)) {
                        return format.getLong(MediaFormat.KEY_DURATION) / 1000L
                    }
                }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to get duration from file")
        } finally {
            try { extractor?.release() } catch (_: Exception) {}
        }
        return null
    }

    /**
     * Saves the trimmed clip to Music/AirBeats/Snippets directory.
     */
    fun saveSnippetToMusic(
        context: Context,
        file: File,
        title: String,
        artist: String,
    ): Uri? {
        try {
            val cleanTitle = title.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
            val displayName = "$cleanTitle (Snippet).m4a"
            val mimeType = "audio/mp4"

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
                    put(MediaStore.Audio.Media.MIME_TYPE, mimeType)
                    put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/AirBeats/Snippets")
                    put(MediaStore.Audio.Media.TITLE, "$cleanTitle (Snippet)")
                    put(MediaStore.Audio.Media.ARTIST, artist)
                    put(MediaStore.Audio.Media.IS_MUSIC, 1)
                    put(MediaStore.Audio.Media.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values) ?: return null
                resolver.openOutputStream(uri)?.use { out ->
                    file.inputStream().use { it.copyTo(out) }
                } ?: return null
                values.clear()
                values.put(MediaStore.Audio.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                return uri
            } else {
                val targetDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "AirBeats/Snippets")
                if (!targetDir.exists()) targetDir.mkdirs()
                val targetFile = File(targetDir, displayName)
                file.copyTo(targetFile, overwrite = true)
                MediaScannerConnection.scanFile(context, arrayOf(targetFile.absolutePath), arrayOf(mimeType), null)
                return Uri.fromFile(targetFile)
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to save snippet to music")
            return null
        }
    }

    /**
     * Shares the snippet file using Android system share sheet.
     */
    fun shareSnippet(context: Context, file: File, title: String) {
        try {
            val contentUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                file
            )
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "audio/*"
                putExtra(Intent.EXTRA_STREAM, contentUri)
                putExtra(Intent.EXTRA_SUBJECT, title)
                putExtra(Intent.EXTRA_TEXT, "$title (Audio Clip via AirBeats)")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(shareIntent, "Share Clip").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to share snippet")
        }
    }
}
