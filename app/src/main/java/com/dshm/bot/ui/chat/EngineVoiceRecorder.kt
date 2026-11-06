package com.dshm.bot.ui.chat

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.dshm.bot.data.remote.SpeechRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * 录制给 DSH 引擎 SenseVoice 用的 WAV：16 kHz、单声道、PCM16。
 * 录完调 `speech/transcribe`，不经过 Android 的 SpeechRecognizer。
 */
class EngineVoiceRecorder(private val context: Context) {

    var recording by mutableStateOf(false)
        private set
    var transcribing by mutableStateOf(false)
        private set
    var level by mutableFloatStateOf(0f)
        private set

    private var record: AudioRecord? = null
    private var thread: Thread? = null
    @Volatile
    private var running = false
    private var output = ByteArrayOutputStream()

    fun start(): Boolean {
        if (recording) return true
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return false
        }
        val min = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (min <= 0) return false
        val created = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(min, SAMPLE_RATE * 2),
            )
        }.getOrNull() ?: return false
        if (created.state != AudioRecord.STATE_INITIALIZED) {
            created.release()
            return false
        }
        output = ByteArrayOutputStream()
        created.startRecording()
        if (created.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            created.release()
            return false
        }
        record = created
        running = true
        recording = true
        level = 0f
        thread = Thread {
            readLoop(created)
        }.apply {
            isDaemon = true
            start()
        }
        return true
    }

    private fun readLoop(audio: AudioRecord) {
        val buffer = ShortArray(1_600)
        var total = 0
        try {
            while (running && total < MAX_SECONDS * SAMPLE_RATE) {
                val read = audio.read(buffer, 0, buffer.size)
                if (read <= 0) continue
                val bytes = ByteArray(read * 2)
                var energy = 0.0
                for (index in 0 until read) {
                    val sample = buffer[index].toInt()
                    energy += sample.toDouble() * sample
                    bytes[index * 2] = (sample and 0xff).toByte()
                    bytes[index * 2 + 1] = ((sample shr 8) and 0xff).toByte()
                }
                synchronized(output) { output.write(bytes) }
                level = (sqrt(energy / read.coerceAtLeast(1)) / 32768.0)
                    .toFloat()
                    .coerceIn(0f, 1f)
                total += read
            }
        } catch (_: Exception) {
            // The recorder was released by cancel/stop; the WAV built so far is kept.
        }
    }

    fun stopAndTranscribe(
        scope: CoroutineScope,
        speech: SpeechRepository,
        onText: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        if (!recording) return
        recording = false
        running = false
        val audio = record
        record = null
        joinReader()
        val pcm = synchronized(output) { output.toByteArray() }
        runCatching { audio?.stop() }
        audio?.release()
        if (pcm.size < 3_200) {
            transcribing = false
            onError("没有录到声音")
            return
        }
        transcribing = true
        scope.launch {
            val wav = withContext(Dispatchers.Default) { wav(pcm) }
            val result = runCatching { speech.transcribe(wav) }
            transcribing = false
            result.onSuccess { text ->
                if (text.isBlank()) onError("没有识别到内容") else onText(text)
            }.onFailure {
                onError(it.message ?: "语音转写失败")
            }
        }
    }

    fun cancel() {
        running = false
        recording = false
        transcribing = false
        joinReader()
        record?.let {
            runCatching { it.stop() }
            it.release()
        }
        record = null
        synchronized(output) { output.reset() }
        level = 0f
    }

    fun release() = cancel()

    private fun joinReader() {
        runCatching { thread?.join(800) }
        thread = null
    }

    private fun wav(pcm: ByteArray): ByteArray {
        val buffer = ByteBuffer.allocate(44 + pcm.size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray())
        buffer.putInt(36 + pcm.size)
        buffer.put("WAVE".toByteArray())
        buffer.put("fmt ".toByteArray())
        buffer.putInt(16)
        buffer.putShort(1)
        buffer.putShort(1)
        buffer.putInt(SAMPLE_RATE)
        buffer.putInt(SAMPLE_RATE * 2)
        buffer.putShort(2)
        buffer.putShort(16)
        buffer.put("data".toByteArray())
        buffer.putInt(pcm.size)
        buffer.put(pcm)
        return buffer.array()
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val MAX_SECONDS = 60
    }
}
