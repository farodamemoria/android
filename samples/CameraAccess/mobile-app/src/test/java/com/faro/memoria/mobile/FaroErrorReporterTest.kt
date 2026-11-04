package com.faro.memoria.mobile

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** KAN-191: el error enviado al log central lleva app, version, contexto y traza. */
class FaroErrorReporterTest {
  @Test
  fun payload_lleva_app_version_contexto_y_traza() {
    val json =
        JSONObject(
            FaroErrorReporter.payload(
                "faro-movil",
                "0.1-mvp+kan191",
                "IllegalStateException",
                "screen=conversation action=stop",
                "at MobileVoiceController.kt:88",
            )
        )
    assertEquals("faro-movil", json.getString("app"))
    assertEquals("0.1-mvp+kan191", json.getString("version"))
    assertEquals("IllegalStateException", json.getString("message"))
    assertEquals("screen=conversation action=stop", json.getString("context"))
    assertEquals("at MobileVoiceController.kt:88", json.getString("traceback_text"))
  }

  @Test
  fun payload_recorta_los_campos_largos_para_no_saturar_el_backend() {
    val json =
        JSONObject(
            FaroErrorReporter.payload(
                "faro-movil",
                "0.1",
                "m".repeat(5000),
                "c".repeat(9000),
                "t".repeat(30000),
            )
        )
    assertEquals(2000, json.getString("message").length)
    assertEquals(4000, json.getString("context").length)
    assertEquals(20000, json.getString("traceback_text").length)
  }
}
