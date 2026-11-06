/*
 * Faro da Memoria — KAN-35
 * Servicio en primer plano que mantiene la escucha ambiental: detecta patrones de
 * tos, los reporta al backend AURA y gestiona la pregunta de comprobación («¿Estás
 * bien?») y la respuesta del paciente (reconocimiento de voz local).
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.stream

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.core.app.NotificationCompat
import com.meta.wearable.dat.externalsampleapps.cameraaccess.BuildConfig
import com.meta.wearable.dat.externalsampleapps.cameraaccess.MainActivity
import com.meta.wearable.dat.externalsampleapps.cameraaccess.R
import java.io.IOException
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class AmbientListeningService : Service() {

  companion object {
    private const val TAG = "AmbientListening"
    private const val CHANNEL_ID = "ambient_listening_channel"
    private const val NOTIFICATION_ID = 1002
    private const val SAMPLE_RATE = 16000
    private const val TICK_INTERVAL_MS = 15_000L
    private const val ACTION_STOP =
        "com.meta.wearable.dat.externalsampleapps.cameraaccess.stream.AMBIENT_STOP"
    private const val WAKELOCK_TAG = "CameraAccess::AmbientListeningWakeLock"
    private const val WAKELOCK_TIMEOUT_MS = 60L * 60L * 1000L
    private val JSON_MEDIA_TYPE = "application/json".toMediaType()

    fun start(context: Context) {
      val intent = Intent(context, AmbientListeningService::class.java).apply {
        `package` = context.packageName
      }
      context.startForegroundService(intent)
    }

    fun stop(context: Context) {
      val intent = Intent(context, AmbientListeningService::class.java).apply {
        `package` = context.packageName
        action = ACTION_STOP
      }
      context.startForegroundService(intent)
    }
  }

  inner class LocalBinder : Binder() {
    fun getService(): AmbientListeningService = this@AmbientListeningService
  }

  private val binder = LocalBinder()
  private val client = OkHttpClient.Builder().retryOnConnectionFailure(true).build()
  private val running = AtomicBoolean(false)
  private val activeEpisode = AtomicBoolean(false)
  private val listeningForResponse = AtomicBoolean(false)

  private var wakeLock: PowerManager.WakeLock? = null
  private var audioRecord: AudioRecord? = null
  private var captureThread: Thread? = null
  private var tts: TextToSpeech? = null
  private var ttsReady = false
  private var speechRecognizer: SpeechRecognizer? = null
  private var tickHandler: Handler? = null
  private val tickRunnable = object : Runnable {
    override fun run() {
      if (!running.get()) return
      postJson("/v1/acoustic-events/tick", JSONObject()) { _, response ->
        val action = response.optString("action")
        if (action == "ask") {
          speakQuestion(response.optString("question", "¿Estás bien?"))
          listenForResponse()
        } else if (action == "idle") {
          activeEpisode.set(false)
          tickHandler?.removeCallbacks(this)
        } else {
          activeEpisode.set(false)
          tickHandler?.removeCallbacks(this)
        }
      }
      tickHandler?.postDelayed(this, TICK_INTERVAL_MS)
    }
  }

  override fun onCreate() {
    super.onCreate()
    createNotificationChannel()
    startForeground(
        NOTIFICATION_ID, buildNotification(), if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
          ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
          0
        },
    )
  }

  override fun onBind(intent: Intent?): IBinder = binder

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action == ACTION_STOP) {
      stopSelf()
      return START_NOT_STICKY
    }
    if (running.compareAndSet(false, true)) {
      acquireWakeLock()
      startAmbientCapture()
      initTts()
      tickHandler = Handler(Looper.getMainLooper())
      Log.i(TAG, "Escucha ambiental iniciada")
    }
    return START_STICKY
  }

  private fun startAmbientCapture() {
    val minBuffer = AudioRecord.getMinBufferSize(
        SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
    if (minBuffer <= 0) {
      Log.w(TAG, "Sin soporte de grabación ambiental")
      return
    }
    val recorder = try {
      AudioRecord(
          MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
          AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuffer * 2)
    } catch (error: SecurityException) {
      Log.w(TAG, "Permiso de micrófono denegado", error)
      return
    }
    if (recorder.state != AudioRecord.STATE_INITIALIZED) {
      recorder.release()
      return
    }
    audioRecord = recorder
    recorder.startRecording()
    val detector = AcousticEventDetector(sampleRate = SAMPLE_RATE)
    captureThread = Thread({
      val buffer = ShortArray(SAMPLE_RATE / 4) // 250 ms
      while (running.get()) {
        val read = recorder.read(buffer, 0, buffer.size)
        if (read <= 0) continue
        for (detection in detector.process(buffer.copyOfRange(0, read))) {
          reportDetection(detection)
        }
      }
    }, "ambient-capture").also { it.start() }
  }

  private fun reportDetection(detection: AcousticDetection) {
    if (listeningForResponse.get()) return
    val payload = JSONObject()
        .put("signal", detection.signal)
        .put("confidence", detection.confidence.toDouble())
    postJson("/v1/acoustic-events", payload) { _, response ->
      when (response.optString("action")) {
        "ask" -> {
          activeEpisode.set(true)
          speakQuestion(response.optString("question", "¿Estás bien?"))
          listenForResponse()
          tickHandler?.removeCallbacks(tickRunnable)
          tickHandler?.postDelayed(tickRunnable, TICK_INTERVAL_MS)
        }
        "escalate" -> activeEpisode.set(false)
      }
    }
  }

  private fun speakQuestion(question: String) {
    if (!ttsReady) return
    tts?.speak(question, TextToSpeech.QUEUE_FLUSH, null, "faro-acoustic-${System.currentTimeMillis()}")
  }

  private fun listenForResponse() {
    if (!listeningForResponse.compareAndSet(false, true)) return
    speechRecognizer?.destroy()
    val recognizer = SpeechRecognizer.createSpeechRecognizer(applicationContext)
    speechRecognizer = recognizer
    recognizer.setRecognitionListener(object : RecognitionListener {
      override fun onReadyForSpeech(params: Bundle?) {}
      override fun onBeginningOfSpeech() {}
      override fun onRmsChanged(rmsdB: Float) {}
      override fun onBufferReceived(buffer: ByteArray?) {}
      override fun onEndOfSpeech() {}
      override fun onError(error: Int) {
        listeningForResponse.set(false)
      }
      override fun onResults(results: Bundle?) {
        listeningForResponse.set(false)
        val text = results
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
        if (!text.isNullOrBlank()) {
          val payload = JSONObject().put("text", text)
          postJson("/v1/acoustic-events/response", payload) { _, response ->
            if (response.optString("action") != "ask") {
              activeEpisode.set(false)
              tickHandler?.removeCallbacks(tickRunnable)
            }
          }
        }
      }
      override fun onPartialResults(partialResults: Bundle?) {}
      override fun onEvent(eventType: Int, params: Bundle?) {}
    })
    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
      putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
      putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-ES")
      putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
    }
    recognizer.startListening(intent)
  }

  private fun postJson(path: String, payload: JSONObject, onResponse: (Int, JSONObject) -> Unit) {
    val request = Request.Builder().url(BuildConfig.AURA_BACKEND_URL.trimEnd('/') + path)
        .header("Authorization", "Bearer ${BuildConfig.AURA_ACCESS_TOKEN}")
        .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE)).build()
    client.newCall(request).enqueue(object : okhttp3.Callback {
      override fun onFailure(call: okhttp3.Call, e: IOException) {
        Log.w(TAG, "Fallo al reportar a $path", e)
      }
      override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
        response.use {
          val body = it.body?.string() ?: "{}"
          val parsed = runCatching { JSONObject(body) }.getOrDefault(JSONObject())
          onResponse(it.code, parsed)
        }
      }
    })
  }

  private fun initTts() {
    tts = TextToSpeech(applicationContext) { status ->
      ttsReady = status == TextToSpeech.SUCCESS
      tts?.language = Locale.forLanguageTag("es-ES")
    }
  }

  private fun acquireWakeLock() {
    if (wakeLock != null) return
    val manager = getSystemService(POWER_SERVICE) as PowerManager
    wakeLock = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG)
    wakeLock?.acquire(WAKELOCK_TIMEOUT_MS)
  }

  private fun createNotificationChannel() {
    val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
    manager.createNotificationChannel(
        NotificationChannel(CHANNEL_ID, "Escucha ambiental", NotificationManager.IMPORTANCE_LOW))
  }

  private fun buildNotification(): Notification {
    val pending = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    return NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.smart_glasses_icon)
        .setContentTitle("Faro")
        .setContentText("Escuchando para cuidarte")
        .setContentIntent(pending)
        .setOngoing(true)
        .build()
  }

  override fun onDestroy() {
    running.set(false)
    tickHandler?.removeCallbacks(tickRunnable)
    tickHandler = null
    speechRecognizer?.destroy()
    speechRecognizer = null
    audioRecord?.let { runCatching { it.stop() }; it.release() }
    audioRecord = null
    tts?.stop()
    tts?.shutdown()
    tts = null
    wakeLock?.let { if (it.isHeld) it.release() }
    wakeLock = null
    client.dispatcher.executorService.shutdown()
    client.connectionPool.evictAll()
    super.onDestroy()
  }
}
