package com.meta.wearable.dat.externalsampleapps.cameraaccess.assistant

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

private const val ANALYZE_PATH = "/v1/vision/analyze"

class AssistantApi(private val baseUrl: String) {
  suspend fun analyze(bitmap: Bitmap): Result<String> =
      withContext(Dispatchers.IO) {
        if (baseUrl.isBlank()) {
          return@withContext Result.failure(
              AssistantApiException("LifeSense is not configured on this device.")
          )
        }

        val imageBytes = ByteArrayOutputStream().use { output ->
          if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 85, output)) {
            return@withContext Result.failure(AssistantApiException("Could not prepare the photo."))
          }
          output.toByteArray()
        }
        val requestBody =
            JSONObject()
                .put("image_base64", Base64.getEncoder().encodeToString(imageBytes))
                .put("prompt", "Describe what is immediately in front of me.")
                .toString()

        val connection = (URL(baseUrl.trimEnd('/') + ANALYZE_PATH).openConnection() as HttpURLConnection)
        try {
          connection.requestMethod = "POST"
          connection.connectTimeout = 10_000
          connection.readTimeout = 30_000
          connection.doOutput = true
          connection.setRequestProperty("Content-Type", "application/json")
          connection.outputStream.bufferedWriter().use { it.write(requestBody) }

          if (connection.responseCode !in 200..299) {
            return@withContext Result.failure(
                AssistantApiException("LifeSense could not analyze this photo right now.")
            )
          }
          val answer =
              JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                  .optString("answer")
                  .trim()
          if (answer.isEmpty()) {
            return@withContext Result.failure(AssistantApiException("LifeSense returned no answer."))
          }
          Result.success(answer)
        } catch (error: IOException) {
          Result.failure(AssistantApiException("LifeSense could not be reached.", error))
        } catch (error: JSONException) {
          Result.failure(AssistantApiException("LifeSense returned an invalid answer.", error))
        } finally {
          connection.disconnect()
        }
      }
}

class AssistantApiException(message: String, cause: Throwable? = null) : IOException(message, cause)
