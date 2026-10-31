/* SPDX-License-Identifier: Apache-2.0 */
package org.lineageos.settings.flourite.cpu

import org.lineageos.settings.flourite.profiles.PerformanceLock
import org.lineageos.settings.flourite.profiles.ProfileRuntime

internal object CpuControlSession {
    internal val manager = CpuControlManager()
    fun <T> read(block: (CpuControlManager) -> T): T = synchronized(PerformanceLock) { block(manager) }
    fun manual(block: (CpuControlManager) -> CpuResult): CpuResult = synchronized(PerformanceLock) {
        if (!ProfileRuntime.beforeManual()) CpuResult(CpuFailure.READBACK_FAILED)
        else block(manager)
    }
}
