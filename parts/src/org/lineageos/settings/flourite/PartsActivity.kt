/* SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.settings.flourite

import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.os.UserHandle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.google.android.material.color.DynamicColors
import org.lineageos.settings.flourite.battery.BatteryScreen
import org.lineageos.settings.flourite.cpu.CpuScreen
import org.lineageos.settings.flourite.gpu.GpuScreen
import org.lineageos.settings.flourite.memory.MemoryScreen
import org.lineageos.settings.flourite.network.NetworkScreen
import org.lineageos.settings.flourite.profiles.ProfileEditorScreen
import org.lineageos.settings.flourite.profiles.ProfileMonitorService
import org.lineageos.settings.flourite.profiles.ProfileRuntime
import org.lineageos.settings.flourite.profiles.ProfileStore
import org.lineageos.settings.flourite.profiles.ProfilesScreen
import org.lineageos.settings.flourite.temperature.TemperatureScreen
import org.lineageos.settings.flourite.ui.FlouritePartsTheme

class PartsApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        DynamicColors.applyToActivitiesIfAvailable(this)
        ProfileRuntime.attach(this)
        ProfileMonitorService.resume(this)
    }
}

class PartsActivity : ComponentActivity() {
    private var pending = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!requireOwner()) return
        enableEdgeToEdge()
        setContent {
            FlouritePartsTheme {
                PartsHomeScreen { route ->
                    if (!pending && route in PAGES) {
                        pending = true
                        startActivity(Intent(this, PartsSubActivity::class.java).putExtra(PAGE, route))
                    }
                }
            }
        }
    }
    override fun onResume() {
        super.onResume()
        pending = false
    }
}

// Native Settings-style window transitions, including predictive Back.
class PartsSubActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!requireOwner()) return
        val route = intent.getStringExtra(PAGE)
        if (route !in PAGES) { finish(); return }
        val profilePackage = intent.getStringExtra(PROFILE_PACKAGE).orEmpty()
        if (route == "profile" && (!ProfileStore.validPackage(profilePackage) || profilePackage == packageName)) {
            finish(); return
        }
        val appName = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(profilePackage, 0)).toString()
        }.getOrDefault(profilePackage)
        enableEdgeToEdge()
        setContent {
            FlouritePartsTheme {
                when (route) {
                    "cpu" -> CpuScreen(::finish)
                    "gpu" -> GpuScreen(::finish)
                    "memory" -> MemoryScreen(::finish)
                    "battery" -> BatteryScreen(::finish)
                    "temperature" -> TemperatureScreen(::finish)
                    "network" -> NetworkScreen(::finish)
                    "profiles" -> ProfilesScreen(::finish) { name, _ ->
                        startActivity(Intent(this, PartsSubActivity::class.java)
                            .putExtra(PAGE, "profile").putExtra(PROFILE_PACKAGE, name))
                    }
                    "profile" -> ProfileEditorScreen(appName, profilePackage, ::finish)
                }
            }
        }
    }
}

private fun ComponentActivity.requireOwner(): Boolean {
    if (UserHandle.myUserId() == UserHandle.USER_SYSTEM) return true
    Toast.makeText(this, R.string.parts_owner_only, Toast.LENGTH_LONG).show()
    finish()
    return false
}
private const val PAGE = "org.lineageos.settings.flourite.PAGE"
private const val PROFILE_PACKAGE = "org.lineageos.settings.flourite.PROFILE_PACKAGE"
private val PAGES = setOf("cpu", "gpu", "memory", "battery", "temperature", "network", "profiles", "profile")
