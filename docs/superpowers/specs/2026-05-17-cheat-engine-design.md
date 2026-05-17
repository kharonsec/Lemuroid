# Cheat Engine Integration — Design Spec

## Overview

Integrate libretro's native cheat system into Lemuroid, allowing users to browse, enable, and disable cheat codes during gameplay. Cheat state is persisted per-game and restored on next session. Official `.cht` files are downloaded from the libretro cheat database; user-provided files are also supported.

## Architecture

### Three Layers

```
┌─────────────────────────────────────────────────┐
│  Layer 3: lemuroid-app (UI)                     │
│  GameMenuHomeScreen → CheatsScreen              │
│  GameViewModelSideEffects → ShowCheats effect   │
│  GameMenuContract → intent extras               │
├─────────────────────────────────────────────────┤
│  Layer 2: retrograde-app-shared (business logic)│
│  CheatsManager — state + persistence            │
│  CheatDatabaseDownloader — fetch .cht files     │
│  DirectoriesManager — cheats directory          │
├─────────────────────────────────────────────────┤
│  Layer 1: LibretroDroid (native JNI bridge)     │
│  GLRetroView cheat APIs                         │
│  getCheatCount / getCheatDescription            │
│  getCheatState / setCheatState / flushCheats    │
│  CheatFileParser — parse .cht files (native)    │
└─────────────────────────────────────────────────┘
```

## Layer 1: LibretroDroid Changes

LibretroDroid must be switched from the remote JitPack dependency to a local build. The commented-out line in `deps.kt` already supports this:

```kotlin
// deps.kt
// Switch from:
const val libretrodroid = "com.github.Swordfish90:LibretroDroid:${versions.libretrodroid}"
// To:
const val libretrodroid = "com.swordfish:libretrodroid:unspecified"
```

### New GLRetroView APIs

| Method | Signature | Description |
|--------|-----------|-------------|
| `getCheatCount()` | `fun getCheatCount(): Int` | Returns number of cheats loaded by the core (from `.cht` files) |
| `getCheatDescription(index: Int)` | `fun getCheatDescription(index: Int): String?` | Returns the display name for a cheat at the given index |
| `getCheatState(index: Int)` | `fun getCheatState(index: Int): Boolean` | Returns whether a cheat is currently enabled |
| `setCheatState(index: Int, enabled: Boolean)` | `fun setCheatState(index: Int, enabled: Boolean)` | Enables or disables a cheat |
| `flushCheats()` | `fun flushCheats()` | Tells the core to apply pending cheat changes |

These map to libretro's `RETRO_ENVIRONMENT_GET_CHEAT_COUNT`, `RETRO_ENVIRONMENT_GET_CHEAT_DESC`, `RETRO_ENVIRONMENT_GET_CHEAT_STATE`, and `RETRO_ENVIRONMENT_SET_CHEAT_STATE`.

### CheatFileParser (native)

Parses `.cht` files in LibretroDroid's native layer. The `.cht` format is a simple INI-like structure:

```
cheat0_enable=0
cheat0_desc="Infinite Lives"
cheat0_code=00000000,00FF
cheat1_enable=0
cheat1_desc="Infinite Health"
cheat1_code=00000001,00FF
```

The parser:
1. Scans the configured cheats directory for `.cht` files
2. Parses each file into an internal cheat list
3. Populates data for the environment callbacks
4. Applies memory patches when cheats are enabled

### Cheat File Loading

In the libretro architecture, **the frontend** (not the core) is responsible for loading and parsing `.cht` files. The core queries cheat data via environment callbacks, and the frontend responds.

LibretroDroid must:
1. Parse `.cht` files from the configured cheats directory when `GLRetroView` is created
2. Store parsed cheat data internally
3. Respond to the core's `RETRO_ENVIRONMENT_GET_CHEAT_COUNT`, `GET_CHEAT_DESC`, `GET_CHEAT_STATE`, and `SET_CHEAT_STATE` callbacks
4. Apply cheat memory patches when the core requests them (via `RETRO_ENVIRONMENT_SET_CHEAT` or frame-by-frame)

The cheats directory is passed to LibretroDroid via `GLRetroViewData.cheatsDirectory` (new field).

## Layer 2: retrograde-app-shared

### CheatInfo Data Class

```kotlin
data class CheatInfo(
    val index: Int,
    val description: String,
    val enabled: Boolean,
) : Serializable
```

### CheatsManager

```kotlin
class CheatsManager(
    private val sharedPreferences: Lazy<SharedPreferences>,
    private val directoriesManager: DirectoriesManager,
) {
    suspend fun ensureCheatDatabaseDownloaded()
    suspend fun loadPersistedCheatStates(game: Game, coreID: CoreID): Map<Int, Boolean>
    suspend fun saveCheatState(game: Game, coreID: CoreID, index: Int, enabled: Boolean)
    fun getCheatsDirectory(): File
}
```

The actual cheat toggling (`setCheatState`, `flushCheats`) is called directly on `GLRetroView`. `CheatsManager` handles persistence and file management only.

**Persistence:** Cheat states are stored in SharedPreferences under a key derived from `game.uri` and `coreID`. Format: a JSON map `{index: boolean}`.

### CheatDatabaseDownloader

Downloads the official libretro cheat database from `https://github.com/libretro/cheats` (zip archive) and extracts `.cht` files into `directoriesManager.getCheatsDirectory()/official/`.

- Downloaded once, then only updated on demand or when the cached version is older than N days.
- Stored at: `Lemuroid/cheats/official/`

### User Cheat Directory

Scanned at: `Lemuroid/cheats/user/`

User `.cht` files take precedence over official ones with the same filename.

### DirectoriesManager Extension

Add `getCheatsDirectory(): File` returning `File(appContext.filesDir, "cheats")`.

### GLRetroViewData Extension

Add `cheatsDirectory: String?` field to pass the cheats directory path to LibretroDroid.

## Layer 3: lemuroid-app (UI)

### GameMenuContract Extensions

```kotlin
// Extras sent TO cheats screen
const val EXTRA_CHEATS = "EXTRA_CHEATS"          // Array<CheatInfo>
const val EXTRA_GAME = "EXTRA_GAME"               // already exists
const val EXTRA_SYSTEM_CORE_CONFIG = "..."        // already exists

// Results sent FROM cheats screen
const val RESULT_CHEATS = "RESULT_CHEATS"         // Array<CheatInfo> with updated states
```

### GameViewModelSideEffects Extension

```kotlin
data class ShowCheats(
    val cheats: List<CheatInfo>,
) : UiEffect
```

### GameMenuRoute Extension

```kotlin
CHEATS(
    route = "cheats",
    titleId = R.string.game_menu_cheats,
    parent = HOME,
)
```

### GameMenuHomeScreen

Add "Cheats" entry after Save/Load, before Quit:

```
[Save]
[Load]
[Cheats]     ← new entry with cheat icon
[Quit]
[Restart]
[Mute Audio]
[Fast Forward]
...
```

The entry is only shown when `cheats.isNotEmpty()`.

### CheatsScreen (new Compose screen)

- Full-screen list of `CheatInfo` items
- Each item: description text + Switch toggle
- Toggling a switch calls `retroGameView.setCheatState(index, enabled)` then `retroGameView.flushCheats()`, and persists state via `CheatsManager.saveCheatState()`
- State is persisted immediately on toggle
- Back button returns to game menu

### BaseGameActivity

Cheat data is fetched asynchronously when the game finishes loading (in `initializeViewModelsEffectsFlow()` or a dedicated coroutine). The cheat list is stored in a property on the activity.

In `displayOptionsDialog()`, the already-loaded cheat list is passed via intent extras.

In `onActivityResult()`, handle `RESULT_CHEATS` to update local state.

### TV Game Menu

`TVGameMenuActivity` and `tv_game_settings.xml` receive the same "Cheats" entry. The TV preference screen uses the same key constants as mobile.

## Data Flow

```
Game Launch
    │
    ├──→ CheatsManager.ensureCheatDatabaseDownloaded() (async, no-op if cached)
    │
    ├──→ GLRetroView created with cheatsDirectory configured
    │         └──→ LibretroDroid parses .cht files, populates cheat list
    │
    └──→ After game loaded (async):
            ├──→ retroGameView.getCheatCount()
            ├──→ retroGameView.getCheatDescription(i) for each → build CheatInfo list
            ├──→ CheatsManager.loadPersistedCheatStates()
            └──→ retroGameView.setCheatState(i, restored) for each

User opens Game Menu → taps "Cheats"
    │
    ├──→ CheatsScreen shows list with toggles
    │
    └──→ User toggles cheat
            ├──→ retroGameView.setCheatState(index, enabled)
            ├──→ retroGameView.flushCheats()
            └──→ CheatsManager.saveCheatState()

User exits game
    │
    └──→ Cheat states already persisted (saved on toggle)

Next session
    │
    └──→ CheatsManager.loadPersistedCheatStates() → re-enables cheats
```

## Error Handling

- **Core doesn't support cheats:** `getCheatCount()` returns 0. The "Cheats" menu entry is hidden.
- **Cheat database download fails:** Gracefully degrade to user-provided cheats only. Show no error to user.
- **`.cht` file parse error:** Log warning, skip the file.
- **SharedPreferences unavailable:** Cheat state is not persisted; cheats reset on next session. No crash.

## String Resources

New strings needed:

| Key | Value |
|-----|-------|
| `game_menu_cheats` | "Cheats" |
| `game_menu_cheats_empty` | "No cheats available for this game" |
| `game_menu_cheats_download_failed` | "Could not download cheat database" |

## Icon

New drawable: `ic_menu_cheats` — a key or wrench icon, consistent with existing menu icon style.

## Play Store Considerations

- The cheat database download uses GitHub's public repository — no API key needed.
- Download is optional; the feature works without it.
- No user data is collected or transmitted.
- For the Play build variant, consider whether to include this feature (some platforms restrict cheat functionality). This is a policy decision, not a technical one.

## Dependencies

- LibretroDroid local build (clone into project or use git submodule)
- OkHttp for cheat database download (already a dependency)
- kotlinx.serialization for cheat state persistence (already a dependency)
