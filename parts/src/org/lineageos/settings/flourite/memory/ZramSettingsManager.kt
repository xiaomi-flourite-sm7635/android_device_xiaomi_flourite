/* SPDX-License-Identifier: Apache-2.0 */
package org.lineageos.settings.flourite.memory

internal enum class ZramProfile(val percent: Int, val token: String) {
    DEFAULT(75, "default"), SMALL(25, "v1_25"), BALANCED(50, "v1_50"), LARGE(75, "v1_75");

    fun bytes(ramBytes: Long): Long? {
        if (ramBytes <= 0 || ramBytes > Long.MAX_VALUE / 100) return null
        val mib = 1024L * 1024
        val sizeMiB = if (this == DEFAULT) {
            ((ramBytes / (1024 * mib) + 1) * 1024 * 3 / 4).coerceAtMost(6144)
        } else ramBytes / 1024 * percent / 100 / 1024
        return (sizeMiB * mib).takeIf { it > 0 }
    }

    companion object {
        fun fromToken(token: String) = entries.firstOrNull { it.token == token }
    }
}

internal interface ZramSettingsIo {
    fun readRequest(): String
    fun writeRequest(value: String): Boolean
    fun bootProfile(): String
    fun ramBytes(): Long?
    fun supported(): Boolean
}

internal class ZramSettingsManager(private val io: ZramSettingsIo) {
    fun requested() = ZramProfile.fromToken(io.readRequest()) ?: ZramProfile.DEFAULT
    fun bootProfile() = ZramProfile.fromToken(io.bootProfile())
    fun ramBytes() = io.ramBytes()
    fun available() = io.supported() && ramBytes()?.let { it > 0 } == true

    @Synchronized fun schedule(profile: ZramProfile): Boolean =
        available() && io.writeRequest(profile.token) && io.readRequest() == profile.token
}
