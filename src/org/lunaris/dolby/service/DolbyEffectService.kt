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
import android.os.Looper
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

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var repository: DolbyRepository
    private lateinit var deviceStateManager: DeviceStateManager
    private var previousActiveDevice: AudioDeviceInfo? = null

    private val deviceChangeRunnable = Runnable {
        processDeviceChange()
    }

    private val prefChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == DolbyConstants.PREF_DEVICE_STATE_MEMORY) {
            if (isDeviceStateMemoryEnabled && repository.getDolbyEnabled()) {
                val currentDevice = repository.getCurrentOutputDevice()
                if (currentDevice != null) {
                    previousActiveDevice = currentDevice
                    deviceStateManager.ensureInitialSnapshot(currentDevice, repository)
                }
            }
        }
    }

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<AudioDeviceInfo>) {
            Log.d(TAG, "Devices added: ${addedDevices.map { it.debugString() }}")
            scheduleDeviceChange()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<AudioDeviceInfo>) {
            Log.d(TAG, "Devices removed: ${removedDevices.map { it.debugString() }}")
            scheduleDeviceChange()
        }
    }

    private val playbackCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
            val isActive = configs?.any { it.isActive } == true
            if (isActive) {
                restoreStateForCurrentDevice()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        repository = DolbyRepository(this)
        deviceStateManager = DeviceStateManager(this)

        val currentDevice = repository.getCurrentOutputDevice()
        if (currentDevice != null) {
            previousActiveDevice = currentDevice
            if (isDeviceStateMemoryEnabled) {
                val key = deviceStateManager.deviceKey(currentDevice)
                val restored = deviceStateManager.restoreSnapshot(key, repository)
                if (!restored) {
                    repository.applySavedState()
                    if (repository.getDolbyEnabled()) {
                        deviceStateManager.saveSnapshot(key, repository)
                    }
                }
            } else {
                repository.applySavedState()
            }
        } else {
            repository.applySavedState()
        }

        dolbyPrefs.registerOnSharedPreferenceChangeListener(prefChangeListener)
        audioManager.registerAudioDeviceCallback(audioDeviceCallback, handler)
        audioManager.registerAudioPlaybackCallback(playbackCallback, handler)
        Log.d(TAG, "Dolby effect service created")
    }

    private fun scheduleDeviceChange() {
        handler.removeCallbacks(deviceChangeRunnable)
        handler.postDelayed(deviceChangeRunnable, 50L)
    }

    private fun processDeviceChange() {
        val newDevice = repository.getCurrentOutputDevice() ?: return
        val newKey = deviceStateManager.deviceKey(newDevice)
        val oldDevice = previousActiveDevice
        val oldKey = oldDevice?.let { deviceStateManager.deviceKey(it) }

        if (oldKey != null && oldKey == newKey) {
            return
        }

        if (isDeviceStateMemoryEnabled) {
            if (oldKey != null && repository.getDolbyEnabled()) {
                Log.d(TAG, "Saving snapshot for previous device: $oldKey")
                deviceStateManager.saveSnapshot(oldKey, repository)
            }
            Log.d(TAG, "Restoring snapshot for new device: $newKey")
            val restored = deviceStateManager.restoreSnapshot(newKey, repository)
            if (!restored) {
                Log.d(TAG, "First time device, initializing snapshot: $newKey")
                repository.applySavedState()
                if (repository.getDolbyEnabled()) {
                    deviceStateManager.saveSnapshot(newKey, repository)
                }
            }
        } else {
            Log.d(TAG, "Device state memory disabled, applying saved state")
            repository.applySavedState()
        }

        previousActiveDevice = newDevice
        repository.updateSpeakerState()
    }

    private fun restoreStateForCurrentDevice() {
        if (!repository.getDolbyEnabled()) {
            return
        }
        if (isDeviceStateMemoryEnabled) {
            val currentDevice = repository.getCurrentOutputDevice()
            if (currentDevice != null) {
                val key = deviceStateManager.deviceKey(currentDevice)
                if (deviceStateManager.hasSnapshot(key)) {
                    deviceStateManager.restoreSnapshot(key, repository)
                    return
                }
            }
        }
        repository.applySavedState()
    }

    private fun AudioDeviceInfo.debugString(): String =
        "name=$productName,type=$type,id=$id,address=$address,isSink=$isSink"

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        restoreStateForCurrentDevice()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isDeviceStateMemoryEnabled && repository.getDolbyEnabled()) {
            previousActiveDevice?.let { device ->
                val key = deviceStateManager.deviceKey(device)
                deviceStateManager.saveSnapshot(key, repository)
            }
        }
        dolbyPrefs.unregisterOnSharedPreferenceChangeListener(prefChangeListener)
        audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)
        audioManager.unregisterAudioPlaybackCallback(playbackCallback)
        handler.removeCallbacksAndMessages(null)
        Log.d(TAG, "Dolby effect service destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "DolbyEffectService"

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
