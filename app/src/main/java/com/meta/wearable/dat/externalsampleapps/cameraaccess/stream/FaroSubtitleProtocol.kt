package com.meta.wearable.dat.externalsampleapps.cameraaccess.stream

import org.json.JSONObject

/** KAN-199: subtitulos de la conversacion (Faro y paciente) en la app de las gafas. */
data class VoiceSubtitle(val text: String, val fromPatient: Boolean)

/**
 * KAN-201: conserva el ultimo subtitulo mientras el enlace de las gafas se reconecta. Antes se
 * borraba en cada reconexion y la banda desaparecia antes de poder leerse. Caduca por tiempo.
 */
class FaroSubtitleLifetime(private val ttlMillis: Long = DEFAULT_TTL_MILLIS) {
  private var entry: VoiceSubtitle? = null
  private var shownAtMillis = 0L

  fun show(subtitle: VoiceSubtitle, nowMillis: Long): VoiceSubtitle {
    entry = subtitle
    shownAtMillis = nowMillis
    return subtitle
  }

  fun onReconnect(nowMillis: Long): VoiceSubtitle? {
    val current = entry ?: return null
    if (nowMillis - shownAtMillis > ttlMillis) {
      entry = null
      return null
    }
    return current
  }

  fun clear() {
    entry = null
  }

  companion object {
    const val DEFAULT_TTL_MILLIS = 30_000L
  }
}

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
