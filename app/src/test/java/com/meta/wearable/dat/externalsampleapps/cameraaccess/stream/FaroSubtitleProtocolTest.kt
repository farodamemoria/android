package com.meta.wearable.dat.externalsampleapps.cameraaccess.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FaroSubtitleProtocolTest {
  @Test
  fun parsesFaroSubtitle() {
    val subtitle = FaroSubtitleProtocol.parse("FARO_TEXT:{\"text\":\"Sí, te escucho\"}")
    assertEquals("Sí, te escucho", subtitle?.text)
    assertFalse(subtitle!!.fromPatient)
  }

  @Test
  fun parsesPatientSubtitle() {
    val subtitle = FaroSubtitleProtocol.parse("FARO_USER_TEXT:{\"text\":\"Estoy bien, gracias\"}")
    assertEquals("Estoy bien, gracias", subtitle?.text)
    assertTrue(subtitle!!.fromPatient)
  }

  @Test
  fun ignoresOtherMessagesAndEmptyText() {
    assertNull(FaroSubtitleProtocol.parse("AUDIO_START:{}"))
    assertNull(FaroSubtitleProtocol.parse("FARO_TEXT:no-json"))
    assertNull(FaroSubtitleProtocol.parse("FARO_USER_TEXT:{\"text\":\"   \"}"))
  }

  @Test
  fun keepsSubtitleAcrossReconnect() {
    val lifetime = FaroSubtitleLifetime(ttlMillis = 30_000L)
    val subtitle = VoiceSubtitle("Sí, te escucho", fromPatient = false)
    lifetime.show(subtitle, nowMillis = 1_000L)
    assertEquals(subtitle, lifetime.onReconnect(nowMillis = 5_000L))
  }

  @Test
  fun expiresSubtitleAfterTtl() {
    val lifetime = FaroSubtitleLifetime(ttlMillis = 30_000L)
    lifetime.show(VoiceSubtitle("Estoy bien", fromPatient = true), nowMillis = 1_000L)
    assertNull(lifetime.onReconnect(nowMillis = 31_001L))
  }

  @Test
  fun clearRemovesSubtitleOnStop() {
    val lifetime = FaroSubtitleLifetime()
    lifetime.show(VoiceSubtitle("Hola", fromPatient = false), nowMillis = 1_000L)
    lifetime.clear()
    assertNull(lifetime.onReconnect(nowMillis = 2_000L))
  }
}
