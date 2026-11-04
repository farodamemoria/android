package com.faro.memoria.mobile

import org.json.JSONObject

/** KAN-143/KAN-199: subtitulos en el movil de lo que habla la IA y de lo que dice el paciente. */
object FaroTextProtocol {
  const val FARO_PREFIX = "FARO_TEXT:"
  const val USER_PREFIX = "FARO_USER_TEXT:"

  /** Texto de Faro (KAN-143). */
  fun parse(message: String): String? = parse(message, FARO_PREFIX)

  /** Texto escuchado del paciente (KAN-199). */
  fun parseUser(message: String): String? = parse(message, USER_PREFIX)

  private fun parse(message: String, prefix: String): String? {
    if (!message.startsWith(prefix)) return null
    val text = runCatching {
      JSONObject(message.removePrefix(prefix)).optString("text")
    }.getOrNull() ?: return null
    return text.trim().takeIf { it.isNotEmpty() }
  }
}
