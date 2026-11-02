/* SPDX-License-Identifier: Apache-2.0 */
package org.lineageos.settings.flourite.memory

import android.text.format.Formatter
import androidx.compose.material3.RadioButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.lineageos.settings.flourite.R
import org.lineageos.settings.flourite.ui.*

@Composable
internal fun ZramSettingsCard(resumed: Boolean) {
    val context = LocalContext.current
    val control = ZramSettingsSession.control
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf(ZramProfile.DEFAULT) }
    var boot by remember { mutableStateOf<ZramProfile?>(null) }
    var ram by remember { mutableStateOf<Long?>(null) }
    var available by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(resumed, busy) {
        if (!resumed || busy) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            selected = control.requested()
            boot = control.bootProfile()
            ram = control.ramBytes()
            available = control.available()
        }
    }
    PartsPreferenceGroup(title = stringResource(R.string.zram_size_title)) {
        ZramProfile.entries.forEach { profile ->
            PartsPreference(
                title = if (profile == ZramProfile.DEFAULT) stringResource(R.string.zram_default)
                    else stringResource(R.string.zram_percent, profile.percent),
                summary = ram?.let(profile::bytes)?.let { Formatter.formatFileSize(context, it) },
                selected = selected == profile,
                enabled = resumed && available && !busy,
                trailing = { RadioButton(selected = selected == profile, onClick = null,
                    enabled = resumed && available && !busy) },
                onClick = {
                    busy = true
                    scope.launch {
                        val result = withContext(Dispatchers.IO + NonCancellable) { control.schedule(profile) }
                        failed = !result
                        if (result) selected = profile
                        busy = false
                    }
                },
            )
        }
    }
    PartsInfoCard(body = stringResource(when {
        failed -> R.string.zram_failed
        !available -> R.string.zram_unavailable
        selected != boot -> R.string.zram_pending
        else -> R.string.zram_info
    }))
}
