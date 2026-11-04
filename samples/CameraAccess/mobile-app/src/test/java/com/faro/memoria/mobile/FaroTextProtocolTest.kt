package com.faro.memoria.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FaroTextProtocolTest {
  @Test fun parsesFaroSubtitle() {
    assertEquals(
        "Ola, Carmen. Estou aquí contigo.",
        FaroTextProtocol.parse("FARO_TEXT:{\"text\":\"Ola, Carmen. Estou aquí contigo.\"}"),
    )
  }

  @Test fun parsesPatientSubtitle() {
    assertEquals(
        "Estoy bien, gracias",
        FaroTextProtocol.parseUser("FARO_USER_TEXT:{\"text\":\"Estoy bien, gracias\"}"),
    )
  }

  @Test fun doesNotMixSpeakers() {
    assertNull(FaroTextProtocol.parse("FARO_USER_TEXT:{\"text\":\"Hola\"}"))
    assertNull(FaroTextProtocol.parseUser("FARO_TEXT:{\"text\":\"Hola\"}"))
  }

  @Test fun ignoresOtherMessages() {
    assertNull(FaroTextProtocol.parse("AUDIO_CLEAR"))
    assertNull(FaroTextProtocol.parse("AUDIO_START:{\"item_id\":\"x\"}"))
  }

  @Test fun rejectsEmptyOrBrokenText() {
    assertNull(FaroTextProtocol.parse("FARO_TEXT:{\"text\":\"   \"}"))
    assertNull(FaroTextProtocol.parse("FARO_TEXT:no-json"))
    assertNull(FaroTextProtocol.parse("FARO_TEXT:{\"text\":\"\"}"))
    assertNull(FaroTextProtocol.parseUser("FARO_USER_TEXT:{\"text\":\"  \"}"))
  }
}
