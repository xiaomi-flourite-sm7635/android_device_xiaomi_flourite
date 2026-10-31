// SPDX-License-Identifier: Apache-2.0
package org.lineageos.settings.flourite.profiles

import android.content.Context
import android.content.Intent
import org.lineageos.settings.flourite.cpu.CpuControlSession
import org.lineageos.settings.flourite.gpu.GpuControlSession

/** Serializes manual controls, foreground transitions and journal recovery. */
internal object ProfileRuntime {
    private var context: Context? = null
    private var engine: ProfileEngine<AppPerformanceProfile, AppPerformanceProfile>? = null
    private var monitorToken = 0L
    @Volatile var status = "off"
        private set
    fun attach(context: Context) = synchronized(PerformanceLock) { this.context = context.applicationContext }
    private fun hardware() = PerformanceHardware(CpuControlSession.manager, GpuControlSession.manager)
    private fun start(context: Context): Boolean {
        if (engine != null) return true
        val storage = ProfileStore(context)
        val saved = runCatching { storage.journal() }.getOrElse { status = "error"; return false }
        val next = ProfileEngine(hardware(), storage::journal)
        engine = next
        return next.recover(saved).also { status = if (it) "waiting" else "error" }
    }
    fun newMonitor(): Long = synchronized(PerformanceLock) { ++monitorToken }
    fun update(context: Context, packageName: String?, token: Long): Boolean = synchronized(PerformanceLock) {
        if (token != monitorToken) return true
        if (!start(context)) return false
        val result = runCatching {
            val storage = ProfileStore(context)
            val profile = packageName?.takeIf { storage.enabled }?.let { storage.profiles()[it] }
            engine!!.update(packageName.takeIf { profile != null }, profile)
        }.getOrDefault(false)
        status = if (result) engine!!.activePackage ?: "waiting" else "error"
        result
    }
    fun stop(context: Context, token: Long? = null): Boolean = synchronized(PerformanceLock) {
        if (token != null && token != monitorToken) return true
        ++monitorToken
        val result = start(context) && runCatching { engine!!.release() }.getOrDefault(false)
        if (result) engine = null
        status = if (result) "off" else "error"
        result
    }
    fun beforeManual(): Boolean = synchronized(PerformanceLock) {
        val app = context ?: return false
        // Prevent a queued foreground callback from reapplying a profile over the user's edit.
        if (!runCatching { ProfileStore(app).enabled = false }.isSuccess) return false
        app.stopService(Intent(app, ProfileMonitorService::class.java))
        stop(app)
    }
    fun capture(): AppPerformanceProfile = synchronized(PerformanceLock) { hardware().capture() }
    fun valid(profile: AppPerformanceProfile): Boolean = synchronized(PerformanceLock) { hardware().valid(profile) }
}
