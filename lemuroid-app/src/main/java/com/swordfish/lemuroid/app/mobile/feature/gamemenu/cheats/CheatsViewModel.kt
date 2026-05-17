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
