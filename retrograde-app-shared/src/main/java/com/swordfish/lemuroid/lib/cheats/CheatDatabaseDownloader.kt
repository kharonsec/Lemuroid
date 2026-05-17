package com.swordfish.lemuroid.lib.cheats

import com.swordfish.lemuroid.lib.storage.DirectoriesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.util.zip.ZipInputStream

class CheatDatabaseDownloader(
    private val directoriesManager: DirectoriesManager,
    private val okHttpClient: OkHttpClient,
) {
    private val cheatsUrl = "https://github.com/libretro/cheats/archive/master.zip"

    suspend fun ensureCheatDatabaseDownloaded() =
        withContext(Dispatchers.IO) {
            val officialDir = directoriesManager.getCheatsDirectory().resolve("official")
            if (isDatabaseFresh(officialDir)) {
                Timber.i("Cheat database is up to date")
                return@withContext
            }

            Timber.i("Downloading cheat database")
            try {
                downloadAndExtract(officialDir)
                Timber.i("Cheat database downloaded successfully")
            } catch (e: Exception) {
                Timber.e(e, "Failed to download cheat database")
            }
        }

    private fun isDatabaseFresh(officialDir: File): Boolean {
        val markerFile = File(officialDir, ".downloaded")
        if (!markerFile.exists()) return false
        val age = System.currentTimeMillis() - markerFile.lastModified()
        return age < ONE_WEEK_MS
    }

    private suspend fun downloadAndExtract(officialDir: File) {
        officialDir.deleteRecursively()
        officialDir.mkdirs()

        val request = Request.Builder().url(cheatsUrl).build()
        val response = okHttpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            throw RuntimeException("Failed to download cheat database: ${response.code}")
        }

        var extractedCount = 0
        response.body?.byteStream()?.use { inputStream ->
            ZipInputStream(inputStream).use { zipStream ->
                var entry = zipStream.nextEntry
                while (entry != null) {
                    val entryName = entry.name
                    if (entryName.endsWith(".cht")) {
                        val fileName = entryName.substringAfterLast("/")
                        if (fileName.isNotEmpty()) {
                            val outFile = File(officialDir, fileName)
                            outFile.outputStream().use { output ->
                                zipStream.copyTo(output)
                            }
                            extractedCount++
                        }
                    }
                    zipStream.closeEntry()
                    entry = zipStream.nextEntry
                }
            }
        }

        if (extractedCount > 0) {
            File(officialDir, ".downloaded").createNewFile()
        } else {
            throw RuntimeException("No .cht files extracted from download")
        }
    }

    companion object {
        private const val ONE_WEEK_MS = 7L * 24 * 60 * 60 * 1000
    }
}
