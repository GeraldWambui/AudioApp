package com.example.audioapp

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import com.example.audioapp.audio.BluetoothAudioManager
import com.example.audioapp.audio.FFTProcessor
import com.example.audioapp.audio.VoiceActivityDetector
import com.example.audioapp.ui.AudioVisualizerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.min

class AudioProcessor(private val context: Context) {
    companion object {
        private const val TAG = "AudioProcessor"
        
        // Audio configuration parameters
        private const val SAMPLE_RATE = 44100
        private const val CHANNEL_CONFIG_IN = AudioFormat.CHANNEL_IN_MONO
        private const val CHANNEL_CONFIG_OUT = AudioFormat.CHANNEL_OUT_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val BYTES_PER_SAMPLE = 2 // 16-bit audio = 2 bytes per sample
    }
    
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var bufferSize = 0
    private val isProcessing = AtomicBoolean(false)
    private val processingScope = CoroutineScope(Dispatchers.Default)
    private var processingJob: Job? = null
    
    // Adjustable noise reduction level (0-100)
    var noiseReductionLevel = 50
    
    // Processing flags and state
    private var useFFTProcessing = false
    private var useVoiceActivityDetection = false
    private var useLowLatencyMode = false
    
    // Advanced audio processing components
    private val fftProcessor = FFTProcessor()
    private val voiceDetector = VoiceActivityDetector()
    private val bluetoothManager = BluetoothAudioManager(context)
    
    // Visualizer state
    private val visualizerState = AudioVisualizerState()
    private var useVisualization = false
    private var visualizationUpdateCount = 0
    
    // Frame counter for visualization updates (don't need to update every frame)
    private var frameCounter = 0
    
    /**
     * Get the visualizer state for UI components
     */
    fun getVisualizerState(): AudioVisualizerState {
        return visualizerState
    }
    
    /**
     * Initialize audio capture and playback components
     */
    fun initialize(): Boolean {
        try {
            // Calculate minimum buffer size for AudioRecord
            val minBufferSizeRecord = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                CHANNEL_CONFIG_IN,
                AUDIO_FORMAT
            )
            
            // Calculate minimum buffer size for AudioTrack
            val minBufferSizeTrack = AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                CHANNEL_CONFIG_OUT,
                AUDIO_FORMAT
            )
            
            // Use a buffer size that's a power of 2 and larger than minimum required
            bufferSize = maxOf(minBufferSizeRecord, minBufferSizeTrack) * 2
            
            // Initialize AudioRecord (for capturing audio)
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG_IN,
                AUDIO_FORMAT,
                bufferSize
            )
            
            // Initialize AudioTrack (for playing audio)
            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setEncoding(AUDIO_FORMAT)
                        .setChannelMask(CHANNEL_CONFIG_OUT)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            
            // Initialize advanced processing components
            fftProcessor.initialize()
            voiceDetector.initialize(context, false) // Don't use TFLite model by default
            bluetoothManager.initialize()
            
            return audioRecord?.state == AudioRecord.STATE_INITIALIZED &&
                    audioTrack?.state == AudioTrack.STATE_INITIALIZED
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing audio components: ${e.message}")
            release()
            return false
        }
    }
    
    /**
     * Start audio processing - capture, process, and playback
     */
    fun start() {
        if (isProcessing.get()) return
        if (audioRecord == null || audioTrack == null) {
            if (!initialize()) {
                Log.e(TAG, "Failed to initialize audio components")
                return
            }
        }
        
        try {
            // Apply low-latency mode settings if enabled
            if (useLowLatencyMode) {
                bluetoothManager.setLowLatencyMode(true)
            }
            
            audioRecord?.startRecording()
            audioTrack?.play()
            isProcessing.set(true)
            
            processingJob = processingScope.launch {
                processAudio()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting audio processing: ${e.message}")
            stop()
        }
    }
    
    /**
     * Stop audio processing and release resources
     */
    fun stop() {
        isProcessing.set(false)
        processingJob?.cancel()
        
        try {
            audioRecord?.stop()
            audioTrack?.stop()
            
            // Disable low-latency mode
            bluetoothManager.setLowLatencyMode(false)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping audio components: ${e.message}")
        }
    }
    
    /**
     * Release audio resources
     */
    fun release() {
        stop()
        
        try {
            audioRecord?.release()
            audioTrack?.release()
            audioRecord = null
            audioTrack = null
            
            // Clean up advanced processing components
            voiceDetector.release()
            bluetoothManager.release()
            
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing audio components: ${e.message}")
        }
        
        processingScope.cancel()
    }
    
    /**
     * Main audio processing loop
     */
    private suspend fun processAudio() = withContext(Dispatchers.IO) {
        val buffer = ShortArray(bufferSize / BYTES_PER_SAMPLE)
        
        while (isProcessing.get()) {
            val readResult = audioRecord?.read(buffer, 0, buffer.size) ?: 0
            
            if (readResult > 0) {
                var processedBuffer = buffer
                
                // Update visualization if enabled (not every frame, to reduce CPU usage)
                if (useVisualization && frameCounter % 3 == 0) {
                    updateVisualization(buffer, readResult)
                }
                
                // Voice Activity Detection
                var voiceDetected = false
                if (useVoiceActivityDetection) {
                    voiceDetected = voiceDetector.detectVoice(buffer, readResult, noiseReductionLevel)
                    visualizerState.updateVoiceDetection(voiceDetected)
                    
                    // Optionally get SNR for visualization
                    val snr = voiceDetector.getEstimatedSNR(buffer, readResult)
                    visualizerState.updateSNR(snr)
                    
                    // If no voice detected and we're in VAD mode, we could optionally
                    // silence the output or reduce gain significantly
                    if (!voiceDetected && useVoiceActivityDetection) {
                        // Apply stronger noise reduction for non-voice segments
                        applyNoiseReduction(processedBuffer, readResult, 80)
                    }
                }
                
                // Apply either FFT-based or simple noise reduction
                processedBuffer = if (useFFTProcessing) {
                    fftProcessor.process(buffer, readResult, noiseReductionLevel)
                } else {
                    // Apply simple amplitude-based noise reduction
                    applyNoiseReduction(buffer, readResult, noiseReductionLevel)
                    buffer
                }
                
                // Play processed audio
                audioTrack?.write(processedBuffer, 0, readResult)
                
                frameCounter++
            }
        }
    }
    
    /**
     * Apply simple noise reduction to audio data
     */
    private fun applyNoiseReduction(buffer: ShortArray, size: Int, level: Int): ShortArray {
        // Simple noise gate based on amplitude threshold
        // Adjust threshold based on noiseReductionLevel (0-100)
        val threshold = (Short.MAX_VALUE * (level / 200.0)).toInt()
        
        for (i in 0 until size) {
            // For very simple noise reduction, we can set small amplitude signals to zero
            if (abs(buffer[i].toInt()) < threshold) {
                buffer[i] = 0
            }
        }
        
        return buffer
    }
    
    /**
     * Update the visualizer with current audio data
     */
    private fun updateVisualization(buffer: ShortArray, size: Int) {
        visualizerState.updateWaveform(buffer, size)
        
        // Update spectrum visualization if using FFT processing
        if (useFFTProcessing) {
            // For simplicity, we'll just use a portion of the buffer to create a dummy spectrum
            // In a real implementation, this would use the actual FFT results
            val dummySpectrum = FloatArray(64)
            val step = size / dummySpectrum.size
            
            for (i in dummySpectrum.indices) {
                val sum = (0 until min(step, size - i * step)).sumOf { 
                    abs(buffer[i * step + it].toInt()).toDouble() 
                }
                dummySpectrum[i] = (sum / (step * Short.MAX_VALUE)).toFloat()
            }
            
            visualizerState.updateSpectrum(dummySpectrum)
        }
    }
    
    /**
     * Enable/disable FFT-based noise reduction
     */
    fun setUseFFTProcessing(enabled: Boolean) {
        useFFTProcessing = enabled
        
        // If enabling FFT, we need to make sure it's initialized
        if (enabled && isProcessing.get()) {
            fftProcessor.initialize()
        }
    }
    
    /**
     * Enable/disable Voice Activity Detection
     */
    fun setUseVoiceActivityDetection(enabled: Boolean) {
        useVoiceActivityDetection = enabled
        
        // Reset the detector state when enabling
        if (enabled) {
            voiceDetector.reset()
        }
    }
    
    /**
     * Enable/disable low latency mode for Bluetooth
     */
    fun setUseLowLatencyMode(enabled: Boolean) {
        useLowLatencyMode = enabled
        
        // Apply immediately if we're already processing
        if (isProcessing.get()) {
            bluetoothManager.setLowLatencyMode(enabled)
        }
    }
    
    /**
     * Enable/disable audio visualization
     */
    fun setUseVisualization(enabled: Boolean) {
        useVisualization = enabled
    }
    
    /**
     * Check if a Bluetooth headset is connected
     */
    fun isBluetoothHeadsetConnected(): Boolean {
        return bluetoothManager.isBluetoothHeadsetConnected()
    }
    
    /**
     * Get the connected Bluetooth device name
     */
    fun getConnectedBluetoothDeviceName(): String? {
        return bluetoothManager.getConnectedDeviceName()
    }
    
    /**
     * Set a callback for Bluetooth connection state changes
     */
    fun setBluetoothStateChangeCallback(callback: (Boolean) -> Unit) {
        bluetoothManager.setStateChangeCallback(callback)
    }
} 