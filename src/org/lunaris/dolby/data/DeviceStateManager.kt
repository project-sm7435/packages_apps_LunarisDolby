/*
 * Copyright (C) 2026 tranQuila-Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lunaris.dolby.data

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioDeviceInfo

import org.lunaris.dolby.DolbyConstants
import org.lunaris.dolby.domain.models.BandGain
import org.lunaris.dolby.domain.models.BandMode

class DeviceStateManager(private val context: Context) {

    fun deviceKey(device: AudioDeviceInfo): String {
        return when (device.type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_BLE_BROADCAST -> {
                val addr = device.address?.takeIf { it.isNotBlank() }
                    ?: device.productName?.toString()?.takeIf { it.isNotBlank() }
                    ?: "unknown"
                "bt_${addr.replace(":", "_").replace(" ", "_").lowercase()}"
            }
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired_headphones"
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_ACCESSORY,
            AudioDeviceInfo.TYPE_DOCK -> {
                val id = device.productName?.toString()?.takeIf { it.isNotBlank() }
                    ?: device.address?.takeIf { it.isNotBlank() }
                    ?: "dac"
                "usb_${id.replace(" ", "_").lowercase()}"
            }
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "builtin_speaker"
            else -> "device_type_${device.type}"
        }
    }

    fun deviceDisplayName(device: AudioDeviceInfo): String {
        val productName = device.productName?.toString()?.takeIf { it.isNotBlank() }
        return when (device.type) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Phone Speaker"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> productName ?: "Wired Headphones"
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_ACCESSORY,
            AudioDeviceInfo.TYPE_DOCK -> productName ?: "USB Audio"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_BLE_BROADCAST -> productName ?: "Bluetooth Device"
            else -> productName ?: "Audio Device"
        }
    }

    fun saveSnapshot(deviceKey: String, repository: DolbyRepository) {
        val profile = repository.getCurrentProfile()
        val prefs = getDevicePrefs(deviceKey)
        val editor = prefs.edit()

        editor.putInt(KEY_VERSION, SNAPSHOT_VERSION)
        editor.putInt(KEY_PROFILE, profile)
        editor.putString(KEY_BAND_MODE, repository.getBandMode().value)

        for (p in SUPPORTED_PROFILES) {
            val pPrefs = repository.getProfilePrefs(p)
            editor.putInt("p_${p}_ieq", pPrefs.getString(DolbyConstants.PREF_IEQ, "0")?.toIntOrNull() ?: 0)
            editor.putBoolean("p_${p}_hp_virt", pPrefs.getBoolean(DolbyConstants.PREF_HP_VIRTUALIZER, false))
            editor.putBoolean("p_${p}_spk_virt", pPrefs.getBoolean(DolbyConstants.PREF_SPK_VIRTUALIZER, false))
            editor.putBoolean("p_${p}_dialogue", pPrefs.getBoolean(DolbyConstants.PREF_DIALOGUE, false))
            editor.putInt("p_${p}_dialogue_amt", pPrefs.getInt(DolbyConstants.PREF_DIALOGUE_AMOUNT, 6))
            editor.putBoolean("p_${p}_bass_enabled", pPrefs.getBoolean(DolbyConstants.PREF_BASS, false))
            editor.putInt("p_${p}_bass_level", pPrefs.getInt(DolbyConstants.PREF_BASS_LEVEL, 0))
            editor.putInt("p_${p}_bass_curve", pPrefs.getInt(DolbyConstants.PREF_BASS_CURVE, 0))
            editor.putBoolean("p_${p}_treble_enabled", pPrefs.getBoolean(DolbyConstants.PREF_TREBLE, false))
            editor.putInt("p_${p}_treble_level", pPrefs.getInt(DolbyConstants.PREF_TREBLE_LEVEL, 0))
            editor.putBoolean("p_${p}_mid_enabled", pPrefs.getBoolean(DolbyConstants.PREF_MID, false))
            editor.putInt("p_${p}_mid_level", pPrefs.getInt(DolbyConstants.PREF_MID_LEVEL, 0))

            if (repository.volumeLevelerSupported) {
                editor.putBoolean("p_${p}_volume", pPrefs.getBoolean(DolbyConstants.PREF_VOLUME, false))
            }
            if (repository.stereoWideningSupported) {
                editor.putInt("p_${p}_stereo", pPrefs.getInt(DolbyConstants.PREF_STEREO_WIDENING, 32))
            }

            val basePreset = pPrefs.getString(DolbyConstants.PREF_PRESET, null)
            if (basePreset != null) {
                editor.putString("p_${p}_eq_gains", basePreset)
            }
        }

        editor.apply()
        DolbyConstants.dlog(TAG, "Snapshot saved for device=$deviceKey profile=$profile v=$SNAPSHOT_VERSION")
    }

    fun restoreSnapshot(deviceKey: String, repository: DolbyRepository): Boolean {
        val prefs = getDevicePrefs(deviceKey)

        if (!prefs.contains(KEY_VERSION)) {
            DolbyConstants.dlog(TAG, "No snapshot for device=$deviceKey")
            return false
        }

        val storedVersion = prefs.getInt(KEY_VERSION, -1)
        if (storedVersion != SNAPSHOT_VERSION) {
            DolbyConstants.dlog(TAG, "Snapshot version mismatch for $deviceKey: stored=$storedVersion current=$SNAPSHOT_VERSION")
            clearSnapshot(deviceKey)
            return false
        }

        return try {
            val profile = prefs.getInt(KEY_PROFILE, 0)
            val bandModeVal = prefs.getString(KEY_BAND_MODE, BandMode.TEN_BAND.value) ?: BandMode.TEN_BAND.value
            repository.setBandMode(BandMode.fromValue(bandModeVal))

            for (p in SUPPORTED_PROFILES) {
                if (prefs.contains("p_${p}_ieq")) {
                    val pPrefs = repository.getProfilePrefs(p).edit()
                    pPrefs.putString(DolbyConstants.PREF_IEQ, prefs.getInt("p_${p}_ieq", 0).toString())
                    pPrefs.putBoolean(DolbyConstants.PREF_HP_VIRTUALIZER, prefs.getBoolean("p_${p}_hp_virt", false))
                    pPrefs.putBoolean(DolbyConstants.PREF_SPK_VIRTUALIZER, prefs.getBoolean("p_${p}_spk_virt", false))
                    pPrefs.putBoolean(DolbyConstants.PREF_DIALOGUE, prefs.getBoolean("p_${p}_dialogue", false))
                    pPrefs.putInt(DolbyConstants.PREF_DIALOGUE_AMOUNT, prefs.getInt("p_${p}_dialogue_amt", 6))
                    pPrefs.putBoolean(DolbyConstants.PREF_BASS, prefs.getBoolean("p_${p}_bass_enabled", false))
                    pPrefs.putInt(DolbyConstants.PREF_BASS_LEVEL, prefs.getInt("p_${p}_bass_level", 0))
                    pPrefs.putInt(DolbyConstants.PREF_BASS_CURVE, prefs.getInt("p_${p}_bass_curve", 0))
                    pPrefs.putBoolean(DolbyConstants.PREF_TREBLE, prefs.getBoolean("p_${p}_treble_enabled", false))
                    pPrefs.putInt(DolbyConstants.PREF_TREBLE_LEVEL, prefs.getInt("p_${p}_treble_level", 0))
                    pPrefs.putBoolean(DolbyConstants.PREF_MID, prefs.getBoolean("p_${p}_mid_enabled", false))
                    pPrefs.putInt(DolbyConstants.PREF_MID_LEVEL, prefs.getInt("p_${p}_mid_level", 0))

                    if (repository.volumeLevelerSupported) {
                        pPrefs.putBoolean(DolbyConstants.PREF_VOLUME, prefs.getBoolean("p_${p}_volume", false))
                    }
                    if (repository.stereoWideningSupported) {
                        pPrefs.putInt(DolbyConstants.PREF_STEREO_WIDENING, prefs.getInt("p_${p}_stereo", 32))
                    }

                    val gainsStr = prefs.getString("p_${p}_eq_gains", null)
                    if (gainsStr != null) {
                        pPrefs.putString(DolbyConstants.PREF_PRESET, gainsStr)
                    }
                    pPrefs.apply()
                }
            }

            repository.setCurrentProfile(profile)
            repository.updateEffectiveGains(profile)
            repository.applyProfileSettings(profile)

            DolbyConstants.dlog(TAG, "Snapshot restored for device=$deviceKey profile=$profile v=$storedVersion")
            true
        } catch (e: Exception) {
            DolbyConstants.dlog(TAG, "Failed to restore snapshot for $deviceKey: ${e.message}")
            clearSnapshot(deviceKey)
            false
        }
    }

    fun ensureInitialSnapshot(device: AudioDeviceInfo?, repository: DolbyRepository) {
        val dev = device ?: repository.getCurrentOutputDevice() ?: return
        val key = deviceKey(dev)
        if (!hasSnapshot(key)) {
            saveSnapshot(key, repository)
        }
    }

    fun hasSnapshot(deviceKey: String): Boolean {
        val prefs = getDevicePrefs(deviceKey)
        return prefs.contains(KEY_VERSION) &&
                prefs.getInt(KEY_VERSION, -1) == SNAPSHOT_VERSION
    }

    fun clearSnapshot(deviceKey: String) {
        getDevicePrefs(deviceKey).edit().clear().apply()
        DolbyConstants.dlog(TAG, "Snapshot cleared for device=$deviceKey")
    }

    fun getAllDeviceKeys(): List<String> {
        val dir = context.filesDir.parentFile?.let {
            java.io.File(it, "shared_prefs")
        } ?: return emptyList()
        return dir.listFiles()
            ?.filter { it.name.startsWith("device_state_") }
            ?.map { it.name.removePrefix("device_state_").removeSuffix(".xml") }
            ?: emptyList()
    }

    private fun getDevicePrefs(deviceKey: String): SharedPreferences =
        context.getSharedPreferences("device_state_$deviceKey", Context.MODE_PRIVATE)

    companion object {
        private const val TAG = "DeviceStateManager"

        const val SNAPSHOT_VERSION = 4

        val SUPPORTED_PROFILES = listOf(0, 1, 2, 3, 4, 5, 6)

        private const val KEY_VERSION = "snapshot_version"
        private const val KEY_PROFILE = "profile"
        private const val KEY_BAND_MODE = "band_mode"
    }
}
