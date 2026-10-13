package org.kandj.kitt_talker

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections
import kotlin.math.sqrt

/**
 * Android ネイティブの AudioRecord を使った簡易的な音声区間検出 (VAD)。
 * 16kHz, 16-bit Mono PCM で音声をサンプリングし、WAVフォーマットでコールバック。
 */
class VoiceActivityDetector(
    private val onSpeechStarted: () -> Unit,
    private val onSpeechEnded: (ByteArray) -> Unit,
    private val checkIsMuted: () -> Boolean = { false }
) {
    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    private var audioRecord: AudioRecord? = null
    private var isRunning = false
    private var vadJob: Job? = null

    // VAD Parameters
    private var threshold = 800.0 // RMS threshold (External mic friendly)
    private val zcrThreshold = 0.25 // ZCR threshold
    private val minSpeechDurationMs = 250 // 0.25s 発話で開始判定
    private val silenceDurationMs = 1800   // 1.8s 無音で終了判定
    private val minTotalAudioMs = 600      // 合計0.6s未満は短すぎるので捨てる

    private var isSpeaking = false
    private var lastSpeechTime = 0L
    private var speechStartedTime = 0L
    private var lastMutedTime = 0L

    // High-pass filter state (RC Filter)
    private var prevX = 0.0
    private var prevY = 0.0
    private val alpha = 0.85 // High-pass filter constant

    // 録音データを一時的に溜めるバッファ
    private val audioBuffer = Collections.synchronizedList(mutableListOf<ByteArray>())

    // 発話開始前の頭切れ防止用プリロールバッファ (直近 約400ms 分)
    private val preRollBuffer = ArrayDeque<ByteArray>()
    private var maxPreRollChunks = 10

    @SuppressLint("MissingPermission")
    fun start() {
        if (isRunning) return

        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        val bufferSize = if (minBufferSize > 0) (minBufferSize * 2).coerceAtLeast(2048) else 4096
        maxPreRollChunks = ((sampleRate * 2 * 0.4) / bufferSize).toInt().coerceAtLeast(4)

        // 外部マイク接続を前提とするため、OSの内蔵処理に邪魔されない MIC を使用
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            channelConfig,
            audioFormat,
            bufferSize
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e("VAD", "AudioRecord initialization failed with MIC, trying DEFAULT...")
            audioRecord?.release()
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.DEFAULT,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            )
        }

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e("VAD", "AudioRecord initialization failed completely")
            return
        }

        isRunning = true
        audioRecord?.startRecording()
        Log.i("VAD", "Recording started (16kHz PCM Mono, bufferSize=$bufferSize, maxPreRoll=$maxPreRollChunks)")

        vadJob = CoroutineScope(Dispatchers.IO).launch {
            val shorts = ShortArray(bufferSize / 2)
            while (isRunning) {
                val read = audioRecord?.read(shorts, 0, shorts.size) ?: 0
                if (read > 0) {
                    processAudio(shorts, read)
                } else {
                    // エラーまたは空読み時はビジーループ防止のため待機
                    delay(20)
                }
            }
        }
    }

    fun stop() {
        isRunning = false
        vadJob?.cancel()
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e("VAD", "Error stopping AudioRecord", e)
        }
        audioRecord = null
        isSpeaking = false
        speechStartedTime = 0L
        audioBuffer.clear()
        synchronized(preRollBuffer) {
            preRollBuffer.clear()
        }
        Log.i("VAD", "Recording stopped")
    }

    private fun processAudio(shorts: ShortArray, size: Int) {
        val currentTime = System.currentTimeMillis()

        // 再生中（Kittが喋っている間）は判定をスキップ
        if (checkIsMuted()) {
            lastMutedTime = currentTime
            if (isSpeaking) {
                isSpeaking = false
                speechStartedTime = 0L
                audioBuffer.clear()
                Log.d("VAD", "Muted by playback: Discarding speech buffer")
            }
            synchronized(preRollBuffer) {
                preRollBuffer.clear()
            }
            return
        }

        // 再生終了直後のエコー（残響）を拾わないように 600ms は待機
        if (currentTime - lastMutedTime < 600) {
            synchronized(preRollBuffer) {
                preRollBuffer.clear()
            }
            return
        }

        // High-pass filter
        val filteredShorts = ShortArray(size)
        for (i in 0 until size) {
            val x = shorts[i].toDouble()
            val y = alpha * (prevY + x - prevX)
            filteredShorts[i] = y.toInt().coerceIn(-32768, 32767).toShort()
            prevX = x
            prevY = y
        }

        // PCM チャンクをバイト配列化
        val bytes = ByteBuffer.allocate(size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until size) {
            bytes.putShort(filteredShorts[i])
        }
        val pcmChunk = bytes.array()

        val rms = calculateRMS(filteredShorts, size)
        val zcr = calculateZCR(filteredShorts, size)

        if (rms > threshold && zcr < zcrThreshold) {
            lastSpeechTime = currentTime

            if (!isSpeaking) {
                if (speechStartedTime == 0L) {
                    speechStartedTime = currentTime
                }

                if (currentTime - speechStartedTime > minSpeechDurationMs) {
                    isSpeaking = true
                    onSpeechStarted()
                    audioBuffer.clear()
                    // 発話開始前のプリロール音声（頭切れ防止）をバッファの先頭に追加
                    synchronized(preRollBuffer) {
                        audioBuffer.addAll(preRollBuffer)
                        preRollBuffer.clear()
                    }
                    Log.d("VAD", "Speech Detected! (RMS: ${rms.toInt()}, ZCR: ${String.format(java.util.Locale.US, "%.3f", zcr)})")
                }
            }
        } else {
            if (isSpeaking && currentTime - lastSpeechTime > silenceDurationMs) {
                val durationMs = lastSpeechTime - speechStartedTime
                isSpeaking = false
                speechStartedTime = 0L

                if (durationMs > minTotalAudioMs) {
                    val fullAudio = combineBuffers()
                    Log.i("VAD", "Speech Ended (${fullAudio.size / 32000.0}s of audio), sending...")
                    onSpeechEnded(fullAudio)
                } else {
                    Log.d("VAD", "Speech discarded: too short (${durationMs}ms)")
                }
                audioBuffer.clear()
            } else if (!isSpeaking) {
                speechStartedTime = 0L
            }
        }

        if (isSpeaking) {
            audioBuffer.add(pcmChunk)
        } else {
            // 未発話時は最新の数チャンク（約400ms）をリングバッファに保持
            synchronized(preRollBuffer) {
                preRollBuffer.addLast(pcmChunk)
                if (preRollBuffer.size > maxPreRollChunks) {
                    preRollBuffer.removeFirst()
                }
            }
        }
    }

    private fun calculateRMS(shorts: ShortArray, size: Int): Double {
        var sum = 0.0
        for (i in 0 until size) {
            sum += (shorts[i] * shorts[i]).toDouble()
        }
        return sqrt(sum / size)
    }

    private fun calculateZCR(shorts: ShortArray, size: Int): Double {
        var count = 0
        for (i in 1 until size) {
            if ((shorts[i].toInt() >= 0 && shorts[i - 1].toInt() < 0) ||
                (shorts[i].toInt() < 0 && shorts[i - 1].toInt() >= 0)
            ) {
                count++
            }
        }
        return count.toDouble() / size
    }

    private fun combineBuffers(): ByteArray {
        val pcmDataSize = audioBuffer.sumOf { it.size }
        val headerSize = 44
        val totalSize = pcmDataSize + headerSize
        val combined = ByteArray(totalSize)

        writeWavHeader(combined, pcmDataSize, sampleRate, 1, 16)

        var offset = headerSize
        audioBuffer.forEach {
            System.arraycopy(it, 0, combined, offset, it.size)
            offset += it.size
        }
        return combined
    }

    private fun writeWavHeader(data: ByteArray, pcmDataSize: Int, sampleRate: Int, channels: Int, bitsPerSample: Int) {
        val totalDataLen = pcmDataSize + 36
        val byteRate = sampleRate * channels * bitsPerSample / 8

        data[0] = 'R'.code.toByte()
        data[1] = 'I'.code.toByte()
        data[2] = 'F'.code.toByte()
        data[3] = 'F'.code.toByte()
        data[4] = (totalDataLen and 0xff).toByte()
        data[5] = ((totalDataLen shr 8) and 0xff).toByte()
        data[6] = ((totalDataLen shr 16) and 0xff).toByte()
        data[7] = ((totalDataLen shr 24) and 0xff).toByte()
        data[8] = 'W'.code.toByte()
        data[9] = 'A'.code.toByte()
        data[10] = 'V'.code.toByte()
        data[11] = 'E'.code.toByte()
        data[12] = 'f'.code.toByte()
        data[13] = 'm'.code.toByte()
        data[14] = 't'.code.toByte()
        data[15] = ' '.code.toByte()
        data[16] = 16 // 16 for PCM
        data[17] = 0
        data[18] = 0
        data[19] = 0
        data[20] = 1 // 1 for PCM
        data[21] = 0
        data[22] = channels.toByte()
        data[23] = 0
        data[24] = (sampleRate and 0xff).toByte()
        data[25] = ((sampleRate shr 8) and 0xff).toByte()
        data[26] = ((sampleRate shr 16) and 0xff).toByte()
        data[27] = ((sampleRate shr 24) and 0xff).toByte()
        data[28] = (byteRate and 0xff).toByte()
        data[29] = ((byteRate shr 8) and 0xff).toByte()
        data[30] = ((byteRate shr 16) and 0xff).toByte()
        data[31] = ((byteRate shr 24) and 0xff).toByte()
        data[32] = (channels * bitsPerSample / 8).toByte()
        data[33] = 0
        data[34] = bitsPerSample.toByte()
        data[35] = 0
        data[36] = 'd'.code.toByte()
        data[37] = 'a'.code.toByte()
        data[38] = 't'.code.toByte()
        data[39] = 'a'.code.toByte()
        data[40] = (pcmDataSize and 0xff).toByte()
        data[41] = ((pcmDataSize shr 8) and 0xff).toByte()
        data[42] = ((pcmDataSize shr 16) and 0xff).toByte()
        data[43] = ((pcmDataSize shr 24) and 0xff).toByte()
    }
}
