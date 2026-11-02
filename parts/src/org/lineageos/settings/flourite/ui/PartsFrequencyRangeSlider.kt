/* SPDX-License-Identifier: Apache-2.0 */
package org.lineageos.settings.flourite.ui

import android.view.HapticFeedbackConstants
import androidx.compose.material3.RangeSlider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

@Composable
internal fun PartsFrequencyRangeSlider(
    value: ClosedFloatingPointRange<Float>,
    onValueChange: (ClosedFloatingPointRange<Float>) -> Unit,
    lastIndex: Int,
    enabled: Boolean = true,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    if (lastIndex < 1) return
    val view = LocalView.current
    val tracker = remember(lastIndex) { FrequencyStepTracker(lastIndex, value) }
    SideEffect { tracker.sync(value) }
    RangeSlider(
        value = value,
        onValueChange = {
            if (enabled) {
                if (tracker.changed(it)) view.performHapticFeedback(HapticFeedbackConstants.SEGMENT_TICK)
                onValueChange(it)
            }
        },
        valueRange = 0f..lastIndex.toFloat(),
        steps = (lastIndex - 1).coerceAtLeast(0),
        enabled = enabled,
        onValueChangeFinished = onValueChangeFinished,
    )
}
