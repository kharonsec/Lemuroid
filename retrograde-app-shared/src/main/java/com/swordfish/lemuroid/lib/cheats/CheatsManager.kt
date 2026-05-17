package com.swordfish.lemuroid.lib.cheats

import android.content.SharedPreferences
import com.swordfish.lemuroid.lib.library.CoreID
import com.swordfish.lemuroid.lib.library.db.entity.Game
import com.swordfish.lemuroid.lib.storage.DirectoriesManager
import dagger.Lazy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import timber.log.Timber

class CheatsManager(
    private val sharedPreferences: Lazy<SharedPreferences>,
    private val directoriesManager: DirectoriesManager,
) {
    fun getCheatsDirectory(): File = directoriesManager.getCheatsDirectory()

    fun getOfficialCheatsDirectory(): File =
        File(getCheatsDirectory(), "official").apply { mkdirs() }

    fun getUserCheatsDirectory(): File =
        File(getCheatsDirectory(), "user").apply { mkdirs() }

    suspend fun loadPersistedCheatStates(
        game: Game,
        coreID: CoreID,
    ): Map<Int, Boolean> =
        withContext(Dispatchers.IO) {
            runCatching {
                val key = computeCheatStateKey(game, coreID)
                val json = sharedPreferences.get().getString(key, null)
                if (json != null) {
                    Json.decodeFromString<Map<Int, Boolean>>(json)
                } else {
                    emptyMap()
                }
            }.getOrElse {
                Timber.e(it, "Failed to load cheat states for ${game.title}")
                emptyMap()
            }
        }

    suspend fun saveCheatState(
        game: Game,
        coreID: CoreID,
        index: Int,
        enabled: Boolean,
    ) = withContext(Dispatchers.IO) {
        runCatching {
            val key = computeCheatStateKey(game, coreID)
            val currentStates = loadPersistedCheatStates(game, coreID)
            val updatedStates = currentStates + (index to enabled)
            sharedPreferences.get().edit().putString(key, Json.encodeToString(updatedStates)).apply()
        }.onFailure {
            Timber.e(it, "Failed to save cheat state for ${game.title}")
        }
    }

    private fun computeCheatStateKey(
        game: Game,
        coreID: CoreID,
    ): String = "cheat_state_${game.title}_${coreID.coreName}"

    companion object {
        private val Json = Json { encodeDefaults = true }
    }
}
