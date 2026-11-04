package com.faro.memoria.mobile

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Base64
import androidx.core.app.ActivityCompat
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject

class MobileVoiceController(
    context: Context,
    private val credentialProvider: () -> String,
    private val onStatus: (String) -> Unit,
    private val onCameraRequest: (String) -> Unit,
    private val onLocationAnswerRequest: (LocationRequest) -> Unit,
    private val onFaroText: (String) -> Unit,
    private val onUserText: (String) -> Unit = {},
) {
  private val app = context.applicationContext
  private val audioManager = app.getSystemService(AudioManager::class.java)
  private val client = OkHttpClient.Builder().retryOnConnectionFailure(true).build()
  private val running = AtomicBoolean(false)
  @Volatile private var socket: WebSocket? = null
  @Volatile private var recorder: AudioRecord? = null
  private var playback: InterruptiblePlayback? = null
  private var captureThread: Thread? = null
  private var reconnectScheduled = false

  @Synchronized fun start(): Boolean {
    if (ActivityCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
      onStatus("Permite el micrófono para hablar con Faro")
      return false
    }
    if (!running.compareAndSet(false, true)) return true
    val credential = credentialProvider()
    if (credential.isBlank()) {
      running.set(false)
      onStatus("Empareja este móvil antes de hablar con Faro")
      return false
    }
    connect()
    return true
  }

  private fun connect() {
    val credential = credentialProvider()
    if (credential.isBlank()) {
      running.set(false)
      onStatus("Empareja este móvil antes de hablar con Faro")
      return
    }
    audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
    audioManager.isSpeakerphoneOn = true
    onStatus("Conectando con Faro…")
    val request = Request.Builder().url(BuildConfig.REALTIME_URL).apply {
      header("Authorization", "Bearer $credential")
      header("X-Faro-Camera-Source", "phone")
    }.build()
    socket?.close(1000, "reconectando")
    socket = client.newWebSocket(request, listener)
  }

  private fun scheduleReconnect(statusMessage: String) {
    if (!running.get() || reconnectScheduled) return
    reconnectScheduled = true
    releaseAudio()
    socket = null
    onStatus(statusMessage)
    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
      synchronized(this@MobileVoiceController) {
        reconnectScheduled = false
        if (running.get()) connect()
      }
    }, 2500)
  }

  @Synchronized fun stop() {
    running.set(false)
    socket?.close(1000, "Sesión finalizada")
    socket = null
    releaseAudio()
    audioManager.mode = AudioManager.MODE_NORMAL
    audioManager.isSpeakerphoneOn = false
    onStatus("Faro está en pausa")
  }

  fun submitImage(callId: String, jpeg: ByteArray) {
    val message = JSONObject().put("type", "camera_image").put("call_id", callId)
        .put("mime_type", "image/jpeg").put("data", Base64.encodeToString(jpeg, Base64.NO_WRAP)).toString()
    if (socket?.send(message) != true) onStatus("No se pudo enviar la imagen")
  }

  /** KAN-68: tells the backend an image could not be captured, so it never retries and reopens the camera. */
  fun submitCameraError(callId: String, message: String) {
    val event = JSONObject().put("type", "camera_error").put("call_id", callId).put("message", message).toString()
    socket?.send(event)
  }

  internal fun submitLocationAnswer(result: LocationAnswerResult) {
    if (socket?.send(result.toWireMessage()) != true) {
      onStatus("No se pudo enviar la respuesta de ubicación")
    }
  }

  private val listener = object : WebSocketListener() {
    override fun onOpen(webSocket: WebSocket, response: Response): Unit = synchronized(this@MobileVoiceController) {
      if (!running.get() || socket !== webSocket) return@synchronized
      reconnectScheduled = false
      if (!initAudio()) { stop(); return@synchronized }
      onStatus("Faro está escuchando")
      captureThread = Thread({ capture(webSocket) }, "Faro-Mobile-Audio").also { it.start() }
    }
    override fun onMessage(webSocket: WebSocket, bytes: ByteString): Unit = synchronized(this@MobileVoiceController) {
      if (!running.get() || socket !== webSocket) return@synchronized
      if (playback?.enqueue(bytes.toByteArray()) == false) {
        stop()
        onStatus("La reproducción se interrumpió; vuelve a iniciar la conversación")
      }
    }
    override fun onMessage(webSocket: WebSocket, text: String): Unit = synchronized(this@MobileVoiceController) {
      if (!running.get() || socket !== webSocket) return@synchronized
      if (text.startsWith("AUDIO_START:")) {
        runCatching { JSONObject(text.removePrefix("AUDIO_START:")) }.onSuccess {
          playback?.begin(it.optString("item_id"), it.optInt("content_index"))
        }
      } else if (text == "AUDIO_CLEAR") {
        playback?.clearAll()?.forEach {
          webSocket.send(JSONObject().put("type", "audio_playback_cleared")
              .put("item_id", it.itemId).put("content_index", it.contentIndex)
              .put("audio_end_ms", it.audioEndMs).toString())
        }
      } else if (text.startsWith(FaroTextProtocol.USER_PREFIX)) {
        FaroTextProtocol.parseUser(text)?.let(onUserText)
      } else if (text.startsWith(FaroTextProtocol.FARO_PREFIX)) {
        FaroTextProtocol.parse(text)?.let(onFaroText)
      } else if (text.startsWith("CAMERA_REQUEST:")) {
        val raw = text.removePrefix("CAMERA_REQUEST:")
        val callId = runCatching { JSONObject(raw).optString("call_id") }.getOrDefault(raw)
        if (callId.isNotBlank()) onCameraRequest(callId)
      } else {
        LocationAnswerProtocol.request(text)?.let(onLocationAnswerRequest)
        if (text.startsWith("ERROR:")) onStatus("Faro no está disponible ahora")
      }
    }
    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?): Unit = synchronized(this@MobileVoiceController) {
      if (socket !== webSocket) return@synchronized
      if (running.get()) scheduleReconnect("O conexión caeu; reconectando…") else stop()
    }
    override fun onClosed(webSocket: WebSocket, code: Int, reason: String): Unit = synchronized(this@MobileVoiceController) {
      if (socket !== webSocket) return@synchronized
      if (running.get()) scheduleReconnect("Faro perdeu a conexión; reconectando…") else stop()
    }
  }

  private fun initAudio(): Boolean {
    // Permission can be revoked while the WebSocket is connecting, so repeat the start-time check
    // immediately before constructing AudioRecord.
    if (
        ActivityCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
    ) {
      onStatus("Permite el micrófono para hablar con Faro")
      return false
    }
    val inSize = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, FORMAT)
    val outSize = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, FORMAT)
    if (inSize <= 0 || outSize <= 0) return false
    recorder = AudioRecord(MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO, FORMAT, inSize * 2)
    val player = AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .setAudioFormat(AudioFormat.Builder().setEncoding(FORMAT).setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
        .setBufferSizeInBytes(outSize * 4).setTransferMode(AudioTrack.MODE_STREAM).build()
    if (recorder?.state != AudioRecord.STATE_INITIALIZED || player.state != AudioTrack.STATE_INITIALIZED) { player.release(); releaseAudio(); return false }
    player.play()
    val activeRecord = recorder
    playback = InterruptiblePlayback(object : InterruptiblePlayback.Sink {
      override fun write(data: ByteArray, offset: Int, count: Int) = player.write(data, offset, count, AudioTrack.WRITE_NON_BLOCKING)
      override fun playedFrames() = player.playbackHeadPosition.toLong() and 0xffffffffL
      override fun flush() { player.pause(); player.flush(); player.play() }
      override fun release() { try { player.pause(); player.flush() } finally { player.release() } }
    }, onError = {
      android.os.Handler(android.os.Looper.getMainLooper()).post {
        synchronized(this@MobileVoiceController) {
          if (recorder === activeRecord && playback != null) { stop(); onStatus("Falló la reproducción de audio") }
        }
      }
    })
    return true
  }

  private fun capture(webSocket: WebSocket) {
    val record = recorder ?: return
    val buffer = ByteArray(2400)
    runCatching {
      record.startRecording()
      while (running.get() && socket === webSocket && recorder === record && !Thread.currentThread().isInterrupted) {
        val count = record.read(buffer, 0, buffer.size)
        if (count > 0) {
          normalize(buffer, count)
          if (!webSocket.send(buffer.toByteString(0, count))) break
        }
      }
    }
  }

  /** AGC simple: amplifica tramas flojas (hasta 8x) para que Faro oiga sonidos bajos (p.ej. fuego). */
  private fun normalize(buffer: ByteArray, count: Int) {
    var maxAbs = 0
    var i = 0
    while (i + 1 < count) {
      val s = ((buffer[i].toInt() and 0xFF) or (buffer[i + 1].toInt() shl 8)).toShort().toInt()
      val a = if (s < 0) -s else s
      if (a > maxAbs) maxAbs = a
      i += 2
    }
    if (maxAbs < 300) return
    var gain = 20000.0 / maxAbs
    if (gain < 1.0) gain = 1.0
    if (gain > 8.0) gain = 8.0
    if (gain <= 1.0) return
    i = 0
    while (i + 1 < count) {
      val s = ((buffer[i].toInt() and 0xFF) or (buffer[i + 1].toInt() shl 8)).toShort().toInt()
      var v = (s * gain).toInt()
      if (v > 32767) v = 32767 else if (v < -32768) v = -32768
      buffer[i] = (v and 0xFF).toByte()
      buffer[i + 1] = ((v shr 8) and 0xFF).toByte()
      i += 2
    }
  }

  private fun releaseAudio() {
    val record = recorder
    recorder = null
    runCatching { record?.stop() }
    captureThread?.interrupt()
    captureThread?.join(1000)
    captureThread = null
    record?.release()
    playback?.close()
    playback = null
  }

  companion object { const val RATE = 24_000; const val FORMAT = AudioFormat.ENCODING_PCM_16BIT }
}
