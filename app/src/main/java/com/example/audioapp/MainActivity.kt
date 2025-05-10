package com.example.audioapp

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.audioapp.ui.AudioVisualizer
import com.example.audioapp.ui.AudioVisualizerState
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState

class MainActivity : ComponentActivity() {
    private val viewModel: AudioViewModel by viewModels()
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Bind to the audio processing service
        viewModel.bindService(this)
        
        setContent {
            AudioAppTheme {
                AudioProcessingApp(viewModel)
            }
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        
        // Unbind from the audio processing service
        viewModel.unbindService(this)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)
@Composable
fun AudioProcessingApp(viewModel: AudioViewModel) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scrollState = rememberScrollState()
    
    // Get visualizer state (or create a dummy one if not available)
    val visualizerState = viewModel.getVisualizerState() ?: remember { AudioVisualizerState() }
    
    // Handle permissions
    val audioPermissionState = rememberPermissionState(Manifest.permission.RECORD_AUDIO)
    
    // Show error in a snackbar if there is one
    LaunchedEffect(uiState.error) {
        uiState.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            color = MaterialTheme.colorScheme.background
        ) {
            if (audioPermissionState.status.isGranted) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState)
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Top
                ) {
                    // Audio visualization
                    if (uiState.useVisualization) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 16.dp),
                            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(8.dp)
                            ) {
                                AudioVisualizer(
                                    visualizerState = visualizerState,
                                    showSpectrum = uiState.useFFTProcessing,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                
                                // Voice detection status
                                if (uiState.useVoiceActivityDetection) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(8.dp),
                                        horizontalArrangement = Arrangement.Center,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        val isVoiceDetected = visualizerState.voiceDetected.value
                                        val statusColor = if (isVoiceDetected) 
                                            Color(0xFF00C853) else Color.Gray
                                        
                                        Text(
                                            text = if (isVoiceDetected) 
                                                stringResource(R.string.voice_detected) else "",
                                            color = statusColor,
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                    }
                                }
                            }
                        }
                    }
                    
                    // Main action button
                    Box(
                        modifier = Modifier
                            .size(200.dp)
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Button(
                            onClick = {
                                if (uiState.isProcessing) {
                                    viewModel.stopAudioProcessing(context)
                                } else {
                                    viewModel.startAudioProcessing(context)
                                }
                            },
                            modifier = Modifier.size(180.dp),
                            shape = CircleShape
                        ) {
                            Text(
                                text = if (uiState.isProcessing) 
                                    stringResource(R.string.stop_recording) 
                                else stringResource(R.string.start_recording),
                                style = MaterialTheme.typography.headlineMedium,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    // Status text
                    Text(
                        text = if (uiState.isProcessing) stringResource(R.string.audio_processing) else "",
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (uiState.isProcessing) MaterialTheme.colorScheme.primary else Color.Transparent
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    // Noise reduction settings card
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp),
                        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.noise_reduction_level),
                                style = MaterialTheme.typography.titleMedium
                            )
                            
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            Slider(
                                value = uiState.noiseReductionLevel.toFloat(),
                                onValueChange = { viewModel.updateNoiseReductionLevel(it.toInt()) },
                                valueRange = 0f..100f,
                                steps = 100,
                                modifier = Modifier.fillMaxWidth()
                            )
                            
                            Text(
                                text = "${uiState.noiseReductionLevel}%",
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                    
                    // Advanced processing settings card
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp),
                        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.processing_settings),
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                            
                            // FFT Noise Reduction Switch
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = stringResource(R.string.fft_noise_reduction),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                
                                Switch(
                                    checked = uiState.useFFTProcessing,
                                    onCheckedChange = { viewModel.toggleFFTProcessing(it) }
                                )
                            }
                            
                            Divider(modifier = Modifier.padding(vertical = 8.dp))
                            
                            // Voice Activity Detection Switch
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = stringResource(R.string.vad_enabled),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                
                                Switch(
                                    checked = uiState.useVoiceActivityDetection,
                                    onCheckedChange = { viewModel.toggleVoiceActivityDetection(it) }
                                )
                            }
                            
                            Divider(modifier = Modifier.padding(vertical = 8.dp))
                            
                            // Low Latency Mode Switch
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = stringResource(R.string.low_latency_mode),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                
                                Switch(
                                    checked = uiState.useLowLatencyMode,
                                    onCheckedChange = { viewModel.toggleLowLatencyMode(it) }
                                )
                            }
                            
                            Divider(modifier = Modifier.padding(vertical = 8.dp))
                            
                            // Visualization Switch
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = stringResource(R.string.audio_visualization),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                
                                Switch(
                                    checked = uiState.useVisualization,
                                    onCheckedChange = { viewModel.toggleVisualization(it) }
                                )
                            }
                        }
                    }
                }
            } else {
                PermissionRequestContent { audioPermissionState.launchPermissionRequest() }
            }
        }
    }
}

@Composable
fun PermissionRequestContent(onRequestPermission: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = stringResource(R.string.permission_required),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Button(onClick = onRequestPermission) {
            Text(stringResource(R.string.grant_permission))
        }
    }
}

@Composable
fun AudioAppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MaterialTheme.colorScheme,
        typography = MaterialTheme.typography,
        content = content
    )
} 