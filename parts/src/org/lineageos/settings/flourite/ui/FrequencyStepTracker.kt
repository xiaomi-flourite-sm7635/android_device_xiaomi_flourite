/* SPDX-License-Identifier: Apache-2.0 */
package org.lineageos.settings.flourite.ui

import kotlin.math.roundToInt

/** Track discrete steps, not every pointer sample. Programmatic updates are silent. */
internal class FrequencyStepTracker(private val lastIndex: Int, value: ClosedFloatingPointRange<Float>) {
    private fun indices(value: ClosedFloatingPointRange<Float>) =
        value.start.roundToInt().coerceIn(0, lastIndex) to
            value.endInclusive.roundToInt().coerceIn(0, lastIndex)
    private var previous = indices(value)

    fun sync(value: ClosedFloatingPointRange<Float>) { previous = indices(value) }

    fun changed(value: ClosedFloatingPointRange<Float>): Boolean {
        val next = indices(value)
        val changed = next != previous
        previous = next
        return changed
    }
}
