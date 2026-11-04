package com.faro.memoria.mobile

import android.Manifest
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.io.File
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class MainActivity : ComponentActivity() {
  private lateinit var controller: MobileVoiceController
  private lateinit var locationAnswers: MobileLocationAnswerCoordinator
  private lateinit var status: TextView
  private lateinit var userSubtitle: TextView
  private lateinit var faroSubtitle: TextView
  private lateinit var toggle: Button
  private lateinit var preview: ImageView
  private lateinit var previewHint: TextView
  private lateinit var pairingCode: EditText
  private lateinit var pair: Button
  private lateinit var cameraOverlay: FrameLayout
  private lateinit var cameraPreviewView: PreviewView
  private lateinit var shutterButton: Button
  private var imageCapture: ImageCapture? = null
  private var cameraProvider: ProcessCameraProvider? = null
  private val http = OkHttpClient()
  private val preferences by lazy { getSharedPreferences("faro-mobile", MODE_PRIVATE) }
  private var listening = false
  private val cameraGate = CameraCaptureGate()
  private var autoCapturePending = false

  private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
    if (granted[Manifest.permission.RECORD_AUDIO] == true) startFaro() else status.text = "El micrófono es necesario"
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    if (BuildConfig.DEBUG && intent.getBooleanExtra("faro_test_crash", false)) {
      throw RuntimeException("Prueba KAN-191 del log de errores")
    }
    status = TextView(this).apply { text = "Faro está en pausa"; textSize = 20f; setTextColor(Color.rgb(18,63,71)); gravity = Gravity.CENTER }
    userSubtitle = TextView(this).apply {
      textSize = 20f
      setTextColor(Color.rgb(246,240,228))
      setBackgroundColor(Color.rgb(90,110,105))
      gravity = Gravity.CENTER
      setPadding(24, 14, 24, 14)
      visibility = android.view.View.GONE
    }
    faroSubtitle = TextView(this).apply {
      textSize = 22f
      setTextColor(Color.rgb(246,240,228))
      setBackgroundColor(Color.rgb(18,63,71))
      gravity = Gravity.CENTER
      setPadding(24, 20, 24, 20)
      visibility = android.view.View.GONE
    }
    toggle = Button(this).apply { text = "Hablar con Faro"; setOnClickListener { if (listening) stopFaro() else requestStart() } }
    preview = ImageView(this).apply { adjustViewBounds = true; scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(Color.rgb(225,232,228)) }
    previewHint = TextView(this).apply {
      text = "Cámara en pausa\n\nPide una foto durante la conversación o usa el botón de abajo."
      textSize = 16f
      setTextColor(Color.rgb(90,110,105))
      gravity = Gravity.CENTER
      setPadding(24,24,24,24)
      setBackgroundColor(Color.rgb(225,232,228))
    }
    val previewBox = FrameLayout(this).apply {
      addView(preview, FrameLayout.LayoutParams(-1,-1))
      addView(previewHint, FrameLayout.LayoutParams(-1,-1))
    }
    pairingCode = EditText(this).apply { hint = "Código de emparejamiento"; gravity = Gravity.CENTER; isAllCaps = true }
    pair = Button(this).apply { text = "Emparejar este móvil"; setOnClickListener { claimPairing() } }
    val photo = Button(this).apply { text = "Hacer foto con el móvil"; setOnClickListener { autoCapturePending = false; if (cameraGate.beginUser()) showCameraOverlay() } }
    val layout = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setBackgroundColor(Color.rgb(246,240,228))
      addView(TextView(this@MainActivity).apply { text = "Faro Móvil"; textSize = 34f; setTextColor(Color.rgb(18,63,71)); gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=32 })
      addView(status, LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=32 })
      addView(pairingCode, LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=8 })
      addView(pair, LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=24 })
      addView(previewBox, LinearLayout.LayoutParams(-1,0,1f).apply { bottomMargin=24 })
      addView(userSubtitle, LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=6 })
      addView(faroSubtitle, LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=16 })
      addView(toggle, LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=16 })
      addView(photo, LinearLayout.LayoutParams(-1,-2))
    }
    cameraPreviewView = PreviewView(this)
    shutterButton = Button(this).apply { text = "Hacer foto"; setOnClickListener { takePhoto() } }
    // KAN-68: observador unico (antes se registraba uno por cada apertura y la camara se relanzaba).
    cameraPreviewView.previewStreamState.observe(this) { state ->
      val streaming = state == PreviewView.StreamState.STREAMING
      shutterButton.isEnabled = streaming
      if (streaming && autoCapturePending) {
        autoCapturePending = false
        cameraPreviewView.postDelayed({ takePhoto() }, 400)
      }
    }
    val cancelCamera = Button(this).apply { text = "Cancelar"; setOnClickListener { closeCamera(cancelled = true) } }
    cameraOverlay = FrameLayout(this).apply {
      visibility = android.view.View.GONE
      setBackgroundColor(Color.rgb(10, 20, 22))
      addView(cameraPreviewView, FrameLayout.LayoutParams(-1,-1))
      addView(shutterButton, FrameLayout.LayoutParams(-2,-2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = 150 })
      addView(cancelCamera, FrameLayout.LayoutParams(-2,-2, Gravity.TOP or Gravity.END).apply { topMargin = 110; rightMargin = 40 })
    }
    val root = FrameLayout(this).apply {
      addView(layout, FrameLayout.LayoutParams(-1,-1))
      addView(cameraOverlay, FrameLayout.LayoutParams(-1,-1))
    }
    setContentView(root)
    androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(layout) { view, insets ->
      val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
      view.setPadding(48, 80 + bars.top, 48, 48 + bars.bottom)
      insets
    }
    androidx.core.view.ViewCompat.requestApplyInsets(layout)
    initCamera()
    controller = MobileVoiceController(
      this,
      { preferences.getString("device_credential", "").orEmpty() },
      { runOnUiThread { status.text = it } },
      { callId -> runOnUiThread { onCameraRequested(callId) } },
      { callId -> locationAnswers.answer(callId) },
      { text -> runOnUiThread { faroSubtitle.text = "Faro: $text"; faroSubtitle.visibility = android.view.View.VISIBLE } },
      { text -> runOnUiThread { userSubtitle.text = "Tú: $text"; userSubtitle.visibility = android.view.View.VISIBLE } },
    )
    locationAnswers = MobileLocationAnswerCoordinator(
      this,
      { preferences.getString("device_credential", "").orEmpty() },
      { result -> controller.submitLocationAnswer(result) },
    )
    updatePairingState()
  }

  private fun initCamera() {
    val providerFuture = ProcessCameraProvider.getInstance(this)
    providerFuture.addListener({
      cameraProvider = runCatching { providerFuture.get() }.getOrNull()
    }, ContextCompat.getMainExecutor(this))
  }

  /** KAN-145: cámara integrada (CameraX) para no perder la sesión de voz al hacer la foto. */
  private fun showCameraOverlay() {
    cameraOverlay.visibility = android.view.View.VISIBLE
    shutterButton.isEnabled = false
    val provider = cameraProvider ?: return
    val cameraPreview = Preview.Builder().build().also { it.setSurfaceProvider(cameraPreviewView.surfaceProvider) }
    imageCapture = ImageCapture.Builder()
        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
        .build()
    try {
      provider.unbindAll()
      provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, cameraPreview, imageCapture)
    } catch (error: Exception) {
      cameraOverlay.visibility = android.view.View.GONE
      status.text = "No he podido abrir la cámara"
      cameraGate.finish()?.let { pending -> controller.submitCameraError(pending, "No he podido abrir la cámara") }
    }
  }

  private fun takePhoto() {
    if (!shutterButton.isEnabled) return
    val capture = imageCapture ?: return
    val file = File(cacheDir, "faro-photo.jpg")
    val output = ImageCapture.OutputFileOptions.Builder(file).build()
    capture.takePicture(output, ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageSavedCallback {
      override fun onImageSaved(results: ImageCapture.OutputFileResults) = finishPhoto(file, null)
      override fun onError(exception: ImageCaptureException) = finishPhoto(null, exception.message ?: "erro")
    })
  }

  private fun finishPhoto(file: File?, errorMessage: String?) {
    val callId = cameraGate.finish()
    cameraOverlay.visibility = android.view.View.GONE
    cameraProvider?.unbindAll()
    if (errorMessage != null) {
      status.text = "No he podido hacer la foto"
      callId?.let { controller.submitCameraError(it, "No he podido hacer la foto") }
      return
    }
    if (file == null) return
    preview.setImageBitmap(BitmapFactory.decodeFile(file.absolutePath))
    previewHint.visibility = android.view.View.GONE
    callId?.let { controller.submitImage(it, compressJpegForSend(file)) }
  }

  /** KAN-150: reduce la foto antes de enviarla (evita superar el limite del socket/backend). */
  private fun compressJpegForSend(file: java.io.File, maxDimension: Int = 1280, quality: Int = 75): ByteArray {
    return try {
      val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
      BitmapFactory.decodeFile(file.absolutePath, bounds)
      var sample = 1
      val maxSide = maxOf(bounds.outWidth, bounds.outHeight)
      while (maxSide / sample > maxDimension) sample *= 2
      val options = BitmapFactory.Options().apply { inSampleSize = sample }
      val bitmap = BitmapFactory.decodeFile(file.absolutePath, options) ?: return file.readBytes()
      val stream = java.io.ByteArrayOutputStream()
      bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, stream)
      bitmap.recycle()
      stream.toByteArray()
    } catch (error: Exception) {
      file.readBytes()
    }
  }

  private fun closeCamera(cancelled: Boolean) {
    val callId = cameraGate.finish()
    cameraOverlay.visibility = android.view.View.GONE
    cameraProvider?.unbindAll()
    if (cancelled && callId != null) controller.submitCameraError(callId, "El usuario canceló la foto")
  }

  private fun updatePairingState() {
    val linked = preferences.getString("device_credential", "").orEmpty().isNotBlank()
    pairingCode.visibility = if (linked) android.view.View.GONE else android.view.View.VISIBLE
    pair.visibility = if (linked) android.view.View.GONE else android.view.View.VISIBLE
    if (linked) status.text = "Móvil emparejado. Faro está en pausa"
  }

  private fun claimPairing() {
    val code = pairingCode.text.toString().trim().uppercase()
    if (code.length != 8) { status.text = "Introduce el código de ocho caracteres"; return }
    status.text = "Emparejando el móvil…"
    pair.isEnabled = false
    Thread {
      val result = runCatching {
        val body = JSONObject().put("code", code).toString()
          .toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url("${BuildConfig.API_URL}/v1/pairing/claim").post(body).build()
        http.newCall(request).execute().use { response ->
          if (!response.isSuccessful) error("pairing rejected")
          JSONObject(response.body?.string().orEmpty()).getString("device_credential")
        }
      }
      runOnUiThread {
        pair.isEnabled = true
        result.onSuccess { credential ->
          preferences.edit().putString("device_credential", credential).apply()
          pairingCode.text.clear()
          pairingCode.clearFocus()
          getSystemService(InputMethodManager::class.java)
            .hideSoftInputFromWindow(pairingCode.windowToken, 0)
          updatePairingState()
        }.onFailure { status.text = "El código no es válido o ha caducado" }
      }
    }.start()
  }

  /** KAN-68: abre la cámara una sola vez por petición; el resto se responden sin reabrirla. */
  private fun onCameraRequested(callId: String) {
    if (!cameraGate.beginAuto(callId)) {
      controller.submitCameraError(callId, "No puedo abrir la cámara ahora")
      return
    }
    autoCapturePending = true
    showCameraOverlay()
  }

  private fun requestStart() {
    permissions.launch(arrayOf(
      Manifest.permission.RECORD_AUDIO,
      Manifest.permission.CAMERA,
      Manifest.permission.ACCESS_COARSE_LOCATION,
      Manifest.permission.ACCESS_FINE_LOCATION,
    ))
  }
  private fun startFaro() { if (controller.start()) { listening=true; toggle.text="Detener Faro"; clearSubtitles() } }
  private fun stopFaro() { controller.stop(); listening=false; toggle.text="Hablar con Faro"; clearSubtitles() }
  private fun clearSubtitles() {
    userSubtitle.text = ""; userSubtitle.visibility = android.view.View.GONE
    faroSubtitle.text = ""; faroSubtitle.visibility = android.view.View.GONE
  }
  override fun onDestroy() { controller.stop(); locationAnswers.close(); cameraProvider?.unbindAll(); super.onDestroy() }
}
