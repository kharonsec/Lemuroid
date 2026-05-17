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
