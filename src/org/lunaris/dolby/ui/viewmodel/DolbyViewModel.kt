/*
 * Copyright (C) 2024-2025 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lunaris.dolby.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import org.lunaris.dolby.DolbyConstants
import org.lunaris.dolby.data.DolbyRepository
import org.lunaris.dolby.domain.models.*
import org.lunaris.dolby.service.DolbyEffectService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.cancelChildren

class DolbyViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = DolbyRepository.getInstance(application)

    private val _uiState = MutableStateFlow<DolbyUiState>(DolbyUiState.Loading)
    val uiState: StateFlow<DolbyUiState> = _uiState.asStateFlow()
    val currentProfile: StateFlow<Int> = repository.currentProfile
    
    private val bassLevelChannel = Channel<Int>(Channel.CONFLATED)
    private val midLevelChannel = Channel<Int>(Channel.CONFLATED)
    private val trebleLevelChannel = Channel<Int>(Channel.CONFLATED)
    private val stereoWideningChannel = Channel<Int>(Channel.CONFLATED)
    private val dialogueEnhancerAmountChannel = Channel<Int>(Channel.CONFLATED)

    private var audioOutputStateJob: Job? = null
    private var profileChangeJob: Job? = null
    private var deviceProfileChangeJob: Job? = null
    private var isCleared = false

    init {
        DolbyConstants.dlog(TAG, "ViewModel initialized")
        loadSettings()
        startTuningWorkers()
        observeAudioOutputState()
        observeProfileChanges()
        observeDeviceProfileChanges()
    }
    
    private fun observeAudioOutputState() {
        audioOutputStateJob?.cancel()
        audioOutputStateJob = viewModelScope.launch {
            repository.activeAudioDevice.collect {
                if (!isCleared) {
                    DolbyConstants.dlog(TAG, "Audio output changed: ${it.name} (${it.category})")
                    loadSettings()
                }
            }
        }
    }
    
    private fun observeProfileChanges() {
        profileChangeJob?.cancel()
        profileChangeJob = viewModelScope.launch {
            repository.currentProfile.collect {
                if (!isCleared) {
                    DolbyConstants.dlog(TAG, "Profile changed to: $it")
                    loadSettings()
                }
            }
        }
    }

    private fun observeDeviceProfileChanges() {
        deviceProfileChangeJob?.cancel()
        deviceProfileChangeJob = viewModelScope.launch {
            repository.deviceProfileChanged.collect {
                if (!isCleared) {
                    DolbyConstants.dlog(TAG, "Device profile changed, reloading settings")
                    loadSettings()
                }
            }
        }
    }

    private fun startTuningWorkers() {
        viewModelScope.launch(Dispatchers.Default) {
            for (level in bassLevelChannel) {
                if (isCleared) break
                try {
                    val profile = repository.getCurrentProfile()
                    repository.setBassLevel(profile, level)
                } catch (e: Exception) {
                    DolbyConstants.dlog(TAG, "Error setting bass level: ${e.message}")
                }
            }
        }
        viewModelScope.launch(Dispatchers.Default) {
            for (level in midLevelChannel) {
                if (isCleared) break
                try {
                    val profile = repository.getCurrentProfile()
                    repository.setMidLevel(profile, level)
                } catch (e: Exception) {
                    DolbyConstants.dlog(TAG, "Error setting mid level: ${e.message}")
                }
            }
        }
        viewModelScope.launch(Dispatchers.Default) {
            for (level in trebleLevelChannel) {
                if (isCleared) break
                try {
                    val profile = repository.getCurrentProfile()
                    repository.setTrebleLevel(profile, level)
                } catch (e: Exception) {
                    DolbyConstants.dlog(TAG, "Error setting treble level: ${e.message}")
                }
            }
        }
        viewModelScope.launch(Dispatchers.Default) {
            for (amount in stereoWideningChannel) {
                if (isCleared) break
                try {
                    val profile = repository.getCurrentProfile()
                    repository.setStereoWideningAmount(profile, amount)
                } catch (e: Exception) {
                    DolbyConstants.dlog(TAG, "Error setting stereo widening: ${e.message}")
                }
            }
        }
        viewModelScope.launch(Dispatchers.Default) {
            for (amount in dialogueEnhancerAmountChannel) {
                if (isCleared) break
                try {
                    val profile = repository.getCurrentProfile()
                    repository.setDialogueEnhancerAmount(profile, amount)
                } catch (e: Exception) {
                    DolbyConstants.dlog(TAG, "Error setting dialogue enhancer amount: ${e.message}")
                }
            }
        }
    }

    fun loadSettings() {
        if (isCleared) {
            DolbyConstants.dlog(TAG, "ViewModel cleared, skipping loadSettings")
            return
        }
        
        viewModelScope.launch {
            try {
                val successState = withContext(Dispatchers.IO) {
                    val enabled = repository.getDolbyEnabled()
                    val profile = repository.getCurrentProfile()
                    val bandMode = repository.getBandMode()
                    
                    val settings = DolbySettings(
                        enabled = enabled,
                        currentProfile = profile,
                        bassEnhancerEnabled = repository.getBassEnhancerEnabled(profile),
                        volumeLevelerEnabled = repository.getVolumeLevelerEnabled(profile),
                        bandMode = bandMode
                    )
                    
                    val profileSettings = ProfileSettings(
                        profile = profile,
                        ieqPreset = repository.getIeqPreset(profile),
                        headphoneVirtualizerEnabled = repository.getHeadphoneVirtualizerEnabled(profile),
                        speakerVirtualizerEnabled = repository.getSpeakerVirtualizerEnabled(profile),
                        stereoWideningAmount = repository.getStereoWideningAmount(profile),
                        dialogueEnhancerEnabled = repository.getDialogueEnhancerEnabled(profile),
                        dialogueEnhancerAmount = repository.getDialogueEnhancerAmount(profile),
                        bassLevel = repository.getBassLevel(profile),
                        midLevel = repository.getMidLevel(profile),
                        trebleLevel = repository.getTrebleLevel(profile),
                        bassCurve = repository.getBassCurve(profile)
                    )
                    
                    DolbyUiState.Success(
                        settings = settings,
                        profileSettings = profileSettings,
                        currentPresetName = repository.getPresetName(profile),
                        isOnSpeaker = repository.isOnSpeaker.value,
                        activeAudioDevice = repository.activeAudioDevice.value
                    )
                }
                
                if (!isCleared) {
                    _uiState.value = successState
                }
            } catch (e: Exception) {
                if (!isCleared) {
                    DolbyConstants.dlog(TAG, "Error loading settings: ${e.message}")
                    _uiState.value = DolbyUiState.Error(e.message ?: "Unknown error")
                }
            }
        }
    }

    fun setDolbyEnabled(enabled: Boolean) {
        val current = _uiState.value
        if (current is DolbyUiState.Success) {
            _uiState.value = current.copy(
                settings = current.settings.copy(enabled = enabled)
            )
        }
        viewModelScope.launch(Dispatchers.Default) {
            try {
                repository.setDolbyEnabled(enabled)
                if (enabled) {
                    DolbyEffectService.start(getApplication())
                } else {
                    DolbyEffectService.stop(getApplication())
                }
                loadSettings()
            } catch (e: Exception) {
                DolbyConstants.dlog(TAG, "Error setting Dolby enabled: ${e.message}")
            }
        }
    }

    fun setProfile(profile: Int) {
        val current = _uiState.value
        if (current is DolbyUiState.Success) {
            _uiState.value = current.copy(
                settings = current.settings.copy(currentProfile = profile)
            )
        }
        viewModelScope.launch(Dispatchers.Default) {
            try {
                repository.setCurrentProfile(profile)
            } catch (e: Exception) {
                DolbyConstants.dlog(TAG, "Error setting profile: ${e.message}")
            }
        }
    }

    fun setBassEnhancer(enabled: Boolean) {
        val current = _uiState.value
        if (current is DolbyUiState.Success) {
            _uiState.value = current.copy(
                settings = current.settings.copy(bassEnhancerEnabled = enabled)
            )
        }
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val profile = repository.getCurrentProfile()
                repository.setBassEnhancerEnabled(profile, enabled)
            } catch (e: Exception) {
                DolbyConstants.dlog(TAG, "Error setting bass enhancer: ${e.message}")
            }
        }
    }

    fun setBassLevel(level: Int) {
        val current = _uiState.value
        if (current is DolbyUiState.Success) {
            if (current.profileSettings.bassLevel == level) return
            _uiState.value = current.copy(
                settings = current.settings.copy(bassEnhancerEnabled = level > 0),
                profileSettings = current.profileSettings.copy(bassLevel = level)
            )
        }
        bassLevelChannel.trySend(level)
    }

    fun setBassCurve(curve: Int) {
        val current = _uiState.value
        if (current is DolbyUiState.Success) {
            if (current.profileSettings.bassCurve == curve) return
            _uiState.value = current.copy(
                profileSettings = current.profileSettings.copy(bassCurve = curve)
            )
        }
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val profile = repository.getCurrentProfile()
                repository.setBassCurve(profile, curve)
            } catch (e: Exception) {
                DolbyConstants.dlog(TAG, "Error setting bass curve: ${e.message}")
            }
        }
    }

    fun setMidLevel(level: Int) {
        val current = _uiState.value
        if (current is DolbyUiState.Success) {
            if (current.profileSettings.midLevel == level) return
            _uiState.value = current.copy(
                profileSettings = current.profileSettings.copy(midLevel = level)
            )
        }
        midLevelChannel.trySend(level)
    }

    fun setTrebleLevel(level: Int) {
        val current = _uiState.value
        if (current is DolbyUiState.Success) {
            if (current.profileSettings.trebleLevel == level) return
            _uiState.value = current.copy(
                profileSettings = current.profileSettings.copy(trebleLevel = level)
            )
        }
        trebleLevelChannel.trySend(level)
    }

    fun setVolumeLeveler(enabled: Boolean) {
        val current = _uiState.value
        if (current is DolbyUiState.Success) {
            _uiState.value = current.copy(
                settings = current.settings.copy(volumeLevelerEnabled = enabled)
            )
        }
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val profile = repository.getCurrentProfile()
                repository.setVolumeLevelerEnabled(profile, enabled)
            } catch (e: Exception) {
                DolbyConstants.dlog(TAG, "Error setting volume leveler: ${e.message}")
            }
        }
    }

    fun setIeqPreset(preset: Int) {
        val current = _uiState.value
        if (current is DolbyUiState.Success) {
            _uiState.value = current.copy(
                profileSettings = current.profileSettings.copy(ieqPreset = preset)
            )
        }
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val profile = repository.getCurrentProfile()
                repository.setIeqPreset(profile, preset)
            } catch (e: Exception) {
                DolbyConstants.dlog(TAG, "Error setting IEQ preset: ${e.message}")
            }
        }
    }

    fun setHeadphoneVirtualizer(enabled: Boolean) {
        val current = _uiState.value
        if (current is DolbyUiState.Success) {
            _uiState.value = current.copy(
                profileSettings = current.profileSettings.copy(headphoneVirtualizerEnabled = enabled)
            )
        }
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val profile = repository.getCurrentProfile()
                repository.setHeadphoneVirtualizerEnabled(profile, enabled)
            } catch (e: Exception) {
                DolbyConstants.dlog(TAG, "Error setting headphone virtualizer: ${e.message}")
            }
        }
    }

    fun setSpeakerVirtualizer(enabled: Boolean) {
        val current = _uiState.value
        if (current is DolbyUiState.Success) {
            _uiState.value = current.copy(
                profileSettings = current.profileSettings.copy(speakerVirtualizerEnabled = enabled)
            )
        }
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val profile = repository.getCurrentProfile()
                repository.setSpeakerVirtualizerEnabled(profile, enabled)
            } catch (e: Exception) {
                DolbyConstants.dlog(TAG, "Error setting speaker virtualizer: ${e.message}")
            }
        }
    }

    fun setStereoWidening(amount: Int) {
        val current = _uiState.value
        if (current is DolbyUiState.Success) {
            if (current.profileSettings.stereoWideningAmount == amount) return
            _uiState.value = current.copy(
                profileSettings = current.profileSettings.copy(stereoWideningAmount = amount)
            )
        }
        stereoWideningChannel.trySend(amount)
    }

    fun setDialogueEnhancer(enabled: Boolean) {
        val current = _uiState.value
        if (current is DolbyUiState.Success) {
            _uiState.value = current.copy(
                profileSettings = current.profileSettings.copy(dialogueEnhancerEnabled = enabled)
            )
        }
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val profile = repository.getCurrentProfile()
                repository.setDialogueEnhancerEnabled(profile, enabled)
            } catch (e: Exception) {
                DolbyConstants.dlog(TAG, "Error setting dialogue enhancer: ${e.message}")
            }
        }
    }

    fun setDialogueEnhancerAmount(amount: Int) {
        val current = _uiState.value
        if (current is DolbyUiState.Success) {
            if (current.profileSettings.dialogueEnhancerAmount == amount) return
            _uiState.value = current.copy(
                profileSettings = current.profileSettings.copy(dialogueEnhancerAmount = amount)
            )
        }
        dialogueEnhancerAmountChannel.trySend(amount)
    }

    fun resetAllProfiles() {
        viewModelScope.launch(Dispatchers.Default) {
            try {
                repository.resetAllProfiles()
                loadSettings()
            } catch (e: Exception) {
                DolbyConstants.dlog(TAG, "Error resetting profiles: ${e.message}")
            }
        }
    }

    fun updateSpeakerState() {
        if (!isCleared) {
            repository.updateSpeakerState()
        }
    }
    
    override fun onCleared() {
        DolbyConstants.dlog(TAG, "ViewModel onCleared")
        isCleared = true
        repository.flushPendingDeviceProfile()
        bassLevelChannel.close()
        midLevelChannel.close()
        trebleLevelChannel.close()
        stereoWideningChannel.close()
        dialogueEnhancerAmountChannel.close()
        viewModelScope.coroutineContext.cancelChildren()
        audioOutputStateJob?.cancel()
        audioOutputStateJob = null
        profileChangeJob?.cancel()
        profileChangeJob = null
        deviceProfileChangeJob?.cancel()
        deviceProfileChangeJob = null
        super.onCleared()
    }
    
    companion object {
        private const val TAG = "DolbyViewModel"
    }
}
