package com.xiaobai.rapidcsac.platform

import android.content.Context
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import androidx.compose.runtime.*
import com.xiaobai.rapidcsac.core.data.ChatRepository
import com.xiaobai.rapidcsac.core.model.Message
import com.xiaobai.rapidcsac.core.network.UploadPart
import com.xiaobai.rapidcsac.core.network.json
import java.io.File
import java.util.UUID
import okhttp3.HttpUrl.Companion.toHttpUrl

data class RecordedVoice(val file: File, val seconds: Int)

/** Owns microphone/player resources for one visible conversation. */
@Stable
class ChatMedia(
    private val context: Context,
    private val repo: ChatRepository,
    private val key: String,
) {
    private val group = key.startsWith("g:")
    private val id = key.substringAfter(':').toLong()
    private var recorder: MediaRecorder? = null
    private var player: MediaPlayer? = null
    private var recordingFile: File? = null
    var recordingSince by mutableLongStateOf(0L)
        private set

    var voice by mutableStateOf<RecordedVoice?>(null)
        private set

    var uploading by mutableStateOf(false)
        private set

    var playing by mutableLongStateOf(0L)
        private set

    var paused by mutableStateOf(false)
        private set

    var positionMs by mutableIntStateOf(0)
        private set

    var durationMs by mutableIntStateOf(0)
        private set

    var preparing by mutableLongStateOf(0L)
        private set

    var error by mutableStateOf("")
        private set

    suspend fun upload(uri: Uri, image: Boolean, reply: Long) {
        if (uploading) return
        uploading = true
        try {
            val part = MediaFiles(context).prepare(uri, if (image) "img" else "file", image)
            try {
                repo.media.upload(
                    if (image) {
                        if (group) "message/send_group_msg" else "message/send_private_msg"
                    } else "message/send_file_msg",
                    json((if (group) "room_id" else "friend_id") to id, "reply_to" to reply),
                    listOf(part),
                )
                if (repo.activeConversation == key) repo.openConversation(key)
            } finally {
                part.file.delete()
            }
        } finally {
            uploading = false
        }
    }

    @Suppress("DEPRECATION")
    fun startRecording() {
        stopPlayback()
        error = ""
        discardVoice()
        val file = File(context.cacheDir, "voice-${UUID.randomUUID()}.m4a")
        val current = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()
        try {
            current.setAudioSource(MediaRecorder.AudioSource.MIC)
            current.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            current.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            current.setAudioSamplingRate(24000)
            current.setAudioEncodingBitRate(64000)
            current.setOutputFile(file.path)
            current.setMaxDuration(120_000)
            current.setOnInfoListener { _, what, _ ->
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED)
                    finishRecording(true)
            }
            current.prepare()
            current.start()
            recorder = current
            recordingFile = file
            recordingSince = System.currentTimeMillis()
        } catch (_: Exception) {
            current.release()
            file.delete()
            error = "无法启动麦克风"
        }
    }

    fun finishRecording(keep: Boolean) {
        val current = recorder ?: return
        recorder = null
        val seconds = ((System.currentTimeMillis() - recordingSince) / 1000).toInt()
        val file = recordingFile
        recordingFile = null
        recordingSince = 0
        val valid = runCatching { current.stop() }.isSuccess
        current.release()
        if (keep && valid && seconds > 0 && file != null) voice = RecordedVoice(file, seconds)
        else {
            file?.delete()
            if (keep) error = "录音太短，请重试"
        }
    }

    suspend fun sendVoice() {
        val recording = voice ?: return
        if (uploading) return
        uploading = true
        try {
            repo.media.upload(
                "message/send_voice_msg",
                json(
                    (if (group) "room_id" else "friend_id") to id,
                    "duration" to recording.seconds,
                ),
                listOf(UploadPart("voice", recording.file, "voice.m4a", "audio/mp4")),
            )
            discardVoice()
            if (repo.activeConversation == key) repo.openConversation(key)
        } finally {
            uploading = false
        }
    }

    fun discardVoice() {
        if (playing == -1L || preparing == -1L) stopPlayback()
        voice?.file?.delete()
        voice = null
    }

    fun play(message: Message) {
        if (playing == message.id) {
            togglePlayback()
            return
        }
        startPlayback(message.id) { current ->
            val url = repo.media.resolve(message.voice).toHttpUrl()
            require(url.isHttps) { "语音地址必须使用 HTTPS" }
            val base = repo.account.origin.toHttpUrl()
            val headers =
                if (url.scheme == base.scheme && url.host == base.host && url.port == base.port)
                    mapOf(
                        "Authorization" to "Bearer ${repo.account.token}",
                        "User-Agent" to "CsAC-Android/0.1",
                    )
                else mapOf("User-Agent" to "CsAC-Android/0.1")
            current.setDataSource(context, Uri.parse(url.toString()), headers)
        }
    }

    fun previewVoice() {
        val recording = voice ?: return
        if (playing == -1L) togglePlayback()
        else startPlayback(-1L) { it.setDataSource(recording.file.absolutePath) }
    }

    private fun startPlayback(id: Long, source: (MediaPlayer) -> Unit) {
        stopPlayback()
        error = ""
        val current = MediaPlayer()
        player = current
        preparing = id
        try {
            current.setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            source(current)
            current.setOnPreparedListener {
                if (player === it) {
                    preparing = 0
                    playing = id
                    durationMs = it.duration
                    it.start()
                }
            }
            current.setOnCompletionListener { if (player === it) stopPlayback() }
            current.setOnErrorListener { failed, _, _ ->
                if (player === failed) {
                    stopPlayback()
                    error = "语音播放失败"
                }
                true
            }
            current.prepareAsync()
        } catch (_: Exception) {
            stopPlayback()
            error = "语音播放失败"
        }
    }

    private fun togglePlayback() {
        val current = player ?: return
        runCatching {
            if (current.isPlaying) current.pause() else current.start()
            paused = !current.isPlaying
        }
            .onFailure { stopPlayback() }
    }

    fun updatePlaybackPosition() {
        if (playing != 0L) runCatching { positionMs = player?.currentPosition ?: 0 }
    }

    fun seek(position: Int) {
        if (playing != 0L)
            runCatching {
                val bounded = position.coerceIn(0, durationMs)
                player?.seekTo(bounded)
                positionMs = bounded
            }
    }

    fun recordingAmplitude(): Float = runCatching {
        (recorder?.maxAmplitude ?: 0) / 32767f
    }
        .getOrDefault(0f)

    fun stopPlayback() {
        player?.release()
        player = null
        playing = 0
        preparing = 0
        paused = false
        positionMs = 0
        durationMs = 0
    }

    fun pause() {
        finishRecording(false)
        stopPlayback()
    }

    fun close() {
        pause()
        discardVoice()
    }
}
