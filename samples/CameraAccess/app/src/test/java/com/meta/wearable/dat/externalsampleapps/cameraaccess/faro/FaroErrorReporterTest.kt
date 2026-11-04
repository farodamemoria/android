package com.meta.wearable.dat.externalsampleapps.cameraaccess.faro

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
                "camera-access",
                "1.0+kan191",
                "NullPointerException",
                "screen=home action=start",
                "at CameraViewModel.kt:42",
            )
        )
    assertEquals("camera-access", json.getString("app"))
    assertEquals("1.0+kan191", json.getString("version"))
    assertEquals("NullPointerException", json.getString("message"))
    assertEquals("screen=home action=start", json.getString("context"))
    assertEquals("at CameraViewModel.kt:42", json.getString("traceback_text"))
  }

  @Test
  fun payload_recorta_los_campos_largos_para_no_saturar_el_backend() {
    val json =
        JSONObject(
            FaroErrorReporter.payload(
                "camera-access",
                "1.0",
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
