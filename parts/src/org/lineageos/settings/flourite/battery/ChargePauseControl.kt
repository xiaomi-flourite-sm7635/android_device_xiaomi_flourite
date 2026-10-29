/* Copyright (C) 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.settings.flourite.battery

import java.io.File

internal enum class ChargePauseState { OFF, ACTIVE, RESTORE_PENDING, UNAVAILABLE }

/** Only the dedicated kernel control is writable. No saved preference or boot action. */
internal class ChargePauseControl(
    private val read: () -> String = { File(NODE).readText() },
    private val write: (String) -> Unit = { File(NODE).writeText(it) },
) {
    @Synchronized fun state(): ChargePauseState = runCatching {
        when (read().trim()) {
            "off" -> ChargePauseState.OFF
            "active" -> ChargePauseState.ACTIVE
            "restore_pending" -> ChargePauseState.RESTORE_PENDING
            else -> ChargePauseState.UNAVAILABLE
        }
    }.getOrDefault(ChargePauseState.UNAVAILABLE)

    @Synchronized fun setEnabled(enabled: Boolean): Boolean {
        // The kernel performs atomic eligibility checks and owns restoration.
        if (enabled && state() != ChargePauseState.OFF) return false
        return runCatching {
            write(if (enabled) "1\n" else "0\n")
            state() == if (enabled) ChargePauseState.ACTIVE else ChargePauseState.OFF
        }.getOrDefault(false)
    }

    companion object {
        private const val NODE = "/sys/class/qcom-battery/parts_charge_pause"
    }
}

internal object ChargePauseSession {
    val control = ChargePauseControl()
}
