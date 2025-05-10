package com.example.audioapp

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.audioapp.ui.AudioVisualizerState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AudioViewModel : ViewModel() {
    companion object {
        private const val TAG = "AudioViewModel"
    }
    
    // State for the UI
    private val _uiState = MutableStateFlow(AudioUiState())
    val uiState: StateFlow<AudioUiState> = _uiState.asStateFlow()
    
    // Service related fields
    private var audioService: AudioProcessingService? = null
    private var bound = false
    
    // Service connection
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as? AudioProcessingService.LocalBinder
            audioService = localBinder?.getService()
            bound = true
            
            // Update UI state to reflect service status
            if (_uiState.value.isProcessing) {
                audioService?.startAudioProcessing(_uiState.value.noiseReductionLevel)
                
                // Apply advanced settings
                updateAdvancedProcessingSettings()
            }
        }
        
        override fun onServiceDisconnected(name: ComponentName?) {
            audioService = null
            bound = false
        }
    }
    
    /**
     * Bind to the audio processing service
     */
    fun bindService(context: Context) {
        Intent(context, AudioProcessingService::class.java).also { intent ->
            context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }
    
    /**
     * Unbind from the audio processing service
     */
    fun unbindService(context: Context) {
        if (bound) {
            context.unbindService(serviceConnection)
            bound = false
        }
    }
    
    /**
     * Start audio processing
     */
    fun startAudioProcessing(context: Context) {
        viewModelScope.launch {
            try {
                // Update UI state
                _uiState.value = _uiState.value.copy(isProcessing = true)
                
                // Start service in foreground
                Intent(context, AudioProcessingService::class.java).also { intent ->
                    intent.action = AudioProcessingService.ACTION_START_SERVICE
                    context.startForegroundService(intent)
                }
                
                // Start processing via bound service if available
                audioService?.startAudioProcessing(_uiState.value.noiseReductionLevel)
                
                // Apply advanced settings
                updateAdvancedProcessingSettings()
            } catch (e: Exception) {
                Log.e(TAG, "Error starting audio processing: ${e.message}")
                _uiState.value = _uiState.value.copy(isProcessing = false, error = e.message)
            }
        }
    }
    
    /**
     * Stop audio processing
     */
    fun stopAudioProcessing(context: Context) {
        viewModelScope.launch {
            try {
                // Update UI state
                _uiState.value = _uiState.value.copy(isProcessing = false)
                
                // Stop processing via bound service if available
                audioService?.stopAudioProcessing()
                
                // Stop the foreground service
                Intent(context, AudioProcessingService::class.java).also { intent ->
                    intent.action = AudioProcessingService.ACTION_STOP_SERVICE
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping audio processing: ${e.message}")
                _uiState.value = _uiState.value.copy(error = e.message)
            }
        }
    }
    
    /**
     * Update noise reduction level
     */
    fun updateNoiseReductionLevel(level: Int) {
        _uiState.value = _uiState.value.copy(noiseReductionLevel = level)
        audioService?.updateNoiseReductionLevel(level)
    }
    
    /**
     * Toggle FFT noise reduction
     */
    fun toggleFFTProcessing(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(useFFTProcessing = enabled)
        updateAdvancedProcessingSettings()
    }
    
    /**
     * Toggle Voice Activity Detection
     */
    fun toggleVoiceActivityDetection(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(useVoiceActivityDetection = enabled)
        updateAdvancedProcessingSettings()
    }
    
    /**
     * Toggle low latency mode
     */
    fun toggleLowLatencyMode(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(useLowLatencyMode = enabled)
        updateAdvancedProcessingSettings()
    }
    
    /**
     * Toggle visualization
     */
    fun toggleVisualization(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(useVisualization = enabled)
        updateAdvancedProcessingSettings()
    }
    
    /**
     * Update advanced processing settings in the service
     */
    private fun updateAdvancedProcessingSettings() {
        val state = _uiState.value
        audioService?.configureAdvancedProcessing(
            fftEnabled = state.useFFTProcessing,
            vadEnabled = state.useVoiceActivityDetection,
            lowLatencyEnabled = state.useLowLatencyMode,
            visualizationEnabled = state.useVisualization
        )
    }
    
    /**
     * Get the audio visualizer state for UI updates
     */
    fun getVisualizerState(): AudioVisualizerState? {
        return audioService?.getVisualizerState()
    }
    
    /**
     * Clear any error message
     */
    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
    
    /**
     * Clean up resources when ViewModel is cleared
     */
    override fun onCleared() {
        super.onCleared()
        audioService = null
    }
}

/**
 * UI state for the audio app
 */
data class AudioUiState(
    val isProcessing: Boolean = false,
    val noiseReductionLevel: Int = 50,
    val useFFTProcessing: Boolean = false,
    val useVoiceActivityDetection: Boolean = false,
    val useLowLatencyMode: Boolean = false,
    val useVisualization: Boolean = true,
    val error: String? = null
) 