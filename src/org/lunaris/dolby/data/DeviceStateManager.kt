/*
 * Copyright (C) 2024-2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lunaris.dolby.data

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.SharedPreferences
import android.media.AudioDeviceInfo
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.lunaris.dolby.DolbyConstants
import org.lunaris.dolby.domain.models.*
import java.util.concurrent.ConcurrentHashMap

class DeviceStateManager private constructor(private val context: Context) {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var saveSnapshotJob: Job? = null

    private val profilesPrefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val profilesCache = ConcurrentHashMap<String, DeviceProfile>()

    @Volatile
    var currentActiveDeviceKey: String? = null
        private set

    @Volatile
    var isCurrentDeviceInherited: Boolean = false
        private set

    init {
        loadAllProfilesFromDisk()
        migrateLegacySnapshotsIfNeeded()
    }

    fun deviceKey(device: AudioDeviceInfo): String {
        val rawAddress = device.address?.trim().orEmpty()
        val hasValidAddress = rawAddress.isNotBlank() && rawAddress != REDACTED_MAC

        return when (device.type) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> KEY_BUILTIN_SPEAKER
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> KEY_BUILTIN_EARPIECE

            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_BLE_BROADCAST,
            AudioDeviceInfo.TYPE_HEARING_AID -> {
                if (hasValidAddress) {
                    val normalizedMac = rawAddress.replace(":", "").replace("-", "").lowercase()
                    "bt_$normalizedMac"
                } else {
                    val bondedMac = findBondedBluetoothAddress(device.productName?.toString())
                    if (!bondedMac.isNullOrBlank()) {
                        "bt_${bondedMac.replace(":", "").replace("-", "").lowercase()}"
                    } else {
                        val nameKey = sanitizeKey(device.productName?.toString() ?: "unknown")
                        "bt_type_${device.type}_$nameKey"
                    }
                }
            }

            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_ACCESSORY -> {
                val nameKey = sanitizeKey(device.productName?.toString() ?: "generic_dac")
                val addrKey = if (hasValidAddress) sanitizeKey(rawAddress) else "default"
                "usb_${nameKey}_$addrKey"
            }

            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> KEY_WIRED_HEADPHONES
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> KEY_WIRED_HEADSET
            AudioDeviceInfo.TYPE_LINE_ANALOG -> KEY_LINE_ANALOG

            else -> {
                val nameKey = sanitizeKey(device.productName?.toString() ?: "unknown")
                val addrKey = if (hasValidAddress) sanitizeKey(rawAddress) else "default"
                "dev_${device.type}_${nameKey}_$addrKey"
            }
        }
    }

    fun deviceDisplayName(device: AudioDeviceInfo): String {
        val productName = device.productName?.toString()?.trim()?.takeIf { it.isNotBlank() }
        return when (device.type) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Phone Speaker"
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Earpiece"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> productName ?: "Wired Headphones"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> productName ?: "Wired Headset"
            AudioDeviceInfo.TYPE_LINE_ANALOG -> productName ?: "Line Output"
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE -> productName ?: "USB Audio Device"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_BLE_BROADCAST -> productName ?: "Bluetooth Audio"
            else -> productName ?: "Audio Device"
        }
    }

    fun connectionType(device: AudioDeviceInfo): String {
        return when (device.type) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "SPEAKER"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_LINE_ANALOG -> "WIRED"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_BLE_BROADCAST,
            AudioDeviceInfo.TYPE_HEARING_AID -> "BLUETOOTH"
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB"
            else -> "OTHER"
        }
    }

    fun hasProfile(deviceKey: String): Boolean = profilesCache.containsKey(deviceKey)

    fun hasSnapshot(deviceKey: String): Boolean = hasProfile(deviceKey)

    fun getProfile(deviceKey: String): DeviceProfile? = profilesCache[deviceKey]

    @Synchronized
    fun saveProfile(profile: DeviceProfile) {
        profilesCache[profile.stableDeviceId] = profile
        profilesPrefs.edit()
            .putString(PREF_PROFILE_PREFIX + profile.stableDeviceId, profile.toJson().toString())
            .apply()
        DolbyConstants.dlog(TAG, "Saved profile for device=${profile.stableDeviceId} (${profile.deviceName})")
    }

    @Synchronized
    fun deleteProfile(deviceKey: String) {
        profilesCache.remove(deviceKey)
        profilesPrefs.edit()
            .remove(PREF_PROFILE_PREFIX + deviceKey)
            .apply()
        DolbyConstants.dlog(TAG, "Deleted profile for device=$deviceKey")
    }

    fun clearSnapshot(deviceKey: String) = deleteProfile(deviceKey)

    fun getAllProfiles(): List<DeviceProfile> = profilesCache.values.toList()

    fun getAllDeviceKeys(): List<String> = profilesCache.keys.toList()

    /**
     * Called whenever a user modifies ANY audio setting in real-time.
     * Persists the current configuration to the active device's profile in the background
     * with debouncing so rapid slider adjustments and UI animations do not stutter.
     */
    fun onUserSettingChanged(repository: DolbyRepository) {
        val prefs = context.getSharedPreferences("dolby_prefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean(DolbyConstants.PREF_DEVICE_STATE_MEMORY, true)) return
        val activeKey = currentActiveDeviceKey ?: repository.getCurrentOutputDevice()?.let { deviceKey(it) } ?: return
        if (currentActiveDeviceKey == null) {
            currentActiveDeviceKey = activeKey
        }
        isCurrentDeviceInherited = false

        synchronized(this) {
            saveSnapshotJob?.cancel()
            saveSnapshotJob = scope.launch {
                delay(DEBOUNCE_DELAY_MS)
                try {
                    saveSnapshot(activeKey, repository)
                    DolbyConstants.dlog(TAG, "User modified setting -> auto-persisted (debounced) to active device=$activeKey")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to persist debounced snapshot for $activeKey", e)
                }
            }
        }
    }

    /**
     * Flushes any pending debounced profile save immediately to ensure state persistence before lifecycle teardown.
     */
    fun flushPendingSave(repository: DolbyRepository) {
        val keyToSave: String?
        synchronized(this) {
            if (saveSnapshotJob?.isActive == true) {
                saveSnapshotJob?.cancel()
                keyToSave = currentActiveDeviceKey
            } else {
                keyToSave = null
            }
        }
        if (keyToSave != null) {
            try {
                saveSnapshot(keyToSave, repository)
                DolbyConstants.dlog(TAG, "Flushed pending profile snapshot for device=$keyToSave")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to flush snapshot for $keyToSave", e)
            }
        }
    }

    /**
     * Handles output device transitions immediately and in real-time.
     * Returns true if a saved profile was loaded and applied, or false if the current state was preserved.
     */
    fun handleDeviceSwitch(newDevice: AudioDeviceInfo, repository: DolbyRepository): Boolean {
        val prefs = context.getSharedPreferences("dolby_prefs", Context.MODE_PRIVATE)
        val isMemoryEnabled = prefs.getBoolean(DolbyConstants.PREF_DEVICE_STATE_MEMORY, true)
        val newKey = deviceKey(newDevice)
        val oldKey = currentActiveDeviceKey

        if (newKey == oldKey) {
            DolbyConstants.dlog(TAG, "Same output device ($newKey), no switch needed")
            return false
        }

        DolbyConstants.dlog(TAG, "Device switch: old=$oldKey -> new=$newKey (${newDevice.productName})")

        // 1. Snapshot previous device if it was actively modified (not inherited) and memory is enabled
        synchronized(this) {
            saveSnapshotJob?.cancel()
        }
        if (isMemoryEnabled && oldKey != null && !isCurrentDeviceInherited) {
            saveSnapshot(oldKey, repository)
            DolbyConstants.dlog(TAG, "Snapshotted previous device=$oldKey before switch")
        }

        // 2. Check if new device already has a saved profile
        val existingProfile = if (isMemoryEnabled) getProfile(newKey) else null
        currentActiveDeviceKey = newKey

        return if (existingProfile != null) {
            // Known device with saved profile: restore it immediately!
            isCurrentDeviceInherited = false
            DolbyConstants.dlog(TAG, "Known device $newKey detected. Restoring profile: ${existingProfile.presetName}")
            restoreProfileToHardware(existingProfile, repository)
            true
        } else {
            // Brand new device detected for the first time:
            // Preserve the currently active configuration (inherited initial state).
            // Do NOT reset to defaults!
            isCurrentDeviceInherited = true
            DolbyConstants.dlog(TAG, "New device $newKey detected. Inheriting active audio state without resetting.")
            false
        }
    }

    /**
     * Captures every configurable audio setting exposed by the app into a DeviceProfile.
     */
    fun createSnapshotFromCurrentState(
        deviceKey: String,
        repository: DolbyRepository,
        device: AudioDeviceInfo? = null
    ): DeviceProfile {
        val currentProfile = repository.getCurrentProfile()
        val currentBandMode = repository.getBandMode()
        val all20Gains = repository.getEqualizerGains(currentProfile, BandMode.TWENTY_BAND)
        val currentPresetName = repository.getPresetName(currentProfile)

        val autoEqPrefs = context.getSharedPreferences("autoeq_prefs", Context.MODE_PRIVATE)
        val autoEqId = if (currentPresetName.contains("AutoEQ", ignoreCase = true)) {
            autoEqPrefs.getString("last_applied_id", null)
        } else null

        val existingProfile = profilesCache[deviceKey]

        // Collect snapshots for profiles 0..3
        val snapshots = mutableMapOf<Int, ProfileAudioSettings>()
        for (p in 0..3) {
            val pPrefs = context.getSharedPreferences("profile_$p", Context.MODE_PRIVATE)
            val pGains: List<Int> = if (p == currentProfile) {
                all20Gains.map { it.gain }
            } else {
                val savedPrefGains = pPrefs.getString(DolbyConstants.PREF_PRESET, null)
                if (savedPrefGains != null) {
                    savedPrefGains.split(",").mapNotNull { it.trim().toIntOrNull() }
                } else {
                    existingProfile?.profileSnapshots?.get(p)?.geqGains
                        ?: repository.getEqualizerGains(p, BandMode.TWENTY_BAND).map { it.gain }
                }
            }

            val pPreset = if (p == currentProfile) {
                currentPresetName
            } else {
                existingProfile?.profileSnapshots?.get(p)?.presetName
                    ?: repository.getPresetName(p)
            }
            val pAutoEq = if (pPreset.contains("AutoEQ", ignoreCase = true)) {
                autoEqPrefs.getString("last_applied_id", null)
            } else null

            val pIeq = if (p == 0) 0 else {
                if (p == currentProfile) {
                    repository.getIeqPreset(p)
                } else {
                    pPrefs.getString(DolbyConstants.PREF_IEQ, null)?.toIntOrNull()
                        ?: existingProfile?.profileSnapshots?.get(p)?.ieqPreset
                        ?: repository.getIeqPreset(p)
                }
            }

            snapshots[p] = ProfileAudioSettings(
                profile = p,
                ieqPreset = pIeq,
                headphoneVirtualizerEnabled = repository.getHeadphoneVirtualizerEnabled(p),
                speakerVirtualizerEnabled = repository.getSpeakerVirtualizerEnabled(p),
                stereoWideningAmount = repository.getStereoWideningAmount(p),
                dialogueEnhancerEnabled = repository.getDialogueEnhancerEnabled(p),
                dialogueEnhancerAmount = repository.getDialogueEnhancerAmount(p),
                bassEnhancerEnabled = repository.getBassEnhancerEnabled(p),
                bassLevel = repository.getBassLevel(p),
                bassCurve = repository.getBassCurve(p),
                midEnhancerEnabled = repository.getMidEnhancerEnabled(p),
                midLevel = repository.getMidLevel(p),
                trebleEnhancerEnabled = repository.getTrebleEnhancerEnabled(p),
                trebleLevel = repository.getTrebleLevel(p),
                volumeLevelerEnabled = repository.getVolumeLevelerEnabled(p),
                geqGains = pGains,
                presetName = pPreset,
                autoEqProfileId = pAutoEq
            )
        }

        val name = if (device != null) deviceDisplayName(device) else {
            profilesCache[deviceKey]?.deviceName ?: resolveFallbackName(deviceKey)
        }
        val type = device?.type ?: profilesCache[deviceKey]?.deviceType ?: 0
        val connType = if (device != null) connectionType(device) else {
            profilesCache[deviceKey]?.connectionType ?: "OTHER"
        }
        val addr = device?.address?.trim().orEmpty().takeIf { it != REDACTED_MAC }
            ?: profilesCache[deviceKey]?.address ?: ""

        return DeviceProfile(
            stableDeviceId = deviceKey,
            deviceName = name,
            deviceType = type,
            connectionType = connType,
            address = addr,
            lastUsedTimestamp = System.currentTimeMillis(),
            dolbyEnabled = repository.getDolbyEnabled(),
            currentProfile = currentProfile,
            bandMode = currentBandMode,
            presetName = currentPresetName,
            autoEqProfileId = autoEqId,
            equalizerGains = all20Gains.map { it.gain },
            preampGain = 0f,
            ieqPreset = repository.getIeqPreset(currentProfile),
            headphoneVirtualizerEnabled = repository.getHeadphoneVirtualizerEnabled(currentProfile),
            speakerVirtualizerEnabled = repository.getSpeakerVirtualizerEnabled(currentProfile),
            stereoWideningAmount = repository.getStereoWideningAmount(currentProfile),
            dialogueEnhancerEnabled = repository.getDialogueEnhancerEnabled(currentProfile),
            dialogueEnhancerAmount = repository.getDialogueEnhancerAmount(currentProfile),
            bassEnhancerEnabled = repository.getBassEnhancerEnabled(currentProfile),
            bassLevel = repository.getBassLevel(currentProfile),
            bassCurve = repository.getBassCurve(currentProfile),
            midEnhancerEnabled = repository.getMidEnhancerEnabled(currentProfile),
            midLevel = repository.getMidLevel(currentProfile),
            trebleEnhancerEnabled = repository.getTrebleEnhancerEnabled(currentProfile),
            trebleLevel = repository.getTrebleLevel(currentProfile),
            volumeLevelerEnabled = repository.getVolumeLevelerEnabled(currentProfile),
            profileSnapshots = snapshots
        )
    }

    fun saveSnapshot(deviceKey: String, repository: DolbyRepository) {
        val currentDevice = repository.getCurrentOutputDevice()
        val snapshot = createSnapshotFromCurrentState(deviceKey, repository, currentDevice)
        saveProfile(snapshot)
    }

    fun restoreSnapshot(deviceKey: String, repository: DolbyRepository): Boolean {
        val profile = getProfile(deviceKey) ?: return false
        restoreProfileToHardware(profile, repository)
        return true
    }

    /**
     * Atomically restores all 20 EQ bands, Dolby parameters, and profile snapshots to hardware.
     */
    fun restoreProfileToHardware(profile: DeviceProfile, repository: DolbyRepository) {
        repository.applyDeviceProfile(profile)
    }

    private fun loadAllProfilesFromDisk() {
        profilesCache.clear()
        val all = profilesPrefs.all
        for ((key, value) in all) {
            if (key.startsWith(PREF_PROFILE_PREFIX) && value is String) {
                try {
                    val json = JSONObject(value)
                    val profile = DeviceProfile.fromJson(json)
                    profilesCache[profile.stableDeviceId] = profile
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse stored profile for key $key", e)
                }
            }
        }
        DolbyConstants.dlog(TAG, "Loaded ${profilesCache.size} device profiles from disk")
    }

    private fun migrateLegacySnapshotsIfNeeded() {
        try {
            val dir = context.filesDir.parentFile?.let { java.io.File(it, "shared_prefs") } ?: return
            val legacyFiles = dir.listFiles { file ->
                file.name.startsWith("device_state_") && file.name.endsWith(".xml")
            } ?: return

            for (file in legacyFiles) {
                val legacyKey = file.name.removePrefix("device_state_").removeSuffix(".xml")
                if (legacyKey.isBlank() || profilesCache.containsKey(legacyKey)) continue

                val legacyPrefs = context.getSharedPreferences("device_state_$legacyKey", Context.MODE_PRIVATE)
                if (!legacyPrefs.contains("snapshot_version")) continue

                val enabled = legacyPrefs.getBoolean("enabled", true)
                val profile = legacyPrefs.getInt("profile", 0)
                val gainsStr = legacyPrefs.getString("eq_gains", null)
                val gains = gainsStr?.split(",")?.mapNotNull { it.toIntOrNull() } ?: List(20) { 0 }

                val migrated = DeviceProfile(
                    stableDeviceId = legacyKey,
                    deviceName = resolveFallbackName(legacyKey),
                    deviceType = 0,
                    connectionType = if (legacyKey.contains("speaker")) "SPEAKER" else "WIRED",
                    dolbyEnabled = enabled,
                    currentProfile = profile,
                    equalizerGains = if (gains.size == 20) gains else List(20) { gains.getOrElse(it) { 0 } },
                    ieqPreset = legacyPrefs.getInt("ieq", 0),
                    headphoneVirtualizerEnabled = legacyPrefs.getBoolean("hp_virt", false),
                    speakerVirtualizerEnabled = legacyPrefs.getBoolean("spk_virt", false),
                    dialogueEnhancerEnabled = legacyPrefs.getBoolean("dialogue", false),
                    dialogueEnhancerAmount = legacyPrefs.getInt("dialogue_amt", 6),
                    bassEnhancerEnabled = legacyPrefs.getBoolean("bass_enabled", false),
                    bassLevel = legacyPrefs.getInt("bass_level", 0),
                    bassCurve = legacyPrefs.getInt("bass_curve", 0),
                    trebleEnhancerEnabled = legacyPrefs.getBoolean("treble_enabled", false),
                    trebleLevel = legacyPrefs.getInt("treble_level", 0),
                    midEnhancerEnabled = legacyPrefs.getBoolean("mid_enabled", false),
                    midLevel = legacyPrefs.getInt("mid_level", 0),
                    volumeLevelerEnabled = legacyPrefs.getBoolean("volume", false),
                    stereoWideningAmount = legacyPrefs.getInt("stereo", 32)
                )

                saveProfile(migrated)
                legacyPrefs.edit().clear().apply()
                file.delete()
                DolbyConstants.dlog(TAG, "Migrated legacy snapshot for $legacyKey to DeviceProfile")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during legacy snapshot migration", e)
        }
    }

    private fun findBondedBluetoothAddress(productName: String?): String? {
        if (productName.isNullOrBlank()) return null
        return try {
            val adapter = BluetoothAdapter.getDefaultAdapter() ?: return null
            adapter.bondedDevices?.firstOrNull { it.name.equals(productName, ignoreCase = true) }?.address
        } catch (e: SecurityException) {
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun sanitizeKey(key: String): String {
        return key.trim().lowercase().replace(Regex("[^a-z0-9_]"), "_")
    }

    private fun resolveFallbackName(key: String): String {
        return when {
            key == KEY_BUILTIN_SPEAKER -> "Phone Speaker"
            key == KEY_BUILTIN_EARPIECE -> "Earpiece"
            key.contains("speaker") -> "Speaker"
            key.startsWith("bt_") -> "Bluetooth Device"
            key.startsWith("usb_") -> "USB Audio Device"
            key.contains("wired") -> "Wired Headphones"
            else -> "Audio Device"
        }
    }

    companion object {
        private const val TAG = "DeviceStateManager"
        private const val PREFS_NAME = "dolby_device_profiles"
        private const val PREF_PROFILE_PREFIX = "profile_"
        private const val DEBOUNCE_DELAY_MS = 350L

        const val KEY_BUILTIN_SPEAKER = "builtin_speaker"
        const val KEY_BUILTIN_EARPIECE = "builtin_earpiece"
        const val KEY_WIRED_HEADPHONES = "wired_headphones"
        const val KEY_WIRED_HEADSET = "wired_headset"
        const val KEY_LINE_ANALOG = "line_analog"

        private const val REDACTED_MAC = "02:00:00:00:00:00"

        @Volatile
        private var instance: DeviceStateManager? = null

        fun getInstance(context: Context): DeviceStateManager {
            return instance ?: synchronized(this) {
                instance ?: DeviceStateManager(context.applicationContext).also { instance = it }
            }
        }
    }
}
