package com.meta.wearable.dat.externalsampleapps.cameraaccess.faro

import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * KAN-35 · Puente entre las detecciones locales de las gafas y el motor acústico del backend.
 *
 * Envía cada detección a `POST /v1/acoustic-events` y consulta el avance temporal con
 * `POST /v1/acoustic-events/tick`. El backend decide si toca preguntar (`ask`), escalar
 * (`escalate`) o seguir escuchando; aquí solo se ejecuta esa decisión.
 */
class AcousticReporter(
    private val baseUrl: String,
    private val token: String,
    private val poster: JsonPoster = HttpJsonPoster,
) {
  data class Outcome(val action: String, val question: String?)

  fun report(signal: String, confidence: Double): Outcome? {
    if (token.isBlank()) {
      Log.w(TAG, "Sin credencial de emparejamiento; no se envía la detección $signal")
      return null
    }
    val body = String.format(Locale.US, "{\"signal\":\"%s\",\"confidence\":%.2f}", signal, confidence)
    val response = poster.post("$baseUrl/v1/acoustic-events", token, body) ?: return null
    val (code, payload) = response
    if (code !in 200..299) {
      Log.w(TAG, "Detección $signal -> HTTP $code")
      return null
    }
    val outcome = Outcome(jsonString(payload, "action").orEmpty(), jsonString(payload, "question"))
    Log.i(TAG, "Detección $signal -> $code acción=${outcome.action}")
    return outcome
  }

  fun tick(): Outcome? {
    if (token.isBlank()) return null
    val response = poster.post("$baseUrl/v1/acoustic-events/tick", token, "{}") ?: return null
    val (code, payload) = response
    if (code !in 200..299) {
      Log.w(TAG, "Tick acústico -> HTTP $code")
      return null
    }
    return Outcome(jsonString(payload, "action").orEmpty(), jsonString(payload, "question"))
  }

  private fun jsonString(payload: String, key: String): String? {
    val match = Regex("\"$key\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(payload) ?: return null
    return match.groupValues[1]
        .replace("\\\"", "\"")
        .replace("\\n", "\n")
        .replace("\\\\", "\\")
  }

  /** Permite inyectar un transporte falso en pruebas sin cambiar el comportamiento real. */
  fun interface JsonPoster {
    fun post(url: String, token: String, body: String): Pair<Int, String>?
  }

  object HttpJsonPoster : JsonPoster {
    override fun post(url: String, token: String, body: String): Pair<Int, String>? {
      return try {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.connectTimeout = 8_000
        connection.readTimeout = 8_000
        connection.setRequestProperty("Authorization", "Bearer $token")
        connection.setRequestProperty("Content-Type", "application/json")
        connection.outputStream.use { it.write(body.toByteArray()) }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        connection.disconnect()
        code to text
      } catch (error: Exception) {
        Log.w(TAG, "Fallo de red acústica: ${error.message}")
        null
      }
    }
  }

  companion object {
    private const val TAG = "FaroAudio"
  }
}
