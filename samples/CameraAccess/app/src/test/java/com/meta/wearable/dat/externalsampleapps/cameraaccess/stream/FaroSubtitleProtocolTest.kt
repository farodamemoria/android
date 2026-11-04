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
}
