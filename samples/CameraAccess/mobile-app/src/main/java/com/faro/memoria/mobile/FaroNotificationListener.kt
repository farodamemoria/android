package com.faro.memoria.mobile

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.speech.tts.TextToSpeech
import java.util.ArrayDeque
import java.util.Locale

/**
 * KAN-152: lee las notificaciones (WhatsApp y otras apps) y avisa de llamadas entrantes.
 *
 * KAN-146/152: la voz de aviso se sintetiza en el dispositivo con TextToSpeech (es-ES).
 * El usuario debe conceder "Acceso a notificaciones" a Faro Movil en los ajustes de Android.
 */
class FaroNotificationListener : NotificationListenerService() {

  private var tts: TextToSpeech? = null
  private val vistos = ArrayDeque<String>()

  private val appsVigiladas = mapOf(
    "com.whatsapp" to "WhatsApp",
    "com.whatsapp.w4b" to "WhatsApp Business",
    "org.telegram.messenger" to "Telegram",
    "com.google.android.apps.messaging" to "Mensaje",
    "com.android.mms" to "Mensaje",
  )

  override fun onCreate() {
    super.onCreate()
    tts = TextToSpeech(this) { estado ->
      if (estado == TextToSpeech.SUCCESS) tts?.language = Locale("es", "ES")
    }
  }

  override fun onNotificationPosted(sbn: StatusBarNotification) {
    val paquete = sbn.packageName ?: return
    val nombreApp = appsVigiladas[paquete] ?: return
    val notificacion = sbn.notification ?: return
    if ((notificacion.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return
    val extras = notificacion.extras ?: return
    val esLlamada = notificacion.category == Notification.CATEGORY_CALL ||
      (extras.getString(Notification.EXTRA_TEMPLATE) ?: "").contains("call", ignoreCase = true)
    val titulo = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
    val texto = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
    if (titulo.isEmpty() && texto.isEmpty()) return
    val clave = "$paquete|$titulo|$texto"
    synchronized(vistos) {
      if (vistos.contains(clave)) return
      if (vistos.size >= 40) vistos.removeFirst()
      vistos.addLast(clave)
    }
    val frase = if (esLlamada) {
      if (titulo.isEmpty()) "Tienes una llamada entrante" else "Te llama $titulo"
    } else if (texto.isEmpty()) {
      "$nombreApp de $titulo"
    } else {
      "$nombreApp de $titulo: $texto"
    }
    hablar(frase)
  }

  private fun hablar(texto: String) {
    tts?.speak(texto, TextToSpeech.QUEUE_ADD, null, "faro-notif-${System.currentTimeMillis()}")
  }

  override fun onDestroy() {
    tts?.shutdown()
    tts = null
    super.onDestroy()
  }
}
