package com.faro.memoria.mobile

import org.json.JSONObject

internal object LocationAnswerProtocol {
  private const val PREFIX = "LOCATION_ANSWER_REQUEST:"

  fun requestCallId(message: String): String? {
    val payload = when {
      message.startsWith(PREFIX) -> message.removePrefix(PREFIX)
      message.trimStart().startsWith("{") -> message
      else -> return null
    }
    val json = runCatching { JSONObject(payload) }.getOrNull() ?: return null
    if (!message.startsWith(PREFIX) &&
        json.optString("type").lowercase() != "location_answer_request") return null
    return json.optString("call_id").trim().takeIf { it.isNotEmpty() && it.length <= 200 }
  }
}

internal data class LocationAnswerResult(
    val callId: String,
    val status: String,
    val message: String,
    val placeLabel: String? = null,
    val distanceMeters: Double? = null,
    val accuracyMeters: Double? = null,
) {
  fun toWireMessage(): String = JSONObject()
      .put("type", "location_answer_result")
      .put("call_id", callId)
      .put("status", status)
      .put("message", message)
      .apply {
        placeLabel?.let { put("place_label", it) }
        distanceMeters?.let { put("distance_meters", it) }
        accuracyMeters?.let { put("accuracy_meters", it) }
      }
      .toString()

  companion object {
    fun fromBackend(callId: String, body: String): LocationAnswerResult {
      val json = JSONObject(body)
      return LocationAnswerResult(
          callId = callId,
          status = json.optString("status").ifBlank { "location_available" },
          message = json.optString("message").ifBlank {
            "Ya tengo tu ubicación, pero no puedo identificar el lugar con seguridad."
          },
          placeLabel = json.optString("place_label").takeIf { it.isNotBlank() },
          distanceMeters = json.optDouble("distance_meters").takeUnless { it.isNaN() },
          accuracyMeters = json.optDouble("accuracy_meters").takeUnless { it.isNaN() },
      )
    }
  }
}
