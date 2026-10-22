/*
 * Copyright (C) 2024-2025 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lunaris.dolby.tile

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Resources
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import org.lunaris.dolby.DolbyConstants
import org.lunaris.dolby.R
import org.lunaris.dolby.data.DolbyRepository
import org.lunaris.dolby.service.DolbyEffectService

class DolbyTileService : TileService() {

    private val repository by lazy { DolbyRepository(applicationContext) }
    private val prefs by lazy { getSharedPreferences("dolby_prefs", Context.MODE_PRIVATE) }
    private val prefChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == DolbyConstants.PREF_ENABLE || key == DolbyConstants.PREF_PROFILE) {
            updateTile()
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        prefs.registerOnSharedPreferenceChangeListener(prefChangeListener)
        updateTile()
    }

    override fun onStopListening() {
        prefs.unregisterOnSharedPreferenceChangeListener(prefChangeListener)
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        val enabled = repository.getDolbyEnabled()
        val newState = !enabled
        repository.setDolbyEnabled(newState)
        if (newState) {
            DolbyEffectService.start(applicationContext)
        } else {
            DolbyEffectService.stop(applicationContext)
        }
        updateTile()
    }

    private fun updateTile() {
        qsTile?.apply {
            val enabled = repository.getDolbyEnabled()
            state = if (enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            subtitle = getProfileName()
            updateTile()
        }
    }

    private fun getProfileName(): String {
        val profile = repository.getCurrentProfile()
        val profiles = resources.getStringArray(R.array.dolby_profile_entries)
        val profileValues = resources.getStringArray(R.array.dolby_profile_values)
        
        return try {
            val index = profileValues.indexOf(profile.toString())
            if (index != -1) profiles[index] else getString(R.string.dolby_unknown)
        } catch (e: Exception) {
            getString(R.string.dolby_unknown)
        }
    }
}
