/*
 * SPDX-FileCopyrightText: 2026 kenway214
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lunaris.dolby.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Handler
import android.content.SharedPreferences
import android.os.IBinder
import android.util.Log
import org.lunaris.dolby.DolbyConstants
import org.lunaris.dolby.data.DeviceStateManager
import org.lunaris.dolby.data.DolbyRepository

class DolbyEffectService : Service() {

    private val audioManager by lazy { getSystemService(AudioManager::class.java) }
    private val dolbyPrefs: SharedPreferences by lazy {
        getSharedPreferences("dolby_prefs", Context.MODE_PRIVATE)
    }
    private val isDeviceStateMemoryEnabled: Boolean
        get() = dolbyPrefs.getBoolean(DolbyConstants.PREF_DEVICE_STATE_MEMORY, false)
    private val handler = Handler()
    private lateinit var repository: DolbyRepository
    private lateinit var deviceStateManager: DeviceStateManager

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<AudioDeviceInfo>) {
            Log.d(TAG, "Devices added: ${addedDevices.map { it.debugString() }}")
            handleDeviceChange()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<AudioDeviceInfo>) {
            Log.d(TAG, "Devices removed: ${removedDevices.map { it.debugString() }}")
            if (isDeviceStateMemoryEnabled) {
                removedDevices.forEach { device ->
                    val key = deviceStateManager.deviceKey(device)
                    Log.d(TAG, "Snapshotting state for removed device: $key")
                    deviceStateManager.saveSnapshot(key, repository)
                }
            }
            handleDeviceChange()
        }
    }

    private val playbackCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
            val isActive = isPlaybackActive(configs)
            if (isActive) {
                repository.applySavedState()
            }
        }
    }

    private fun isPlaybackActive(configs: List<AudioPlaybackConfiguration>?): Boolean {
        if (configs.isNullOrEmpty()) return false
        return configs.any { config ->
            try {
                val method = config.javaClass.getMethod("isActive")
                method.invoke(config) as? Boolean ?: true
            } catch (e: Throwable) {
                true
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        repository = DolbyRepository.getInstance(this)
        deviceStateManager = DeviceStateManager.getInstance(this)

        if (isDeviceStateMemoryEnabled) {
            repository.handleAudioDeviceChanged()
        } else {
            repository.applySavedState()
        }

        audioManager.registerAudioDeviceCallback(audioDeviceCallback, handler)
        audioManager.registerAudioPlaybackCallback(playbackCallback, handler)
        Log.d(TAG, "Dolby effect service created")
    }

    private fun handleDeviceChange() {
        if (isDeviceStateMemoryEnabled) {
            repository.handleAudioDeviceChanged()
        } else {
            repository.updateSpeakerState()
            repository.applySavedState()
        }
    }

    private fun AudioDeviceInfo.debugString(): String =
        "name=$productName,type=$type,id=$id,address=$address,isSink=$isSink"

    private fun tryGetActiveMediaRouteDevice(): AudioDeviceInfo? {
        return try {
            val method = AudioManager::class.java.getMethod("getDevicesForAttributes", AudioAttributes::class.java)
            val result = method.invoke(audioManager, *arrayOf<Any>(ATTRIBUTES_MEDIA)) as? List<*>
            val routedDevice = result?.firstOrNull() ?: return null
            val typeMethod = routedDevice.javaClass.getMethod("getType")
            val addrMethod = runCatching { routedDevice.javaClass.getMethod("getAddress") }.getOrNull()
            val type = typeMethod.invoke(routedDevice) as? Int ?: return null
            val address = (addrMethod?.invoke(routedDevice) as? String).orEmpty()
            val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            outputs.firstOrNull { device ->
                device.isSink && device.type == type && (address.isEmpty() || device.address == address)
            } ?: outputs.firstOrNull { device ->
                device.isSink && device.type == type
            }
        } catch (e: Throwable) {
            null
        }
    }

    private fun getCurrentOutputDevice(): AudioDeviceInfo? {
        val activeRoute = tryGetActiveMediaRouteDevice()
        if (activeRoute != null) return activeRoute

        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)

        val priorityOrder = listOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_BLE_BROADCAST,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        )

        for (type in priorityOrder) {
            val device = devices.firstOrNull { it.type == type }
            if (device != null) return device
        }
        return devices.firstOrNull()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        repository.applySavedState()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isDeviceStateMemoryEnabled) {
            val currentDevice = repository.getCurrentOutputDevice()
            if (currentDevice != null && !deviceStateManager.isCurrentDeviceInherited) {
                val key = deviceStateManager.deviceKey(currentDevice)
                deviceStateManager.saveSnapshot(key, repository)
            }
        }
        audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)
        audioManager.unregisterAudioPlaybackCallback(playbackCallback)
        handler.removeCallbacksAndMessages(null)
        repository.releaseEffect()
        Log.d(TAG, "Dolby effect service destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "DolbyEffectService"

        private val ATTRIBUTES_MEDIA = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        fun start(context: Context) {
            val intent = Intent(context, DolbyEffectService::class.java)
            context.startService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, DolbyEffectService::class.java)
            context.stopService(intent)
        }
    }
}
