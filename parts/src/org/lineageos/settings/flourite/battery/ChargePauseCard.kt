/* Copyright (C) 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.settings.flourite.battery

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.lineageos.settings.flourite.R
import org.lineageos.settings.flourite.ui.PartsInfoCard
import org.lineageos.settings.flourite.ui.PartsPreference
import org.lineageos.settings.flourite.ui.PartsPreferenceGroup
import org.lineageos.settings.flourite.ui.PartsSwitchPreference

@Composable
internal fun ChargePauseCard(resumed: Boolean) {
    val control = ChargePauseSession.control
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(ChargePauseState.UNAVAILABLE) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var failureDetail by remember { mutableStateOf<String?>(null) }
    var requestedState by remember { mutableStateOf<ChargePauseState?>(null) }

    LaunchedEffect(resumed, busy) {
        if (!resumed || busy) return@LaunchedEffect
        while (isActive) {
            state = withContext(Dispatchers.IO) { control.state() }
            if (state == requestedState) {
                failed = false
                failureDetail = null
                requestedState = null
            }
            delay(2_000)
        }
    }

    fun apply(enabled: Boolean) {
        if (busy) return
        busy = true
        failed = false
        failureDetail = null
        requestedState = if (enabled) ChargePauseState.ACTIVE else ChargePauseState.OFF
        scope.launch {
            // Finish the write/readback even when the screen closes during the operation.
            val result = withContext(Dispatchers.IO + NonCancellable) {
                control.setEnabled(enabled)
            }
            failed = !result.confirmed
            failureDetail = result.error
            state = result.state
            busy = false
        }
    }

    PartsPreferenceGroup {
        PartsSwitchPreference(
            title = stringResource(R.string.charge_pause_title),
            summary = stringResource(when {
                busy -> R.string.charge_pause_busy
                state == ChargePauseState.ACTIVE -> R.string.charge_pause_active
                state == ChargePauseState.RESTORE_PENDING -> R.string.charge_pause_pending
                state == ChargePauseState.OFF -> R.string.charge_pause_off
                else -> R.string.charge_pause_unavailable
            }),
            icon = Icons.Filled.BatteryChargingFull,
            checked = state == ChargePauseState.ACTIVE || state == ChargePauseState.RESTORE_PENDING,
            enabled = !busy && state != ChargePauseState.UNAVAILABLE,
            onCheckedChange = ::apply,
        )
        // Preserve a recovery action when the state is unreadable or restoration is pending.
        if (state == ChargePauseState.UNAVAILABLE || state == ChargePauseState.RESTORE_PENDING) {
            PartsPreference(title = stringResource(R.string.charge_pause_restore),
                enabled = !busy, onClick = { apply(false) })
        }
    }
    if (failed) {
        PartsInfoCard(body = stringResource(if (requestedState == ChargePauseState.OFF)
            R.string.charge_pause_restore_failed else R.string.charge_pause_failed) +
            failureDetail?.let { "\n$it" }.orEmpty())
    }
    PartsInfoCard(body = stringResource(R.string.charge_pause_warning))
}
