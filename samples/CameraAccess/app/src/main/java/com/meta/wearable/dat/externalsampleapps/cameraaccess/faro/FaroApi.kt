package com.meta.wearable.dat.externalsampleapps.cameraaccess.faro

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import org.json.JSONObject

object FaroApi {
  const val BASE = "https://d2n7ih9kfxbzvd.cloudfront.net"
  const val TOKEN = "local-development-only"

  fun recognize(frames: List<ByteArray>): JSONObject? {
    return try {
      val boundary = "faro" + System.currentTimeMillis()
      val connection = URL("$BASE/v1/recognitions").openConnection() as HttpURLConnection
      connection.requestMethod = "POST"
      connection.doOutput = true
      connection.connectTimeout = 15000
      connection.readTimeout = 30000
      connection.setRequestProperty("Authorization", "Bearer $TOKEN")
      connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
      connection.outputStream.use { out ->
        frames.forEachIndexed { index, bytes ->
          out.write("--$boundary\r\n".toByteArray())
          out.write(
              "Content-Disposition: form-data; name=\"files\"; filename=\"frame$index.jpg\"\r\n"
                  .toByteArray()
          )
          out.write("Content-Type: image/jpeg\r\n\r\n".toByteArray())
          out.write(bytes)
          out.write("\r\n".toByteArray())
        }
        out.write("--$boundary--\r\n".toByteArray())
      }
      if (connection.responseCode != 200) return null
      JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
    } catch (error: Exception) {
      Log.w("FaroApi", "recognize failed: ${error.message}")
      null
    }
  }

  fun claim(code: String): JSONObject? {
    return try {
      val connection = URL("$BASE/v1/pairing/claim").openConnection() as HttpURLConnection
      connection.requestMethod = "POST"
      connection.doOutput = true
      connection.connectTimeout = 15000
      connection.readTimeout = 20000
      connection.setRequestProperty("Content-Type", "application/json")
      connection.outputStream.write("{\"code\":\"$code\"}".toByteArray())
      if (connection.responseCode != 200) return null
      JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
    } catch (error: Exception) {
      Log.w("FaroApi", "claim failed: ${error.message}")
      null
    }
  }
}

object FaroSpeaker {
  private var tts: TextToSpeech? = null
  private var ready = false
  private val pending = mutableListOf<String>()

  fun speak(context: Context, text: String) {
    if (text.isBlank()) return
    if (tts == null) {
      tts =
          TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
              tts?.language = Locale("es", "ES")
              val queued = pending.toList()
              pending.clear()
              queued.forEach { tts?.speak(it, TextToSpeech.QUEUE_ADD, null, "faro") }
            }
          }
    }
    if (ready) {
      tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "faro")
    } else {
      pending.add(text)
    }
  }
}
