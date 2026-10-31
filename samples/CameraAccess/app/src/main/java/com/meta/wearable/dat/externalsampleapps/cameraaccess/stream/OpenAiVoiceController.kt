/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.stream

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
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject

/**
 * Realtime voice session with the Faro backend, ported from the original Faro APK's
 * `OpenAiVoiceController`.
 *
 * Opens a WebSocket to `/realtime` authenticated with the device credential obtained through
 * pairing, routes 24 kHz mono PCM through the glasses' Bluetooth HFP link, streams microphone
 * audio as binary frames and plays the assistant's audio through [InterruptiblePlayback].
 *
 * Backend text protocol (server to device):
 * - `AUDIO_START:{...}` marks the start of a server audio item (`item_id`, `content_index`).
 * - `AUDIO_CLEAR` asks to drop current playback; the device answers with `audio_playback_cleared`.
 * - `ERROR:{message}` surfaces a session error.
 * - `CAMERA_REQUEST:{...}` asks for a glasses image (`call_id`, `purpose`).
 * - `FAMILY_HELP_REQUEST:{...}` asks to alert the care network (`call_id`, `kind`, `spoken_message`).
 * - `LOCATION_ANSWER_REQUEST:{...}` asks for the current location (`call_id`).
 * - `FACE_RECOGNITION_REQUEST:{...}` asks to identify the person in front of the patient (`call_id`).
 */
class OpenAiVoiceController(
    context: Context,
    private val credentialProvider: () -> String,
) {

  private val appContext = context.applicationContext
  private val audioManager = appContext.getSystemService(AudioManager::class.java)
  private val httpClient = OkHttpClient.Builder().retryOnConnectionFailure(true).build()
  private val running = AtomicBoolean(false)
  private val reconnectHandler = Handler(Looper.getMainLooper())

  private val _state = MutableStateFlow(VoiceConnectionState.STOPPED)
  val state: StateFlow<VoiceConnectionState> = _state.asStateFlow()

  private val _error = MutableStateFlow<String?>(null)
  val error: StateFlow<String?> = _error.asStateFlow()

  private val _cameraRequests = MutableSharedFlow<CameraCaptureRequest>(extraBufferCapacity = 1)
  val cameraRequests: SharedFlow<CameraCaptureRequest> = _cameraRequests.asSharedFlow()

  private val _familyHelpRequests = MutableSharedFlow<FamilyHelpRequest>(extraBufferCapacity = 1)
  val familyHelpRequests: SharedFlow<FamilyHelpRequest> = _familyHelpRequests.asSharedFlow()

  private val _currentLocationRequests =
      MutableSharedFlow<CurrentLocationRequest>(extraBufferCapacity = 1)
  val currentLocationRequests: SharedFlow<CurrentLocationRequest> =
      _currentLocationRequests.asSharedFlow()

  private val _faceRecognitionRequests =
      MutableSharedFlow<FaceRecognitionRequest>(extraBufferCapacity = 1)
  val faceRecognitionRequests: SharedFlow<FaceRecognitionRequest> =
      _faceRecognitionRequests.asSharedFlow()

  @Volatile private var socket: WebSocket? = null
  @Volatile private var audioRecord: AudioRecord? = null
  @Volatile private var playback: InterruptiblePlayback? = null
  private var captureThread: Thread? = null
  private var reconnectAttempt = 0

  private val listener =
      object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
          synchronized(this@OpenAiVoiceController) {
            if (running.get() && socket === webSocket) {
              if (initializeAudio()) {
                _state.value = VoiceConnectionState.LISTENING
                reconnectAttempt = 0
                Log.i(TAG, "Realtime voice connected")
                startCapture(webSocket)
              } else {
                webSocket.close(1011, "Audio initialization failed")
              }
            }
          }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
          synchronized(this@OpenAiVoiceController) {
            if (running.get() && socket === webSocket) {
              val queued = playback?.enqueue(bytes.toByteArray()) ?: true
              if (!queued) {
                webSocket.cancel()
                scheduleReconnect("Playback buffer limit exceeded")
              }
            }
          }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
          synchronized(this@OpenAiVoiceController) {
            if (!running.get() || socket !== webSocket) return
            when (val event = VoiceProtocol.parse(text)) {
              is VoiceEvent.AudioStart -> handleAudioStart(event.payload)
              VoiceEvent.AudioClear -> handleAudioClear(webSocket)
              is VoiceEvent.Error -> fail(event.message)
              is VoiceEvent.CameraRequest -> handleCameraRequest(event.payload)
              is VoiceEvent.FamilyHelpRequest -> handleFamilyHelpRequest(event.payload)
              is VoiceEvent.LocationAnswerRequest -> handleLocationRequest(event.payload)
              is VoiceEvent.FaceRecognitionRequest -> handleFaceRecognitionRequest(event.payload)
              VoiceEvent.Ignored -> Log.d(TAG, "Realtime event ignored: ${text.take(80)}")
            }
          }
        }

        override fun onFailure(webSocket: WebSocket, error: Throwable, response: Response?) {
          synchronized(this@OpenAiVoiceController) {
            if (running.get() && socket === webSocket) {
              if (response?.code == HTTP_UNAUTHORIZED) {
                endSession(AUTH_FAILED_MESSAGE)
              } else {
                scheduleReconnect(error.message ?: "unknown error")
              }
            }
          }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
          synchronized(this@OpenAiVoiceController) {
            Log.i(TAG, "Realtime voice disconnected; code=$code, reason=$reason")
            if (running.get() && socket === webSocket) {
              when (code) {
                CLOSE_UNAUTHORIZED -> endSession(AUTH_FAILED_MESSAGE)
                CLOSE_DUPLICATE_SESSION -> endSession(DUPLICATE_SESSION_MESSAGE)
                else -> scheduleReconnect("connection closed")
              }
            }
          }
        }
      }

  private fun handleAudioStart(payload: String) {
    val start = runCatching { JSONObject(payload) }.getOrNull() ?: return
    playback?.begin(start.optString("item_id"), start.optInt("content_index"))
  }

  private fun handleAudioClear(webSocket: WebSocket) {
    val positions = playback?.clearAll() ?: return
    positions.forEach { position ->
      val message =
          JSONObject()
              .put("type", "audio_playback_cleared")
              .put("item_id", position.itemId)
              .put("content_index", position.contentIndex)
              .put("audio_end_ms", position.audioEndMs)
              .toString()
      webSocket.send(message)
      Log.i(TAG, "Realtime playback cleared; audioEndMs=${position.audioEndMs}")
    }
  }

  private fun handleCameraRequest(payload: String) {
    val json = runCatching { JSONObject(payload) }.getOrNull()
    val callId =
        json?.optString("call_id")?.takeIf { it.isNotBlank() } ?: payload.trim()
    val safety = json?.optString("purpose") == "safety"
    Log.i(TAG, "Realtime requested glasses camera; callId=$callId, safety=$safety")
    if (callId.isNotBlank()) {
      _cameraRequests.tryEmit(CameraCaptureRequest(callId, safety))
    }
  }

  private fun handleFamilyHelpRequest(payload: String) {
    val json = runCatching { JSONObject(payload) }.getOrNull() ?: return
    val callId = json.optString("call_id")
    if (!callId.isBlank()) {
      _familyHelpRequests.tryEmit(
          FamilyHelpRequest(
              callId,
              json.optString("kind", "episode"),
              json.optString("spoken_message", "A persoa pediu axuda"),
          )
      )
    }
  }

  private fun handleLocationRequest(payload: String) {
    val callId = runCatching { JSONObject(payload).optString("call_id") }.getOrNull()
    if (!callId.isNullOrBlank()) {
      _currentLocationRequests.tryEmit(CurrentLocationRequest(callId))
    }
  }

  private fun handleFaceRecognitionRequest(payload: String) {
    val callId = runCatching { JSONObject(payload).optString("call_id") }.getOrNull()
    if (!callId.isNullOrBlank()) {
      _faceRecognitionRequests.tryEmit(FaceRecognitionRequest(callId))
    }
  }

  /** Starts the realtime session. Returns false (and sets [error]) when it can't start. */
  @Synchronized
  fun start(): Boolean {
    if (running.get()) return true
    if (ContextCompat.checkSelfPermission(appContext, PERMISSION_RECORD_AUDIO) !=
        PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(appContext, PERMISSION_BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED) {
      fail("Microphone and Bluetooth permissions are required")
      return false
    }
    val hfpDevice =
        audioManager
            ?.availableCommunicationDevices
            ?.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
    if (hfpDevice == null) {
      fail("Connect the Meta glasses for call audio first")
      return false
    }
    if (credentialProvider().isBlank()) {
      fail("Empareja este dispositivo con Faro antes de iniciar la conversación")
      return false
    }
    audioManager?.mode = AudioManager.MODE_IN_COMMUNICATION
    if (audioManager?.setCommunicationDevice(hfpDevice) != true) {
      audioManager?.mode = AudioManager.MODE_NORMAL
      fail("Android could not activate the glasses HFP route")
      return false
    }
    running.set(true)
    _error.value = null
    connectSocket()
    return true
  }

  @Synchronized
  private fun connectSocket() {
    if (!running.get()) return
    _state.value = VoiceConnectionState.CONNECTING
    val request =
        Request.Builder()
            .url(REALTIME_URL)
            .header("Authorization", "Bearer ${credentialProvider()}")
            .header("X-Faro-Camera-Source", "glasses")
            .build()
    socket = httpClient.newWebSocket(request, listener)
  }

  @Synchronized
  fun stop() {
    if (running.getAndSet(false) || _state.value != VoiceConnectionState.STOPPED) {
      socket?.close(1000, "Voice session ended")
      socket = null
      reconnectHandler.removeCallbacksAndMessages(null)
      reconnectAttempt = 0
      captureThread?.interrupt()
      runCatching { captureThread?.join(1000) }
      captureThread = null
      releaseAudio()
      audioManager?.clearCommunicationDevice()
      audioManager?.mode = AudioManager.MODE_NORMAL
      _state.value = VoiceConnectionState.STOPPED
    }
  }

  fun close() {
    stop()
    httpClient.dispatcher.executorService.shutdown()
    httpClient.connectionPool.evictAll()
  }

  /**
   * Ends the session without reconnecting: the backend rejected the device credential (close code
   * 4401 / HTTP 401) or another session is already active (close code 4000). Retrying would loop.
   */
  @Synchronized
  private fun endSession(message: String) {
    running.set(false)
    socket = null
    reconnectHandler.removeCallbacksAndMessages(null)
    reconnectAttempt = 0
    captureThread?.interrupt()
    runCatching { captureThread?.join(1000) }
    captureThread = null
    releaseAudio()
    audioManager?.clearCommunicationDevice()
    audioManager?.mode = AudioManager.MODE_NORMAL
    Log.e(TAG, message)
    _error.value = message
    _state.value = VoiceConnectionState.ERROR
  }

  @Synchronized
  private fun scheduleReconnect(reason: String) {
    releaseAudio()
    socket = null
    if (!running.get()) return
    val delayMillis = min(1000L shl min(reconnectAttempt, 4), 15000L)
    reconnectAttempt++
    _error.value = "Faro perdió la conexión y volverá a intentarlo en ${delayMillis / 1000} segundos"
    _state.value = VoiceConnectionState.CONNECTING
    Log.w(TAG, "Realtime reconnect scheduled in ${delayMillis}ms: $reason")
    reconnectHandler.removeCallbacksAndMessages(null)
    reconnectHandler.postDelayed({ connectSocket() }, delayMillis)
  }

  /** Answers a `CAMERA_REQUEST:` with a JPEG frame captured from the glasses. */
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
      fail("Could not send the glasses camera image")
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
    val event =
        JSONObject()
            .put("type", "family_help_result")
            .put("call_id", callId)
            .put("success", success)
            .put("message", message)
            .toString()
    if (socket?.send(event) != true) {
      fail("No ha sido posible confirmar el aviso familiar")
    }
  }

  fun submitCurrentLocationResult(
      callId: String,
      success: Boolean,
      status: String?,
      message: String?,
      placeLabel: String? = null,
      accuracyMeters: Double? = null,
  ) {
    val event =
        JSONObject()
            .put("type", "location_answer_result")
            .put("call_id", callId)
            .put("success", success)
            .put("status", status ?: "unavailable")
            .put("message", message ?: "No he podido comprobar dónde estás")
    if (!placeLabel.isNullOrBlank()) event.put("place_label", placeLabel)
    if (accuracyMeters != null) event.put("accuracy_meters", accuracyMeters)
    if (socket?.send(event.toString()) != true) {
      fail("No ha sido posible devolver la ubicación verificada")
    }
  }

  fun submitFaceRecognitionResult(
      callId: String,
      success: Boolean,
      status: String,
      message: String,
      displayName: String? = null,
  ) {
    val event =
        JSONObject()
            .put("type", "face_recognition_result")
            .put("call_id", callId)
            .put("success", success)
            .put("status", status)
            .put("message", message)
    if (!displayName.isNullOrBlank()) event.put("display_name", displayName)
    if (socket?.send(event.toString()) != true) {
      fail("No ha sido posible devolver la identificación")
    }
  }

  private fun initializeAudio(): Boolean {
    val inputBuffer =
        AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
    val outputBuffer =
        AudioTrack.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
    if (inputBuffer <= 0 || outputBuffer <= 0) {
      fail("The HFP route does not support 24 kHz PCM")
      return false
    }
    return try {
      val record =
          AudioRecord.Builder()
              .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
              .setAudioFormat(
                  AudioFormat.Builder()
                      .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                      .setSampleRate(SAMPLE_RATE)
                      .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                      .build())
              .setBufferSizeInBytes(inputBuffer * 2)
              .build()
      val track =
          AudioTrack.Builder()
              .setAudioAttributes(
                  AudioAttributes.Builder()
                      .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                      .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                      .build())
              .setAudioFormat(
                  AudioFormat.Builder()
                      .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                      .setSampleRate(SAMPLE_RATE)
                      .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                      .build())
              .setBufferSizeInBytes(outputBuffer * 4)
              .setTransferMode(AudioTrack.MODE_STREAM)
              .build()
      if (record.state != AudioRecord.STATE_INITIALIZED ||
          track.state != AudioTrack.STATE_INITIALIZED) {
        record.release()
        track.release()
        fail("Could not initialize HFP audio")
        return false
      }
      audioRecord = record
      track.play()
      playback =
          InterruptiblePlayback(
              object : InterruptiblePlayback.Sink {
                override fun write(data: ByteArray, offset: Int, count: Int): Int =
                    track.write(data, offset, count, AudioTrack.WRITE_BLOCKING)

                override fun playedFrames(): Long =
                    track.playbackHeadPosition.toLong() and 0xFFFFFFFFL

                override fun flush() {
                  track.pause()
                  track.flush()
                  track.play()
                }

                override fun release() {
                  try {
                    track.pause()
                    track.flush()
                  } finally {
                    track.release()
                  }
                }
              },
              onError = {
                reconnectHandler.post {
                  synchronized(this) {
                    if (running.get() && audioRecord === record) {
                      scheduleReconnect("Audio playback failed")
                    }
                  }
                }
              },
          )
      true
    } catch (error: SecurityException) {
      fail("Audio permission was revoked")
      false
    } catch (error: Exception) {
      fail("Could not initialize audio: ${error.message ?: "unknown error"}")
      false
    }
  }

  private fun startCapture(webSocket: WebSocket) {
    val thread =
        Thread(
            {
              val record = audioRecord ?: return@Thread
              val buffer = ByteArray(CAPTURE_CHUNK_BYTES)
              try {
                record.startRecording()
                while (running.get() &&
                    socket === webSocket &&
                    audioRecord === record &&
                    !Thread.currentThread().isInterrupted) {
                  val count = record.read(buffer, 0, buffer.size)
                  if (count > 0 && !webSocket.send(buffer.toByteString(0, count))) {
                    if (!running.get() || socket !== webSocket || audioRecord !== record) {
                      return@Thread
                    }
                    fail("Realtime audio queue is full")
                    return@Thread
                  }
                }
              } catch (error: Exception) {
                if (!running.get() || socket !== webSocket || audioRecord !== record) {
                  return@Thread
                }
                fail("Audio capture stopped: ${error.message ?: "unknown error"}")
              }
            },
            "OpenAI-HFP-Capture",
        )
    thread.start()
    captureThread = thread
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
    val thread = captureThread
    thread?.interrupt()
    runCatching { thread?.join(1000) }
    captureThread = null
    runCatching { record?.release() }
    playback?.close()
    playback = null
  }

  companion object {
    const val TAG = "OpenAiVoiceController"
    const val SAMPLE_RATE = 24000

    private const val CAPTURE_CHUNK_BYTES = 2400

    // Stable CNAME of the Faro backend. The previous CloudFront distribution domain stopped
    // resolving after the AWS migration, so it must not be used again.
    private const val REALTIME_URL = "wss://api.loginea.com/realtime"

    private const val PERMISSION_RECORD_AUDIO = "android.permission.RECORD_AUDIO"
    private const val PERMISSION_BLUETOOTH_CONNECT = "android.permission.BLUETOOTH_CONNECT"

    private const val HTTP_UNAUTHORIZED = 401
    private const val CLOSE_DUPLICATE_SESSION = 4000
    private const val CLOSE_UNAUTHORIZED = 4401

    private const val AUTH_FAILED_MESSAGE =
        "Las gafas no están autorizadas: vuelve a emparejarlas con Faro"
    private const val DUPLICATE_SESSION_MESSAGE =
        "Ya hay una conversación de Faro activa en este dispositivo"
  }
}
