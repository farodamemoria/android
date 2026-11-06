package com.faro.memoria.mobile

import org.json.JSONObject

/** KAN-143: subtítulos en el móvil de lo que habla la IA. */
object FaroTextProtocol {
  private const val PREFIX = "FARO_TEXT:"

  fun parse(message: String): String? {
    if (!message.startsWith(PREFIX)) return null
    val text = runCatching {
      JSONObject(message.removePrefix(PREFIX)).optString("text")
    }.getOrNull() ?: return null
    return text.trim().takeIf { it.isNotEmpty() }
  }
}
