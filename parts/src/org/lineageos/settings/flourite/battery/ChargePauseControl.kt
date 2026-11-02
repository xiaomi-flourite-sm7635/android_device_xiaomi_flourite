/* Copyright (C) 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.settings.flourite.battery

import java.io.File

internal enum class ChargePauseState { OFF, ACTIVE, RESTORE_PENDING, UNAVAILABLE }

internal data class ChargePauseResult(
    val state: ChargePauseState,
    val confirmed: Boolean,
    val error: String? = null,
)

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

    @Synchronized fun setEnabled(enabled: Boolean): ChargePauseResult {
        // The kernel performs atomic eligibility checks and owns restoration.
        val target = if (enabled) ChargePauseState.ACTIVE else ChargePauseState.OFF
        val before = state()
        // A second enable must neither renew the kernel lease nor report a false error.
        if (enabled && before == target) return ChargePauseResult(before, true)
        if (enabled && before != ChargePauseState.OFF) return ChargePauseResult(before, false)
        val failure = runCatching { write(if (enabled) "1\n" else "0\n") }.exceptionOrNull()
        // A write can reach the kernel even when userspace receives an error. Always
        // read back, including after failures, and return one authoritative snapshot.
        val after = state()
        return ChargePauseResult(after, after == target,
            failure?.let { it.message ?: it.javaClass.simpleName })
    }

    @Synchronized fun toggle(): ChargePauseResult = when (val current = state()) {
        ChargePauseState.OFF -> setEnabled(true)
        ChargePauseState.ACTIVE, ChargePauseState.RESTORE_PENDING -> setEnabled(false)
        ChargePauseState.UNAVAILABLE -> ChargePauseResult(current, false)
    }

    companion object {
        private const val NODE = "/sys/class/qcom-battery/parts_charge_pause"
    }
}

internal object ChargePauseSession {
    val control = ChargePauseControl()
}
