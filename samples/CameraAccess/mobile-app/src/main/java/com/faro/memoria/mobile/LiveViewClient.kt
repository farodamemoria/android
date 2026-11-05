package com.faro.memoria.mobile

import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject

/** KAN-209: red del directo de Faro Movil (credencial de dispositivo ya emparejada). */
class LiveViewClient(
  private val baseUrl: String,
  private val tokenProvider: () -> String,
) : LiveViewTransport {
  override fun pending(source: String): List<LiveViewRequest> {
    val token = tokenProvider()
    if (token.isBlank()) return emptyList()
    return runCatching {
      val connection =
          URL("$baseUrl/v1/live-views?source=$source").openConnection() as HttpURLConnection
      connection.requestMethod = "GET"
      connection.connectTimeout = 8000
      connection.readTimeout = 8000
      connection.setRequestProperty("Authorization", "Bearer $token")
      connection.setRequestProperty("X-Faro-Device-Source", source)
      if (connection.responseCode != 200) return emptyList()
      val body = connection.inputStream.bufferedReader().use { it.readText() }
      val array = JSONArray(body)
      (0 until array.length()).mapNotNull { index ->
        val item = array.optJSONObject(index) ?: return@mapNotNull null
        LiveViewRequest(
            id = item.optString("id"),
            source = item.optString("source"),
            status = item.optString("status"),
        )
      }
    }.getOrDefault(emptyList())
  }

  override fun uploadFrame(viewId: String, jpeg: ByteArray): Boolean {
    val token = tokenProvider()
    if (token.isBlank() || jpeg.isEmpty()) return false
    return runCatching {
      val connection =
          URL("$baseUrl/v1/live-views/$viewId/frame").openConnection() as HttpURLConnection
      connection.requestMethod = "POST"
      connection.doOutput = true
      connection.connectTimeout = 8000
      connection.readTimeout = 12000
      connection.setRequestProperty("Authorization", "Bearer $token")
      connection.setRequestProperty("X-Faro-Device-Source", "phone")
      connection.setRequestProperty("Content-Type", "image/jpeg")
      connection.outputStream.use { it.write(jpeg) }
      connection.responseCode in 200..299
    }.getOrDefault(false)
  }

  override fun finish(viewId: String, status: String): Boolean {
    val token = tokenProvider()
    if (token.isBlank()) return false
    return runCatching {
      val connection =
          URL("$baseUrl/v1/live-views/$viewId/status").openConnection() as HttpURLConnection
      connection.requestMethod = "POST"
      connection.doOutput = true
      connection.connectTimeout = 8000
      connection.readTimeout = 8000
      connection.setRequestProperty("Authorization", "Bearer $token")
      connection.setRequestProperty("X-Faro-Device-Source", "phone")
      connection.setRequestProperty("Content-Type", "application/json")
      connection.outputStream.use {
        it.write(JSONObject().put("status", status).toString().toByteArray())
      }
      connection.responseCode in 200..299
    }.getOrDefault(false)
  }
}
