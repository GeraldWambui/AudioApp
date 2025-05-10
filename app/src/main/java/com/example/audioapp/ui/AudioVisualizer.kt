package com.example.audioapp.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.min

/**
 * Audio visualizer component that displays a waveform or spectrum of audio data
 */
class AudioVisualizerState {
    // Buffer for waveform display
    val waveformData = mutableStateOf(FloatArray(128) { 0f })
    
    // Buffer for spectrum display
    val spectrumData = mutableStateOf(FloatArray(64) { 0f })
    
    // Flag for voice detection
    val voiceDetected = mutableStateOf(false)
    
    // Estimated SNR value
    val snr = mutableStateOf(0f)
    
    /**
     * Update waveform data for visualization
     */
    fun updateWaveform(audioData: ShortArray, size: Int) {
        val samples = waveformData.value.size
        val data = FloatArray(samples)
        
        // Downsample the audio data to fit our visualization
        val step = size / samples
        for (i in 0 until samples) {
            val index = min((i * step), size - 1)
            if (index < size) {
                // Normalize to -1.0 to 1.0 range
                data[i] = audioData[index] / Short.MAX_VALUE.toFloat()
            }
        }
        
        waveformData.value = data
    }
    
    /**
     * Update spectrum data for visualization
     */
    fun updateSpectrum(spectrumMagnitudes: FloatArray) {
        val data = FloatArray(spectrumData.value.size)
        
        // Normalize and scale the spectrum data
        for (i in spectrumMagnitudes.indices) {
            if (i < data.size) {
                // Apply logarithmic scaling for better visualization
                data[i] = (1.0 + kotlin.math.log10(0.01 + spectrumMagnitudes[i].toDouble())).toFloat()
                // Clamp to 0-1 range
                data[i] = data[i].coerceIn(0f, 1f)
            }
        }
        
        spectrumData.value = data
    }
    
    /**
     * Update voice detection status
     */
    fun updateVoiceDetection(detected: Boolean) {
        voiceDetected.value = detected
    }
    
    /**
     * Update SNR value
     */
    fun updateSNR(snrValue: Float) {
        snr.value = snrValue
    }
}

/**
 * Waveform visualization of audio data
 */
@Composable
fun WaveformVisualizer(
    visualizerState: AudioVisualizerState,
    modifier: Modifier = Modifier
) {
    val waveform = visualizerState.waveformData.value
    val voiceDetected = visualizerState.voiceDetected.value
    
    // Color based on voice detection status
    val waveColor = if (voiceDetected) {
        Color(0xFF00C853) // Green when voice detected
    } else {
        Color(0xFF2196F3) // Blue otherwise
    }
    
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(100.dp)
            .padding(8.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF212121))
    ) {
        val width = size.width
        val height = size.height
        val middleY = height / 2
        
        // Draw centerline
        drawLine(
            color = Color.Gray.copy(alpha = 0.5f),
            start = Offset(0f, middleY),
            end = Offset(width, middleY),
            strokeWidth = 1f
        )
        
        // Draw waveform
        val path = Path()
        val stepX = width / waveform.size
        
        path.moveTo(0f, middleY)
        
        waveform.forEachIndexed { index, amplitude ->
            val x = index * stepX
            val y = middleY - (amplitude * height / 2)
            path.lineTo(x, y)
        }
        
        // Continue to the end
        path.lineTo(width, middleY)
        
        drawPath(
            path = path,
            color = waveColor,
            style = Stroke(width = 2f)
        )
    }
}

/**
 * Spectrum visualization of audio data
 */
@Composable
fun SpectrumVisualizer(
    visualizerState: AudioVisualizerState,
    modifier: Modifier = Modifier
) {
    val spectrum = visualizerState.spectrumData.value
    val voiceDetected = visualizerState.voiceDetected.value
    
    // Color based on voice detection status
    val barBaseColor = if (voiceDetected) {
        Color(0xFFFF9800) // Orange when voice detected
    } else {
        Color(0xFF9C27B0) // Purple otherwise
    }
    
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(100.dp)
            .padding(8.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF212121))
    ) {
        val width = size.width
        val height = size.height
        val barWidth = width / spectrum.size
        
        // Draw spectrum bars
        spectrum.forEachIndexed { index, magnitude ->
            val barHeight = magnitude * height
            val x = index * barWidth
            
            // Gradient color based on frequency (blue to red)
            val hue = 240f - (180f * index / spectrum.size)
            val barColor = barBaseColor.copy(alpha = 0.6f + 0.4f * magnitude)
            
            drawRect(
                color = barColor,
                topLeft = Offset(x, height - barHeight),
                size = androidx.compose.ui.geometry.Size(barWidth - 1f, barHeight)
            )
        }
    }
}

/**
 * Combined audio visualizer with both waveform and spectrum
 */
@Composable
fun AudioVisualizer(
    visualizerState: AudioVisualizerState,
    showSpectrum: Boolean = true,
    modifier: Modifier = Modifier
) {
    WaveformVisualizer(
        visualizerState = visualizerState,
        modifier = modifier
    )
    
    if (showSpectrum) {
        SpectrumVisualizer(
            visualizerState = visualizerState,
            modifier = modifier.padding(top = 8.dp)
        )
    }
} 