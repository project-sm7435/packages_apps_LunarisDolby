/*
 * Copyright (C) 2024-2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lunaris.dolby.data.autoeq

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Network access used ONLY by the explicit AutoEQ Sync action. */
object AutoEqConfig {
    const val BASE_URL = "https://raw.githubusercontent.com/Pong-Development/DolbyProfiles/main"
    const val METADATA = "$BASE_URL/metadata.json"
    const val ARCHIVE = "https://codeload.github.com/Pong-Development/DolbyProfiles/zip/refs/heads/main"
    const val TAG = "AutoEqSync"
    const val USER_AGENT = "Lunaris-Dolby-AutoEQ/2.0"
}

class AutoEqDownloader(context: Context) {
    private val downloadDir = File(context.cacheDir, "autoeq_sync").apply { mkdirs() }

    suspend fun fetchMetadata(): AutoEqMetadata = withContext(Dispatchers.IO) {
        val json = fetchText(AutoEqConfig.METADATA)
        return@withContext try {
            AutoEqMetadata.fromJson(json)
        } catch (e: Exception) {
            throw IOException("Server returned invalid AutoEQ metadata", e)
        }
    }

    suspend fun downloadArchive(
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ): File = withContext(Dispatchers.IO) {
        val target = File(downloadDir, "dolbyprofiles-${System.currentTimeMillis()}.zip")
        try {
            downloadTo(AutoEqConfig.ARCHIVE, target, onProgress)
            target
        } catch (e: Throwable) {
            target.delete()
            throw e
        }
    }

    private fun fetchText(urlString: String): String {
        var connection: HttpURLConnection? = null
        try {
            Log.d(AutoEqConfig.TAG, "Fetching $urlString")
            connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", AutoEqConfig.USER_AGENT)
                setRequestProperty("Accept", "application/json")
            }
            val response = connection.responseCode
            if (response != HttpURLConnection.HTTP_OK) {
                throw IOException("Server returned HTTP $response")
            }
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection?.disconnect()
        }
    }

    private fun downloadTo(
        urlString: String,
        target: File,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit
    ) {
        var connection: HttpURLConnection? = null
        try {
            Log.d(AutoEqConfig.TAG, "Downloading AutoEQ archive")
            connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", AutoEqConfig.USER_AGENT)
                setRequestProperty("Accept", "application/zip, application/octet-stream")
            }
            val response = connection.responseCode
            if (response != HttpURLConnection.HTTP_OK) {
                throw IOException("Server returned HTTP $response while downloading AutoEQ")
            }
            val total = connection.contentLengthLong
            var downloaded = 0L
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        onProgress(downloaded, total)
                    }
                    output.flush()
                }
            }
        } finally {
            connection?.disconnect()
        }
    }
}
