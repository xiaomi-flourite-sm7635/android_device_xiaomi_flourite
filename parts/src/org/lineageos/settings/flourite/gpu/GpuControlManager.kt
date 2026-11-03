/* SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.settings.flourite.gpu

import java.io.File

internal enum class GpuMode { AUTOMATIC, RANGE, UNKNOWN }
internal enum class GpuFailure { UNAVAILABLE, INVALID_FREQUENCY, INVALID_RANGE, WRITE_FAILED, READBACK_FAILED }
internal data class GpuResult(val failure: GpuFailure? = null, val rollbackFailed: Boolean = false) {
    val successful get() = failure == null
}
internal data class GpuState(
    val currentKHz: Long?, val frequenciesKHz: List<Long>,
    val requestedMinimumKHz: Long?, val requestedMaximumKHz: Long?,
    val effectiveMinimumKHz: Long?, val effectiveMaximumKHz: Long?,
    val controlMode: GpuMode, val readable: Boolean, val writable: Boolean,
    val unavailableReason: GpuFailure?, val active: Boolean?, val canRestore: Boolean,
)
internal class GpuControlManager(
    private val root: File = File("/sys/class/kgsl/kgsl-3d0"),
    private val writer: (File, String) -> Unit = { file, value -> file.writeText(value) },
) {
    private val control get() = File(root, "parts_limits")
    private fun numbers(name: String): List<Long>? = runCatching {
        File(root, name).readText().trim().split(Regex("\\s+")).map { it.toLong() }
    }.getOrNull()
    private fun limits(): List<Long>? = numbers("parts_limits")?.takeIf {
        it.size == 3 && it[0] in 0L..1L && it[1] > 0 && it[1] <= it[2]
    }
    @Synchronized fun readState(): GpuState {
        val table = numbers("gpu_available_frequencies")?.filter { it > 0 && it % 1000 == 0L }?.map { it / 1000 }?.distinct()?.sorted().orEmpty()
        val raw = limits()
        val effective = numbers("parts_effective_limits")?.takeIf { it.size == 2 && it[0] > 0 && it[0] <= it[1] }
        val current = numbers("gpuclk")?.singleOrNull()?.takeIf { it >= 0 }?.div(1000)
        val writable = raw != null && control.canWrite() && table.isNotEmpty()
        return GpuState(current?.takeIf { it > 0 }, table,
            raw?.takeIf { it[0] == 1L }?.get(1)?.div(1000), raw?.takeIf { it[0] == 1L }?.get(2)?.div(1000),
            effective?.get(0)?.div(1000), effective?.get(1)?.div(1000),
            when (raw?.first()) { 0L -> GpuMode.AUTOMATIC; 1L -> GpuMode.RANGE; else -> GpuMode.UNKNOWN },
            table.isNotEmpty(), writable, if (writable) null else GpuFailure.UNAVAILABLE,
            // gpuclk is the selected rate, not proof that the GPU is awake.
            null, raw != null && control.canWrite())
    }
    @Synchronized fun setFrequencyRange(minimumKHz: Long, maximumKHz: Long): GpuResult {
        if (minimumKHz > maximumKHz) return GpuResult(GpuFailure.INVALID_RANGE)
        val state = readState()
        if (!state.writable) return GpuResult(GpuFailure.UNAVAILABLE)
        if (minimumKHz !in state.frequenciesKHz || maximumKHz !in state.frequenciesKHz) return GpuResult(GpuFailure.INVALID_FREQUENCY)
        return apply(listOf(1, minimumKHz * 1000, maximumKHz * 1000))
    }
    @Synchronized fun restoreAutomatic(): GpuResult = apply(listOf(0, 0, 0))
    private fun apply(target: List<Long>): GpuResult {
        val before = limits() ?: return GpuResult(GpuFailure.UNAVAILABLE)
        fun write(value: List<Long>) = writer(control, if (value[0] == 0L) "0\n" else value.joinToString(" ") + "\n")
        fun matches(value: List<Long>) = limits()?.let { it == value || value[0] == 0L && it[0] == 0L } == true
        var failure = GpuFailure.WRITE_FAILED
        try {
            write(target)
            failure = GpuFailure.READBACK_FAILED
            // Verify requested controls, not thermal-limited effective frequencies.
            if (matches(target)) return GpuResult()
        } catch (_: Exception) { }
        val restored = runCatching { write(before); matches(before) }.getOrDefault(false)
        return GpuResult(failure, !restored)
    }
}
