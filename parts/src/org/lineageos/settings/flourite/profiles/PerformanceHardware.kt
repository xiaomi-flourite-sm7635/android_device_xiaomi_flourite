// SPDX-License-Identifier: Apache-2.0
package org.lineageos.settings.flourite.profiles

import org.lineageos.settings.flourite.cpu.CpuControlManager
import org.lineageos.settings.flourite.cpu.CpuTopology
import org.lineageos.settings.flourite.gpu.GpuControlManager
import org.lineageos.settings.flourite.gpu.GpuMode

/** Only these driver controls may be stored. No paths or shell commands are accepted. */
internal data class AppPerformanceProfile(val controls: Map<String, String> = emptyMap())
internal object ProfileControls {
    val keys = CpuTopology.policyIds.map { "cpu.$it" }.toSet() +
        CpuTopology.policyIds.map { "governor.$it" } +
        (1..6).map { "core.$it" } + "gpu"
    fun range(value: String): Pair<Long, Long>? {
        val values = value.split(' ')
        if (values.size != 2) return null
        val min = values[0].toLongOrNull() ?: return null
        val max = values[1].toLongOrNull() ?: return null
        return (min to max).takeIf { min in 1..10_000_000L && max in min..10_000_000L }
    }
    fun wellFormed(key: String, value: String): Boolean = key in keys && when {
        key.startsWith("governor.") -> value.matches(Regex("[a-zA-Z0-9_-]{1,32}"))
        key.startsWith("core.") -> value in listOf("0", "1")
        else -> value == "auto" || range(value)?.let { value == "${it.first} ${it.second}" } == true
    }
}

/** Call under PerformanceLock; normal Power HAL and thermal requests remain independent. */
internal class PerformanceHardware(
    private val cpu: CpuControlManager,
    private val gpu: GpuControlManager,
) : ProfileHardware<AppPerformanceProfile, AppPerformanceProfile> {
    override fun capture(): AppPerformanceProfile {
        val state = cpu.readState()
        val controls = mutableMapOf<String, String>()
        state.policies.filter { it.lockSupported }.forEach {
            controls["cpu.${it.id}"] = if (it.locked) "${it.minimum} ${it.maximum}" else "auto"
        }
        state.policies.filter { it.governorWritable }.forEach {
            controls["governor.${it.id}"] = checkNotNull(it.governor)
        }
        state.cores.filter { it.writable && it.online != null }.forEach {
            controls["core.${it.id}"] = if (it.online == true) "1" else "0"
        }
        val g = gpu.readState()
        if (g.canRestore) when (g.controlMode) {
            GpuMode.AUTOMATIC -> controls["gpu"] = "auto"
            GpuMode.RANGE -> if (g.requestedMinimumKHz != null && g.requestedMaximumKHz != null)
                controls["gpu"] = "${g.requestedMinimumKHz} ${g.requestedMaximumKHz}"
            else -> Unit
        }
        return AppPerformanceProfile(controls)
    }
    fun valid(profile: AppPerformanceProfile): Boolean {
        val c = cpu.readState()
        val g = gpu.readState()
        if (profile.controls.isEmpty()) return false
        if (!profile.controls.all { (key, value) ->
            if (!ProfileControls.wellFormed(key, value)) false
            else if (key.startsWith("governor.")) {
                c.policies.any { it.id == key.substringAfter('.').toInt() && it.governorWritable && value in it.governors }
            }
            else if (key.startsWith("core.")) {
                val id = key.substringAfter('.').toInt()
                c.cores.any { it.id == id && it.writable }
            } else {
                val p = if (key == "gpu") null else c.policies.find { it.id == key.substringAfter('.').toInt() }
                val writable = if (key == "gpu") g.writable else p?.writable == true
                val table = if (key == "gpu") g.frequenciesKHz else p?.frequencies.orEmpty()
                val range = ProfileControls.range(value)
                writable && (value == "auto" || range != null && range.first in table && range.second in table)
            }
        }) return false
        // Never accept a draft that offlines every CPU of a cluster.
        return c.policies.all { p -> p.cpus.any { id ->
            profile.controls["core.$id"]?.let { it == "1" } ?: (c.cores.find { it.id == id }?.online == true)
        } }
    }
    override fun expected(baseline: AppPerformanceProfile, profile: AppPerformanceProfile): AppPerformanceProfile? {
        if (!valid(profile) || !baseline.controls.keys.containsAll(profile.controls.keys)) return null
        return AppPerformanceProfile(baseline.controls + profile.controls)
    }
    private fun ordered(values: Map<String, String>) = values.entries.sortedWith(
        compareBy<Map.Entry<String, String>> {
            when { it.key.startsWith("core.") && it.value == "1" -> 0
                it.key.startsWith("core.") -> 2
                else -> 1 }
        }.thenBy { it.key })
    private fun write(key: String, value: String): Boolean {
        if (!ProfileControls.wellFormed(key, value)) return false
        if (key.startsWith("governor.")) return cpu.setGovernor(key.substringAfter('.').toInt(), value).successful
        if (key.startsWith("core.")) return cpu.setCoreOnline(key.substringAfter('.').toInt(), value == "1").successful
        val range = ProfileControls.range(value)
        return if (key == "gpu") {
            if (value == "auto") gpu.restoreAutomatic().successful
            else range != null && gpu.setFrequencyRange(range.first, range.second).successful
        } else {
            val id = key.substringAfter('.').toInt()
            if (value == "auto") cpu.setFrequencyLock(id, false).successful
            else range != null && cpu.setFrequencyRange(id, range.first, range.second).successful
        }
    }
    override fun apply(profile: AppPerformanceProfile): Boolean {
        if (!valid(profile)) return false
        return ordered(profile.controls).all { (key, value) -> write(key, value) }
    }
    override fun restore(baseline: AppPerformanceProfile, expected: AppPerformanceProfile): Boolean {
        var success = true
        val changed = baseline.controls.filter { (key, value) -> expected.controls[key] != value }
        for ((key, value) in ordered(changed)) {
            val now = capture().controls[key]
            // Keep requests changed by another writer; unreadable state keeps the journal.
            if (now == null) success = false
            else if (now == expected.controls[key] && !write(key, value)) success = false
        }
        return success
    }
}

internal object PerformanceLock
