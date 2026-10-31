// SPDX-License-Identifier: Apache-2.0
package org.lineageos.settings.flourite.profiles

/** The journal is saved before hardware writes, including the anticipated partial-apply state. */
internal data class ProfileLease<P, S>(val packageName: String, val profile: P,
    val baseline: S, val expected: S)

internal interface ProfileHardware<P, S> {
    fun capture(): S
    fun expected(baseline: S, profile: P): S?
    fun acknowledged(expected: S, observed: S): S = expected
    fun apply(profile: P): Boolean
    fun restore(baseline: S, expected: S): Boolean
}

internal class ProfileEngine<P, S>(
    private val hardware: ProfileHardware<P, S>,
    private val journal: (ProfileLease<P, S>?) -> Boolean,
) {
    private var lease: ProfileLease<P, S>? = null
    var failed: Boolean = false
        private set
    val activePackage: String? get() = lease?.packageName

    fun recover(saved: ProfileLease<P, S>?): Boolean {
        check(lease == null)
        lease = saved
        return release()
    }

    fun update(packageName: String?, profile: P?): Boolean {
        val active = lease
        if (active != null && active.packageName == packageName && active.profile == profile && !failed)
            return true
        if (!release()) return false
        if (packageName == null || profile == null) return true
        val before = runCatching { hardware.capture() }.getOrElse { failed = true; return false }
        val expected = runCatching { hardware.expected(before, profile) }.getOrNull()
            ?: run { failed = true; return false }
        val pending = ProfileLease<P, S>(packageName, profile, before, expected)
        if (!runCatching { journal(pending) }.getOrDefault(false)) { failed = true; return false }
        lease = pending
        if (!runCatching { hardware.apply(profile) }.getOrDefault(false)) {
            release()
            failed = true
            return false
        }
        // Preserve generation counters from the acknowledged write, where available.
        val applied = runCatching { pending.copy(expected =
            hardware.acknowledged(pending.expected, hardware.capture())) }.getOrElse {
            release(); failed = true; return false
        }
        lease = applied
        if (!runCatching { journal(applied) }.getOrDefault(false)) {
            release()
            failed = true
            return false
        }
        lease = applied
        failed = false
        return true
    }

    fun release(): Boolean {
        val active = lease ?: run { failed = false; return true }
        if (!runCatching { hardware.restore(active.baseline, active.expected) }.getOrDefault(false)) { failed = true; return false }
        if (!runCatching { journal(null) }.getOrDefault(false)) { failed = true; return false }
        lease = null
        failed = false
        return true
    }
}
