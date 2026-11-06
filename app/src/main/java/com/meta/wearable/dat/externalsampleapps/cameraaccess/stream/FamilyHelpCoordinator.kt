package com.meta.wearable.dat.externalsampleapps.cameraaccess.stream

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import android.util.Base64
import androidx.core.content.ContextCompat
import com.meta.wearable.dat.externalsampleapps.cameraaccess.BuildConfig
import java.io.IOException
import java.time.Instant
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject

data class VerifiedLocationAnswer(
    val status: String,
    val message: String,
    val placeLabel: String? = null,
    val accuracyMeters: Double? = null,
)

/** Turns an explicit voice help request into an authenticated family alert and temporary tracking. */
class FamilyHelpCoordinator(context: Context, private val scope: CoroutineScope) : AutoCloseable {
  private val appContext = context.applicationContext
  private val locationManager = appContext.getSystemService(LocationManager::class.java)
  private val client = OkHttpClient.Builder().retryOnConnectionFailure(true).build()
  private var trackingJob: Job? = null

  suspend fun handle(request: FamilyHelpRequest, evidenceJpeg: ByteArray? = null): Result<String> = runCatching {
    val payload = JSONObject()
        .put("kind", if (request.kind == "lost") "lost" else "episode")
        .put("spoken_message", request.spokenMessage)
        .put("explicit_help_request", true)

    var locationIncluded = false
    runCatching {
      ensureLocationPermission()
      val first = recentLastKnownLocation() ?: currentLocation()
          ?: error("No he podido obtener la ubicación del teléfono")
      val session = post(
          "/v1/location-sessions",
          JSONObject().put("explicit_help_request", true).put("duration_minutes", 60),
      )
      val sessionId = session.getString("id")
      updateLocation(sessionId, first)
      val trackingUrl = "${BuildConfig.AURA_BACKEND_URL.trimEnd('/')}/track/${session.getString("share_token")}" 
      payload.put("latitude", first.latitude)
          .put("longitude", first.longitude)
          .put("tracking_url", trackingUrl)
      startTracking(sessionId)
      locationIncluded = true
    }

    if (evidenceJpeg != null && evidenceJpeg.isNotEmpty()) {
      payload.put("evidence_image_base64", Base64.encodeToString(evidenceJpeg, Base64.NO_WRAP))
          .put("evidence_mime_type", "image/jpeg")
          .put("evidence_captured_at", Instant.now().toString())
    }

    val alert = post("/v1/emergency-alerts", payload)
    buildString {
      append(deliveryMessage(alert))
      if (locationIncluded) append(" con la ubicación")
      if (evidenceJpeg != null) append(" e unha foto recente")
    }
  }

  /** Answers a current-location question without ever treating a stale fix as fresh. */
  suspend fun answerCurrentLocation(): Result<VerifiedLocationAnswer> = runCatching {
    ensureLocationPermission()
    val location = recentLastKnownLocation() ?: currentLocation()
        ?: error("No he podido obtener la ubicación del teléfono")
    check(location.time > 0) { "La ubicación del teléfono no tiene una hora verificable" }
    val payload = JSONObject()
        .put("latitude", location.latitude)
        .put("longitude", location.longitude)
        .put("accuracy_meters", location.accuracy.toDouble())
        .put("recorded_at", Instant.ofEpochMilli(location.time).toString())
    val answer = post("/v1/location/answer", payload)
    VerifiedLocationAnswer(
        status = answer.optString("status", "unavailable"),
        message = answer.optString("message", "No he podido comprobar dónde estás"),
        placeLabel = answer.optString("place_label").takeIf { it.isNotBlank() },
        accuracyMeters = answer.optDouble("accuracy_meters").takeIf { !it.isNaN() },
    )
  }

  /** Prepares a short-lived location link for a visually confirmed automatic safety alert. */
  suspend fun ensureSafetyTracking(): Result<Unit> = runCatching {
    ensureLocationPermission()
    val first = recentLastKnownLocation() ?: currentLocation()
        ?: error("No he podido obtener la ubicación del teléfono")
    val session = post(
        "/v1/location-sessions",
        JSONObject().put("automatic_hazard", true).put("duration_minutes", 60),
    )
    val sessionId = session.getString("id")
    updateLocation(sessionId, first)
    startTracking(sessionId)
  }

  private fun ensureLocationPermission() {
    val fine = ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION)
    val coarse = ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_COARSE_LOCATION)
    check(fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED) {
      "Falta o permiso de localización no teléfono"
    }
  }

  private suspend fun currentLocation(): Location? {
    val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        .filter { provider -> locationManager.isProviderEnabled(provider) }
    for (provider in providers) {
      val location = withTimeoutOrNull(7_000) { requestCurrentLocation(provider) }
      if (location != null) return location
    }
    return newestLastKnownLocation(maxAgeMillis = 15 * 60_000L)
  }

  private suspend fun requestCurrentLocation(provider: String): Location? =
    suspendCancellableCoroutine { continuation ->
      val cancellation = CancellationSignal()
      continuation.invokeOnCancellation { cancellation.cancel() }
      val executor = Executor { command -> command.run() }
      try {
        locationManager.getCurrentLocation(provider, cancellation, executor) { location ->
          if (continuation.isActive) continuation.resume(location)
        }
      } catch (_: SecurityException) {
        if (continuation.isActive) continuation.resume(null)
      }
    }

  private fun recentLastKnownLocation(): Location? {
    return newestLastKnownLocation(maxAgeMillis = 120_000L)
  }

  private fun newestLastKnownLocation(maxAgeMillis: Long): Location? {
    val fine = ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION)
    val coarse = ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_COARSE_LOCATION)
    if (fine != PackageManager.PERMISSION_GRANTED && coarse != PackageManager.PERMISSION_GRANTED) return null
    val newest = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        .mapNotNull { provider -> runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull() }
        .maxByOrNull { it.time }
    return newest?.takeIf { it.time > 0 && System.currentTimeMillis() - it.time <= maxAgeMillis }
  }

  private fun startTracking(sessionId: String) {
    trackingJob?.cancel()
    trackingJob = scope.launch {
      repeat(180) {
        if (!isActive) return@launch
        delay(20_000)
        currentLocation()?.let { runCatching { updateLocation(sessionId, it) } }
      }
    }
  }

  private suspend fun updateLocation(sessionId: String, location: Location) {
    val battery = appContext.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        ?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)?.takeIf { it >= 0 }
    val payload = JSONObject().put("latitude", location.latitude).put("longitude", location.longitude)
        .put("accuracy_meters", location.accuracy.toDouble())
    if (battery != null) payload.put("battery_percent", battery)
    put("/v1/location-sessions/$sessionId/location", payload)
  }

  private fun deliveryMessage(alert: JSONObject): String = when (alert.optString("delivery_status")) {
    "sent" -> "Aviso enviado correctamente á familia"
    "test_mode" -> "O aviso quedou preparado para a familia"
    else -> "No he podido confirmar el aviso; seguiré intentándolo"
  }

  private suspend fun post(path: String, payload: JSONObject) = request("POST", path, payload)
  private suspend fun put(path: String, payload: JSONObject) = request("PUT", path, payload)

  private suspend fun request(method: String, path: String, payload: JSONObject): JSONObject =
      suspendCancellableCoroutine { continuation ->
        val request = Request.Builder().url(BuildConfig.AURA_BACKEND_URL.trimEnd('/') + path)
            .header("Authorization", "Bearer ${BuildConfig.AURA_ACCESS_TOKEN}")
            .method(method, payload.toString().toRequestBody("application/json".toMediaType())).build()
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
          override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWith(Result.failure(e)) }
          override fun onResponse(call: Call, response: Response) { response.use { if (!continuation.isActive) return; val body = it.body.string(); if (!it.isSuccessful) continuation.resumeWith(Result.failure(IOException(JSONObject(body).optString("detail", "Backend ${it.code}")))) else continuation.resume(JSONObject(body)) } }
        })
      }

  override fun close() { trackingJob?.cancel(); client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
}
