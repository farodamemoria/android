package com.meta.wearable.dat.externalsampleapps.cameraaccess.stream

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import androidx.core.app.ActivityCompat
import com.meta.wearable.dat.externalsampleapps.cameraaccess.BuildConfig
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject

data class CameraCaptureRequest(val callId: String, val safetyInvestigation: Boolean = false)
data class FamilyHelpRequest(val callId: String, val kind: String, val spokenMessage: String)
data class CurrentLocationRequest(val callId: String)
data class FaceRecognitionRequest(val callId: String)

enum class VoiceConnectionState {
  STOPPED,
  CONNECTING,
  LISTENING,
  ERROR,
}

/** Full-duplex PCM bridge between the glasses HFP route and the local Realtime backend. */
class OpenAiVoiceController(
    context: Context,
    private val credentialProvider: () -> String,
) {
  private val context = context.applicationContext
  private val audioManager = context.getSystemService(AudioManager::class.java)
  private val httpClient = OkHttpClient.Builder()
      .retryOnConnectionFailure(true)
      .readTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS)
      .pingInterval(20, java.util.concurrent.TimeUnit.SECONDS)
      .build()
  private val running = AtomicBoolean(false)
  private val reconnectHandler = Handler(Looper.getMainLooper())
  private var reconnectAttempt = 0

  private val _state = MutableStateFlow(VoiceConnectionState.STOPPED)
  val state: StateFlow<VoiceConnectionState> = _state.asStateFlow()

  private val _error = MutableStateFlow<String?>(null)
  val error: StateFlow<String?> = _error.asStateFlow()

  private val _cameraRequests = MutableSharedFlow<CameraCaptureRequest>(extraBufferCapacity = 1)
  val cameraRequests: SharedFlow<CameraCaptureRequest> = _cameraRequests

  private val _familyHelpRequests = MutableSharedFlow<FamilyHelpRequest>(extraBufferCapacity = 1)
  val familyHelpRequests: SharedFlow<FamilyHelpRequest> = _familyHelpRequests

  private val _currentLocationRequests = MutableSharedFlow<CurrentLocationRequest>(extraBufferCapacity = 1)
  val currentLocationRequests: SharedFlow<CurrentLocationRequest> = _currentLocationRequests

  private val _faceRecognitionRequests = MutableSharedFlow<FaceRecognitionRequest>(extraBufferCapacity = 1)
  val faceRecognitionRequests: SharedFlow<FaceRecognitionRequest> = _faceRecognitionRequests

  @Volatile private var socket: WebSocket? = null
  @Volatile private var audioRecord: AudioRecord? = null
  @Volatile private var playback: InterruptiblePlayback? = null
  private var captureThread: Thread? = null

  @Synchronized fun start(): Boolean {
    if (running.get()) return true
    if (
        ActivityCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED ||
            ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) !=
                PackageManager.PERMISSION_GRANTED
    ) {
      fail("Microphone and Bluetooth permissions are required")
      return false
    }

    val hfpDevice =
        audioManager.availableCommunicationDevices.firstOrNull {
          it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        }
    if (hfpDevice == null) {
      fail("Connect the Meta glasses for call audio first")
      return false
    }

    if (credentialProvider().isBlank()) {
      fail("Empareja este dispositivo con Faro antes de iniciar la conversación")
      return false
    }

    audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
    if (!audioManager.setCommunicationDevice(hfpDevice)) {
      audioManager.mode = AudioManager.MODE_NORMAL
      fail("Android could not activate the glasses HFP route")
      return false
    }

    running.set(true)
    _error.value = null
    connectSocket()
    return true
  }

  @Synchronized private fun connectSocket() {
    if (!running.get()) return
    _state.value = VoiceConnectionState.CONNECTING
    val request =
        Request.Builder()
            .url(BuildConfig.REALTIME_BACKEND_URL)
            .header("Authorization", "Bearer ${credentialProvider()}")
            .header("X-Faro-Camera-Source", "glasses")
            .build()
    socket = httpClient.newWebSocket(request, listener)
  }

  @Synchronized fun stop() {
    if (!running.getAndSet(false) && _state.value == VoiceConnectionState.STOPPED) return
    socket?.close(1000, "Voice session ended")
    socket = null
    reconnectHandler.removeCallbacksAndMessages(null)
    reconnectAttempt = 0
    captureThread?.interrupt()
    captureThread?.join(1000)
    captureThread = null
    releaseAudio()
    audioManager.clearCommunicationDevice()
    audioManager.mode = AudioManager.MODE_NORMAL
    _state.value = VoiceConnectionState.STOPPED
  }

  fun close() {
    stop()
    httpClient.dispatcher.executorService.shutdown()
    httpClient.connectionPool.evictAll()
  }

  private val listener =
      object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response): Unit = synchronized(this@OpenAiVoiceController) {
          if (!running.get() || socket !== webSocket) return@synchronized
          if (!initializeAudio()) {
            webSocket.close(1011, "Audio initialization failed")
            return@synchronized
          }
          _state.value = VoiceConnectionState.LISTENING
          reconnectAttempt = 0
          Log.i(TAG, "Realtime voice connected")
          startCapture(webSocket)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString): Unit = synchronized(this@OpenAiVoiceController) {
          if (!running.get() || socket !== webSocket) return@synchronized
          if (playback?.enqueue(bytes.toByteArray()) == false) {
            webSocket.cancel()
            scheduleReconnect("Playback buffer limit exceeded")
          }
        }

        override fun onMessage(webSocket: WebSocket, text: String): Unit = synchronized(this@OpenAiVoiceController) {
          if (!running.get() || socket !== webSocket) return@synchronized
          when {
            text.startsWith("AUDIO_START:") -> {
              runCatching { JSONObject(text.removePrefix("AUDIO_START:")) }.onSuccess {
                playback?.begin(it.optString("item_id"), it.optInt("content_index"))
              }
            }
            text == "AUDIO_CLEAR" -> {
              playback?.clearAll()?.forEach {
                webSocket.send(JSONObject().put("type", "audio_playback_cleared")
                    .put("item_id", it.itemId).put("content_index", it.contentIndex)
                    .put("audio_end_ms", it.audioEndMs).toString())
                Log.i(TAG, "Realtime playback cleared; audioEndMs=${it.audioEndMs}")
              }
            }
            text.startsWith("ERROR:") -> fail(text.removePrefix("ERROR:"))
            text.startsWith("CAMERA_REQUEST:") -> {
              val raw = text.removePrefix("CAMERA_REQUEST:")
              val payload = runCatching { JSONObject(raw) }.getOrNull()
              val callId = payload?.optString("call_id").takeUnless { it.isNullOrBlank() } ?: raw
              val safetyInvestigation = payload?.optString("purpose") == "safety"
              Log.i(TAG, "Realtime requested glasses camera; callId=$callId, safety=$safetyInvestigation")
              if (callId.isNotBlank()) {
                _cameraRequests.tryEmit(CameraCaptureRequest(callId, safetyInvestigation))
              }
            }
            text.startsWith("FAMILY_HELP_REQUEST:") -> {
              runCatching { JSONObject(text.removePrefix("FAMILY_HELP_REQUEST:")) }
                  .onSuccess { payload ->
                    val callId = payload.optString("call_id")
                    if (callId.isNotBlank()) {
                      _familyHelpRequests.tryEmit(
                          FamilyHelpRequest(
                              callId = callId,
                              kind = payload.optString("kind", "episode"),
                              spokenMessage = payload.optString("spoken_message", "La persona ha pedido ayuda"),
                          )
                      )
                    }
                  }
                  .onFailure { Log.e(TAG, "Invalid family help request", it) }
            }
            text.startsWith("LOCATION_ANSWER_REQUEST:") -> {
              runCatching { JSONObject(text.removePrefix("LOCATION_ANSWER_REQUEST:")) }
                  .onSuccess { payload ->
                    payload.optString("call_id").takeIf { it.isNotBlank() }?.let { callId ->
                      _currentLocationRequests.tryEmit(CurrentLocationRequest(callId))
                    }
                  }
                  .onFailure { Log.e(TAG, "Invalid current location request", it) }
            }
            text.startsWith("FACE_RECOGNITION_REQUEST:") -> {
              runCatching { JSONObject(text.removePrefix("FACE_RECOGNITION_REQUEST:")) }
                  .onSuccess { payload ->
                    payload.optString("call_id").takeIf { it.isNotBlank() }?.let { callId ->
                      _faceRecognitionRequests.tryEmit(FaceRecognitionRequest(callId))
                    }
                  }
                  .onFailure { Log.e(TAG, "Invalid face recognition request", it) }
            }
          }
        }

        override fun onFailure(webSocket: WebSocket, error: Throwable, response: Response?): Unit = synchronized(this@OpenAiVoiceController) {
          if (running.get() && socket === webSocket) scheduleReconnect(error.message ?: "unknown error")
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String): Unit = synchronized(this@OpenAiVoiceController) {
          Log.i(TAG, "Realtime voice disconnected; code=$code, reason=$reason")
          if (running.get() && socket === webSocket) scheduleReconnect("connection closed")
        }
      }

  private fun scheduleReconnect(reason: String) {
    releaseAudio()
    socket = null
    if (!running.get()) return
    val delayMillis = (1_000L shl reconnectAttempt.coerceAtMost(4)).coerceAtMost(15_000L)
    reconnectAttempt++
    _error.value = "Faro perdió la conexión y volverá a intentarlo en ${delayMillis / 1000} segundos"
    _state.value = VoiceConnectionState.CONNECTING
    Log.w(TAG, "Realtime reconnect scheduled in ${delayMillis}ms: $reason")
    reconnectHandler.removeCallbacksAndMessages(null)
    reconnectHandler.postDelayed({ connectSocket() }, delayMillis)
  }

  fun submitCameraImage(callId: String, jpegBytes: ByteArray) {
    Log.i(TAG, "Submitting glasses image; callId=$callId, jpegBytes=${jpegBytes.size}")
    val message =
        JSONObject()
            .put("type", "camera_image")
            .put("call_id", callId)
            .put("mime_type", "image/jpeg")
            .put("data", Base64.encodeToString(jpegBytes, Base64.NO_WRAP))
            .toString()
    if (socket?.send(message) != true) {
      Log.w(TAG, "Socket closed; dropping camera image for call $callId")
    }
  }

  fun submitCameraError(callId: String, message: String) {
    Log.e(TAG, "Submitting camera error; callId=$callId: $message")
    val event =
        JSONObject()
            .put("type", "camera_error")
            .put("call_id", callId)
            .put("message", message)
            .toString()
    socket?.send(event)
  }

  fun submitFamilyHelpResult(callId: String, success: Boolean, message: String) {
    val event = JSONObject().put("type", "family_help_result").put("call_id", callId)
        .put("success", success).put("message", message).toString()
    if (socket?.send(event) != true) {
      Log.w(TAG, "Socket closed; dropping family help result for call $callId")
    }
  }

  fun submitCurrentLocationResult(
      callId: String,
      success: Boolean,
      answer: VerifiedLocationAnswer? = null,
      errorMessage: String? = null,
  ) {
    val event = JSONObject().put("type", "location_answer_result").put("call_id", callId)
        .put("success", success)
        .put("status", answer?.status ?: "unavailable")
        .put("message", answer?.message ?: errorMessage ?: "No he podido comprobar dónde estás")
    answer?.placeLabel?.let { event.put("place_label", it) }
    answer?.accuracyMeters?.let { event.put("accuracy_meters", it) }
    if (socket?.send(event.toString()) != true) {
      Log.w(TAG, "Socket closed; dropping location result for call $callId")
    }
  }

  fun submitFaceRecognitionResult(
      callId: String,
      success: Boolean,
      status: String,
      message: String,
      displayName: String? = null,
  ) {
    val event = JSONObject().put("type", "face_recognition_result").put("call_id", callId)
        .put("success", success).put("status", status).put("message", message)
    displayName?.takeIf { it.isNotBlank() }?.let { event.put("display_name", it) }
    if (socket?.send(event.toString()) != true) {
      Log.w(TAG, "Socket closed; dropping face recognition result for call $callId")
    }
  }

  /** KAN-171: habla un texto con la MISMA voz de Faro (TTS del backend), no con el TTS local. */
  fun announce(text: String): Boolean {
    val spoken = text.trim()
    if (spoken.isEmpty()) return false
    val event = JSONObject().put("type", "announce").put("text", spoken).toString()
    return socket?.send(event) == true
  }

  /** Starts a deterministic end-to-end vision check once voice and DAT streaming are both ready. */
  fun requestVisionAudit(): Boolean {
    val event = JSONObject().put("type", "vision_audit_request").toString()
    val sent = socket?.send(event) == true
    Log.i(TAG, "Vision audit request sent=$sent")
    return sent
  }

  fun submitLiveVisionFrame(jpegBytes: ByteArray, sequence: Long): Boolean {
    val message =
        JSONObject()
            .put("type", "stream_frame")
            .put("sequence", sequence)
            .put("mime_type", "image/jpeg")
            .put("data", Base64.encodeToString(jpegBytes, Base64.NO_WRAP))
            .toString()
    return socket?.send(message) == true
  }

  private fun initializeAudio(): Boolean {
    val inputBuffer =
        AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, PCM_FORMAT)
    val outputBuffer =
        AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, PCM_FORMAT)
    if (inputBuffer <= 0 || outputBuffer <= 0) {
      fail("The HFP route does not support 24 kHz PCM")
      return false
    }

    return try {
      val record =
          AudioRecord(
              MediaRecorder.AudioSource.MIC,
              SAMPLE_RATE,
              AudioFormat.CHANNEL_IN_MONO,
              PCM_FORMAT,
              inputBuffer * 2,
          )
      val track =
          AudioTrack.Builder()
              .setAudioAttributes(
                  AudioAttributes.Builder()
                      .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                      .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                      .build()
              )
              .setAudioFormat(
                  AudioFormat.Builder()
                      .setEncoding(PCM_FORMAT)
                      .setSampleRate(SAMPLE_RATE)
                      .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                      .build()
              )
              .setBufferSizeInBytes(outputBuffer * 4)
              .setTransferMode(AudioTrack.MODE_STREAM)
              .build()
      if (record.state != AudioRecord.STATE_INITIALIZED || track.state != AudioTrack.STATE_INITIALIZED) {
        record.release()
        track.release()
        fail("Could not initialize HFP audio")
        false
      } else {
        audioRecord = record
        track.play()
        playback = InterruptiblePlayback(object : InterruptiblePlayback.Sink {
          override fun write(data: ByteArray, offset: Int, count: Int) =
              track.write(data, offset, count, AudioTrack.WRITE_NON_BLOCKING)
          override fun playedFrames() = track.playbackHeadPosition.toLong() and 0xffffffffL
          override fun flush() { track.pause(); track.flush(); track.play() }
          override fun release() { try { track.pause(); track.flush() } finally { track.release() } }
        }, onError = {
          reconnectHandler.post {
            synchronized(this@OpenAiVoiceController) {
              if (running.get() && audioRecord === record) scheduleReconnect("Audio playback failed")
            }
          }
        })
        true
      }
    } catch (error: SecurityException) {
      fail("Audio permission was revoked")
      false
    } catch (error: Exception) {
      fail("Could not initialize audio: ${error.message ?: "unknown error"}")
      false
    }
  }

  private fun startCapture(webSocket: WebSocket) {
    captureThread =
        Thread(
                {
                  val record = audioRecord ?: return@Thread
                  val buffer = ByteArray(CAPTURE_CHUNK_BYTES)
                  try {
                    record.startRecording()
                    while (running.get() && socket === webSocket && audioRecord === record && !Thread.currentThread().isInterrupted) {
                      val count = record.read(buffer, 0, buffer.size)
                      if (count > 0 && !webSocket.send(buffer.toByteString(0, count))) {
                        if (running.get() && socket === webSocket && audioRecord === record) fail("Realtime audio queue is full")
                        break
                      }
                    }
                  } catch (error: Exception) {
                    if (running.get() && socket === webSocket && audioRecord === record) fail("Audio capture stopped: ${error.message ?: "unknown error"}")
                  }
                },
                "OpenAI-HFP-Capture",
            )
            .also { it.start() }
  }

  private fun fail(message: String) {
    Log.e(TAG, message)
    _error.value = message
    _state.value = VoiceConnectionState.ERROR
  }

  private fun releaseAudio() {
    val record = audioRecord
    audioRecord = null
    runCatching { record?.stop() }
    captureThread?.interrupt()
    captureThread?.join(1000)
    captureThread = null
    record?.release()
    playback?.close()
    playback = null
  }

  private companion object {
    const val TAG = "OpenAiVoiceController"
    
    const val SAMPLE_RATE = 24_000
    const val PCM_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    const val CAPTURE_CHUNK_BYTES = 2_400 // 50 ms at 24 kHz mono PCM16.
  }
}
