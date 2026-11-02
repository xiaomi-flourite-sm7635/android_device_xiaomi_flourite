/* SPDX-License-Identifier: Apache-2.0 */
package org.lineageos.settings.flourite.battery

import android.os.UserHandle
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import kotlinx.coroutines.*
import org.lineageos.settings.flourite.R

/** No background polling when the QS panel is closed, and no saved enable state. */
class ChargePauseTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observation: Job? = null
    private var busy = false
    private val owner get() = UserHandle.myUserId() == UserHandle.USER_SYSTEM

    override fun onStartListening() {
        super.onStartListening()
        observation?.cancel()
        observation = scope.launch {
            while (isActive) {
                if (!busy) render(withContext(Dispatchers.IO) {
                    if (owner) ChargePauseSession.control.state() else ChargePauseState.UNAVAILABLE
                })
                delay(2_000)
            }
        }
    }

    override fun onStopListening() {
        observation?.cancel()
        observation = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        if (!owner || busy) return
        if (isLocked) unlockAndRun { toggle() } else toggle()
    }

    private fun toggle() {
        if (!owner || busy) return
        busy = true
        qsTile?.apply {
            state = Tile.STATE_UNAVAILABLE
            subtitle = getString(R.string.charge_pause_busy)
            updateTile()
        }
        scope.launch {
            val result = withContext(Dispatchers.IO + NonCancellable) {
                ChargePauseSession.control.toggle()
            }
            busy = false
            render(result.state)
            if (!result.confirmed) Toast.makeText(this@ChargePauseTileService,
                R.string.charge_pause_tile_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun render(value: ChargePauseState) {
        if (busy) return
        qsTile?.apply {
            label = getString(R.string.charge_pause_title)
            state = when (value) {
                ChargePauseState.ACTIVE, ChargePauseState.RESTORE_PENDING -> Tile.STATE_ACTIVE
                ChargePauseState.OFF -> Tile.STATE_INACTIVE
                ChargePauseState.UNAVAILABLE -> Tile.STATE_UNAVAILABLE
            }
            subtitle = getString(when (value) {
                ChargePauseState.ACTIVE -> R.string.charge_pause_tile_on
                ChargePauseState.OFF -> R.string.charge_pause_tile_off
                ChargePauseState.RESTORE_PENDING -> R.string.charge_pause_pending
                ChargePauseState.UNAVAILABLE -> R.string.charge_pause_unavailable
            })
            contentDescription = "$label: $subtitle"
            updateTile()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
