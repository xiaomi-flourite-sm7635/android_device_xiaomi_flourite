/* SPDX-License-Identifier: Apache-2.0 */
package org.lineageos.settings.flourite.gpu

import org.lineageos.settings.flourite.profiles.PerformanceLock
import org.lineageos.settings.flourite.profiles.ProfileRuntime

internal object GpuControlSession {
    internal val manager = GpuControlManager()
    fun readState() = synchronized(PerformanceLock) { manager.readState() }
    fun manual(block: (GpuControlManager) -> GpuResult): GpuResult = synchronized(PerformanceLock) {
        if (!ProfileRuntime.beforeManual()) GpuResult(GpuFailure.READBACK_FAILED)
        else block(manager)
    }
}
