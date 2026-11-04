package com.meta.wearable.dat.externalsampleapps.cameraaccess.stream

import org.json.JSONObject

/** KAN-199: subtitulos de la conversacion (Faro y paciente) en la app de las gafas. */
data class VoiceSubtitle(val text: String, val fromPatient: Boolean)

object FaroSubtitleProtocol {
  private const val FARO_PREFIX = "FARO_TEXT:"
  private const val USER_PREFIX = "FARO_USER_TEXT:"

  fun parse(message: String): VoiceSubtitle? {
    val fromPatient =
        when {
          message.startsWith(USER_PREFIX) -> true
          message.startsWith(FARO_PREFIX) -> false
          else -> return null
        }
    val prefix = if (fromPatient) USER_PREFIX else FARO_PREFIX
    val text =
        runCatching { JSONObject(message.removePrefix(prefix)).optString("text") }
            .getOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return null
    return VoiceSubtitle(text, fromPatient)
  }
}
