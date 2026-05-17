package com.swordfish.lemuroid.lib.cheats

import java.io.Serializable

data class CheatInfo(
    val index: Int,
    val description: String,
    val enabled: Boolean,
) : Serializable
