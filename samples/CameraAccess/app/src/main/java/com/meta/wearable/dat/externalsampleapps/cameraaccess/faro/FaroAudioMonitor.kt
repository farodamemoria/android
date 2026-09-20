package com.meta.wearable.dat.externalsampleapps.cameraaccess.faro

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread
import kotlin.math.sqrt

/**
 * Escucha continua del micrófono de las gafas (HFP/SCO) para detectar golpes secos y tos, y avisa
 * al backend. El audio nunca se guarda: solo se calculan niveles en memoria.
 */
object FaroAudioMonitor {
  private const val TAG = "FaroAudio"
  private const val SAMPLE_RATE = 16000
  private const val FRAME_MS = 20
  private const val FRAME_SAMPLES = SAMPLE_RATE * FRAME_MS / 1000
  private const val IMPACT_RMS = 9000.0
  private const val IMPACT_FACTOR = 6.0
  private const val COUGH_RMS = 3000.0
  private const val IMPACT_COOLDOWN_MS = 30000L
  private const val COUGH_COOLDOWN_MS = 30000L
  private const val SPEECH_RMS = 450.0
  private const val SPEECH_SILENCE_MS = 700L
  private const val SPEECH_MAX_MS = 6000L
  private const val SPEECH_MIN_MS = 700L
  private const val SPEECH_COOLDOWN_MS = 3000L

  @Volatile private var running = false
  private var worker: Thread? = null
  private var record: AudioRecord? = null
  private var lastImpactAt = 0L
  private var lastCoughAt = 0L
  private var appContext: Context? = null

  fun start(context: Context) {
    appContext = context.applicationContext
    if (running) return
    val app = appContext ?: return
    if (ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) !=
        PackageManager.PERMISSION_GRANTED) {
      Log.w(TAG, "Sin permiso RECORD_AUDIO; no se inicia la escucha")
      return
    }
    running = true
    worker = thread(name = "FaroAudioMonitor") { loop(app) }
  }

  /** Suelta el micrófono/SCO (p. ej. mientras se usa la cámara) para no competir por el Bluetooth. */
  fun pause() {
    if (running) {
      Log.d(TAG, "Escucha en pausa (vista previa activa)")
      stop()
    }
  }

  fun resume() {
    appContext?.let { start(it) }
  }

  fun stop() {
    running = false
    worker?.interrupt()
    worker = null
    record?.let { runCatching { it.stop() }; runCatching { it.release() } }
    record = null
  }

  private fun loop(context: Context) {
    val bufferSize = AudioRecord.getMinBufferSize(
        SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
    if (bufferSize <= 0) {
      Log.w(TAG, "Buffer de audio no válido")
      running = false
      return
    }
    val glasses = preferredGlassesDevice(context)
    val audioManager = context.getSystemService(AudioManager::class.java)
    if (glasses != null && audioManager != null) {
      val routed = runCatching {
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.setCommunicationDevice(glasses)
      }.getOrDefault(false)
      Log.i(TAG, "SCO disponible=${glasses.productName} enrutado=$routed")
      Thread.sleep(400)
    }
    val audioRecord = AudioRecord(
        MediaRecorder.AudioSource.VOICE_COMMUNICATION,
        SAMPLE_RATE,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
        bufferSize * 2,
    )
    if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
      Log.w(TAG, "AudioRecord no inicializado")
      audioRecord.release()
      running = false
      return
    }
    if (glasses != null) {
      runCatching { audioRecord.preferredDevice = glasses }
      Log.d(TAG, "Pidiendo micrófono de gafas: ${glasses.productName}")
    } else {
      Log.d(TAG, "Sin dispositivo SCO/HFP; se usará el micrófono por defecto")
    }
    record = audioRecord
    try {
      audioRecord.startRecording()
      Log.i(TAG, "Micrófono en uso: ${audioRecord.routedDevice?.productName} (${audioRecord.routedDevice?.type})")
      val samples = ShortArray(FRAME_SAMPLES)
      var ambient = 100.0
      var coughBurstAt = 0L
      var lastLogAt = 0L
      var previousRms = 0.0
      var speech = java.io.ByteArrayOutputStream()
      var speechStart = 0L
      var speechPeak = 0.0
      var speechLoudFrames = 0
      var lastVoiceAt = 0L
      var lastSpeechSentAt = 0L
      while (running && !Thread.currentThread().isInterrupted) {
        var read = 0
        while (read < samples.size) {
          val chunk = audioRecord.read(samples, read, samples.size - read)
          if (chunk <= 0) break
          read += chunk
        }
        if (read < samples.size) continue
        var sum = 0.0
        var crossings = 0
        var previous = samples[0]
        for (value in samples) {
          sum += (value.toDouble() * value)
          if ((value >= 0) != (previous >= 0)) crossings++
          previous = value
        }
        val rms = sqrt(sum / samples.size)
        ambient = ambient * 0.98 + rms * 0.02
        val now = System.currentTimeMillis()
        if (now - lastLogAt > 1000) {
          Log.d(TAG, "rms=${rms.toInt()} ambiente=${ambient.toInt()} cruces=$crossings")
          lastLogAt = now
        }
        previousRms = rms
        val speaking = speech.size() > 0 || now - lastVoiceAt < 500
        if (!speaking && rms > IMPACT_RMS && now - lastImpactAt > IMPACT_COOLDOWN_MS) {
          lastImpactAt = now
          Log.i(TAG, "Golpe seco detectado (rms=${rms.toInt()})")
          sendDetection("fall")
        }
        if (rms > SPEECH_RMS || speech.size() > 0) {
          if (speech.size() == 0) {
            speechStart = now
            speechPeak = 0.0
            speechLoudFrames = 0
          }
          if (rms > SPEECH_RMS) {
            lastVoiceAt = now
            speechLoudFrames++
            if (rms > speechPeak) speechPeak = rms
          }
          for (value in samples) {
            speech.write(value.toInt() and 0xFF)
            speech.write((value.toInt() shr 8) and 0xFF)
          }
        }
        if (speech.size() > 0 && (now - lastVoiceAt > SPEECH_SILENCE_MS || now - speechStart > SPEECH_MAX_MS)) {
          val loudMs = speechLoudFrames * FRAME_MS
          if (loudMs <= 600 && speechPeak >= 3000.0) {
            if (now - coughBurstAt in 150..3000 && now - lastCoughAt > COUGH_COOLDOWN_MS) {
              lastCoughAt = now
              Log.i(TAG, "Tos detectada (rms=${speechPeak.toInt()})")
              sendDetection("cough")
            }
            coughBurstAt = now
          } else if (loudMs >= SPEECH_MIN_MS && now - lastSpeechSentAt > SPEECH_COOLDOWN_MS) {
            lastSpeechSentAt = now
            Log.i(TAG, "Segmento de voz (${loudMs}ms de voz) -> transcripción")
            sendVoiceIntent(speech.toByteArray())
          }
          speech = java.io.ByteArrayOutputStream()
        }
      }
    } catch (error: Exception) {
      Log.w(TAG, "Escucha de audio detenida: ${error.message}")
    } finally {
      runCatching { audioRecord.stop() }
      runCatching { audioRecord.release() }
      record = null
      running = false
    }
  }

  private fun preferredGlassesDevice(context: Context): AudioDeviceInfo? {
    val manager = context.getSystemService(AudioManager::class.java) ?: return null
    runCatching {
      manager.availableCommunicationDevices
          .firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
    }.getOrNull()?.let { return it }
    return manager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        .firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
  }

  private fun sendVoiceIntent(pcm: ByteArray) {
    val wav = toWav(pcm)
    thread {
      try {
        val boundary = "faro" + System.currentTimeMillis()
        val connection = URL("${FaroApi.BASE}/v1/voice/intent").openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.connectTimeout = 10000
        connection.readTimeout = 30000
        connection.setRequestProperty("Authorization", "Bearer ${FaroApi.TOKEN}")
        connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        connection.outputStream.use { out ->
          out.write("--$boundary\r\n".toByteArray())
          out.write(
              "Content-Disposition: form-data; name=\"audio\"; filename=\"command.wav\"\r\n".toByteArray()
          )
          out.write("Content-Type: audio/wav\r\n\r\n".toByteArray())
          out.write(wav)
          out.write("\r\n--$boundary--\r\n".toByteArray())
        }
        val code = connection.responseCode
        val body = runCatching { connection.inputStream.bufferedReader().readText() }.getOrDefault("")
        Log.i(TAG, "Intención de voz -> HTTP $code $body")
        connection.disconnect()
      } catch (error: Exception) {
        Log.w(TAG, "No se pudo enviar la voz: ${error.message}")
      }
    }
  }

  private fun toWav(pcm: ByteArray): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val dataLen = pcm.size
    val byteRate = SAMPLE_RATE * 2
    fun le32(value: Int) = byteArrayOf(
        (value and 0xFF).toByte(), ((value shr 8) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(), ((value shr 24) and 0xFF).toByte())
    fun le16(value: Int) = byteArrayOf((value and 0xFF).toByte(), ((value shr 8) and 0xFF).toByte())
    out.write("RIFF".toByteArray()); out.write(le32(36 + dataLen)); out.write("WAVE".toByteArray())
    out.write("fmt ".toByteArray()); out.write(le32(16)); out.write(le16(1)); out.write(le16(1))
    out.write(le32(SAMPLE_RATE)); out.write(le32(byteRate)); out.write(le16(2)); out.write(le16(16))
    out.write("data".toByteArray()); out.write(le32(dataLen)); out.write(pcm)
    return out.toByteArray()
  }

  private fun sendDetection(signal: String) {
    val kind = if (signal == "fall") "hazard" else "episode"
    val message =
        if (signal == "fall") "Faro: posible caída o golpe fuerte detectado."
        else "Faro: se han detectado varios episodios de tos."
    thread {
      try {
        val connection = URL("${FaroApi.BASE}/v1/emergency-alerts").openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.connectTimeout = 8000
        connection.readTimeout = 8000
        connection.setRequestProperty("Authorization", "Bearer ${FaroApi.TOKEN}")
        connection.setRequestProperty("Content-Type", "application/json")
        val body =
            "{\"kind\":\"$kind\",\"spoken_message\":\"$message\",\"explicit_help_request\":true}"
        connection.outputStream.use { it.write(body.toByteArray()) }
        Log.i(TAG, "Aviso $signal ($kind) -> HTTP ${connection.responseCode}")
        connection.disconnect()
      } catch (error: Exception) {
        Log.w(TAG, "No se pudo enviar el aviso $signal: ${error.message}")
      }
    }
  }
}
