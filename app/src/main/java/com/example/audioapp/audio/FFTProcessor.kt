package com.example.audioapp.audio

import android.util.Log
import com.github.psambit9791.jdsp.signal.Convolution
import com.github.psambit9791.jdsp.transform.FastFourier
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * FFT-based audio processor for frequency domain noise reduction
 */
class FFTProcessor {
    companion object {
        private const val TAG = "FFTProcessor"
        
        // FFT parameters - must be a power of 2
        private const val FFT_SIZE = 1024
        
        // Spectral noise floor estimation parameters
        private const val SPECTRAL_FLOOR_ALPHA = 0.95
        private const val NOISE_REDUCTION_FACTOR = 0.6
    }
    
    // Estimated noise floor spectrum
    private var noiseFloor: DoubleArray? = null
    
    // Processing flags
    private var isInitialized = false
    
    // Buffer for overlap-add processing (to avoid discontinuities)
    private val overlapBuffer = DoubleArray(FFT_SIZE / 2)
    
    /**
     * Initialize the FFT processor
     */
    fun initialize() {
        noiseFloor = DoubleArray(FFT_SIZE / 2 + 1) { 0.0 }
        isInitialized = true
        Log.d(TAG, "FFT processor initialized with size $FFT_SIZE")
    }
    
    /**
     * Process audio data using FFT-based spectral subtraction
     * 
     * @param input Short array of audio samples
     * @param size Number of samples to process
     * @param noiseReductionLevel Strength of noise reduction (0-100)
     * @param calibrationMode If true, updates noise floor estimate without processing
     * @return Processed audio samples
     */
    fun process(input: ShortArray, size: Int, noiseReductionLevel: Int, calibrationMode: Boolean = false): ShortArray {
        if (!isInitialized) {
            initialize()
        }
        
        // Ensure proper size for FFT (must be power of 2)
        if (size < FFT_SIZE) {
            return input // Not enough data, return original
        }
        
        // Convert input to double array for FFT processing
        val inputDouble = DoubleArray(FFT_SIZE) { i -> 
            if (i < size) input[i].toDouble() else 0.0
        }
        
        // Apply Hanning window to reduce spectral leakage
        applyWindow(inputDouble)
        
        // Perform FFT to get frequency spectrum
        val fft = FastFourier(inputDouble)
        fft.transform()
        
        // Get magnitude and phase from complex FFT output
        val spectrum = fft.getMagnitude()
        val phase = fft.getPhase()
        
        if (calibrationMode) {
            // Update noise floor estimation during calibration
            updateNoiseFloor(spectrum)
            return input // Return original audio during calibration
        }
        
        // Spectral subtraction for noise reduction
        val processedSpectrum = applySpectralSubtraction(spectrum, phase, noiseReductionLevel)
        
        // Convert back to time domain with inverse FFT
        val processedAudio = performInverseFFT(processedSpectrum, phase)
        
        // Apply overlap-add to avoid discontinuities
        val result = applyOverlapAdd(processedAudio)
        
        // Convert back to short array for audio output
        return convertToShortArray(result, size)
    }
    
    /**
     * Apply window function to input signal
     */
    private fun applyWindow(signal: DoubleArray) {
        // Hanning window
        for (i in signal.indices) {
            val window = 0.5 * (1 - kotlin.math.cos(2 * Math.PI * i / (signal.size - 1)))
            signal[i] *= window
        }
    }
    
    /**
     * Update the estimated noise floor
     */
    private fun updateNoiseFloor(spectrum: DoubleArray) {
        noiseFloor?.let { floor ->
            for (i in floor.indices) {
                if (i < spectrum.size) {
                    // Exponential averaging for noise floor estimation
                    floor[i] = SPECTRAL_FLOOR_ALPHA * floor[i] + (1 - SPECTRAL_FLOOR_ALPHA) * spectrum[i]
                }
            }
        }
    }
    
    /**
     * Apply spectral subtraction for noise reduction
     */
    private fun applySpectralSubtraction(
        magnitude: DoubleArray,
        phase: DoubleArray,
        noiseReductionLevel: Int
    ): Array<Complex> {
        val scaleFactor = noiseReductionLevel / 100.0 * NOISE_REDUCTION_FACTOR
        val result = Array(FFT_SIZE) { Complex(0.0, 0.0) }
        
        noiseFloor?.let { floor ->
            for (i in 0 until magnitude.size) {
                // Spectral subtraction with adjustable noise reduction
                var newMagnitude = magnitude[i] - floor[i] * scaleFactor
                
                // Apply spectral floor to avoid negative values
                newMagnitude = maxOf(newMagnitude, magnitude[i] * 0.05)
                
                // Convert back to complex form using the original phase
                if (i < phase.size) {
                    result[i] = Complex(
                        newMagnitude * kotlin.math.cos(phase[i]),
                        newMagnitude * kotlin.math.sin(phase[i])
                    )
                }
            }
        }
        
        return result
    }
    
    /**
     * Perform inverse FFT to convert back to time domain
     */
    private fun performInverseFFT(spectrum: Array<Complex>, phase: DoubleArray): DoubleArray {
        // Create complex input for inverse FFT
        val complexInput = DoubleArray(FFT_SIZE * 2)
        
        // Populate real and imaginary parts
        for (i in 0 until FFT_SIZE / 2 + 1) {
            complexInput[2 * i] = spectrum[i].real
            complexInput[2 * i + 1] = spectrum[i].imag
        }
        
        // Mirror for the inverse FFT (complex conjugate)
        for (i in 1 until FFT_SIZE / 2) {
            complexInput[2 * (FFT_SIZE - i)] = spectrum[i].real
            complexInput[2 * (FFT_SIZE - i) + 1] = -spectrum[i].imag
        }
        
        // Perform inverse FFT
        val ifft = FastFourier(complexInput, true)
        ifft.transform()
        
        // Extract real part for audio output
        val result = DoubleArray(FFT_SIZE)
        for (i in 0 until FFT_SIZE) {
            result[i] = ifft.getReal()[i] / FFT_SIZE
        }
        
        return result
    }
    
    /**
     * Apply overlap-add method to avoid discontinuities
     */
    private fun applyOverlapAdd(processedAudio: DoubleArray): DoubleArray {
        val result = DoubleArray(processedAudio.size)
        
        // Apply overlap-add with previous frame
        for (i in 0 until FFT_SIZE / 2) {
            result[i] = processedAudio[i] + overlapBuffer[i]
        }
        
        // Copy the rest of the frame
        for (i in FFT_SIZE / 2 until FFT_SIZE) {
            result[i] = processedAudio[i]
        }
        
        // Save the overlap for the next frame
        for (i in 0 until FFT_SIZE / 2) {
            overlapBuffer[i] = processedAudio[i + FFT_SIZE / 2]
        }
        
        return result
    }
    
    /**
     * Convert double array to short array for audio output
     */
    private fun convertToShortArray(input: DoubleArray, size: Int): ShortArray {
        val result = ShortArray(size)
        
        for (i in 0 until minOf(size, input.size)) {
            // Apply soft clipping to avoid hard clipping artifacts
            var sample = input[i]
            
            // Soft clipping
            if (abs(sample) > 0.9) {
                val sign = if (sample > 0) 1.0 else -1.0
                sample = sign * (0.9 + 0.1 * kotlin.math.tanh((abs(sample) - 0.9) / 0.1))
            }
            
            // Convert to short with bounds checking
            result[i] = (sample * Short.MAX_VALUE).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
        }
        
        return result
    }
    
    /**
     * Helper class for complex number operations
     */
    data class Complex(val real: Double, val imag: Double) {
        fun magnitude(): Double = sqrt(real.pow(2) + imag.pow(2))
        fun phase(): Double = kotlin.math.atan2(imag, real)
    }
}