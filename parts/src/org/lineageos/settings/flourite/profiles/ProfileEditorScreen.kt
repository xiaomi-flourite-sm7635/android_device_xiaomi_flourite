// SPDX-License-Identifier: Apache-2.0
package org.lineageos.settings.flourite.profiles

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.lineageos.settings.flourite.R
import org.lineageos.settings.flourite.cpu.CpuControlSession
import org.lineageos.settings.flourite.cpu.CpuState
import org.lineageos.settings.flourite.gpu.GpuControlSession
import org.lineageos.settings.flourite.gpu.GpuState
import org.lineageos.settings.flourite.ui.*

private data class ProfileEditorData(val initial: AppPerformanceProfile, val cpu: CpuState, val gpu: GpuState)

@Composable
internal fun ProfileEditorScreen(appName: String, packageName: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var data by remember { mutableStateOf<ProfileEditorData?>(null) }
    var draftJson by rememberSaveable(packageName) { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var discard by remember { mutableStateOf(false) }
    var attempt by remember { mutableStateOf(0) }
    LaunchedEffect(packageName, attempt) {
        error = false
        data = withContext(Dispatchers.IO) {
            runCatching {
                synchronized(PerformanceLock) {
                    val initial = ProfileStore(context).profiles()[packageName] ?: ProfileRuntime.capture()
                    ProfileEditorData(initial, CpuControlSession.read { it.readState() }, GpuControlSession.snapshot().state)
                }
            }.getOrNull()
        }
        if (data != null && draftJson == null) draftJson = ProfileCodec.encode(data!!.initial).toString()
        error = data == null
    }
    val draft = remember(draftJson) { draftJson?.let { runCatching { ProfileCodec.decode(JSONObject(it)) }.getOrNull() } }
    val dirty = draft != null && draft != data?.initial
    fun leave() { if (!busy) { if (dirty) discard = true else onBack() } }
    fun edit(key: String, value: String?) {
        if (!busy && draft != null) {
            val controls = draft.controls.toMutableMap()
            if (value == null) controls.remove(key) else controls[key] = value
            draftJson = ProfileCodec.encode(AppPerformanceProfile(controls)).toString()
        }
    }
    fun save() {
        if (busy || draft == null) return
        busy = true
        scope.launch {
            try {
                val ok = withContext(Dispatchers.IO) {
                    ProfileRuntime.valid(draft) && ProfileStore(context).save(packageName, draft)
                }
                if (ok) {
                    withContext(Dispatchers.IO) { ProfileMonitorService.refresh(context) }
                    Toast.makeText(context, R.string.profiles_saved, Toast.LENGTH_SHORT).show()
                    onBack()
                } else Toast.makeText(context, R.string.profiles_save_failed, Toast.LENGTH_LONG).show()
            } finally { busy = false }
        }
    }
    BackHandler(enabled = busy || dirty) { leave() }
    PartsScaffold(title = appName, onBack = ::leave, actions = {
        TextButton(onClick = ::save, enabled = !busy && data != null && draft?.controls?.isNotEmpty() == true) {
            Text(stringResource(R.string.profiles_save))
        }
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(
            top = padding.calculateTopPadding() + 6.dp, bottom = padding.calculateBottomPadding() + 24.dp)) {
            item { PartsInfoCard(title = packageName, body = stringResource(R.string.profiles_editor_help)) }
            val loaded = data
            if (loaded == null || draft == null) item {
                PartsPreferenceGroup { PartsPreference(
                    title = stringResource(if (error) R.string.profiles_load_failed else R.string.profiles_loading),
                    onClick = if (error) ({ attempt++ }) else null) }
            } else {
                for (policy in loaded.cpu.policies) item("cpu_${policy.id}") {
                    PartsPreferenceGroup(title = stringResource(R.string.profiles_cluster, policy.cpus.joinToString(", "))) {
                        ProfileRangeControls("cpu.${policy.id}", draft, policy.frequencies,
                            !busy && policy.writable, ::edit)
                        val key = "governor.${policy.id}"
                        PartsChoicePreference(stringResource(R.string.cpu_governor_title),
                            draft.controls[key] ?: stringResource(R.string.profiles_unchanged),
                            listOf(null to stringResource(R.string.profiles_unchanged)) + policy.governors.map { it to it },
                            !busy && policy.governorWritable) { edit(key, it) }
                    }
                }
                item("cores") {
                    PartsPreferenceGroup(title = stringResource(R.string.cpu_cores_title)) {
                        loaded.cpu.cores.filter { it.id in 1..6 }.forEach { core ->
                            val key = "core.${core.id}"
                            val choices = listOf(null to stringResource(R.string.profiles_unchanged),
                                "1" to stringResource(R.string.cpu_core_online), "0" to stringResource(R.string.cpu_core_offline))
                            PartsChoicePreference(stringResource(R.string.cpu_core_title, core.id),
                                choices.firstOrNull { it.first == draft.controls[key] }?.second.orEmpty(),
                                choices, !busy && core.writable) { edit(key, it) }
                        }
                    }
                }
                item("gpu") {
                    PartsPreferenceGroup(title = stringResource(R.string.gpu_settings_title)) {
                        ProfileRangeControls("gpu", draft, loaded.gpu.frequenciesKHz, !busy && loaded.gpu.writable, ::edit)
                    }
                }
            }
        }
    }
    if (discard) AlertDialog(onDismissRequest = { discard = false },
        title = { Text(stringResource(R.string.profiles_discard_title)) },
        confirmButton = { TextButton(onClick = onBack) { Text(stringResource(R.string.profiles_discard)) } },
        dismissButton = { TextButton(onClick = { discard = false }) { Text(stringResource(android.R.string.cancel)) } })
}

@Composable
private fun ProfileRangeControls(key: String, draft: AppPerformanceProfile, table: List<Long>, enabled: Boolean,
    edit: (String, String?) -> Unit) {
    val value = draft.controls[key]
    val range = value?.let(ProfileControls::range)
    val modes = listOf<String?>(null, "auto", "custom")
    val labels = listOf(stringResource(R.string.profiles_unchanged), stringResource(R.string.profiles_automatic),
        stringResource(R.string.cpu_range_title))
    val mode = when { value == null -> 0; value == "auto" -> 1; else -> 2 }
    PartsChoicePreference(stringResource(R.string.cpu_range_title), labels[mode], modes.zip(labels), enabled) {
        edit(key, if (it == "custom") "${table.first()} ${table.last()}" else it)
    }
    if (range != null) {
        PartsChoicePreference(stringResource(R.string.gpu_minimum_frequency), "${range.first / 1000.0} MHz",
            table.filter { it <= range.second }.map { it.toString() to "${it / 1000.0} MHz" }, enabled) {
            edit(key, "$it ${range.second}")
        }
        PartsChoicePreference(stringResource(R.string.gpu_maximum_frequency), "${range.second / 1000.0} MHz",
            table.filter { it >= range.first }.map { it.toString() to "${it / 1000.0} MHz" }, enabled) {
            edit(key, "${range.first} $it")
        }
    }
}
