/* SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.settings.flourite.cpu

import java.io.File
import org.lineageos.settings.flourite.cpu.CpuTopology.policyIds

internal data class CpuPolicyState(
    val id: Int, val cpus: List<Int>, val frequencies: List<Long>,
    val minimum: Long, val maximum: Long, val current: Long?,
    val writable: Boolean, val lockSupported: Boolean, val locked: Boolean,
    val governor: String?, val governors: List<String>, val governorWritable: Boolean,
)
internal data class CpuCoreState(val id: Int, val online: Boolean?, val canToggle: Boolean, val writable: Boolean)
internal data class CpuState(val policies: List<CpuPolicyState>, val cores: List<CpuCoreState>, val unavailablePolicies: List<Int>)
internal enum class CpuFailure { UNAVAILABLE, INVALID_FREQUENCY, INVALID_RANGE, LAST_CORE, WRITE_FAILED, READBACK_FAILED, LOCK_UNSUPPORTED }
internal data class CpuResult(val failure: CpuFailure? = null, val rollbackFailed: Boolean = false) {
    val successful get() = failure == null
}

/** Only dedicated Parts constraints are written; ordinary QoS requests remain intact. */
internal class CpuControlManager(
    private val cpuRoot: File = File("/sys/devices/system/cpu"),
    private val writer: (File, String) -> Unit = { file, value -> file.writeText(value) },
) {
    private data class Limits(val enabled: Boolean, val min: Long, val max: Long)
    private val initialCores = mutableMapOf<Int, Boolean>()
    private val changedCores = mutableSetOf<Int>()
    private val initialGovernors = mutableMapOf<Int, String>()
    private fun policy(id: Int) = File(cpuRoot, "cpufreq/policy$id")
    private fun control(id: Int) = File(policy(id), "scaling_parts_limits")
    private fun onlineFile(id: Int) = File(cpuRoot, "cpu$id/online")
    private fun read(file: File) = runCatching { file.readText().trim() }.getOrNull()
    private fun numbers(file: File): List<Long>? {
        return read(file)?.split(Regex("\\s+"))?.map { it.toLongOrNull() ?: return null }
    }
    private fun limits(id: Int): Limits? {
        val v = numbers(control(id)) ?: return null
        if (v.size != 3 || v[0] !in 0L..1L || v[1] <= 0 || v[1] > v[2]) return null
        return Limits(v[0] == 1L, v[1], v[2])
    }
    private fun cpus(id: Int): List<Int> = parseCpus(read(File(policy(id), "related_cpus")))
    private fun online(id: Int): Boolean? = when (read(onlineFile(id))) {
        "0" -> false
        "1" -> true
        else -> read(File(cpuRoot, "online"))?.let { id in parseCpus(it) }
    }
    private fun readPolicy(id: Int): CpuPolicyState? {
        val dir = policy(id)
        val frequencies = numbers(File(dir, "scaling_available_frequencies"))?.filter { it > 0 }?.distinct()?.sorted().orEmpty()
        val raw = limits(id)
        val minimum = raw?.takeIf { it.enabled }?.min ?: read(File(dir, "scaling_min_freq"))?.toLongOrNull() ?: return null
        val maximum = raw?.takeIf { it.enabled }?.max ?: read(File(dir, "scaling_max_freq"))?.toLongOrNull() ?: return null
        if (minimum <= 0 || minimum > maximum) return null
        val writable = raw != null && control(id).canWrite() && frequencies.isNotEmpty()
        val governorFile = File(dir, "scaling_governor")
        val governors = read(File(dir, "scaling_available_governors"))
            ?.split(Regex("\\s+"))?.filter { it.matches(Regex("[a-zA-Z0-9_-]{1,32}")) }?.distinct().orEmpty()
        val governor = read(governorFile)?.takeIf { it in governors }
        governor?.let { initialGovernors.putIfAbsent(id, it) }
        return CpuPolicyState(id, cpus(id), frequencies, minimum, maximum,
            read(File(dir, "scaling_cur_freq"))?.toLongOrNull(), writable, raw != null && control(id).canWrite(), raw?.enabled == true,
            governor, governors, governor != null && governorFile.canWrite())
    }
    @Synchronized fun readState(): CpuState {
        val policies = policyIds.mapNotNull(::readPolicy)
        val cores = (0..7).map { id ->
            val active = online(id)
            active?.let { initialCores.putIfAbsent(id, it) }
            val group = policies.firstOrNull { id in it.cpus }?.cpus.orEmpty()
            val writable = id in 1..6 && active != null && group.isNotEmpty() && onlineFile(id).canWrite()
            CpuCoreState(id, active, writable && (active == false || group.count { online(it) == true } > 1), writable)
        }
        return CpuState(policies, cores, policyIds.filter { id -> policies.none { it.id == id } })
    }
    @Synchronized fun setFrequencyRange(id: Int, minimum: Long, maximum: Long): CpuResult {
        if (id !in policyIds) return CpuResult(CpuFailure.UNAVAILABLE)
        val state = readPolicy(id) ?: return CpuResult(CpuFailure.UNAVAILABLE)
        if (!state.lockSupported) return CpuResult(CpuFailure.LOCK_UNSUPPORTED)
        if (minimum > maximum) return CpuResult(CpuFailure.INVALID_RANGE)
        if (minimum !in state.frequencies || maximum !in state.frequencies) return CpuResult(CpuFailure.INVALID_FREQUENCY)
        return writeLimits(id, Limits(true, minimum, maximum))
    }
    @Synchronized fun setGovernor(id: Int, governor: String): CpuResult {
        if (id !in policyIds) return CpuResult(CpuFailure.UNAVAILABLE)
        val policy = readPolicy(id) ?: return CpuResult(CpuFailure.UNAVAILABLE)
        if (!policy.governorWritable || governor !in policy.governors) return CpuResult(CpuFailure.UNAVAILABLE)
        val before = policy.governor ?: return CpuResult(CpuFailure.UNAVAILABLE)
        if (before == governor) return CpuResult()
        val node = File(this.policy(id), "scaling_governor")
        var failure = CpuFailure.WRITE_FAILED
        try {
            writer(node, "$governor\n")
            failure = CpuFailure.READBACK_FAILED
            if (read(node) == governor) return CpuResult()
        } catch (_: Exception) { }
        val restored = runCatching { writer(node, "$before\n"); read(node) == before }.getOrDefault(false)
        return CpuResult(failure, !restored)
    }
    @Synchronized fun setFrequencyLock(id: Int, enabled: Boolean): CpuResult {
        if (id !in policyIds) return CpuResult(CpuFailure.UNAVAILABLE)
        if (!enabled) return writeLimits(id, Limits(false, 0, 0))
        val state = readPolicy(id) ?: return CpuResult(CpuFailure.UNAVAILABLE)
        return setFrequencyRange(id, state.minimum, state.maximum)
    }
    private fun writeLimits(id: Int, target: Limits): CpuResult {
        val before = limits(id) ?: return CpuResult(CpuFailure.LOCK_UNSUPPORTED)
        fun apply(value: Limits) = writer(control(id), if (value.enabled) "1 ${value.min} ${value.max}\n" else "0\n")
        fun matches(value: Limits) = limits(id)?.let { actual ->
            actual.enabled == value.enabled && (!value.enabled || actual == value)
        } == true
        var failure = CpuFailure.WRITE_FAILED
        try {
            apply(target)
            failure = CpuFailure.READBACK_FAILED
            if (matches(target)) return CpuResult()
        } catch (_: Exception) { }
        val restored = runCatching { apply(before); matches(before) }.getOrDefault(false)
        return CpuResult(failure, !restored)
    }
    @Synchronized fun setCoreOnline(id: Int, enabled: Boolean): CpuResult {
        val state = readState()
        val core = state.cores.firstOrNull { it.id == id } ?: return CpuResult(CpuFailure.UNAVAILABLE)
        if (core.online == enabled) return CpuResult()
        if (!core.canToggle) return CpuResult(if (id != 0 && core.online == true) CpuFailure.LAST_CORE else CpuFailure.UNAVAILABLE)
        val before = core.online ?: return CpuResult(CpuFailure.UNAVAILABLE)
        var failure = CpuFailure.WRITE_FAILED
        try {
            writer(onlineFile(id), if (enabled) "1\n" else "0\n")
            failure = CpuFailure.READBACK_FAILED
            if (online(id) == enabled) { changedCores += id; return CpuResult() }
        } catch (_: Exception) { }
        val restored = runCatching {
            writer(onlineFile(id), if (before) "1\n" else "0\n")
            online(id) == before
        }.getOrDefault(false)
        if (!restored) changedCores += id
        return CpuResult(failure, !restored)
    }
    @Synchronized fun restoreInitialValues(): CpuResult {
        var failed = false
        for (id in policyIds) if (!setFrequencyLock(id, false).successful) failed = true
        // Flourite's post-boot policy uses WALT. Restore it even after an app process restart.
        for (policy in readState().policies.filter { it.governorWritable }) {
            val governor = "walt".takeIf { it in policy.governors } ?: initialGovernors[policy.id]
            if (governor != null && !setGovernor(policy.id, governor).successful) failed = true
        }
        // Restore only cores changed by this process, with online operations first.
        for (id in changedCores.sortedBy { initialCores[it] != true }) {
            if (!setCoreOnline(id, initialCores.getValue(id)).successful) failed = true
        }
        if (!failed) changedCores.clear()
        return CpuResult(if (failed) CpuFailure.READBACK_FAILED else null)
    }
    private fun parseCpus(text: String?): List<Int> = text.orEmpty().split(Regex("[,\\s]+"))
        .flatMap { item ->
            val range = item.split('-')
            val first = range.firstOrNull()?.toIntOrNull()
            val last = range.lastOrNull()?.toIntOrNull()
            if (first == null || last == null || first !in 0..7 || last !in first..7) emptyList() else (first..last).toList()
        }.distinct()
}
