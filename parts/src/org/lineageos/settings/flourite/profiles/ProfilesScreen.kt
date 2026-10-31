// SPDX-License-Identifier: Apache-2.0
package org.lineageos.settings.flourite.profiles

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.lineageos.settings.flourite.R
import org.lineageos.settings.flourite.ui.*

private data class ProfileApp(val packageName: String, val label: String)

@Composable
fun ProfilesScreen(onBack: () -> Unit, onEditProfile: (String, String) -> Unit) {
    val context = LocalContext.current
    val store = remember(context) { ProfileStore(context) }
    val scope = rememberCoroutineScope()
    val resumed = rememberPartsResumed()
    var enabled by remember { mutableStateOf(store.enabled) }
    var profiles by remember { mutableStateOf(runCatching { store.profiles() }.getOrDefault(emptyMap())) }
    var apps by remember { mutableStateOf<List<ProfileApp>>(emptyList()) }
    var choosing by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var search by rememberSaveable { mutableStateOf("") }
    var status by remember { mutableStateOf(ProfileRuntime.status) }
    fun setEnabled(value: Boolean) {
        scope.launch {
            busy = true
            val ok = withContext(Dispatchers.IO) {
                runCatching { store.enabled = value; true }.getOrDefault(false)
            }
            if (ok && withContext(Dispatchers.IO) { ProfileMonitorService.refresh(context) }) enabled = value
            else {
                withContext(Dispatchers.IO) { runCatching { store.enabled = false }; ProfileMonitorService.refresh(context) }
                enabled = false
                Toast.makeText(context, R.string.profiles_failed, Toast.LENGTH_LONG).show()
            }
            busy = false
        }
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) setEnabled(true)
        else Toast.makeText(context, R.string.profiles_notification_required, Toast.LENGTH_LONG).show()
    }
    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) {
            context.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .filter { it.activityInfo.packageName != context.packageName }
                .map { ProfileApp(it.activityInfo.packageName, it.loadLabel(context.packageManager).toString()) }
                .distinctBy { it.packageName }.sortedBy { it.label.lowercase() }
        }
    }
    LaunchedEffect(resumed) {
        if (resumed) profiles = withContext(Dispatchers.IO) { runCatching { store.profiles() }.getOrDefault(emptyMap()) }
        while (resumed) {
            enabled = store.enabled; status = ProfileRuntime.status
            delay(1_000)
        }
    }
    fun remove(name: String) {
        scope.launch {
            busy = true
            val ok = withContext(Dispatchers.IO) { store.save(name, null) }
            if (ok) { profiles = store.profiles(); withContext(Dispatchers.IO) { ProfileMonitorService.refresh(context) } }
            else Toast.makeText(context, R.string.profiles_failed, Toast.LENGTH_LONG).show()
            busy = false
        }
    }
    PartsScaffold(title = stringResource(R.string.profiles_title), onBack = onBack,
        actions = { IconButton(onClick = { choosing = true }, enabled = !busy) {
            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.profiles_add))
        } }) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(
            top = padding.calculateTopPadding() + 6.dp, bottom = padding.calculateBottomPadding() + 24.dp)) {
            item { PartsPreferenceGroup {
                PartsSwitchPreference(title = stringResource(R.string.profiles_enable),
                    summary = stringResource(R.string.profiles_enable_summary), checked = enabled,
                    enabled = !busy && (enabled || profiles.isNotEmpty()), onCheckedChange = { wanted ->
                        if (wanted && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        else setEnabled(wanted)
                    })
                PartsPreference(title = stringResource(R.string.profiles_status), summary = when {
                    status == "error" -> stringResource(R.string.profiles_failed)
                    !enabled -> stringResource(R.string.profiles_disabled)
                    status in listOf("waiting", "off") -> stringResource(R.string.profiles_waiting)
                    else -> stringResource(R.string.profiles_active, apps.firstOrNull { it.packageName == status }?.label ?: status)
                })
            } }
            item { PartsPreferenceGroup { PartsPreference(title = stringResource(R.string.profiles_how_title),
                summary = stringResource(R.string.profiles_how)) } }
            if (profiles.isEmpty()) item { PartsPreferenceGroup { PartsPreference(
                title = stringResource(R.string.profiles_add), summary = stringResource(R.string.profiles_empty),
                onClick = { choosing = true }) } }
            items(profiles.keys.sorted(), key = { it }) { name ->
                val app = apps.firstOrNull { it.packageName == name } ?: ProfileApp(name, name)
                val profile = profiles.getValue(name)
                PartsPreferenceGroup {
                    PartsPreference(title = app.label,
                        summary = stringResource(R.string.profiles_saved_summary, profile.controls.size),
                        onClick = { onEditProfile(app.packageName, app.label) }, enabled = !busy,
                        trailing = { IconButton(onClick = { remove(name) }, enabled = !busy) {
                            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.profiles_remove))
                        } })
                }
            }
        }
    }
    if (choosing) AlertDialog(onDismissRequest = { choosing = false },
        title = { Text(stringResource(R.string.profiles_choose)) },
        text = { Column { OutlinedTextField(value = search, onValueChange = { search = it }, singleLine = true,
            label = { Text(stringResource(R.string.profiles_search)) })
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                items(apps.filter { it.label.contains(search, true) || it.packageName.contains(search, true) }, key = { it.packageName }) { app ->
                    TextButton(onClick = { choosing = false; onEditProfile(app.packageName, app.label) }) { Text(app.label) }
                }
            }
        } }, confirmButton = {}, dismissButton = { TextButton(onClick = { choosing = false }) {
            Text(stringResource(android.R.string.cancel)) } })
}
