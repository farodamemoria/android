package com.meta.wearable.dat.externalsampleapps.cameraaccess.face

import java.io.IOException
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject

class FaceRecognitionApi(
    private val baseUrl: String,
    private val accessToken: String,
    private val client: OkHttpClient = OkHttpClient(),
) {
  suspend fun recognize(faceJpegs: List<ByteArray>): Result<RemoteRecognition> {
    require(faceJpegs.size == 3) { "Exactly three consecutive face crops are required" }
    val body = MultipartBody.Builder().setType(MultipartBody.FORM).apply {
      faceJpegs.forEachIndexed { index, bytes ->
        addFormDataPart("files", "face-$index.jpg", bytes.toRequestBody("image/jpeg".toMediaType()))
      }
    }.build()
    val request = Request.Builder()
        .url("${baseUrl.trimEnd('/')}/v1/recognitions")
        .header("Authorization", "Bearer $accessToken")
        .post(body)
        .build()
    return suspendCancellableCoroutine { continuation ->
      val call = client.newCall(request)
      continuation.invokeOnCancellation { call.cancel() }
      call.enqueue(object : Callback {
        override fun onFailure(call: Call, error: IOException) {
          if (continuation.isActive) continuation.resume(Result.failure(error))
        }
        override fun onResponse(call: Call, response: Response) {
          response.use {
            val payload = it.body.string()
            if (!continuation.isActive) return
            if (!it.isSuccessful) {
              continuation.resume(Result.failure(IOException("Recognition HTTP ${it.code}: $payload")))
              return
            }
            continuation.resume(runCatching {
              val json = JSONObject(payload)
              val person = json.optJSONObject("person")
              RemoteRecognition(
                  status = json.getString("status"),
                  displayName = person?.optString("display_name")?.takeIf(String::isNotBlank),
                  relationship = person?.optString("relationship")?.takeIf(String::isNotBlank),
                  confidence = if (json.isNull("confidence")) null else json.optDouble("confidence").toFloat(),
                  isPatient = json.optBoolean("is_patient", false),
              )
            })
          }
        }
      })
    }
  }
}

data class RemoteRecognition(
    val status: String,
    val displayName: String?,
    val relationship: String?,
    val confidence: Float?,
    val isPatient: Boolean,
)
