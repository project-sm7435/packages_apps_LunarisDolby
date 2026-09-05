/*
 * Copyright (C) 2024-2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lunaris.dolby.domain.models

import org.json.JSONArray
import org.json.JSONObject

data class ProfileAudioSettings(
    val profile: Int = 0,
    val ieqPreset: Int = 0,
    val headphoneVirtualizerEnabled: Boolean = false,
    val speakerVirtualizerEnabled: Boolean = false,
    val stereoWideningAmount: Int = 32,
    val dialogueEnhancerEnabled: Boolean = false,
    val dialogueEnhancerAmount: Int = 6,
    val bassEnhancerEnabled: Boolean = false,
    val bassLevel: Int = 0,
    val bassCurve: Int = 0,
    val midEnhancerEnabled: Boolean = false,
    val midLevel: Int = 0,
    val trebleEnhancerEnabled: Boolean = false,
    val trebleLevel: Int = 0,
    val volumeLevelerEnabled: Boolean = false,
    val geqGains: List<Int> = List(20) { 0 },
    val presetName: String = "Flat (off)",
    val autoEqProfileId: String? = null
) {
    fun toJson(): JSONObject {
        val obj = JSONObject()
        obj.put("profile", profile)
        obj.put("ieqPreset", ieqPreset)
        obj.put("headphoneVirtualizerEnabled", headphoneVirtualizerEnabled)
        obj.put("speakerVirtualizerEnabled", speakerVirtualizerEnabled)
        obj.put("stereoWideningAmount", stereoWideningAmount)
        obj.put("dialogueEnhancerEnabled", dialogueEnhancerEnabled)
        obj.put("dialogueEnhancerAmount", dialogueEnhancerAmount)
        obj.put("bassEnhancerEnabled", bassEnhancerEnabled)
        obj.put("bassLevel", bassLevel)
        obj.put("bassCurve", bassCurve)
        obj.put("midEnhancerEnabled", midEnhancerEnabled)
        obj.put("midLevel", midLevel)
        obj.put("trebleEnhancerEnabled", trebleEnhancerEnabled)
        obj.put("trebleLevel", trebleLevel)
        obj.put("volumeLevelerEnabled", volumeLevelerEnabled)
        obj.put("geqGains", JSONArray(geqGains))
        obj.put("presetName", presetName)
        autoEqProfileId?.let { obj.put("autoEqProfileId", it) }
        return obj
    }

    companion object {
        fun fromJson(obj: JSONObject): ProfileAudioSettings {
            val gainsArray = obj.optJSONArray("geqGains")
            val gains = if (gainsArray != null && gainsArray.length() > 0) {
                List(gainsArray.length()) { gainsArray.optInt(it, 0) }
            } else {
                List(20) { 0 }
            }

            return ProfileAudioSettings(
                profile = obj.optInt("profile", 0),
                ieqPreset = obj.optInt("ieqPreset", 0),
                headphoneVirtualizerEnabled = obj.optBoolean("headphoneVirtualizerEnabled", false),
                speakerVirtualizerEnabled = obj.optBoolean("speakerVirtualizerEnabled", false),
                stereoWideningAmount = obj.optInt("stereoWideningAmount", 32),
                dialogueEnhancerEnabled = obj.optBoolean("dialogueEnhancerEnabled", false),
                dialogueEnhancerAmount = obj.optInt("dialogueEnhancerAmount", 6),
                bassEnhancerEnabled = obj.optBoolean("bassEnhancerEnabled", false),
                bassLevel = obj.optInt("bassLevel", 0),
                bassCurve = obj.optInt("bassCurve", 0),
                midEnhancerEnabled = obj.optBoolean("midEnhancerEnabled", false),
                midLevel = obj.optInt("midLevel", 0),
                trebleEnhancerEnabled = obj.optBoolean("trebleEnhancerEnabled", false),
                trebleLevel = obj.optInt("trebleLevel", 0),
                volumeLevelerEnabled = obj.optBoolean("volumeLevelerEnabled", false),
                geqGains = if (gains.size == 20) gains else List(20) { gains.getOrElse(it) { 0 } },
                presetName = obj.optString("presetName", "Flat (off)"),
                autoEqProfileId = if (obj.has("autoEqProfileId") && !obj.isNull("autoEqProfileId")) {
                    obj.getString("autoEqProfileId")
                } else null
            )
        }
    }
}

data class DeviceProfile(
    val stableDeviceId: String,
    val deviceName: String,
    val deviceType: Int,
    val connectionType: String,
    val address: String = "",
    val lastUsedTimestamp: Long = System.currentTimeMillis(),

    // Master Dolby state
    val dolbyEnabled: Boolean = true,
    val currentProfile: Int = 0,
    val bandMode: BandMode = BandMode.TEN_BAND,
    val presetName: String = "Flat (off)",
    val autoEqProfileId: String? = null,

    // All 20 Equalizer Band Gains in tenths of dB (-150 to +150)
    val equalizerGains: List<Int> = List(20) { 0 },
    val preampGain: Float = 0f,

    // Audio processing & tuning settings for currentProfile
    val ieqPreset: Int = 0,
    val headphoneVirtualizerEnabled: Boolean = false,
    val speakerVirtualizerEnabled: Boolean = false,
    val stereoWideningAmount: Int = 32,
    val dialogueEnhancerEnabled: Boolean = false,
    val dialogueEnhancerAmount: Int = 6,
    val bassEnhancerEnabled: Boolean = false,
    val bassLevel: Int = 0,
    val bassCurve: Int = 0,
    val midEnhancerEnabled: Boolean = false,
    val midLevel: Int = 0,
    val trebleEnhancerEnabled: Boolean = false,
    val trebleLevel: Int = 0,
    val volumeLevelerEnabled: Boolean = false,

    // Snapshots for all profiles (0..3) so switching profiles on this device preserves all of them
    val profileSnapshots: Map<Int, ProfileAudioSettings> = emptyMap(),

    // Extensible parameters for future audio features
    val extraParams: Map<String, String> = emptyMap(),
    val schemaVersion: Int = 2
) {
    fun toJson(): JSONObject {
        val obj = JSONObject()
        obj.put("stableDeviceId", stableDeviceId)
        obj.put("deviceName", deviceName)
        obj.put("deviceType", deviceType)
        obj.put("connectionType", connectionType)
        obj.put("address", address)
        obj.put("lastUsedTimestamp", lastUsedTimestamp)

        obj.put("dolbyEnabled", dolbyEnabled)
        obj.put("currentProfile", currentProfile)
        obj.put("bandMode", bandMode.name)
        obj.put("presetName", presetName)
        autoEqProfileId?.let { obj.put("autoEqProfileId", it) }

        obj.put("equalizerGains", JSONArray(equalizerGains))
        obj.put("preampGain", preampGain.toDouble())

        obj.put("ieqPreset", ieqPreset)
        obj.put("headphoneVirtualizerEnabled", headphoneVirtualizerEnabled)
        obj.put("speakerVirtualizerEnabled", speakerVirtualizerEnabled)
        obj.put("stereoWideningAmount", stereoWideningAmount)
        obj.put("dialogueEnhancerEnabled", dialogueEnhancerEnabled)
        obj.put("dialogueEnhancerAmount", dialogueEnhancerAmount)
        obj.put("bassEnhancerEnabled", bassEnhancerEnabled)
        obj.put("bassLevel", bassLevel)
        obj.put("bassCurve", bassCurve)
        obj.put("midEnhancerEnabled", midEnhancerEnabled)
        obj.put("midLevel", midLevel)
        obj.put("trebleEnhancerEnabled", trebleEnhancerEnabled)
        obj.put("trebleLevel", trebleLevel)
        obj.put("volumeLevelerEnabled", volumeLevelerEnabled)

        val snapshotsObj = JSONObject()
        profileSnapshots.forEach { (profileId, settings) ->
            snapshotsObj.put(profileId.toString(), settings.toJson())
        }
        obj.put("profileSnapshots", snapshotsObj)

        val extraObj = JSONObject()
        extraParams.forEach { (k, v) -> extraObj.put(k, v) }
        obj.put("extraParams", extraObj)

        obj.put("schemaVersion", schemaVersion)
        return obj
    }

    companion object {
        fun fromJson(obj: JSONObject): DeviceProfile {
            val gainsArray = obj.optJSONArray("equalizerGains")
            val gains = if (gainsArray != null && gainsArray.length() > 0) {
                List(gainsArray.length()) { gainsArray.optInt(it, 0) }
            } else {
                List(20) { 0 }
            }

            val bandModeStr = obj.optString("bandMode", BandMode.TEN_BAND.name)
            val bandMode = runCatching { BandMode.valueOf(bandModeStr) }.getOrDefault(BandMode.TEN_BAND)

            val snapshots = mutableMapOf<Int, ProfileAudioSettings>()
            val snapshotsObj = obj.optJSONObject("profileSnapshots")
            if (snapshotsObj != null) {
                val keys = snapshotsObj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val profileId = key.toIntOrNull() ?: continue
                    val pObj = snapshotsObj.optJSONObject(key) ?: continue
                    snapshots[profileId] = ProfileAudioSettings.fromJson(pObj)
                }
            }

            val extraParams = mutableMapOf<String, String>()
            val extraObj = obj.optJSONObject("extraParams")
            if (extraObj != null) {
                val keys = extraObj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    extraParams[key] = extraObj.optString(key, "")
                }
            }

            return DeviceProfile(
                stableDeviceId = obj.getString("stableDeviceId"),
                deviceName = obj.optString("deviceName", "Audio Device"),
                deviceType = obj.optInt("deviceType", 0),
                connectionType = obj.optString("connectionType", "OTHER"),
                address = obj.optString("address", ""),
                lastUsedTimestamp = obj.optLong("lastUsedTimestamp", System.currentTimeMillis()),
                dolbyEnabled = obj.optBoolean("dolbyEnabled", true),
                currentProfile = obj.optInt("currentProfile", 0),
                bandMode = bandMode,
                presetName = obj.optString("presetName", "Flat (off)"),
                autoEqProfileId = if (obj.has("autoEqProfileId") && !obj.isNull("autoEqProfileId")) {
                    obj.getString("autoEqProfileId")
                } else null,
                equalizerGains = if (gains.size == 20) gains else List(20) { gains.getOrElse(it) { 0 } },
                preampGain = obj.optDouble("preampGain", 0.0).toFloat(),
                ieqPreset = obj.optInt("ieqPreset", 0),
                headphoneVirtualizerEnabled = obj.optBoolean("headphoneVirtualizerEnabled", false),
                speakerVirtualizerEnabled = obj.optBoolean("speakerVirtualizerEnabled", false),
                stereoWideningAmount = obj.optInt("stereoWideningAmount", 32),
                dialogueEnhancerEnabled = obj.optBoolean("dialogueEnhancerEnabled", false),
                dialogueEnhancerAmount = obj.optInt("dialogueEnhancerAmount", 6),
                bassEnhancerEnabled = obj.optBoolean("bassEnhancerEnabled", false),
                bassLevel = obj.optInt("bassLevel", 0),
                bassCurve = obj.optInt("bassCurve", 0),
                midEnhancerEnabled = obj.optBoolean("midEnhancerEnabled", false),
                midLevel = obj.optInt("midLevel", 0),
                trebleEnhancerEnabled = obj.optBoolean("trebleEnhancerEnabled", false),
                trebleLevel = obj.optInt("trebleLevel", 0),
                volumeLevelerEnabled = obj.optBoolean("volumeLevelerEnabled", false),
                profileSnapshots = snapshots,
                extraParams = extraParams,
                schemaVersion = obj.optInt("schemaVersion", 2)
            )
        }
    }
}
