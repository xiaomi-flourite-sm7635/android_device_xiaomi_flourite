// SPDX-License-Identifier: Apache-2.0
package org.lineageos.settings.flourite.profiles

import android.content.Context
import android.provider.Settings
import org.json.JSONObject

internal class ProfileStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("app_performance_profiles", Context.MODE_PRIVATE)
    var enabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        set(value) { check(prefs.edit().putBoolean("enabled", value).commit()) }
    fun profiles(): Map<String, AppPerformanceProfile> = synchronized(storageLock) {
        val json = JSONObject(prefs.getString("profiles", "{}") ?: "{}")
        require(json.length() <= 256)
        json.keys().asSequence().associateWith { name ->
            require(validPackage(name)); ProfileCodec.decode(json.getJSONObject(name))
        }
    }
    fun save(packageName: String, profile: AppPerformanceProfile?): Boolean = synchronized(storageLock) {
        runCatching {
            require(validPackage(packageName))
            val profiles = profiles().toMutableMap()
            if (profile == null) profiles.remove(packageName) else profiles[packageName] = profile
            require(profiles.size <= 256)
            val json = JSONObject()
            profiles.forEach { (name, value) -> json.put(name, ProfileCodec.encode(value)) }
            prefs.edit().putString("profiles", json.toString()).commit()
        }.getOrDefault(false)
    }
    private fun boot() = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
    fun journal(value: ProfileLease<AppPerformanceProfile, AppPerformanceProfile>?): Boolean {
        if (value == null) return prefs.edit().remove("journal").commit()
        val boot = boot()
        if (boot < 0) return false
        val json = JSONObject().put("boot", boot).put("version", 1).put("package", value.packageName)
            .put("profile", ProfileCodec.encode(value.profile))
            .put("before", ProfileCodec.encode(value.baseline)).put("expected", ProfileCodec.encode(value.expected))
        return prefs.edit().putString("journal", json.toString()).commit()
    }
    fun journal(): ProfileLease<AppPerformanceProfile, AppPerformanceProfile>? {
        val text = prefs.getString("journal", null) ?: return null
        val json = JSONObject(text)
        val currentBoot = boot(); require(currentBoot >= 0)
        if (json.getInt("boot") != currentBoot) { check(journal(null)); return null }
        require(json.getInt("version") == 1 && validPackage(json.getString("package")))
        val before = ProfileCodec.decode(json.getJSONObject("before"))
        val expected = ProfileCodec.decode(json.getJSONObject("expected"))
        val profile = ProfileCodec.decode(json.getJSONObject("profile"))
        require(before.controls.keys == expected.controls.keys && before.controls.keys.containsAll(profile.controls.keys))
        require(expected.controls == before.controls + profile.controls)
        return ProfileLease(json.getString("package"), profile, before, expected)
    }
    companion object {
        private val storageLock = Any()
        fun validPackage(name: String) = name.length <= 255 && name.matches(Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+"))
    }
}

internal object ProfileCodec {
    fun encode(profile: AppPerformanceProfile) = JSONObject().apply {
        profile.controls.forEach { (key, value) -> require(ProfileControls.wellFormed(key, value)); put(key, value) }
    }
    fun decode(json: JSONObject): AppPerformanceProfile {
        require(json.length() <= ProfileControls.keys.size)
        return AppPerformanceProfile(json.keys().asSequence().associateWith { key ->
            val value = json.getString(key)
            require(ProfileControls.wellFormed(key, value)); value
        })
    }
}
