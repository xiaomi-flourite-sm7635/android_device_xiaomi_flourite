/* Copyright (C) 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.settings.flourite.battery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.lineageos.settings.flourite.R
import org.lineageos.settings.flourite.ui.PartsGroupShape
import org.lineageos.settings.flourite.ui.PartsPageGutter

@Composable
internal fun ChargePauseCard(resumed: Boolean) {
    val control = ChargePauseSession.control
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(ChargePauseState.UNAVAILABLE) }
    var busy by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }

    LaunchedEffect(resumed, busy) {
        if (!resumed || busy) return@LaunchedEffect
        while (isActive) {
            state = withContext(Dispatchers.IO) { control.state() }
            delay(2_000)
        }
    }

    fun apply(enabled: Boolean) {
        if (busy) return
        busy = true
        failed = false
        scope.launch {
            // Finish the write/readback even when the screen closes during the operation.
            val result = withContext(Dispatchers.IO + NonCancellable) {
                control.setEnabled(enabled) to control.state()
            }
            failed = !result.first
            state = result.second
            busy = false
        }
    }

    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = PartsPageGutter, vertical = 6.dp),
        shape = PartsGroupShape,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.charge_pause_title), Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium)
                Switch(
                    checked = state == ChargePauseState.ACTIVE,
                    enabled = !busy && state in listOf(ChargePauseState.OFF, ChargePauseState.ACTIVE),
                    onCheckedChange = { if (it) confirm = true else apply(false) },
                )
            }
            Text(stringResource(when {
                busy -> R.string.charge_pause_busy
                state == ChargePauseState.ACTIVE -> R.string.charge_pause_active
                state == ChargePauseState.RESTORE_PENDING -> R.string.charge_pause_pending
                state == ChargePauseState.OFF -> R.string.charge_pause_off
                else -> R.string.charge_pause_unavailable
            }), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.charge_pause_summary),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (failed) {
                Text(stringResource(R.string.charge_pause_failed), color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium)
            }
            // Keep an explicit recovery action available even if state cannot be read.
            if (state != ChargePauseState.OFF) {
                TextButton(onClick = { apply(false) }, enabled = !busy) {
                    Text(stringResource(R.string.charge_pause_restore))
                }
            }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.charge_pause_title)) },
            text = { Text(stringResource(R.string.charge_pause_warning)) },
            confirmButton = {
                TextButton(onClick = { confirm = false; apply(true) }) {
                    Text(stringResource(R.string.charge_pause_enable))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirm = false }) { Text(stringResource(android.R.string.cancel)) }
            },
        )
    }
}
