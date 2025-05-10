package com.example.audioapp.audio

import android.content.Context
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max

/**
 * Voice Activity Detector (VAD) for detecting human speech in audio
 * This implementation uses both a simple energy-based approach and an optional
 * TensorFlow Lite model for more accurate detection.
 */
class VoiceActivityDetector {
    companion object {
        private const val TAG = "VoiceActivityDetector"
        
        // Energy thresholds for VAD
        private const val ENERGY_THRESHOLD = 0.1
        private const val ZCR_THRESHOLD = 20
        
        // Signal analysis parameters
        private const val FRAME_SIZE = 160  // 10ms at 16kHz
        private const val ENERGY_SMOOTHING = 0.8
    }
    
    // State variables
    private var lastEnergy = 0.0
    private var lastZeroCrossings = 0
    private var voiceDetected = false
    private var voiceDetectionCount = 0
    private var silenceCount = 0
    
    // TensorFlow Lite interpreter
    private var tfLiteInterpreter: Interpreter? = null
    private var useTFLiteModel = false
    
    /**
     * Initialize with optional TensorFlow Lite model for better accuracy
     */
    fun initialize(context: Context, useTFLite: Boolean) {
        useTFLiteModel = useTFLite
        
        if (useTFLite) {
            try {
                val tfliteModel = loadModelFile(context, "vad_model.tflite")
                val tfliteOptions = Interpreter.Options()
                tfLiteInterpreter = Interpreter(tfliteModel, tfliteOptions)
                Log.d(TAG, "TensorFlow Lite model loaded successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Error loading TF Lite model: ${e.message}")
                useTFLiteModel = false
            }
        }
    }
    
    /**
     * Detect voice activity in the given audio buffer
     * 
     * @param buffer Audio data buffer
     * @param size Size of valid data in buffer
     * @param sensitivity VAD sensitivity (0-100)
     * @return true if voice is detected, false otherwise
     */
    fun detectVoice(buffer: ShortArray, size: Int, sensitivity: Int): Boolean {
        if (useTFLiteModel && tfLiteInterpreter != null) {
            return detectVoiceTFLite(buffer, size)
        } else {
            return detectVoiceEnergy(buffer, size, sensitivity)
        }
    }
    
    /**
     * Detect voice using energy and zero-crossing rate
     */
    private fun detectVoiceEnergy(buffer: ShortArray, size: Int, sensitivity: Int): Boolean {
        if (size < FRAME_SIZE) return voiceDetected
        
        // Apply sensitivity adjustment
        val adjustedThreshold = ENERGY_THRESHOLD * (1.0 - sensitivity / 200.0)
        
        // Calculate signal energy (in dB) and zero-crossing rate
        var energy = 0.0
        var zeroCrossings = 0
        
        for (i in 0 until minOf(size, FRAME_SIZE)) {
            val sample = buffer[i].toDouble() / Short.MAX_VALUE
            energy += sample * sample
            
            if (i > 0 && ((buffer[i] >= 0 && buffer[i-1] < 0) || 
                          (buffer[i] < 0 && buffer[i-1] >= 0))) {
                zeroCrossings++
            }
        }
        
        // Average energy and convert to dB
        energy /= FRAME_SIZE
        energy = if (energy > 0) 10 * log10(energy) else -100.0
        
        // Smooth the energy to avoid rapid fluctuations
        energy = ENERGY_SMOOTHING * lastEnergy + (1 - ENERGY_SMOOTHING) * energy
        lastEnergy = energy
        
        // Update zero crossing count
        zeroCrossings = (ENERGY_SMOOTHING * lastZeroCrossings + 
                        (1 - ENERGY_SMOOTHING) * zeroCrossings).toInt()
        lastZeroCrossings = zeroCrossings
        
        // Decision logic: combine energy and zero-crossing rate
        val isEnergyHigh = energy > adjustedThreshold
        val isZcrInSpeechRange = zeroCrossings > ZCR_THRESHOLD * (1 + sensitivity / 100.0)
        
        // Hysteresis for voice activity detection
        if (isEnergyHigh && isZcrInSpeechRange) {
            voiceDetectionCount++
            silenceCount = maxOf(0, silenceCount - 1)
        } else {
            voiceDetectionCount = maxOf(0, voiceDetectionCount - 1)
            silenceCount++
        }
        
        // State transitions with hysteresis to avoid rapid switches
        if (!voiceDetected && voiceDetectionCount > 3) {
            voiceDetected = true
        } else if (voiceDetected && silenceCount > 10) {
            voiceDetected = false
        }
        
        return voiceDetected
    }
    
    /**
     * Detect voice using TensorFlow Lite model
     */
    private fun detectVoiceTFLite(buffer: ShortArray, size: Int): Boolean {
        val interpreter = tfLiteInterpreter ?: return detectVoiceEnergy(buffer, size, 50)
        
        try {
            // Prepare input: convert audio buffer to model input format
            val inputBuffer = ByteBuffer.allocateDirect(FRAME_SIZE * 2)
                .order(ByteOrder.nativeOrder())
            
            for (i in 0 until minOf(size, FRAME_SIZE)) {
                inputBuffer.putShort(buffer[i])
            }
            
            // Zero-pad if needed
            for (i in size until FRAME_SIZE) {
                inputBuffer.putShort(0)
            }
            
            inputBuffer.rewind()
            
            // Prepare output buffer
            val outputBuffer = Array(1) { FloatArray(1) }
            
            // Run inference
            interpreter.run(inputBuffer, outputBuffer)
            
            // Get prediction (0-1 score where higher means speech detected)
            val speechProbability = outputBuffer[0][0]
            
            // Update state with hysteresis
            if (speechProbability > 0.6) {
                voiceDetectionCount++
                silenceCount = maxOf(0, silenceCount - 1)
            } else if (speechProbability < 0.4) {
                voiceDetectionCount = maxOf(0, voiceDetectionCount - 1)
                silenceCount++
            }
            
            // State transitions with hysteresis
            if (!voiceDetected && voiceDetectionCount > 3) {
                voiceDetected = true
            } else if (voiceDetected && silenceCount > 10) {
                voiceDetected = false
            }
            
            return voiceDetected
        } catch (e: Exception) {
            Log.e(TAG, "Error during TF Lite inference: ${e.message}")
            return detectVoiceEnergy(buffer, size, 50)
        }
    }
    
    /**
     * Get estimated SNR (Signal-to-Noise Ratio) of the audio
     */
    fun getEstimatedSNR(buffer: ShortArray, size: Int): Float {
        if (size < FRAME_SIZE) return 0f
        
        var signalPower = 0.0
        var noisePower = 0.0
        
        for (i in 0 until minOf(size, FRAME_SIZE)) {
            val sample = buffer[i].toDouble() / Short.MAX_VALUE
            signalPower += sample * sample
        }
        
        signalPower /= FRAME_SIZE
        
        // Estimate noise as the minimum power in recent history
        if (lastEnergy < 0) {
            // Convert from dB back to power
            noisePower = Math.pow(10.0, lastEnergy / 10.0)
        } else {
            noisePower = 0.0001 // Default low noise floor
        }
        
        // Calculate SNR in dB
        val snr = if (noisePower > 0 && signalPower > noisePower) {
            10 * log10(signalPower / noisePower)
        } else {
            0.0
        }
        
        return snr.toFloat()
    }
    
    /**
     * Reset the detector state
     */
    fun reset() {
        lastEnergy = 0.0
        lastZeroCrossings = 0
        voiceDetected = false
        voiceDetectionCount = 0
        silenceCount = 0
    }
    
    /**
     * Clean up resources
     */
    fun release() {
        tfLiteInterpreter?.close()
        tfLiteInterpreter = null
    }
    
    /**
     * Load TensorFlow Lite model from assets
     */
    @Throws(IOException::class)
    private fun loadModelFile(context: Context, modelFile: String): MappedByteBuffer {
        val fileDescriptor = context.assets.openFd(modelFile)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }
} 