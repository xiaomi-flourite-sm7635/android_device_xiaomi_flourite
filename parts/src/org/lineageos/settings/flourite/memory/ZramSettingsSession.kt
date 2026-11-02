/* SPDX-License-Identifier: Apache-2.0 */
package org.lineageos.settings.flourite.memory

import android.os.SystemProperties
import android.os.UserHandle
import java.io.File

internal object ZramSettingsSession {
    private const val REQUEST = "persist.sys.flouriteparts.zram_profile"
    private const val STATUS = "vendor.flouriteparts.zram_profile"
    val control = ZramSettingsManager(object : ZramSettingsIo {
        override fun readRequest() = SystemProperties.get(REQUEST, "default")
        override fun writeRequest(value: String) = runCatching {
            if (UserHandle.myUserId() != UserHandle.USER_SYSTEM) return@runCatching false
            SystemProperties.set(REQUEST, value)
            true
        }.getOrDefault(false)
        override fun bootProfile() = SystemProperties.get(STATUS)
        override fun ramBytes(): Long? = runCatching {
            File("/proc/meminfo").useLines { lines ->
                lines.firstOrNull { it.startsWith("MemTotal:") }
                    ?.substringAfter(':')?.trim()?.substringBefore(' ')
                    ?.toLongOrNull()?.takeIf { it in 1..Long.MAX_VALUE / 1024 }?.times(1024)
            }
        }.getOrNull()
        override fun supported() = UserHandle.myUserId() == UserHandle.USER_SYSTEM &&
            ZramProfile.fromToken(bootProfile()) != null && runCatching {
                File("/sys/block/zram0/disksize").readText().trim().toLong() > 0
            }.getOrDefault(false)
    })
}
