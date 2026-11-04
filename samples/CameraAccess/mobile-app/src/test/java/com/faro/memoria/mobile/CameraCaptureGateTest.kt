package com.faro.memoria.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraCaptureGateTest {

  @Test
  fun `una peticion abre la camara y la segunda no la reabre`() {
    val gate = CameraCaptureGate()
    assertTrue(gate.beginAuto("call-1"))
    assertFalse(gate.beginAuto("call-2"))
    assertTrue(gate.isInFlight)
  }

  @Test
  fun `finish devuelve el call id pendiente una sola vez`() {
    val gate = CameraCaptureGate()
    gate.beginAuto("call-1")
    assertEquals("call-1", gate.finish())
    assertNull(gate.finish())
    assertFalse(gate.isInFlight)
  }

  @Test
  fun `tras terminar, una peticion nueva vuelve a abrir una sola vez`() {
    val gate = CameraCaptureGate()
    gate.beginAuto("call-1")
    gate.finish()
    assertTrue(gate.beginAuto("call-2"))
    assertFalse(gate.beginAuto("call-3"))
  }

  @Test
  fun `la captura del usuario no responde a ningun call id`() {
    val gate = CameraCaptureGate()
    assertTrue(gate.beginUser())
    assertFalse(gate.beginAuto("call-1"))
    assertNull(gate.finish())
  }

  @Test
  fun `mientras hay captura en curso el usuario no puede abrir otra`() {
    val gate = CameraCaptureGate()
    assertTrue(gate.beginAuto("call-1"))
    assertFalse(gate.beginUser())
  }
}
