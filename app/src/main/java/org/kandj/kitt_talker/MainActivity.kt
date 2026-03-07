package org.kandj.kitt_talker

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import org.kandj.kitt_talker.ui.theme.KittTalkerTheme
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.Locale
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {
    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var exoPlayer: ExoPlayer
    // apiService will be managed in Compose state
    
    // Default values
    private val DEFAULT_KITT_ID = "2d16ba43-3eb0-46c2-9583-e38dbb82c5fa" 
    private val DEFAULT_BASE_URL = ""

    private fun getSavedKittId(): String {
        val prefs = getSharedPreferences("KittPrefs", MODE_PRIVATE)
        return prefs.getString("selected_kitt_id", DEFAULT_KITT_ID) ?: DEFAULT_KITT_ID
    }

    private fun saveKittId(id: String) {
        val prefs = getSharedPreferences("KittPrefs", MODE_PRIVATE)
        prefs.edit().putString("selected_kitt_id", id).apply()
    }

    private fun getSavedBaseUrl(): String {
        val prefs = getSharedPreferences("KittPrefs", MODE_PRIVATE)
        return prefs.getString("base_url", DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL
    }

    private fun saveBaseUrl(url: String) {
        val prefs = getSharedPreferences("KittPrefs", MODE_PRIVATE)
        prefs.edit().putString("base_url", url).apply()
    }

    private fun createApiService(baseUrl: String): KittApiService? {
        if (baseUrl.isBlank()) return null
        
        // Simple check for valid URL format (must verify scheme)
        if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) return null

        val okHttpClient = OkHttpClient.Builder()
            .readTimeout(60, TimeUnit.SECONDS)
            .connectTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()

        return try {
            val retrofit = Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(okHttpClient)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
            retrofit.create(KittApiService::class.java)
        } catch (e: Exception) {
            Log.e("KittTalker", "Invalid URL: $baseUrl", e)
            null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Init ExoPlayer
        exoPlayer = ExoPlayer.Builder(this).build()
        exoPlayer.addListener(object : androidx.media3.common.Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    androidx.media3.common.Player.STATE_ENDED -> {
                        Log.d("KittTalker", "ExoPlayer: Playback Ended")
                    }
                    androidx.media3.common.Player.STATE_READY -> {
                        Log.d("KittTalker", "ExoPlayer: Ready to play")
                    }
                    else -> {}
                }
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                Log.e("KittTalker", "ExoPlayer Error: ${error.message}", error)
            }
        })

        // Init SpeechRecognizer
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)

        setContent {
            KittTalkerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    var kittId by remember { mutableStateOf(getSavedKittId()) }
                    var baseUrl by remember { mutableStateOf(getSavedBaseUrl()) }
                    
                    // Recreate ApiService when baseUrl changes
                    val apiService = remember(baseUrl) {
                        createApiService(baseUrl)
                    }
                    
                    KittScreen(
                        speechRecognizer = speechRecognizer,
                        exoPlayer = exoPlayer,
                        apiService = apiService,
                        kittId = kittId,
                        onKittSelected = { newId ->
                            kittId = newId
                            saveKittId(newId)
                        },
                        baseUrl = baseUrl,
                        onBaseUrlChanged = { newUrl ->
                            baseUrl = newUrl
                            saveBaseUrl(newUrl)
                        }
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        speechRecognizer.destroy()
        exoPlayer.release()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KittScreen(
    speechRecognizer: SpeechRecognizer,
    exoPlayer: ExoPlayer,
    apiService: KittApiService?,
    kittId: String,
    onKittSelected: (String) -> Unit,
    baseUrl: String,
    onBaseUrlChanged: (String) -> Unit
) {
    var statusText by remember { mutableStateOf(if (apiService == null) "Set Server URL" else "Ready") }
    // ... (rest of state vars) ...
    var recognizedText by remember { mutableStateOf("") }
    var currentMood by remember { mutableStateOf("neutral") }
    var isListening by remember { mutableStateOf(false) }
    
    // Kitt Selection State
    var kitts by remember { mutableStateOf(emptyList<Kitt>()) }
    var expanded by remember { mutableStateOf(false) }
    
    val context = LocalContext.current

    // Initial load of Kitts (Reload when apiService changes)
    LaunchedEffect(apiService) {
        apiService?.getKitts()?.enqueue(object : Callback<List<Kitt>> {
            override fun onResponse(call: Call<List<Kitt>>, response: Response<List<Kitt>>) {
                if (response.isSuccessful) {
                    kitts = response.body() ?: emptyList()
                }
            }
            override fun onFailure(call: Call<List<Kitt>>, t: Throwable) {
                Log.e("KittTalker", "Failed to load kitts", t)
            }
        })
    }
    
    // ... (Permission launcher) ...
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        // ... (inside logic remains same, just ensuring context) ...
        if (isGranted) {
            startListening(speechRecognizer, { text ->
                recognizedText = text
                statusText = "Sending..."
                if (apiService != null) {
                    sendText(apiService, kittId, text, {
                        statusText = "Sent. Waiting for reply..."
                    }, { error ->
                        statusText = "Send Error: $error"
                    })
                } else {
                    statusText = "Error: Invalid Server URL"
                }
            }, { error ->
                statusText = "Error: $error"
                isListening = false
            })
            isListening = true
            statusText = "Listening..."
        } else {
            Toast.makeText(context, "Permission needed", Toast.LENGTH_SHORT).show()
        }
    }

    // Polling effect
    LaunchedEffect(apiService, kittId) {
        while (true) {
            delay(2000) // Poll every 2 seconds
            
            if (!isListening && !exoPlayer.isPlaying && apiService != null) {
                pollForContent(apiService, kittId) { content ->
                    if (content != null && content.audioPath != null) {
                        statusText = "Playing audio..."
                        currentMood = content.mood ?: "neutral"
                        playAudio(exoPlayer, baseUrl, content.audioPath)
                    }
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Server URL Settings
        OutlinedTextField(
            value = baseUrl,
            onValueChange = onBaseUrlChanged,
            label = { Text("Server URL") },
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            singleLine = true,
            isError = apiService == null
        )

        // Kitt Selector
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = !expanded },
            modifier = Modifier.padding(bottom = 32.dp)
        ) {
            val selectedKitt = kitts.find { it.id == kittId }
            TextField(
                value = selectedKitt?.name ?: "Select Kitt",
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                colors = ExposedDropdownMenuDefaults.textFieldColors(),
                modifier = Modifier.menuAnchor()
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                kitts.forEach { kitt ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(kitt.name, style = MaterialTheme.typography.bodyLarge)
                                kitt.biography?.personality?.let {
                                    Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                                }
                            }
                        },
                        onClick = {
                            onKittSelected(kitt.id)
                            expanded = false
                        }
                    )
                }
            }
        }

        // Mood Indicator
        Box(
            modifier = Modifier
                .size(120.dp)
                .padding(bottom = 32.dp),
            contentAlignment = Alignment.Center
        ) {
            // Outer glow or circle
            Surface(
                modifier = Modifier.fillMaxSize(),
                shape = androidx.compose.foundation.shape.CircleShape,
                color = getMoodColor(currentMood).copy(alpha = 0.2f)
            ) {}
            // Core
            Surface(
                modifier = Modifier.size(80.dp),
                shape = androidx.compose.foundation.shape.CircleShape,
                color = getMoodColor(currentMood)
            ) {}
        }

        Text(text = "Mood: $currentMood", style = MaterialTheme.typography.titleMedium, color = getMoodColor(currentMood))
        Spacer(modifier = Modifier.height(16.dp))

        Text(text = "Status: $statusText", style = MaterialTheme.typography.bodyLarge)
        Spacer(modifier = Modifier.height(16.dp))
        Text(text = "You said: $recognizedText", style = MaterialTheme.typography.bodyMedium)
        Spacer(modifier = Modifier.height(32.dp))

        // Push-to-Talk Button
        Surface(
            modifier = Modifier
                .padding(16.dp)
                .size(width = 200.dp, height = 60.dp)
                .pointerInput(apiService) {
                    awaitPointerEventScope {
                        while (true) {
                            val downEvent = awaitFirstDown()
                            
                            if (apiService != null && ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                isListening = true
                                statusText = "Listening... (Release to send)"
                                startListening(speechRecognizer, { text ->
                                    isListening = false
                                    recognizedText = text
                                    statusText = "Sending..."
                                    sendText(apiService, kittId, text, {
                                        statusText = "Sent."
                                    }, { sendErr ->
                                        statusText = "Send Error: $sendErr"
                                    })
                                }, { errorCode ->
                                    isListening = false
                                    if (errorCode == SpeechRecognizer.ERROR_NO_MATCH || errorCode == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                                        statusText = "Canceled (No speech detected)"
                                    } else {
                                        statusText = "Error code: $errorCode"
                                    }
                                })
                            } else if (apiService == null) {
                                // Fallback info update
                                statusText = "No Server URL!"
                            } else {
                                // Request permission if not granted
                                // Cannot launch permission from within pointerInput easily, so we just prompt via text
                                statusText = "Need Audio Permission!"
                            }

                            // Wait for ACTION_UP
                            val upEvent = waitForUpOrCancellation()
                            
                            // Finger released
                            if (isListening) {
                                speechRecognizer.stopListening()
                                isListening = false
                                statusText = "Processing speech..."
                            }
                        }
                    }
                },
            shape = MaterialTheme.shapes.extraLarge,
            color = if (isListening) getMoodColor(currentMood).copy(alpha = 0.7f) else getMoodColor(currentMood),
            shadowElevation = if (isListening) 2.dp else 8.dp
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = if (isListening) "Listening..." else "Hold to Talk",
                    color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }
    }
}

fun startListening(
    speechRecognizer: SpeechRecognizer,
    onResult: (String) -> Unit,
    onError: (Int) -> Unit
) {
    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
        // VAD (Voice Activity Detection) tuning parameters
        // Request partial results so we know it's working
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        // Increase the silence lengths to prevent early timeout
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 10000L) // 10 seconds min
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 5000L) // 5 seconds of silence allowed
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 5000L)
    }

    speechRecognizer.setRecognitionListener(object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onError(error: Int) {
            onError(error)
        }

        override fun onResults(results: Bundle?) {
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (!matches.isNullOrEmpty()) {
                onResult(matches[0])
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            // We could update the UI with partial results here if we passed a callback,
            // but for now just logging it to prove it's alive.
            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (!matches.isNullOrEmpty()) {
                Log.d("KittTalker", "Partial: ${matches[0]}")
            }
        }
        override fun onEvent(eventType: Int, params: Bundle?) {}
    })

    speechRecognizer.startListening(intent)
}

fun sendText(
    apiService: KittApiService, 
    id: String, 
    text: String, 
    onSuccess: () -> Unit,
    onError: (String) -> Unit
) {
    apiService.talk(id, text).enqueue(object : Callback<KittContent> {
        override fun onResponse(call: Call<KittContent>, response: Response<KittContent>) {
            if (response.isSuccessful) {
                onSuccess()
            } else {
                onError("HTTP ${response.code()}")
            }
        }
        override fun onFailure(call: Call<KittContent>, t: Throwable) {
            Log.e("KittTalker", "Failed to send text", t)
            onError(t.message ?: "Unknown error")
        }
    })
}

fun pollForContent(apiService: KittApiService, id: String, onContentReceived: (KittContent?) -> Unit) {
    apiService.dequeueTalk(id).enqueue(object : Callback<KittContent> {
        override fun onResponse(call: Call<KittContent>, response: Response<KittContent>) {
            if (response.isSuccessful) {
                onContentReceived(response.body())
            } else {
                onContentReceived(null)
            }
        }
        override fun onFailure(call: Call<KittContent>, t: Throwable) {
            Log.e("KittTalker", "Poll error", t)
            onContentReceived(null)
        }
    })
}

fun getMoodColor(mood: String?): androidx.compose.ui.graphics.Color {
    val m = (mood ?: "neutral").lowercase()
    return when {
        // Kitt's Top 3
        m.contains("assisting") -> androidx.compose.ui.graphics.Color(0xFF00BCD4) // Cyan (Default)
        m.contains("smug")      -> androidx.compose.ui.graphics.Color(0xFF9C27B0) // Purple
        m.contains("kindly")    -> androidx.compose.ui.graphics.Color(0xFFFF80AB) // Pink

        // Universal Emotions
        m.contains("happy") || m.contains("joy") -> androidx.compose.ui.graphics.Color(0xFFFFC107) // Amber
        m.contains("sad") || m.contains("cry")   -> androidx.compose.ui.graphics.Color(0xFF2196F3) // Blue
        m.contains("angr") || m.contains("mad")  -> androidx.compose.ui.graphics.Color(0xFFF44336) // Red
        m.contains("fear") || m.contains("scar") -> androidx.compose.ui.graphics.Color(0xFF673AB7) // Deep Purple
        m.contains("surpris") || m.contains("shock") -> androidx.compose.ui.graphics.Color(0xFF4CAF50) // Green
        m.contains("love") || m.contains("sedu") -> androidx.compose.ui.graphics.Color(0xFFE91E63) // Deep Pink
        
        // Others
        m.contains("serious") || m.contains("cold") -> androidx.compose.ui.graphics.Color(0xFF607D8B) // Blue Grey
        
        // Default (Original Cyan)
        else -> androidx.compose.ui.graphics.Color(0xFF00BCD4)
    }
}

fun playAudio(exoPlayer: ExoPlayer, baseUrl: String, audioPath: String) {
    Log.d("KittTalker", "DEBUG: Raw audioPath from server: $audioPath")

    var cleanedPath = audioPath
    
    // 1. Remove "/app" if it's there (case for internal container paths)
    if (cleanedPath.startsWith("/app/")) {
        cleanedPath = cleanedPath.removePrefix("/app")
    }
    
    // 2. Ensure it starts with "/" for consistent concatenation
    if (!cleanedPath.startsWith("/")) {
        cleanedPath = "/$cleanedPath"
    }

    val finalBaseUrl = if (baseUrl.endsWith("/")) baseUrl.substring(0, baseUrl.length - 1) else baseUrl
    val fullUrl = "$finalBaseUrl$cleanedPath"

    Log.d("KittTalker", "DEBUG: Final Playback URL: $fullUrl")

    val mediaItem = MediaItem.fromUri(fullUrl)
    exoPlayer.setMediaItem(mediaItem)
    exoPlayer.prepare()
    exoPlayer.play()
}