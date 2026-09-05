/*
 * Copyright (C) 2024-2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lunaris.dolby.data.autoeq

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.GZIPInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

data class AutoEqProgress(
    val stage: String,
    val percent: Int
)

class AutoEqCache(private val context: Context) {
    companion object {
        private const val TAG = "AutoEqCache"
        private const val ASSET_ROOT = "autoeq"
        private const val DATABASE_NAME = "database.zip"
        private const val COMPLETE_MARKER = ".complete"
        private const val DATABASE_DIR = "current"
        private const val STAGING_PREFIX = "staging-"
        private const val PROFILE_SUFFIX = ".json.gz"
        private const val PROFILE_PREFIX = "profiles/"
        private val SAFE_ID = Regex("[A-Za-z0-9._-]+")
    }

    private val rootDir = File(context.filesDir, "autoeq")
    private val databaseDir = File(rootDir, DATABASE_DIR)
    private val databaseFile get() = File(databaseDir, DATABASE_NAME)

    suspend fun ensureBundledDatabase(
        onProgress: (AutoEqProgress) -> Unit = {}
    ) = withContext(Dispatchers.IO) {
        if (isInstalledDatabaseValid()) {
            onProgress(AutoEqProgress("Offline AutoEQ database ready", 100))
            return@withContext
        }

        rootDir.mkdirs()
        val staging = File(rootDir, "$STAGING_PREFIX${UUID.randomUUID()}")
        try {
            onProgress(AutoEqProgress("Preparing bundled AutoEQ database…", 0))
            staging.mkdirs()
            val target = File(staging, DATABASE_NAME)
            copyBundledDatabase(target, onProgress)
            onProgress(AutoEqProgress("Verifying bundled AutoEQ database…", 92))
            validateDatabaseFile(target, onProgress = {
                val scaled = 92 + (it.percent * 8 / 100)
                onProgress(AutoEqProgress("Verifying bundled AutoEQ database…", scaled.coerceAtMost(100)))
            })
            File(staging, COMPLETE_MARKER).writeText("1")
            commitStagedDirectoryInternal(staging)
            onProgress(AutoEqProgress("AutoEQ database ready — 8,847 profiles offline", 100))
            Log.i(TAG, "Installed bundled AutoEQ database at ${databaseFile.absolutePath}")
        } catch (t: Throwable) {
            staging.deleteRecursively()
            throw IOException("Unable to install bundled AutoEQ database", t)
        }
    }

    suspend fun loadMetadata(): AutoEqMetadata? = withContext(Dispatchers.IO) {
        openDatabase().use { zip ->
            readMetadata(zip)
        }
    }

    suspend fun loadIndex(): AutoEqIndex? = withContext(Dispatchers.IO) {
        openDatabase().use { zip ->
            readIndex(zip)
        }
    }

    suspend fun loadProfile(id: String): AutoEqProfile? = withContext(Dispatchers.IO) {
        if (!SAFE_ID.matches(id)) return@withContext null
        runCatching {
            openDatabase().use { zip ->
                val entry = zip.getEntry("$PROFILE_PREFIX$id$PROFILE_SUFFIX")
                    ?: return@use null
                zip.getInputStream(entry).use { compressed ->
                    GZIPInputStream(BufferedInputStream(compressed)).bufferedReader().use {
                        AutoEqProfile.fromJson(it.readText())
                    }
                }
            }
        }.getOrElse {
            Log.e(TAG, "Failed to read local AutoEQ profile $id", it)
            null
        }
    }

    suspend fun stageDownloadedArchive(
        archive: File,
        expectedMetadata: AutoEqMetadata,
        onProgress: (AutoEqProgress) -> Unit = {}
    ): File = withContext(Dispatchers.IO) {
        rootDir.mkdirs()
        val staging = File(rootDir, "$STAGING_PREFIX${UUID.randomUUID()}")
        try {
            staging.mkdirs()
            onProgress(AutoEqProgress("Reading downloaded AutoEQ database…", 5))
            val output = File(staging, DATABASE_NAME)
            importDownloadedRepository(
                sourceArchive = archive,
                output = output,
                onProgress = onProgress
            )
            onProgress(AutoEqProgress("Final verification of AutoEQ profiles…", 92))
            validateDatabaseFile(output, onProgress = {
                val scaled = 92 + (it.percent * 8 / 100)
                onProgress(AutoEqProgress("Final verification of AutoEQ profiles…", scaled.coerceAtMost(100)))
            })

            output.also {
                val stagedMetadata = java.util.zip.ZipFile(it).use { zip -> readMetadata(zip) }
                    ?: throw IOException("Downloaded AutoEQ archive is missing metadata.json")
                if (!stagedMetadata.contentEquals(expectedMetadata)) {
                    throw IOException("Server data changed during download; please sync again")
                }
            }

            File(staging, COMPLETE_MARKER).writeText("1")
            staging
        } catch (t: Throwable) {
            staging.deleteRecursively()
            throw t
        }
    }

    suspend fun commitStagedDirectory(staging: File) = withContext(Dispatchers.IO) {
        if (!staging.isDirectory || !File(staging, DATABASE_NAME).isFile) {
            throw IOException("Invalid AutoEQ staging directory")
        }
        commitStagedDirectoryInternal(staging)
    }

    suspend fun getProfileCount(): Int = withContext(Dispatchers.IO) {
        openDatabase().use { zip -> readIndex(zip)?.profiles?.distinctBy { it.id }?.size ?: 0 }
    }

    private fun isInstalledDatabaseValid(): Boolean {
        if (!databaseDir.isDirectory || !File(databaseDir, COMPLETE_MARKER).isFile || !databaseFile.isFile) {
            return false
        }

        // Fast readiness check only. Full profile-by-profile validation is performed
        // when a database is installed or synchronized, not on every dialog open.
        return runCatching {
            ZipFile(databaseFile).use { zip ->
                val metadata = readMetadata(zip) ?: return false
                val index = readIndex(zip) ?: return false
                val profileCount = index.profiles.distinctBy { it.id }.size
                metadata.profileCount > 0 &&
                    index.profiles.isNotEmpty() &&
                    profileCount > 0 &&
                    zip.getEntry("metadata.json") != null &&
                    zip.getEntry("index.json.gz") != null &&
                    zip.getEntry("$PROFILE_PREFIX${index.profiles.first().id}$PROFILE_SUFFIX") != null
            }
        }.getOrDefault(false)
    }

    private fun copyBundledDatabase(
        destination: File,
        onProgress: (AutoEqProgress) -> Unit
    ) {
        context.assets.open("$ASSET_ROOT/$DATABASE_NAME").use { input ->
            FileOutputStream(destination).use { output ->
                val total = runCatching { input.available().toLong() }.getOrDefault(-1L).coerceAtLeast(1L)
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var copied = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                    copied += read
                    val percent = if (total > 0L) {
                        (copied * 85L / total).toInt().coerceIn(0, 85)
                    } else {
                        0
                    }
                    onProgress(AutoEqProgress("Installing bundled AutoEQ database…", percent))
                }
                output.fd.sync()
            }
        }
    }

    /**
     * Converts GitHub's source repository archive directly into the app's
     * single-file local database. No profile files are extracted to disk.
     */
    private fun importDownloadedRepository(
        sourceArchive: File,
        output: File,
        onProgress: (AutoEqProgress) -> Unit
    ) {
        ZipFile(sourceArchive).use { sourceZip ->
            val rootPrefix = findRepositoryRoot(sourceZip)
            val metadataEntry = sourceZip.getEntry("$rootPrefix/metadata.json")
                ?: throw IOException("Downloaded AutoEQ archive has no metadata.json")
            val indexEntry = sourceZip.getEntry("$rootPrefix/index.json.gz")
                ?: throw IOException("Downloaded AutoEQ archive has no index.json.gz")

            val indexBytes = sourceZip.getInputStream(indexEntry).use { it.readBytes() }
            val index = runCatching {
                GZIPInputStream(ByteArrayInputStream(indexBytes)).bufferedReader().use {
                    AutoEqIndex.fromJson(it.readText())
                }
            }.getOrElse { throw IOException("Downloaded AutoEQ index is invalid", it) }

            val expectedIds = index.profiles.map { it.id }.distinct().toSet()
            if (expectedIds.isEmpty()) throw IOException("Downloaded AutoEQ index is empty")

            ZipOutputStream(BufferedOutputStream(FileOutputStream(output))).use { outputZip ->
                writeStoredEntry(
                    outputZip,
                    "metadata.json",
                    sourceZip.getInputStream(metadataEntry).use { it.readBytes() }
                )
                writeStoredEntry(outputZip, "index.json.gz", indexBytes)

                val profilePrefix = "$rootPrefix/profiles/"
                val sourceProfiles = sourceZip.entries().asSequence()
                    .filter { !it.isDirectory && it.name.startsWith(profilePrefix) && it.name.endsWith(PROFILE_SUFFIX) }
                    .toList()

                val foundIds = HashSet<String>(expectedIds.size)
                var processed = 0
                for (sourceEntry in sourceProfiles) {
                    val fileName = sourceEntry.name.removePrefix(profilePrefix)
                    val id = fileName.removeSuffix(PROFILE_SUFFIX)
                    if (!SAFE_ID.matches(id) || id !in expectedIds || !foundIds.add(id)) continue

                    val payload = sourceZip.getInputStream(sourceEntry).use { it.readBytes() }
                    validateGzipProfilePayload(payload, id)

                    writeStoredEntry(outputZip, "$PROFILE_PREFIX$fileName", payload)

                    processed++
                    onProgress(
                        AutoEqProgress(
                            "Building offline AutoEQ database…",
                            5 + (processed * 85 / expectedIds.size).coerceAtMost(85)
                        )
                    )
                }

                val missing = expectedIds - foundIds
                if (missing.isNotEmpty()) {
                    throw IOException("Downloaded AutoEQ archive is missing ${missing.size} profiles")
                }
            }
        }
    }

    private fun writeStoredEntry(zip: ZipOutputStream, name: String, payload: ByteArray) {
        val entry = ZipEntry(name)
        entry.method = ZipEntry.STORED
        entry.size = payload.size.toLong()
        entry.crc = CRC32().apply { update(payload) }.value
        zip.putNextEntry(entry)
        zip.write(payload)
        zip.closeEntry()
    }

    private fun findRepositoryRoot(zip: ZipFile): String =
        zip.entries().asSequence()
            .map { it.name.trimEnd('/') }
            .firstNotNullOfOrNull { name ->
                if (name.endsWith("/metadata.json")) name.removeSuffix("/metadata.json") else null
            }
            ?: throw IOException("Downloaded AutoEQ archive has no repository root")

    private fun validateGzipProfilePayload(payload: ByteArray, expectedId: String) {
        val profile = runCatching {
            GZIPInputStream(ByteArrayInputStream(payload)).bufferedReader().use {
                AutoEqProfile.fromJson(it.readText())
            }
        }.getOrElse { throw IOException("Invalid AutoEQ profile: $expectedId", it) }
        if (profile.id != expectedId) throw IOException("AutoEQ profile ID mismatch for $expectedId")
    }

    private fun validateDatabaseFile(
        file: File,
        onProgress: (AutoEqProgress) -> Unit = {}
    ) {
        if (!file.isFile || file.length() <= 0L) throw IOException("AutoEQ database archive is missing or empty")

        ZipFile(file).use { zip ->
            val metadata = readMetadata(zip)
                ?: throw IOException("AutoEQ database is missing or has invalid metadata.json")
            val index = readIndex(zip)
                ?: throw IOException("AutoEQ database is missing or has invalid index.json.gz")

            if (metadata.profileCount != index.profiles.size) {
                throw IOException(
                    "AutoEQ index count mismatch: metadata=${metadata.profileCount}, index=${index.profiles.size}"
                )
            }
            if (index.profiles.any { !SAFE_ID.matches(it.id) }) {
                throw IOException("AutoEQ index contains an unsafe profile ID")
            }

            val uniqueIds = index.profiles.map { it.id }.distinct()
            val expectedNames = uniqueIds.map { "$PROFILE_PREFIX$it$PROFILE_SUFFIX" }.toSet()
            val actualNames = zip.entries().asSequence()
                .filter { !it.isDirectory && it.name.startsWith(PROFILE_PREFIX) && it.name.endsWith(PROFILE_SUFFIX) }
                .map { it.name }
                .toSet()

            if (actualNames != expectedNames) {
                throw IOException(
                    "AutoEQ profile archive mismatch: expected=${expectedNames.size}, actual=${actualNames.size}"
                )
            }

            uniqueIds.forEachIndexed { indexPos, id ->
                val entry = zip.getEntry("$PROFILE_PREFIX$id$PROFILE_SUFFIX")
                    ?: throw IOException("Missing AutoEQ profile: $id")
                val profile = runCatching {
                    zip.getInputStream(entry).use { compressed ->
                        GZIPInputStream(BufferedInputStream(compressed)).bufferedReader().use {
                            AutoEqProfile.fromJson(it.readText())
                        }
                    }
                }.getOrElse { throw IOException("Invalid AutoEQ profile: $id", it) }

                if (profile.id != id) throw IOException("AutoEQ profile ID mismatch for $id")
                if (uniqueIds.size > 0) {
                    onProgress(AutoEqProgress("", ((indexPos + 1) * 100 / uniqueIds.size)))
                }
            }

            val indexEntry = zip.getEntry("index.json.gz")
                ?: throw IOException("AutoEQ index.json.gz is missing")
            val indexHash = md5UncompressedGzip(zip, indexEntry)
            if (!indexHash.equals(metadata.indexHash, ignoreCase = true)) {
                throw IOException("AutoEQ index hash mismatch")
            }
        }
    }

    private fun readMetadata(zip: ZipFile): AutoEqMetadata? =
        runCatching {
            zip.getInputStream(zip.getEntry("metadata.json") ?: return null).bufferedReader().use {
                AutoEqMetadata.fromJson(it.readText())
            }
        }.getOrNull()

    private fun readIndex(zip: ZipFile): AutoEqIndex? =
        runCatching {
            val entry = zip.getEntry("index.json.gz") ?: return null
            zip.getInputStream(entry).use { input ->
                GZIPInputStream(BufferedInputStream(input)).bufferedReader().use {
                    AutoEqIndex.fromJson(it.readText())
                }
            }
        }.getOrNull()

    private fun md5UncompressedGzip(zip: ZipFile, entry: ZipEntry): String {
        val digest = MessageDigest.getInstance("MD5")
        zip.getInputStream(entry).use { raw ->
            GZIPInputStream(BufferedInputStream(raw)).use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun openDatabase(): ZipFile {
        if (!databaseFile.isFile) throw IOException(
            "Offline AutoEQ database is not installed: ${databaseFile.absolutePath}"
        )
        return ZipFile(databaseFile)
    }

    private fun commitStagedDirectoryInternal(staging: File) {
        rootDir.mkdirs()
        val previous = File(rootDir, "previous-${UUID.randomUUID()}")
        try {
            if (databaseDir.exists() && !databaseDir.renameTo(previous)) {
                throw IOException("Could not prepare existing AutoEQ database for replacement")
            }
            if (!staging.renameTo(databaseDir)) {
                if (previous.exists() && !databaseDir.exists()) previous.renameTo(databaseDir)
                throw IOException("Could not install the new AutoEQ database")
            }
        } finally {
            previous.deleteRecursively()
        }
    }
}
