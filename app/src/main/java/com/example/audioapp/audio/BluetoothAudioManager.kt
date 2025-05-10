package com.example.audioapp.audio

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.BroadcastReceiver
import android.media.AudioManager
import android.util.Log

/**
 * Bluetooth audio manager that optimizes audio for headset connections
 * and handles Bluetooth device connection/disconnection
 */
class BluetoothAudioManager(private val context: Context) {
    companion object {
        private const val TAG = "BluetoothAudioManager"
    }
    
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothHeadset: BluetoothHeadset? = null
    private var audioManager: AudioManager? = null
    private var isBluetoothConnected = false
    private var isBluetoothScoOn = false
    private var lowLatencyMode = false
    
    // Callback for notifying state changes
    private var stateChangeCallback: ((Boolean) -> Unit)? = null
    
    // Bluetooth headset connection listener
    private val bluetoothHeadsetListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile == BluetoothProfile.HEADSET) {
                bluetoothHeadset = proxy as BluetoothHeadset
                checkConnectedDevices()
            }
        }
        
        override fun onServiceDisconnected(profile: Int) {
            if (profile == BluetoothProfile.HEADSET) {
                bluetoothHeadset = null
                isBluetoothConnected = false
                stateChangeCallback?.invoke(false)
            }
        }
    }
    
    // Broadcast receiver for Bluetooth state changes
    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action
            
            when (action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(
                        BluetoothAdapter.EXTRA_STATE,
                        BluetoothAdapter.ERROR
                    )
                    
                    when (state) {
                        BluetoothAdapter.STATE_ON -> initialize()
                        BluetoothAdapter.STATE_OFF -> {
                            isBluetoothConnected = false
                            stateChangeCallback?.invoke(false)
                        }
                    }
                }
                
                BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(
                        BluetoothProfile.EXTRA_STATE,
                        BluetoothProfile.STATE_DISCONNECTED
                    )
                    
                    if (state == BluetoothProfile.STATE_CONNECTED) {
                        isBluetoothConnected = true
                        stateChangeCallback?.invoke(true)
                        
                        // Apply optimizations if in low latency mode
                        if (lowLatencyMode) {
                            applyBluetoothOptimizations()
                        }
                    } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                        isBluetoothConnected = false
                        stateChangeCallback?.invoke(false)
                        
                        // Stop SCO if active
                        stopBluetoothSco()
                    }
                }
                
                BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED -> {
                    val state = intent.getIntExtra(
                        BluetoothHeadset.EXTRA_STATE,
                        BluetoothHeadset.STATE_AUDIO_DISCONNECTED
                    )
                    
                    when (state) {
                        BluetoothHeadset.STATE_AUDIO_CONNECTED -> {
                            isBluetoothScoOn = true
                            Log.d(TAG, "Bluetooth SCO connected")
                        }
                        BluetoothHeadset.STATE_AUDIO_DISCONNECTED -> {
                            isBluetoothScoOn = false
                            Log.d(TAG, "Bluetooth SCO disconnected")
                        }
                    }
                }
            }
        }
    }
    
    /**
     * Initialize the Bluetooth audio manager
     */
    @SuppressLint("MissingPermission")
    fun initialize() {
        try {
            audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
            
            // Register for Bluetooth headset profile
            bluetoothAdapter?.getProfileProxy(
                context,
                bluetoothHeadsetListener,
                BluetoothProfile.HEADSET
            )
            
            // Register receiver for Bluetooth state changes
            val filter = IntentFilter().apply {
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
                addAction(BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED)
            }
            
            context.registerReceiver(bluetoothStateReceiver, filter)
            
            // Check for already connected devices
            checkConnectedDevices()
            
            Log.d(TAG, "Bluetooth audio manager initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing Bluetooth audio manager: ${e.message}")
        }
    }
    
    /**
     * Check for already connected Bluetooth headset devices
     */
    @SuppressLint("MissingPermission")
    private fun checkConnectedDevices() {
        try {
            val connectedDevices = bluetoothHeadset?.connectedDevices
            isBluetoothConnected = !connectedDevices.isNullOrEmpty()
            
            if (isBluetoothConnected) {
                Log.d(TAG, "Bluetooth headset already connected: ${connectedDevices?.firstOrNull()?.name}")
                stateChangeCallback?.invoke(true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking connected devices: ${e.message}")
        }
    }
    
    /**
     * Set callback for Bluetooth connection state changes
     */
    fun setStateChangeCallback(callback: (Boolean) -> Unit) {
        stateChangeCallback = callback
        // Notify current state immediately
        callback(isBluetoothConnected)
    }
    
    /**
     * Enable low latency mode for Bluetooth audio
     */
    fun setLowLatencyMode(enabled: Boolean) {
        lowLatencyMode = enabled
        
        if (enabled && isBluetoothConnected) {
            applyBluetoothOptimizations()
        } else if (!enabled && isBluetoothScoOn) {
            stopBluetoothSco()
            
            // Reset audio mode
            audioManager?.mode = AudioManager.MODE_NORMAL
        }
    }
    
    /**
     * Apply Bluetooth audio optimizations for low latency
     */
    private fun applyBluetoothOptimizations() {
        try {
            // Set audio mode to communication for lower latency
            audioManager?.mode = AudioManager.MODE_IN_COMMUNICATION
            
            // Start Bluetooth SCO for lower latency audio path
            startBluetoothSco()
            
            // Set mic and music volume to optimal levels
            setOptimalAudioLevels()
            
            Log.d(TAG, "Bluetooth optimizations applied")
        } catch (e: Exception) {
            Log.e(TAG, "Error applying Bluetooth optimizations: ${e.message}")
        }
    }
    
    /**
     * Start Bluetooth SCO audio connection
     */
    private fun startBluetoothSco() {
        try {
            if (!isBluetoothScoOn) {
                audioManager?.startBluetoothSco()
                audioManager?.isBluetoothScoOn = true
                Log.d(TAG, "Bluetooth SCO started")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting Bluetooth SCO: ${e.message}")
        }
    }
    
    /**
     * Stop Bluetooth SCO audio connection
     */
    private fun stopBluetoothSco() {
        try {
            if (isBluetoothScoOn) {
                audioManager?.stopBluetoothSco()
                audioManager?.isBluetoothScoOn = false
                isBluetoothScoOn = false
                Log.d(TAG, "Bluetooth SCO stopped")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping Bluetooth SCO: ${e.message}")
        }
    }
    
    /**
     * Set optimal audio levels for mic and music streams
     */
    private fun setOptimalAudioLevels() {
        try {
            // Set mic volume to maximum for better pickup
            val micStreamMax = audioManager?.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL) ?: 0
            audioManager?.setStreamVolume(
                AudioManager.STREAM_VOICE_CALL,
                micStreamMax,
                0
            )
            
            // Set music stream to 80% for comfortable listening
            val musicStreamMax = audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 0
            val musicStreamTarget = (musicStreamMax * 0.8).toInt()
            audioManager?.setStreamVolume(
                AudioManager.STREAM_MUSIC,
                musicStreamTarget,
                0
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error setting optimal audio levels: ${e.message}")
        }
    }
    
    /**
     * Release resources
     */
    fun release() {
        try {
            // Stop SCO connection if active
            stopBluetoothSco()
            
            // Reset audio mode
            audioManager?.mode = AudioManager.MODE_NORMAL
            
            // Unregister receiver
            context.unregisterReceiver(bluetoothStateReceiver)
            
            // Close proxy connection
            bluetoothAdapter?.closeProfileProxy(BluetoothProfile.HEADSET, bluetoothHeadset)
            
            bluetoothHeadset = null
            stateChangeCallback = null
            
            Log.d(TAG, "Bluetooth audio manager released")
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing Bluetooth audio manager: ${e.message}")
        }
    }
    
    /**
     * Check if a Bluetooth headset is connected
     */
    fun isBluetoothHeadsetConnected(): Boolean {
        return isBluetoothConnected
    }
    
    /**
     * Get connected Bluetooth device name
     */
    @SuppressLint("MissingPermission")
    fun getConnectedDeviceName(): String? {
        return try {
            bluetoothHeadset?.connectedDevices?.firstOrNull()?.name
        } catch (e: Exception) {
            Log.e(TAG, "Error getting connected device name: ${e.message}")
            null
        }
    }
} 