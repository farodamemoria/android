package com.faro.memoria.mobile

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import java.io.IOException
import java.time.Instant
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/** Resolves a voice location question using only permissioned phone location data. */
internal class MobileLocationAnswerCoordinator(
    context: Context,
    private val credentialProvider: () -> String,
    private val onResult: (LocationAnswerResult) -> Unit,
) : AutoCloseable {
  private val app = context.applicationContext
  private val locationManager = app.getSystemService(LocationManager::class.java)
  private val http = OkHttpClient.Builder().retryOnConnectionFailure(true).build()
  private val worker = Executors.newSingleThreadExecutor()
  private val closed = AtomicBoolean(false)
  private val activeSignals = Collections.synchronizedList(mutableListOf<CancellationSignal>())

  fun answer(callId: String, fresh: Boolean = false) {
    if (callId.isBlank() || closed.get()) return
    worker.execute {
      val result = runCatching { resolve(callId, fresh) }.getOrElse {
        LocationAnswerResult(
            callId,
            "location_unavailable",
            "No puedo comprobar tu ubicación ahora. Puedo seguir intentándolo o avisar a tu familiar.",
        )
      }
      if (!closed.get()) onResult(result)
    }
  }

  private fun resolve(callId: String, fresh: Boolean): LocationAnswerResult {
    if (!hasLocationPermission()) {
      return LocationAnswerResult(
          callId,
          "permission_required",
          "Necesito permiso de ubicación en el móvil para decirte dónde estás.",
      )
    }
    val credential = credentialProvider().trim()
    if (credential.isEmpty()) {
      return LocationAnswerResult(
          callId,
          "not_paired",
          "Este móvil debe estar emparejado antes de consultar la ubicación.",
      )
    }
    // KAN-205: si el pipeline pide un fix fresco se intenta primero el GPS actual; si no, se
    // reutiliza la ultima posicion reciente para responder rapido.
    val location = if (fresh) {
      currentLocation() ?: recentLastKnownLocation() ?: fallbackLastKnownLocation()
    } else {
      recentLastKnownLocation() ?: currentLocation() ?: fallbackLastKnownLocation()
    } ?: return LocationAnswerResult(
        callId,
        "location_unavailable",
        "No consigo obtener tu ubicación ahora. Puedo seguir intentándolo o avisar a tu familiar.",
    )

    val first = postAnswer(callId, credential, location)
    // KAN-205: el backend ya no responde `refresh_required` (era un callejon sin salida), pero
    // con versiones antiguas se reintenta una vez forzando un fix nuevo.
    if (!fresh && first.status == "refresh_required") {
      val refreshed = currentLocation() ?: fallbackLastKnownLocation() ?: location
      return postAnswer(callId, credential, refreshed)
    }
    return first
  }

  private fun postAnswer(callId: String, credential: String, location: Location): LocationAnswerResult {
    val recordedAt = location.time.takeIf { it > 0 } ?: System.currentTimeMillis()
    val payload = JSONObject()
        .put("latitude", location.latitude)
        .put("longitude", location.longitude)
        .put("accuracy_meters", location.accuracy.coerceAtLeast(0f).toDouble())
        .put("recorded_at", Instant.ofEpochMilli(recordedAt).toString())
    val request = Request.Builder()
        .url(BuildConfig.API_URL.trimEnd('/') + "/v1/location/answer")
        .header("Authorization", "Bearer $credential")
        .post(payload.toString().toRequestBody("application/json".toMediaType()))
        .build()
    http.newCall(request).execute().use { response ->
      val body = response.body?.string().orEmpty()
      if (!response.isSuccessful) throw IOException("Location backend ${response.code}")
      return LocationAnswerResult.fromBackend(callId, body)
    }
  }

  private fun hasLocationPermission(): Boolean {
    val fine = ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_FINE_LOCATION)
    val coarse = ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_COARSE_LOCATION)
    return fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED
  }

  private fun recentLastKnownLocation(): Location? = newestLastKnownLocation(RECENT_LOCATION_MILLIS)

  private fun fallbackLastKnownLocation(): Location? = newestLastKnownLocation(FALLBACK_LOCATION_MILLIS)

  @SuppressLint("MissingPermission") // Guarded immediately below; SecurityException is contained.
  private fun newestLastKnownLocation(maxAgeMillis: Long): Location? {
    if (!hasLocationPermission()) return null
    return providers(includeDisabled = true)
        .mapNotNull { provider -> runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull() }
        .filter { it.time > 0 && System.currentTimeMillis() - it.time <= maxAgeMillis }
        .maxByOrNull { it.time }
  }

  private fun currentLocation(): Location? {
    val enabled = providers(includeDisabled = false)
    if (enabled.isEmpty()) return null
    val candidates = Collections.synchronizedList(mutableListOf<Location>())
    val remaining = AtomicInteger(enabled.size)
    val complete = CountDownLatch(1)
    val directExecutor = java.util.concurrent.Executor { command -> command.run() }
    val signals = enabled.map { provider ->
      CancellationSignal().also { signal ->
        activeSignals += signal
        try {
          locationManager.getCurrentLocation(provider, signal, directExecutor) { location ->
            if (location != null) candidates += location
            if (remaining.decrementAndGet() == 0 || location?.accuracy?.let { it <= GOOD_ACCURACY_METERS } == true) {
              complete.countDown()
            }
          }
        } catch (_: SecurityException) {
          if (remaining.decrementAndGet() == 0) complete.countDown()
        } catch (_: IllegalArgumentException) {
          if (remaining.decrementAndGet() == 0) complete.countDown()
        }
      }
    }
    complete.await(CURRENT_LOCATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    signals.forEach {
      runCatching { it.cancel() }
      activeSignals -= it
    }
    return synchronized(candidates) { candidates.minByOrNull { it.accuracy } }
  }

  private fun providers(includeDisabled: Boolean): List<String> =
      listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).filter { provider ->
        includeDisabled || runCatching { locationManager.isProviderEnabled(provider) }.getOrDefault(false)
      }

  override fun close() {
    if (!closed.compareAndSet(false, true)) return
    synchronized(activeSignals) {
      activeSignals.forEach { runCatching { it.cancel() } }
      activeSignals.clear()
    }
    http.dispatcher.cancelAll()
    http.connectionPool.evictAll()
    worker.shutdownNow()
  }

  companion object {
    private const val RECENT_LOCATION_MILLIS = 2 * 60_000L
    private const val FALLBACK_LOCATION_MILLIS = 15 * 60_000L
    private const val CURRENT_LOCATION_TIMEOUT_SECONDS = 7L
    private const val GOOD_ACCURACY_METERS = 50f
  }
}
