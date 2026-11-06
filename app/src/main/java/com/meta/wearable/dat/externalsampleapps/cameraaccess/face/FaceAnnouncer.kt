package com.meta.wearable.dat.externalsampleapps.cameraaccess.face

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

class FaceAnnouncer(context: Context) : AutoCloseable {
  private var ready = false
  private lateinit var tts: TextToSpeech

  init {
    tts = TextToSpeech(context.applicationContext) { status ->
      ready = status == TextToSpeech.SUCCESS
      if (ready) configureVoice()
    }
  }

  private fun configureVoice() {
    tts.language = Locale.forLanguageTag("es-ES")
    val voices = tts.voices ?: return
    val male = voices.firstOrNull { voice ->
      val name = voice.name.lowercase()
      voice.locale.language == "es" && (
        name.contains("male") || name.contains("eeb") || name.contains("eed") || name.contains("eef") ||
          name.contains("wavenet-b") || name.contains("wavenet-d") ||
          name.endsWith("-b-local") || name.endsWith("-c-local")
        )
    }
    if (male != null) tts.voice = male
  }

  fun announce(name: String, relationship: String?) {
    if (!ready) return
    val text = if (relationship.isNullOrBlank()) "Es $name" else "Es $name, $relationship"
    tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "faro-face-${System.currentTimeMillis()}")
  }

  override fun close() {
    tts.stop()
    tts.shutdown()
  }
}
