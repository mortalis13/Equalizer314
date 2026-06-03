package com.bearinmind.equalizer314.audio

import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import androidx.annotation.RequiresApi
import com.bearinmind.equalizer314.R

/**
 * Quick Settings tile that toggles the global DynamicsProcessing on /
 * off without opening MainActivity. Mirrors the power FAB's behaviour:
 *
 *   - When EQ is off → tile reads "EQ314 OFF" (inactive, dim icon).
 *     Tapping fires [EqService.ACTION_START_FROM_TILE], which loads
 *     persisted bands from `eq_settings` SP and starts the global DP.
 *   - When EQ is on → tile reads "EQ314 ON" (active, lit icon).
 *     Tapping fires [EqService.ACTION_STOP], which tears down the DP,
 *     releases per-session effects, and stops the foreground service.
 *
 * The tile updates optimistically on click so the user sees the label
 * flip instantly; the next [onStartListening] re-syncs against the
 * persisted power-state pref in case the service start failed silently
 * (e.g. no saved bands on a fresh install).
 */
@RequiresApi(Build.VERSION_CODES.N)
class Eq314TileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        // Read the live in-process flag — the persisted pref drifts
        // (MainActivity intentionally resets it to false on every
        // cold launch). EqService.isDpRunning is the authoritative
        // source for whether the global DP is actually processing.
        val on = EqService.isDpRunning
        Log.d(TAG, "onStartListening — isDpRunning=$on")
        renderState(isOn = on)
    }

    override fun onClick() {
        super.onClick()
        // ACTION_START_FROM_TILE is now a true toggle on the service
        // side — it interprets isDpRunning live and either starts the
        // DP from persisted bands or tears down whatever's running.
        // We just fire the intent and update the tile optimistically.
        val turningOn = !EqService.isDpRunning
        Log.d(TAG, "onClick — turningOn=$turningOn (isDpRunning=${EqService.isDpRunning})")
        val intent = Intent(this, EqService::class.java)
            .setAction(EqService.ACTION_START_FROM_TILE)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            Log.d(TAG, "onClick — dispatched ${intent.action}")
        } catch (t: Throwable) {
            // Foreground-service-from-tile is allowed on every API
            // we target, but if a future restriction fires we just
            // skip — onStartListening will re-sync the tile next open.
            Log.w(TAG, "onClick — startService failed", t)
            return
        }
        renderState(isOn = turningOn)
    }

    companion object {
        private const val TAG = "Eq314Tile"
    }

    private fun renderState(isOn: Boolean) {
        val tile = qsTile ?: return
        tile.label = if (isOn) "EQ314 ON" else "EQ314 OFF"
        tile.state = if (isOn) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.icon = Icon.createWithResource(this, R.drawable.ic_nav_equalizer)
        // No subtitle — the label ("EQ314 ON" / "EQ314 OFF") says it all.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = ""
        }
        tile.updateTile()
    }
}
