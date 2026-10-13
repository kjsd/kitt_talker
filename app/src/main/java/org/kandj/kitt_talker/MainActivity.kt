package org.kandj.kitt_talker

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.MediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import org.kandj.kitt_talker.ui.theme.KittTalkerTheme
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

enum class InteractionState {
    IDLE,           // 待機停止中
    LISTENING,      // マイク待受中 (VAD稼働中)
    HEARING,        // ユーザーが話し中 (音声入力検出中)
    SENDING,        // 録音完了、サーバーSTT(Whisper)へ送信中
    WAITING_REPLY,  // Kittの返答待ち (音声生成ポーリング中)
    PLAYING         // Kittが音声再生中 (エコー防止のためマイクミュート)
}

class MainActivity : ComponentActivity() {
    private lateinit var exoPlayer: ExoPlayer

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
        if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) return null

        val okHttpClient = OkHttpClient.Builder()
            .readTimeout(60, TimeUnit.SECONDS)
            .connectTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()

        return try {
            val normalizedUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
            val retrofit = Retrofit.Builder()
                .baseUrl(normalizedUrl)
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

        // 展示・対話中のスリープ・スタンバイを無効化 (Keep Screen On)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Init ExoPlayer
        exoPlayer = ExoPlayer.Builder(this).build()

        setContent {
            KittTalkerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    var kittId by remember { mutableStateOf(getSavedKittId()) }
                    var baseUrl by remember { mutableStateOf(getSavedBaseUrl()) }

                    val apiService = remember(baseUrl) {
                        createApiService(baseUrl)
                    }

                    KittScreen(
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
        exoPlayer.release()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KittScreen(
    exoPlayer: ExoPlayer,
    apiService: KittApiService?,
    kittId: String,
    onKittSelected: (String) -> Unit,
    baseUrl: String,
    onBaseUrlChanged: (String) -> Unit
) {
    var interactionState by remember { mutableStateOf(InteractionState.IDLE) }
    var statusText by remember { mutableStateOf(if (apiService == null) "Set Server URL" else "Initializing...") }
    var recognizedText by remember { mutableStateOf("") }
    var currentMood by remember { mutableStateOf("neutral") }

    // Kitt Selection State
    var kitts by remember { mutableStateOf(emptyList<Kitt>()) }
    var expanded by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val isAudioPlaying = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    val isSendingAudio = remember { java.util.concurrent.atomic.AtomicBoolean(false) }

    val currentApiService by rememberUpdatedState(apiService)
    val currentKittId by rememberUpdatedState(kittId)
    val currentBaseUrl by rememberUpdatedState(baseUrl)

    // VoiceActivityDetector: アプリ起動中ずっと単一インスタンスを保持
    val vad = remember {
        VoiceActivityDetector(
            onSpeechStarted = {
                scope.launch(Dispatchers.Main) {
                    Log.i("KittTalker", "VAD: User started speaking")
                    interactionState = InteractionState.HEARING
                    statusText = "Hearing voice..."
                }
            },
            onSpeechEnded = { wavBytes ->
                // 重複送信防止 (In-Flight Lock)
                if (isSendingAudio.getAndSet(true)) {
                    Log.w("KittTalker", "VAD: Already sending audio, ignoring duplicate speech end")
                    return@VoiceActivityDetector
                }

                scope.launch(Dispatchers.Main) {
                    Log.i("KittTalker", "VAD: Speech ended (${wavBytes.size} bytes). Sending to Kitt...")
                    interactionState = InteractionState.SENDING
                    statusText = "Thinking..."

                    val service = currentApiService
                    val id = currentKittId
                    val url = currentBaseUrl

                    if (service == null) {
                        isSendingAudio.set(false)
                        statusText = "Error: Server not connected"
                        interactionState = InteractionState.IDLE
                        return@launch
                    }

                    sendAudio(
                        apiService = service,
                        id = id,
                        wavBytes = wavBytes,
                        onSuccess = { res ->
                            isSendingAudio.set(false)
                            if (res.status == "ignored") {
                                Log.i("KittTalker", "STT ignored: ${res.message}")
                                interactionState = InteractionState.LISTENING
                                statusText = "Listening (Ready)..."
                            } else {
                                Log.i("KittTalker", "STT Text: \"${res.text}\", Reply: \"${res.reply}\"")
                                recognizedText = res.text ?: ""
                                currentMood = res.mood ?: "neutral"

                                if (!res.audioPath.isNullOrBlank()) {
                                    // 音声パスが即座に返ってきた場合
                                    interactionState = InteractionState.PLAYING
                                    statusText = "Kitt is talking..."
                                    playAudio(exoPlayer, url, res.audioPath)
                                } else {
                                    // 非同期TTS生成中の場合、ポーリングで待機
                                    interactionState = InteractionState.WAITING_REPLY
                                    statusText = "Kitt is thinking..."
                                }
                            }
                        },
                        onError = { error ->
                            isSendingAudio.set(false)
                            Log.e("KittTalker", "STT Error: $error")
                            statusText = "STT Error: $error"
                            scope.launch {
                                delay(1500)
                                if (!isAudioPlaying.get() && interactionState != InteractionState.PLAYING) {
                                    interactionState = InteractionState.LISTENING
                                    statusText = "Listening (Ready)..."
                                }
                            }
                        }
                    )
                }
            },
            checkIsMuted = {
                isAudioPlaying.get() ||
                        isSendingAudio.get() ||
                        interactionState == InteractionState.PLAYING ||
                        interactionState == InteractionState.SENDING ||
                        interactionState == InteractionState.WAITING_REPLY
            }
        )
    }

    // Permission launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            vad.start()
            interactionState = InteractionState.LISTENING
            statusText = "Listening (Ready)..."
        } else {
            Toast.makeText(context, "Audio permission needed for Kitt", Toast.LENGTH_SHORT).show()
            statusText = "Audio Permission Denied"
            interactionState = InteractionState.IDLE
        }
    }

    // ライフサイクル連動: フォアグラウンド(ON_RESUME)時のみマイク待受、非表示(ON_PAUSE/STOP)時は停止
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, vad) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        vad.start()
                        if (interactionState == InteractionState.IDLE) {
                            interactionState = InteractionState.LISTENING
                            statusText = "Listening (Ready)..."
                        }
                    } else {
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }
                Lifecycle.Event.ON_PAUSE -> {
                    vad.stop()
                    if (interactionState == InteractionState.LISTENING || interactionState == InteractionState.HEARING) {
                        interactionState = InteractionState.IDLE
                        statusText = "Paused"
                    }
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            vad.stop()
        }
    }

    // ExoPlayer Listener: 再生完了時にエコーガードディレイを挟んで待受復帰
    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                isAudioPlaying.set(isPlaying)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_ENDED -> {
                        isAudioPlaying.set(false)
                        Log.d("KittTalker", "ExoPlayer: Playback Ended")
                        interactionState = InteractionState.IDLE
                        statusText = "Finished speaking."

                        // エコー防止（残響カット）のため 600ms 待機してから待受復帰
                        scope.launch {
                            delay(600)
                            interactionState = InteractionState.LISTENING
                            statusText = "Listening (Ready)..."
                        }
                    }
                    Player.STATE_READY -> {
                        Log.d("KittTalker", "ExoPlayer: Ready to play")
                    }
                    else -> {}
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                isAudioPlaying.set(false)
                Log.e("KittTalker", "ExoPlayer Error: ${error.message}", error)
                statusText = "Playback error"
                interactionState = InteractionState.IDLE
                scope.launch {
                    delay(1000)
                    interactionState = InteractionState.LISTENING
                    statusText = "Listening (Ready)..."
                }
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
        }
    }

    // Initial load of Kitts (Reload when baseUrl changes)
    LaunchedEffect(baseUrl) {
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

    // Polling effect: 返答待ち時は 500ms、通常時は 2000ms でポーリング
    LaunchedEffect(baseUrl, kittId) {
        while (true) {
            val pollDelay = if (interactionState == InteractionState.WAITING_REPLY) 500L else 2000L
            delay(pollDelay)

            // ユーザー発話中(HEARING)・送信中(SENDING)・再生中(PLAYING)はポーリングからの再生割り込みを避ける
            if (interactionState != InteractionState.HEARING &&
                interactionState != InteractionState.SENDING &&
                !isSendingAudio.get() &&
                !exoPlayer.isPlaying &&
                apiService != null
            ) {
                pollForContent(apiService, kittId) { content ->
                    if (content != null && !content.audioPath.isNullOrBlank()) {
                        Log.i("KittTalker", "Received content from queue: ${content.message}")
                        interactionState = InteractionState.PLAYING
                        statusText = "Kitt is talking..."
                        currentMood = content.mood ?: "neutral"
                        playAudio(exoPlayer, baseUrl, content.audioPath)
                    }
                }
            }
        }
    }

    // Pulse animation for active mic / listening state
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = if (interactionState == InteractionState.LISTENING || interactionState == InteractionState.HEARING) 1.15f else 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(800),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    val coreColor by animateColorAsState(
        targetValue = when (interactionState) {
            InteractionState.PLAYING -> getMoodColor(currentMood)
            InteractionState.HEARING -> Color(0xFFFF9800) // Orange while hearing voice
            InteractionState.SENDING, InteractionState.WAITING_REPLY -> Color(0xFF9C27B0) // Purple while waiting
            InteractionState.LISTENING -> Color(0xFF4CAF50) // Green while listening
            InteractionState.IDLE -> Color(0xFF757575) // Grey when idle
        },
        label = "coreColor"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp)
            .verticalScroll(rememberScrollState()),
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
                .padding(bottom = 8.dp),
            singleLine = true,
            isError = apiService == null
        )

        // Kitt Selector
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = !expanded },
            modifier = Modifier.padding(bottom = 24.dp)
        ) {
            val selectedKitt = kitts.find { it.id == kittId }
            TextField(
                value = selectedKitt?.name ?: "Select Kitt",
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                colors = ExposedDropdownMenuDefaults.textFieldColors(),
                modifier = Modifier.menuAnchor(),
                textStyle = MaterialTheme.typography.bodyMedium
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                kitts.forEach { kitt ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(kitt.name, style = MaterialTheme.typography.bodyMedium)
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

        // Mood / Interaction Indicator (タップで強制リフレッシュ/再リッスン可能なトリガー付き)
        Box(
            modifier = Modifier
                .size(130.dp)
                .padding(bottom = 20.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    if (!exoPlayer.isPlaying && apiService != null) {
                        interactionState = InteractionState.LISTENING
                        statusText = "Listening (Ready)..."
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            // Outer glow circle (animated pulse during listening)
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .scale(pulseScale),
                shape = androidx.compose.foundation.shape.CircleShape,
                color = coreColor.copy(alpha = 0.25f)
            ) {}
            // Core
            Surface(
                modifier = Modifier.size(80.dp),
                shape = androidx.compose.foundation.shape.CircleShape,
                color = coreColor
            ) {}
        }

        Text(
            text = "Mood: $currentMood",
            style = MaterialTheme.typography.titleMedium,
            color = getMoodColor(currentMood)
        )
        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "Status: $statusText",
            style = MaterialTheme.typography.bodyLarge
        )
        Spacer(modifier = Modifier.height(8.dp))

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            )
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = "You said:",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = if (recognizedText.isNotBlank()) recognizedText else "(Listening for your voice...)",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 3
                )
            }
        }
    }
}

fun sendAudio(
    apiService: KittApiService,
    id: String,
    wavBytes: ByteArray,
    onSuccess: (KittAudioResponse) -> Unit,
    onError: (String) -> Unit
) {
    val mediaType = MediaType.parse("audio/wav")
    val requestBody = RequestBody.create(mediaType, wavBytes)
    val part = MultipartBody.Part.createFormData("audio", "speech.wav", requestBody)

    apiService.talkAudio(id, part).enqueue(object : Callback<KittAudioResponse> {
        override fun onResponse(call: Call<KittAudioResponse>, response: Response<KittAudioResponse>) {
            if (response.isSuccessful && response.body() != null) {
                onSuccess(response.body()!!)
            } else {
                val errBody = response.errorBody()?.string() ?: response.message()
                onError("HTTP ${response.code()}: $errBody")
            }
        }

        override fun onFailure(call: Call<KittAudioResponse>, t: Throwable) {
            onError(t.localizedMessage ?: "Network error")
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

fun getMoodColor(mood: String?): Color {
    val m = (mood ?: "neutral").lowercase()
    return when {
        // Kitt's Top 3
        m.contains("assisting") -> Color(0xFF00BCD4) // Cyan (Default)
        m.contains("smug")      -> Color(0xFF9C27B0) // Purple
        m.contains("kindly")    -> Color(0xFFFF80AB) // Pink

        // Universal Emotions
        m.contains("happy") || m.contains("joy") -> Color(0xFFFFC107) // Amber
        m.contains("sad") || m.contains("cry")   -> Color(0xFF2196F3) // Blue
        m.contains("angr") || m.contains("mad")  -> Color(0xFFF44336) // Red
        m.contains("fear") || m.contains("scar") -> Color(0xFF673AB7) // Deep Purple
        m.contains("surpris") || m.contains("shock") -> Color(0xFF4CAF50) // Green
        m.contains("love") || m.contains("sedu") -> Color(0xFFE91E63) // Deep Pink

        // Others
        m.contains("serious") || m.contains("cold") -> Color(0xFF607D8B) // Blue Grey

        // Default (Original Cyan)
        else -> Color(0xFF00BCD4)
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