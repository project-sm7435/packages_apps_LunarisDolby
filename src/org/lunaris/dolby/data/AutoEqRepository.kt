/*
 * Copyright (C) 2024-2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lunaris.dolby.data.autoeq

import android.content.Context
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

sealed class AutoEqSyncResult {
    data class Updated(val profileCount: Int) : AutoEqSyncResult()
    data class UpToDate(val profileCount: Int) : AutoEqSyncResult()
}

class AutoEqRepository(context: Context) {
    private val cache = AutoEqCache(context)
    private val downloader = AutoEqDownloader(context)
    private val memoryCache = LruCache<String, AutoEqProfile>(50)
    private val syncMutex = Mutex()

    @Volatile
    private var cachedIndex: List<IndexEntry> = emptyList()

    @Volatile
    private var initialized = false

    suspend fun initializeOffline(
        onProgress: (AutoEqProgress) -> Unit = {}
    ) = withContext(Dispatchers.IO) {
        if (initialized) return@withContext
        syncMutex.withLock {
            if (initialized) return@withLock
            cache.ensureBundledDatabase(onProgress)
            loadLocalIndex()
            initialized = true
        }
    }

    suspend fun sync(
        onProgress: (AutoEqProgress) -> Unit = {}
    ): AutoEqSyncResult = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            cache.ensureBundledDatabase(onProgress)
            val localMetadata = cache.loadMetadata()
                ?: throw IllegalStateException("Local AutoEQ database has no metadata")
            val remoteMetadata = downloader.fetchMetadata()

            if (localMetadata.contentEquals(remoteMetadata)) {
                loadLocalIndex()
                initialized = true
                return@withLock AutoEqSyncResult.UpToDate(cachedIndex.size)
            }

            val archive = downloader.downloadArchive { downloaded, total ->
                val percent = if (total > 0L) ((downloaded * 25L / total).toInt()) else 0
                onProgress(AutoEqProgress("Downloading AutoEQ update…", percent.coerceIn(0, 25)))
            }
            try {
                val staging = cache.stageDownloadedArchive(archive, remoteMetadata) { progress ->
                    onProgress(
                        AutoEqProgress(
                            progress.stage,
                            (25 + (progress.percent * 75 / 100)).coerceIn(25, 100)
                        )
                    )
                }
                try {
                    cache.commitStagedDirectory(staging)
                } catch (e: Throwable) {
                    staging.deleteRecursively()
                    throw e
                }

                memoryCache.evictAll()
                loadLocalIndex()
                initialized = true
                return@withLock AutoEqSyncResult.Updated(cachedIndex.size)
            } finally {
                archive.delete()
            }
        }
    }

    fun search(query: String): List<IndexEntry> {
        return AutoEqSearch.search(query, cachedIndex)
    }

    suspend fun getProfile(id: String): AutoEqProfile? = withContext(Dispatchers.IO) {
        if (!initialized) initializeOffline()
        memoryCache.get(id)?.let { return@withContext it }

        syncMutex.withLock {
            memoryCache.get(id)?.let { return@withLock it }
            val profile = cache.loadProfile(id) ?: return@withLock null
            memoryCache.put(id, profile)
            return@withLock profile
        }
    }

    suspend fun getLocalProfileCount(): Int = withContext(Dispatchers.IO) {
        if (!initialized) initializeOffline()
        cachedIndex.size
    }

    suspend fun getLocalMetadata(): AutoEqMetadata? = withContext(Dispatchers.IO) {
        if (!initialized) initializeOffline()
        cache.loadMetadata()
    }

    private suspend fun loadLocalIndex() {
        val index = cache.loadIndex()
            ?: throw IllegalStateException("Local AutoEQ index is missing or corrupted")
        // The upstream index currently contains six duplicate IDs. Collapse those
        // duplicates here so the UI never receives duplicate LazyColumn keys.
        cachedIndex = index.profiles.distinctBy { it.id }
    }
}
