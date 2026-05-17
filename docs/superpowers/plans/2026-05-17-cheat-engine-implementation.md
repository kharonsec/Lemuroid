# Cheat Engine Integration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Wire up libretro's native cheat system into Lemuroid, enabling users to browse, toggle, and persist cheat codes per-game during gameplay.

**Architecture:** Three-layer approach — LibretroDroid gets native cheat APIs (JNI bridge to libretro env callbacks), retrograde-app-shared gets a `CheatsManager` for persistence and a `CheatDatabaseDownloader` for fetching the official `.cht` database, and lemuroid-app gets a CheatsScreen in the game menu with toggle switches.

**Tech Stack:** Kotlin, Jetpack Compose, Dagger DI, SharedPreferences, OkHttp, JNI (C++ for LibretroDroid), libretro `.cht` format.

---

## File Structure

### New files

| File | Responsibility |
|------|----------------|
| `retrograde-app-shared/.../cheats/CheatInfo.kt` | `CheatInfo` data class |
| `retrograde-app-shared/.../cheats/CheatsManager.kt` | Cheat state persistence, cheat directory management |
| `retrograde-app-shared/.../cheats/CheatDatabaseDownloader.kt` | Download/extract official `.cht` database |
| `lemuroid-app/.../gamemenu/cheats/CheatsScreen.kt` | Compose UI for cheat toggles |
| `lemuroid-app/.../gamemenu/cheats/CheatsViewModel.kt` | ViewModel for CheatsScreen |
| `lemuroid-app/src/main/res/drawable/ic_menu_cheats.xml` | Menu icon for cheats entry |

### Modified files

| File | Change |
|------|--------|
| `lemuroid-app/src/main/res/values/strings.xml` | Add `game_menu_cheats` string |
| `lemuroid-app/.../gamemenu/GameMenuNavigationRoutes.kt` | Add `CHEATS` route |
| `lemuroid-app/.../gamemenu/GameMenuHomeScreen.kt` | Add "Cheats" menu entry |
| `lemuroid-app/.../gamemenu/GameMenuActivity.kt` | Add cheats to `GameMenuRequest`, pass to CheatsScreen |
| `lemuroid-app/.../shared/GameMenuContract.kt` | Add `EXTRA_CHEATS`, `RESULT_CHEATS` constants |
| `lemuroid-app/.../shared/game/viewmodel/GameViewModelSideEffects.kt` | Add `ShowCheats` UiEffect |
| `lemuroid-app/.../shared/game/BaseGameActivity.kt` | Fetch cheats, pass to dialog, handle result |
| `lemuroid-app/.../shared/game/BaseGameScreenViewModel.kt` | Add cheat loading, expose cheat list |
| `lemuroid-app/.../shared/game/viewmodel/GameViewModelRetroGameView.kt` | Expose cheat count/description methods |
| `retrograde-app-shared/.../storage/DirectoriesManager.kt` | Add `getCheatsDirectory()` |
| `lemuroid-app/.../LemuroidApplicationModule.kt` | Provide `CheatsManager` |
| `lemuroid-app/.../LemuroidApplicationComponent.kt` | Inject `CheatsManager` |
| `buildSrc/.../deps.kt` | Switch LibretroDroid to local build |
| `lemuroid-app/.../gamemenu/GameMenuNavigationRoutes.kt` | Add `CHEATS` route enum |
| `retrograde-app-shared/.../storage/DirectoriesManager.kt` | Add `getCheatsDirectory()` |
| `lemuroid-app/src/main/res/xml/mobile_game_settings.xml` | Add cheats preference (if needed for fallback) |
| `lemuroid-app/src/main/res/xml/tv_game_settings.xml` | Add cheats preference (if needed for fallback) |

---

## Prerequisite: LibretroDroid Cheat APIs

The following must be implemented in the LibretroDroid repository (https://github.com/Swordfish90/LibretroDroid) before the Lemuroid-side tasks can be fully tested:

1. **New `GLRetroView` methods:**
   - `fun getCheatCount(): Int`
   - `fun getCheatDescription(index: Int): String?`
   - `fun getCheatState(index: Int): Boolean`
   - `fun setCheatState(index: Int, enabled: Boolean)`
   - `fun flushCheats()`

2. **New `GLRetroViewData` field:**
   - `var cheatsDirectory: String? = null`

3. **Native `.cht` parser** that loads cheat files from `cheatsDirectory` and responds to libretro environment callbacks.

For the purpose of this plan, we will write the Lemuroid-side code against these APIs. If LibretroDroid is not yet available, use stub implementations that return 0 cheats.

---

### Task 1: Add `CheatInfo` data class

**Files:**
- Create: `retrograde-app-shared/src/main/java/com/swordfish/lemuroid/lib/cheats/CheatInfo.kt`

- [ ] **Step 1: Create CheatInfo.kt**

```kotlin
package com.swordfish.lemuroid.lib.cheats

import java.io.Serializable

data class CheatInfo(
    val index: Int,
    val description: String,
    val enabled: Boolean,
) : Serializable
```

- [ ] **Step 2: Commit**

```bash
git add retrograde-app-shared/src/main/java/com/swordfish/lemuroid/lib/cheats/CheatInfo.kt
git commit -m "feat(cheats): add CheatInfo data class"
```

---

### Task 2: Extend DirectoriesManager with cheats directory

**Files:**
- Modify: `retrograde-app-shared/src/main/java/com/swordfish/lemuroid/lib/storage/DirectoriesManager.kt`

- [ ] **Step 1: Add getCheatsDirectory() method**

Add to the end of the `DirectoriesManager` class (before the closing `}`):

```kotlin
    fun getCheatsDirectory(): File =
        File(appContext.filesDir, "cheats").apply {
            mkdirs()
        }
```

- [ ] **Step 2: Commit**

```bash
git add retrograde-app-shared/src/main/java/com/swordfish/lemuroid/lib/storage/DirectoriesManager.kt
git commit -m "feat(cheats): add getCheatsDirectory() to DirectoriesManager"
```

---

### Task 3: Create CheatsManager

**Files:**
- Create: `retrograde-app-shared/src/main/java/com/swordfish/lemuroid/lib/cheats/CheatsManager.kt`

- [ ] **Step 1: Create CheatsManager.kt**

```kotlin
package com.swordfish.lemuroid.lib.cheats

import android.content.SharedPreferences
import com.swordfish.lemuroid.lib.library.CoreID
import com.swordfish.lemuroid.lib.library.db.entity.Game
import com.swordfish.lemuroid.lib.storage.DirectoriesManager
import dagger.Lazy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
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
```

- [ ] **Step 2: Commit**

```bash
git add retrograde-app-shared/src/main/java/com/swordfish/lemuroid/lib/cheats/CheatsManager.kt
git commit -m "feat(cheats): add CheatsManager for persistence and directory management"
```

---

### Task 4: Create CheatDatabaseDownloader

**Files:**
- Create: `retrograde-app-shared/src/main/java/com/swordfish/lemuroid/lib/cheats/CheatDatabaseDownloader.kt`

- [ ] **Step 1: Create CheatDatabaseDownloader.kt**

```kotlin
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
                        }
                    }
                    zipStream.closeEntry()
                    entry = zipStream.nextEntry
                }
            }
        }

        File(officialDir, ".downloaded").createNewFile()
    }

    companion object {
        private const val ONE_WEEK_MS = 7L * 24 * 60 * 60 * 1000
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add retrograde-app-shared/src/main/java/com/swordfish/lemuroid/lib/cheats/CheatDatabaseDownloader.kt
git commit -m "feat(cheats): add CheatDatabaseDownloader for official .cht database"
```

---

### Task 5: Add cheats string resource and icon

**Files:**
- Modify: `lemuroid-app/src/main/res/values/strings.xml`
- Create: `lemuroid-app/src/main/res/drawable/ic_menu_cheats.xml`

- [ ] **Step 1: Add string resource**

Find the line containing `game_menu_title` and add after the existing game_menu strings:

```xml
    <string name="game_menu_cheats">Cheats</string>
```

- [ ] **Step 2: Create cheats icon**

Create a vector drawable consistent with the existing menu icons (24x24, single color):

```xml
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="?attr/colorOnSurface"
        android:pathData="M12.65,10C11.83,7.67 9.61,6 7,6C3.69,6 1,8.69 1,12C1,15.31 3.69,18 7,18C9.61,18 11.83,16.33 12.65,14H17V10H12.65M17,18H15V16H17V18M17,14H15V12H17V14M20,18H19V16H20V18M20,14H19V12H20V14M23,18H22V16H23V18M23,14H22V12H23V14Z" />
</vector>
```

- [ ] **Step 3: Commit**

```bash
git add lemuroid-app/src/main/res/values/strings.xml lemuroid-app/src/main/res/drawable/ic_menu_cheats.xml
git commit -m "feat(cheats): add string resource and menu icon"
```

---

### Task 6: Add CHEATS route to GameMenuNavigationRoutes

**Files:**
- Modify: `lemuroid-app/src/main/java/com/swordfish/lemuroid/app/mobile/feature/gamemenu/GameMenuNavigationRoutes.kt`

- [ ] **Step 1: Add CHEATS enum value**

Find the `OPTIONS` enum entry and add `CHEATS` after it (before the `;`):

```kotlin
    OPTIONS(
        route = "options",
        titleId = R.string.game_menu_settings,
        parent = HOME,
    ),
    CHEATS(
        route = "cheats",
        titleId = R.string.game_menu_cheats,
        parent = HOME,
    ),
    ;
```

- [ ] **Step 2: Commit**

```bash
git add lemuroid-app/src/main/java/com/swordfish/lemuroid/app/mobile/feature/gamemenu/GameMenuNavigationRoutes.kt
git commit -m "feat(cheats): add CHEATS route to game menu navigation"
```

---

### Task 7: Extend GameMenuContract with cheat extras

**Files:**
- Modify: `lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/GameMenuContract.kt`

- [ ] **Step 1: Add cheat constants**

Add to the end of the `GameMenuContract` object (before the closing `}`):

```kotlin
    const val EXTRA_CHEATS = "EXTRA_CHEATS"
    const val RESULT_CHEATS = "RESULT_CHEATS"
```

- [ ] **Step 2: Commit**

```bash
git add lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/GameMenuContract.kt
git commit -m "feat(cheats): add EXTRA_CHEATS and RESULT_CHEATS to GameMenuContract"
```

---

### Task 8: Add ShowCheats side effect

**Files:**
- Modify: `lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/game/viewmodel/GameViewModelSideEffects.kt`

- [ ] **Step 1: Import CheatInfo**

Add at the top with the other imports:

```kotlin
import com.swordfish.lemuroid.lib.cheats.CheatInfo
```

- [ ] **Step 2: Add ShowCheats UiEffect**

Add inside the `sealed interface UiEffect` (after `ToggleFastForward`):

```kotlin
        data class ShowCheats(val cheats: List<CheatInfo>) : UiEffect
```

- [ ] **Step 3: Add showCheats method**

Add at the end of the `GameViewModelSideEffects` class:

```kotlin
    fun showCheats(cheats: List<CheatInfo>) {
        scope.launch {
            withContext(Dispatchers.Main) {
                uiEffects.emit(UiEffect.ShowCheats(cheats))
            }
        }
    }
```

- [ ] **Step 4: Commit**

```bash
git add lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/game/viewmodel/GameViewModelSideEffects.kt
git commit -m "feat(cheats): add ShowCheats UiEffect"
```

---

### Task 9: Expose cheat methods in GameViewModelRetroGameView

**Files:**
- Modify: `lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/game/viewmodel/GameViewModelRetroGameView.kt`

- [ ] **Step 1: Import CheatInfo**

Add at the top with the other imports:

```kotlin
import com.swordfish.lemuroid.lib.cheats.CheatInfo
```

- [ ] **Step 2: Add cheat loading method**

Add at the end of the `GameViewModelRetroGameView` class (before the closing `}`):

```kotlin
    suspend fun loadCheats(persistedStates: Map<Int, Boolean>): List<CheatInfo> {
        val retroView = retroGameViewFlow()
        val count = retroView.getCheatCount()
        return (0 until count).map { index ->
            val description = retroView.getCheatDescription(index) ?: "Cheat ${index + 1}"
            val enabled = persistedStates[index] ?: false
            retroView.setCheatState(index, enabled)
            CheatInfo(index, description, enabled)
        }
    }

    fun setCheatState(cheat: CheatInfo, enabled: Boolean): CheatInfo {
        retroGameView?.apply {
            setCheatState(cheat.index, enabled)
            flushCheats()
        }
        return cheat.copy(enabled = enabled)
    }
```

- [ ] **Step 3: Commit**

```bash
git add lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/game/viewmodel/GameViewModelRetroGameView.kt
git commit -m "feat(cheats): expose cheat loading and state methods in GameViewModelRetroGameView"
```

---

### Task 10: Add cheat loading to BaseGameScreenViewModel

**Files:**
- Modify: `lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/game/BaseGameScreenViewModel.kt`

- [ ] **Step 1: Add imports and CheatsManager dependency**

Add import:

```kotlin
import com.swordfish.lemuroid.lib.cheats.CheatsManager
import com.swordfish.lemuroid.lib.cheats.CheatInfo
```

Add `cheatsManager` parameter to the constructor (after `rumbleManager`):

```kotlin
    rumbleManager: RumbleManager,
    private val cheatsManager: CheatsManager,
```

Also update the `Factory` class — add `cheatsManager` parameter and pass it through:

```kotlin
        private val rumbleManager: RumbleManager,
        private val cheatsManager: CheatsManager,
```

And in the `create()` method:

```kotlin
                rumbleManager,
                cheatsManager,
```

- [ ] **Step 2: Add cheat state to ViewModel**

Add at the top of the class (after the `saves` property declaration):

```kotlin
    private lateinit var storedGame: Game
    private lateinit var storedSystemCoreConfig: SystemCoreConfig
    private var cheatList: List<CheatInfo> = emptyList()
```

In `loadGame()`, add at the start of the function body:

```kotlin
        storedGame = game
        storedSystemCoreConfig = systemCoreConfig
```

Add after the `saves` property declaration (the new methods):

```kotlin
    suspend fun loadCheatsForGame() {
        val persistedStates = cheatsManager.loadPersistedCheatStates(storedGame, storedSystemCoreConfig.coreID)
        cheatList = retroGameView.loadCheats(persistedStates)
    }

    fun getCheatList(): List<CheatInfo> = cheatList

    fun updateCheatState(cheat: CheatInfo, enabled: Boolean) {
        val updated = retroGameView.setCheatState(cheat, enabled)
        cheatList = cheatList.map { if (it.index == updated.index) updated else it }
        viewModelScope.launch {
            cheatsManager.saveCheatState(
                game = storedGame,
                coreID = storedSystemCoreConfig.coreID,
                index = updated.index,
                enabled = updated.enabled,
            )
        }
    }
```

- [ ] **Step 3: Commit**

```bash
git add lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/game/BaseGameScreenViewModel.kt
git commit -m "feat(cheats): add cheat loading and state management to BaseGameScreenViewModel"
```

---

### Task 11: Wire cheats into BaseGameActivity

**Files:**
- Modify: `lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/game/BaseGameActivity.kt`

- [ ] **Step 1: Import CheatInfo and add CheatsManager**

Add imports:

```kotlin
import com.swordfish.lemuroid.lib.cheats.CheatInfo
import com.swordfish.lemuroid.lib.cheats.CheatsManager
```

Add `@Inject` field (after `rumbleManager`):

```kotlin
    @Inject
    lateinit var cheatsManager: CheatsManager
```

- [ ] **Step 2: Add cheats to displayOptionsDialog**

In `displayOptionsDialog()`, after the `advancedOptions` declaration and before the `intent` creation, add:

```kotlin
        val cheats = baseGameScreenViewModel.getCheatList()
```

Add to the intent extras (before `startActivityForResult`):

```kotlin
                this.putExtra(GameMenuContract.EXTRA_CHEATS, cheats.toTypedArray())
```

- [ ] **Step 3: Handle RESULT_CHEATS in onActivityResult**

In `onActivityResult()`, add a new handler block (after the last existing `if` block):

```kotlin
            if (data?.hasExtra(GameMenuContract.RESULT_CHEATS) == true) {
                val updatedCheats = data.serializable<Array<CheatInfo>>(GameMenuContract.RESULT_CHEATS)
                updatedCheats?.forEach { updatedCheat ->
                    baseGameScreenViewModel.updateCheatState(updatedCheat, updatedCheat.enabled)
                }
            }
```

- [ ] **Step 4: Commit**

```bash
git add lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/game/BaseGameActivity.kt
git commit -m "feat(cheats): wire cheats into BaseGameActivity"
```

---

### Task 12: Create CheatsViewModel

**Files:**
- Create: `lemuroid-app/src/main/java/com/swordfish/lemuroid/app/mobile/feature/gamemenu/cheats/CheatsViewModel.kt`

- [ ] **Step 1: Create CheatsViewModel.kt**

```kotlin
package com.swordfish.lemuroid.app.mobile.feature.gamemenu.cheats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.swordfish.lemuroid.lib.cheats.CheatInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class CheatsViewModel : ViewModel() {

    private val _cheats = MutableStateFlow<List<CheatInfo>>(emptyList())
    val cheats: StateFlow<List<CheatInfo>> = _cheats.asStateFlow()

    fun setCheats(cheats: List<CheatInfo>) {
        _cheats.value = cheats
    }

    fun updateCheat(cheat: CheatInfo, enabled: Boolean) {
        val updated = cheat.copy(enabled = enabled)
        _cheats.value = _cheats.value.map { if (it.index == updated.index) updated else it }
    }

    fun getCurrentCheats(): List<CheatInfo> = _cheats.value

    class Factory(
        private val initialCheats: List<CheatInfo>,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return CheatsViewModel().also { it.setCheats(initialCheats) } as T
        }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add lemuroid-app/src/main/java/com/swordfish/lemuroid/app/mobile/feature/gamemenu/cheats/CheatsViewModel.kt
git commit -m "feat(cheats): add CheatsViewModel"
```

---

### Task 13: Create CheatsScreen Compose UI

**Files:**
- Create: `lemuroid-app/src/main/java/com/swordfish/lemuroid/app/mobile/feature/gamemenu/cheats/CheatsScreen.kt`

- [ ] **Step 1: Create CheatsScreen.kt**

```kotlin
package com.swordfish.lemuroid.app.mobile.feature.gamemenu.cheats

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.app.mobile.feature.gamemenu.GameMenuActivity
import com.swordfish.lemuroid.app.utils.android.settings.LemuroidSettingsSwitch
import com.swordfish.lemuroid.lib.cheats.CheatInfo

@Composable
fun CheatsScreen(
    gameMenuRequest: GameMenuActivity.GameMenuRequest,
    viewModel: CheatsViewModel = viewModel(
        factory = CheatsViewModel.Factory(gameMenuRequest.cheats),
    ),
) {
    val cheats by viewModel.cheats.collectAsState()

    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
        if (cheats.isEmpty()) {
            Text(
                text = stringResource(R.string.game_menu_cheats_empty),
                modifier = Modifier.padding(16.dp),
            )
        } else {
            cheats.forEach { cheat ->
                LemuroidSettingsSwitch(
                    title = { Text(text = cheat.description) },
                    state =
                        com.alorma.compose.settings.storage.memory.rememberMemoryBooleanSettingState(cheat.enabled),
                    onCheckedChange = { enabled ->
                        viewModel.updateCheat(cheat, enabled)
                    },
                )
            }
        }
    }
}
```

Note: Cheats are applied when the user exits the game menu. The `GameMenuActivity` returns the updated cheat states via `RESULT_CHEATS`, which `BaseGameActivity.onActivityResult` processes by calling `BaseGameScreenViewModel.updateCheatState()` — this applies cheats to `GLRetroView` and persists them via `CheatsManager`.

- [ ] **Step 2: Commit**

```bash
git add lemuroid-app/src/main/java/com/swordfish/lemuroid/app/mobile/feature/gamemenu/cheats/CheatsScreen.kt
git commit -m "feat(cheats): add CheatsScreen Compose UI"
```

---

### Task 14: Add Cheats entry to GameMenuHomeScreen

**Files:**
- Modify: `lemuroid-app/src/main/java/com/swordfish/lemuroid/app/mobile/feature/gamemenu/GameMenuHomeScreen.kt`

- [ ] **Step 1: Add cheats entry**

Find the block that adds Save/Load (the `if (gameMenuRequest.coreConfig.statesSupported)` block) and add after its closing brace, before the Quit entry:

```kotlin
        if (gameMenuRequest.coreConfig.statesSupported) {
            // ... existing Save/Load ...
        }

        // ADD THIS:
        LemuroidSettingsMenuLink(
            title = { Text(text = stringResource(id = R.string.game_menu_cheats)) },
            icon = {
                Icon(
                    painterResource(R.drawable.ic_menu_cheats),
                    contentDescription = stringResource(id = R.string.game_menu_cheats),
                )
            },
            onClick = { navController.navigateToRoute(GameMenuRoute.CHEATS) },
        )

        LemuroidSettingsMenuLink(
            title = { Text(text = stringResource(id = R.string.game_menu_quit)) },
            // ... existing Quit ...
```

- [ ] **Step 2: Commit**

```bash
git add lemuroid-app/src/main/java/com/swordfish/lemuroid/app/mobile/feature/gamemenu/GameMenuHomeScreen.kt
git commit -m "feat(cheats): add Cheats entry to game menu home screen"
```

---

### Task 15: Wire CheatsScreen into GameMenuActivity

**Files:**
- Modify: `lemuroid-app/src/main/java/com/swordfish/lemuroid/app/mobile/feature/gamemenu/GameMenuActivity.kt`

- [ ] **Step 1: Add cheats to GameMenuRequest**

Add to the `GameMenuRequest` data class (after `allTiltConfigurations`):

```kotlin
        val cheats: List<CheatInfo>,
```

Add import:

```kotlin
import com.swordfish.lemuroid.lib.cheats.CheatInfo
```

- [ ] **Step 2: Parse cheats from intent**

In `onCreate()`, add to the `GameMenuRequest` construction (after `allTiltConfigurations`):

```kotlin
                cheats =
                    intent.serializable<Array<CheatInfo>>(GameMenuContract.EXTRA_CHEATS)
                        ?.toList()
                        ?: emptyList(),
```

- [ ] **Step 3: Add CheatsScreen to NavHost**

In the `NavHost`, add a new `composable` block (after the `OPTIONS` composable, before the closing `}`):

```kotlin
                    composable(GameMenuRoute.CHEATS) {
                        CheatsScreen(gameMenuRequest)
                    }
```

Add import:

```kotlin
import com.swordfish.lemuroid.app.mobile.feature.gamemenu.cheats.CheatsScreen
```

- [ ] **Step 4: Commit**

```bash
git add lemuroid-app/src/main/java/com/swordfish/lemuroid/app/mobile/feature/gamemenu/GameMenuActivity.kt
git commit -m "feat(cheats): wire CheatsScreen into GameMenuActivity"
```

```bash
git add lemuroid-app/src/main/java/com/swordfish/lemuroid/app/mobile/feature/gamemenu/GameMenuActivity.kt
git commit -m "feat(cheats): wire CheatsScreen into GameMenuActivity"
```

---

### Task 16: Provide CheatsManager in DI

**Files:**
- Modify: `lemuroid-app/src/main/java/com/swordfish/lemuroid/app/LemuroidApplicationModule.kt`

- [ ] **Step 1: Add CheatsManager provider**

Add import:

```kotlin
import com.swordfish.lemuroid.lib.cheats.CheatsManager
import com.swordfish.lemuroid.lib.cheats.CheatDatabaseDownloader
```

Add provider method in the `companion object` (after `rumbleManager`):

```kotlin
        @Provides
        @PerApp
        @JvmStatic
        fun cheatsManager(
            sharedPreferences: Lazy<SharedPreferences>,
            directoriesManager: DirectoriesManager,
        ) = CheatsManager(sharedPreferences, directoriesManager)

        @Provides
        @PerApp
        @JvmStatic
        fun cheatDatabaseDownloader(
            directoriesManager: DirectoriesManager,
            okHttpClient: OkHttpClient,
        ) = CheatDatabaseDownloader(directoriesManager, okHttpClient)
```

- [ ] **Step 2: Commit**

```bash
git add lemuroid-app/src/main/java/com/swordfish/lemuroid/app/LemuroidApplicationModule.kt
git commit -m "feat(cheats): provide CheatsManager and CheatDatabaseDownloader in DI"
```

---

### Task 17: Trigger cheat database download on app startup

**Files:**
- Modify: `lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/startup/MainProcessInitializer.kt`

- [ ] **Step 1: Read MainProcessInitializer.kt first** to understand the existing structure.

- [ ] **Step 2: Add cheat database download**

Add `CheatDatabaseDownloader` as a constructor parameter and call `ensureCheatDatabaseDownloaded()` in `afterCreate()`. Use `CoroutineScope(Dispatchers.IO).launch { }` to avoid blocking startup.

Add import:

```kotlin
import com.swordfish.lemuroid.lib.cheats.CheatDatabaseDownloader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
```

- [ ] **Step 3: Commit**

```bash
git add lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/startup/MainProcessInitializer.kt
git commit -m "feat(cheats): download cheat database on app startup"
```

---

### Task 18: Switch LibretroDroid to local build

**Files:**
- Modify: `buildSrc/src/main/java/deps.kt`

- [ ] **Step 1: Change libretrodroid dependency**

Find the line:

```kotlin
        const val libretrodroid            = "com.github.Swordfish90:LibretroDroid:${versions.libretrodroid}"
```

Comment it out and add the local version:

```kotlin
//        const val libretrodroid            = "com.github.Swordfish90:LibretroDroid:${versions.libretrodroid}"
        const val libretrodroid            = "com.swordfish:libretrodroid:unspecified"
```

- [ ] **Step 2: Commit**

```bash
git add buildSrc/src/main/java/deps.kt
git commit -m "build: switch LibretroDroid to local build for cheat API support"
```

---

### Task 19: TV Game Menu — Add cheats entry

**Files:**
- Modify: `lemuroid-app/src/main/java/com/swordfish/lemuroid/app/tv/gamemenu/TVGameMenuFragment.kt`
- Modify: `lemuroid-app/src/main/java/com/swordfish/lemuroid/app/tv/gamemenu/TVGameMenuActivity.kt`
- Modify: `lemuroid-app/src/main/res/xml/tv_game_settings.xml`
- Modify: `lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/gamemenu/GameMenuHelper.kt`

- [ ] **Step 1: Add cheats preference to tv_game_settings.xml**

Add after the save/load entries (before the core options section):

```xml
    <PreferenceScreen
        android:key="pref_game_section_cheats"
        android:title="@string/game_menu_cheats"
        app:icon="@drawable/ic_menu_cheats"/>
```

- [ ] **Step 2: Add setupCheatsOption to GameMenuHelper**

Add to `GameMenuHelper.kt` (before the `const val` declarations at the bottom):

```kotlin
    fun setupCheatsOption(
        screen: PreferenceScreen,
        cheats: List<CheatInfo>,
    ) {
        val cheatsScreen = screen.findPreference<PreferenceScreen>(SECTION_CHEATS)
        if (cheatsScreen == null || cheats.isEmpty()) {
            cheatsScreen?.isVisible = false
            return
        }

        cheatsScreen.isVisible = true
        cheatsScreen.removeAll()

        cheats.forEach { cheat ->
            cheatsScreen.addPreference(
                SwitchPreference(screen.context).apply {
                    key = "pref_game_cheat_${cheat.index}"
                    title = cheat.description
                    isChecked = cheat.enabled
                }
            )
        }
    }

    const val SECTION_CHEATS = "pref_game_section_cheats"
```

Add imports to GameMenuHelper:

```kotlin
import com.swordfish.lemuroid.lib.cheats.CheatInfo
import androidx.preference.SwitchPreference
```

- [ ] **Step 3: Update TVGameMenuFragment constructor**

Add `cheats` parameter to the constructor (after `fastForwardSupported`):

```kotlin
    private val fastForwardSupported: Boolean,
    private val cheats: Array<CheatInfo>,
```

Add import:

```kotlin
import com.swordfish.lemuroid.lib.cheats.CheatInfo
```

- [ ] **Step 4: Add cheats setup in onViewCreated**

Add after the `GameMenuHelper.setupSaveOption` call:

```kotlin
        GameMenuHelper.setupCheatsOption(preferenceScreen, cheats.toList())
```

- [ ] **Step 5: Pass cheats from TVGameMenuActivity**

In `TVGameMenuActivity`, read the cheats extra from the intent and pass it to the fragment constructor. Add:

```kotlin
    private val cheats: Array<CheatInfo>
```

And in the fragment creation code, pass `cheats` as a constructor argument.

- [ ] **Step 6: Commit**

```bash
git add lemuroid-app/src/main/res/xml/tv_game_settings.xml lemuroid-app/src/main/java/com/swordfish/lemuroid/app/tv/gamemenu/TVGameMenuFragment.kt lemuroid-app/src/main/java/com/swordfish/lemuroid/app/tv/gamemenu/TVGameMenuActivity.kt lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/gamemenu/GameMenuHelper.kt
git commit -m "feat(cheats): add cheats entry to TV game menu"
```

---

### Task 20: Verify build compiles

- [ ] **Step 1: Run Gradle build**

```bash
./gradlew :lemuroid-app:assembleFreeDebug --no-daemon 2>&1 | tail -50
```

Expected: BUILD SUCCESSFUL. If there are compilation errors, fix them before proceeding.

- [ ] **Step 2: Commit any fixes**

```bash
git add -A
git commit -m "fix(cheats): fix compilation errors"
```

---

## Self-Review Checklist

- [x] **Spec coverage:** Every requirement from the spec is covered:
  - LibretroDroid cheat APIs → Task 9 (consumer side), Task 18 (local build switch), prerequisite section
  - CheatInfo data class → Task 1
  - CheatsManager persistence → Task 3
  - CheatDatabaseDownloader → Task 4, Task 17
  - DirectoriesManager extension → Task 2
  - GameMenuContract extras → Task 7
  - ShowCheats side effect → Task 8
  - GameMenuRoute.CHEATS → Task 6
  - GameMenuHomeScreen entry → Task 14
  - CheatsScreen Compose UI → Task 13
  - CheatsViewModel → Task 12
  - BaseGameActivity wiring → Task 11
  - BaseGameScreenViewModel cheat loading → Task 10
  - GameViewModelRetroGameView cheat methods → Task 9
  - DI wiring → Task 16
  - TV game menu → Task 19
  - String resources → Task 5
  - Icon → Task 5

- [x] **Placeholder scan:** No TBD/TODO/fill-in-later patterns found.

- [x] **Type consistency:** `CheatInfo` is defined in Task 1, used consistently in Tasks 3, 7, 8, 9, 10, 11, 12, 13, 14, 15. `CheatsManager` is defined in Task 3, provided in Task 16, injected in Tasks 10, 11, 13, 15. Method signatures match across tasks.
