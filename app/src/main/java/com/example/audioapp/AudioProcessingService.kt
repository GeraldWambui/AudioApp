package com.example.audioapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

class AudioProcessingService : Service() {
    companion object {
        private const val TAG = "AudioProcessingService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "audio_processing_channel"
        
        // Intent actions
        const val ACTION_START_SERVICE = "com.example.audioapp.START_SERVICE"
        const val ACTION_STOP_SERVICE = "com.example.audioapp.STOP_SERVICE"
    }
    
    private val binder = LocalBinder()
    private lateinit var audioProcessor: AudioProcessor
    private var isRunning = false
    
    // Advanced processing settings
    private var useFFTProcessing = false
    private var useVoiceActivityDetection = false
    private var useLowLatencyMode = false
    private var useVisualization = true
    
    inner class LocalBinder : Binder() {
        fun getService(): AudioProcessingService = this@AudioProcessingService
    }
    
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        audioProcessor = AudioProcessor(this)
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_SERVICE -> startAudioProcessing()
            ACTION_STOP_SERVICE -> stopSelf()
        }
        
        return START_NOT_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder {
        return binder
    }
    
    override fun onDestroy() {
        stopAudioProcessing()
        super.onDestroy()
    }
    
    /**
     * Start audio processing and create foreground service notification
     */
    fun startAudioProcessing(noiseReductionLevel: Int = 50) {
        if (isRunning) return
        
        try {
            // Configure the audio processor
            audioProcessor.noiseReductionLevel = noiseReductionLevel
            
            // Apply advanced processing settings
            audioProcessor.setUseFFTProcessing(useFFTProcessing)
            audioProcessor.setUseVoiceActivityDetection(useVoiceActivityDetection)
            audioProcessor.setUseLowLatencyMode(useLowLatencyMode)
            audioProcessor.setUseVisualization(useVisualization)
            
            // Setup Bluetooth callback for notification updates
            audioProcessor.setBluetoothStateChangeCallback { isConnected ->
                if (isRunning) {
                    // Update notification when Bluetooth state changes
                    updateNotification(isConnected)
                }
            }
            
            // Initialize and start processing
            if (audioProcessor.initialize()) {
                audioProcessor.start()
                isRunning = true
                
                // Start as foreground service with notification
                startForeground(NOTIFICATION_ID, createNotification(audioProcessor.isBluetoothHeadsetConnected()))
                Log.d(TAG, "Audio processing started in foreground service")
            } else {
                Log.e(TAG, "Failed to initialize audio processor")
                stopSelf()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting audio processing: ${e.message}")
            stopSelf()
        }
    }
    
    /**
     * Stop audio processing
     */
    fun stopAudioProcessing() {
        if (!isRunning) return
        
        try {
            audioProcessor.stop()
            audioProcessor.release()
            isRunning = false
            Log.d(TAG, "Audio processing stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping audio processing: ${e.message}")
        }
    }
    
    /**
     * Update noise reduction level
     */
    fun updateNoiseReductionLevel(level: Int) {
        audioProcessor.noiseReductionLevel = level
    }
    
    /**
     * Configure advanced processing options
     */
    fun configureAdvancedProcessing(
        fftEnabled: Boolean,
        vadEnabled: Boolean,
        lowLatencyEnabled: Boolean,
        visualizationEnabled: Boolean
    ) {
        useFFTProcessing = fftEnabled
        useVoiceActivityDetection = vadEnabled
        useLowLatencyMode = lowLatencyEnabled
        useVisualization = visualizationEnabled
        
        // Apply settings if already running
        if (isRunning) {
            audioProcessor.setUseFFTProcessing(fftEnabled)
            audioProcessor.setUseVoiceActivityDetection(vadEnabled)
            audioProcessor.setUseLowLatencyMode(lowLatencyEnabled)
            audioProcessor.setUseVisualization(visualizationEnabled)
            
            // Update notification to reflect new settings
            updateNotification(audioProcessor.isBluetoothHeadsetConnected())
        }
    }
    
    /**
     * Get the audio visualizer state for UI updates
     */
    fun getVisualizerState() = audioProcessor.getVisualizerState()
    
    /**
     * Create notification channel for foreground service
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Audio Processing",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Audio processing service notification channel"
                setShowBadge(false)
            }
            
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    /**
     * Create notification for foreground service
     */
    private fun createNotification(isBluetoothConnected: Boolean = false): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Audio Processing Active")
            .setContentText(buildNotificationText(isBluetoothConnected))
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
        
        return builder.build()
    }
    
    /**
     * Update notification with current settings
     */
    private fun updateNotification(isBluetoothConnected: Boolean) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, createNotification(isBluetoothConnected))
    }
    
    /**
     * Build notification text based on active features
     */
    private fun buildNotificationText(isBluetoothConnected: Boolean): String {
        val sb = StringBuilder("Processing audio")
        
        // List active features
        val activeFeatures = mutableListOf<String>()
        if (useFFTProcessing) activeFeatures.add("FFT")
        if (useVoiceActivityDetection) activeFeatures.add("VAD")
        if (useLowLatencyMode) activeFeatures.add("Low Latency")
        
        if (activeFeatures.isNotEmpty()) {
            sb.append(" with: ${activeFeatures.joinToString(", ")}")
        }
        
        // Add Bluetooth info if connected
        if (isBluetoothConnected) {
            val deviceName = audioProcessor.getConnectedBluetoothDeviceName()
            sb.append(" | BT: $deviceName")
        }
        
        return sb.toString()
    }
} 