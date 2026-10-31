// SPDX-License-Identifier: Apache-2.0
package org.lineageos.settings.flourite.profiles

import android.app.ActivityManager
import android.app.ActivityTaskManager
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.TaskStackListener
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.os.UserHandle
import android.os.UserManager
import org.lineageos.settings.flourite.PartsActivity
import kotlin.concurrent.thread
import org.lineageos.settings.flourite.R

/** Explicitly enabled, visible foreground service; task events replace continuous process polling. */
class ProfileMonitorService : Service() {
    private val thread = HandlerThread("FlouriteAppProfiles")
    private lateinit var worker: Handler
    private val notificationLock = Any()
    @Volatile private var destroyed = false
    @Volatile private var monitorToken = 0L
    private var registered = false
    private var observing = false
    private val taskListener = object : TaskStackListener() {
        override fun onTaskStackChanged() { schedule() }
        override fun onTaskFocusChanged(taskId: Int, focused: Boolean) { schedule() }
    }
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_SCREEN_OFF && intent.action != Intent.ACTION_USER_SWITCHED) {
                schedule(); return
            }
            // Hold only for the bounded restoration, never for foreground monitoring or idle.
            val pending = goAsync()
            val wake = getSystemService(PowerManager::class.java).newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, "FlouriteParts:profile-restore")
            wake.acquire(5_000)
            worker.removeCallbacks(refresh)
            val token = monitorToken
            val posted = worker.post {
                try {
                    if (!ProfileRuntime.update(this@ProfileMonitorService, null, token)) {
                        runCatching { ProfileStore(this@ProfileMonitorService).enabled = false }
                        stopSelf()
                    } else notifyStatus(getString(R.string.profiles_waiting))
                } finally {
                    if (wake.isHeld) wake.release()
                    pending.finish()
                }
            }
            if (!posted) { if (wake.isHeld) wake.release(); pending.finish() }
        }
    }
    private val refresh = Runnable {
        if (destroyed) return@Runnable
        val storage = ProfileStore(this)
        if (!storage.enabled) {
            ProfileRuntime.stop(this, monitorToken); stopSelf(); return@Runnable
        }
        val packageName = runCatching {
            if (ActivityManager.getCurrentUser() != UserHandle.USER_SYSTEM ||
                getSystemService(PowerManager::class.java)?.isInteractive != true ||
                getSystemService(KeyguardManager::class.java)?.isKeyguardLocked != false) null
            else ActivityTaskManager.getService().focusedRootTaskInfo?.let {
                val uid = it.topActivityInfo?.applicationInfo?.uid
                if (uid != null && UserHandle.getUserId(uid) == UserHandle.USER_SYSTEM)
                    it.topActivity?.packageName else null
            }
        }.getOrNull()
        val success = ProfileRuntime.update(this, packageName, monitorToken)
        if (!success) {
            runCatching { storage.enabled = false }
            notifyStatus(getString(R.string.profiles_failed))
            // Keep the persistent journal if restoration cannot complete.
            ProfileRuntime.stop(this, monitorToken)
            stopSelf()
        } else notifyStatus(when (val status = ProfileRuntime.status) {
            "waiting" -> getString(R.string.profiles_waiting)
            "off" -> getString(R.string.profiles_disabled)
            "error" -> getString(R.string.profiles_failed)
            else -> getString(R.string.profiles_active, appLabel(status))
        })
    }
    private fun appLabel(name: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(name, 0)).toString()
    }.getOrDefault(name)
    private fun schedule() {
        if (destroyed || !::worker.isInitialized) return
        worker.removeCallbacks(refresh)
        worker.postDelayed(refresh, 100)
    }
    override fun onCreate() {
        super.onCreate()
        thread.start(); worker = Handler(thread.looper)
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(CHANNEL,
            getString(R.string.profiles_title), NotificationManager.IMPORTANCE_LOW))
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Meet the foreground deadline even when a persisted decision has just changed.
        startForeground(NOTIFICATION, notification(getString(R.string.profiles_waiting)))
        monitorToken = ProfileRuntime.newMonitor()
        if (UserHandle.myUserId() != UserHandle.USER_SYSTEM || !ProfileStore(this).enabled) {
            stopSelf(); return START_NOT_STICKY
        }
        if (!getSystemService(NotificationManager::class.java).areNotificationsEnabled()) {
            runCatching { ProfileStore(this).enabled = false }
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            if (!observing) {
                ActivityTaskManager.getService().registerTaskStackListener(taskListener)
                observing = true
            }
            if (!registered) {
                registerReceiver(screenReceiver, IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF)
                    addAction(Intent.ACTION_USER_PRESENT); addAction(Intent.ACTION_USER_SWITCHED)
                }, Context.RECEIVER_EXPORTED)
                registered = true
            }
            schedule()
        } catch (e: Exception) {
            runCatching { ProfileStore(this).enabled = false }
            worker.post { ProfileRuntime.stop(this, monitorToken); stopSelf() }
            return START_NOT_STICKY
        }
        return START_STICKY
    }
    private fun notification(text: String): Notification = Notification.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_xiaomi_parts).setContentTitle(getString(R.string.profiles_title))
        .setContentText(text).setOngoing(true).setOnlyAlertOnce(true)
        .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, PartsActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)).build()
    private fun notifyStatus(text: String) = synchronized(notificationLock) {
        if (!destroyed)
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(text))
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        synchronized(notificationLock) {
            destroyed = true
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
        if (observing) runCatching { ActivityTaskManager.getService().unregisterTaskStackListener(taskListener) }
        if (registered) unregisterReceiver(screenReceiver)
        worker.removeCallbacks(refresh)
        worker.post { ProfileRuntime.stop(this, monitorToken); thread.quitSafely() }
        super.onDestroy()
    }
    companion object {
        private const val CHANNEL = "app_performance_profiles"
        private const val NOTIFICATION = 31
        fun resume(context: Context) {
            if (UserHandle.myUserId() != UserHandle.USER_SYSTEM ||
                context.getSystemService(UserManager::class.java)?.isUserUnlocked != true) return
            val app = context.applicationContext
            thread(name = "FlouriteProfileRecovery") { refresh(app) }
        }
        fun refresh(context: Context): Boolean = runCatching {
            if (ProfileStore(context).enabled)
                context.startForegroundService(Intent(context, ProfileMonitorService::class.java))
            else {
                context.stopService(Intent(context, ProfileMonitorService::class.java))
                return ProfileRuntime.stop(context)
            }
            true
        }.getOrDefault(false)
    }
}

class ProfileBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action in listOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED) &&
            UserHandle.myUserId() == UserHandle.USER_SYSTEM &&
            context.getSystemService(UserManager::class.java)?.isUserUnlocked == true) {
            val pending = goAsync()
            thread(name = "FlouriteProfileBoot") {
                try {
                    ProfileMonitorService.refresh(context)
                } finally { pending.finish() }
            }
        }
    }
}
