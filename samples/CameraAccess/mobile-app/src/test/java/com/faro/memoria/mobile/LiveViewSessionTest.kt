package com.faro.memoria.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveViewSessionTest {
  private class FakeTransport(
    var requests: List<LiveViewRequest> = emptyList(),
  ) : LiveViewTransport {
    val uploaded = mutableListOf<Pair<String, Int>>()
    val finished = mutableListOf<Pair<String, String>>()
    var uploadResult = true

    override fun pending(source: String): List<LiveViewRequest> = requests

    override fun uploadFrame(viewId: String, jpeg: ByteArray): Boolean {
      if (!uploadResult) return false
      uploaded += viewId to jpeg.size
      return true
    }

    override fun finish(viewId: String, status: String): Boolean {
      finished += viewId to status
      return true
    }
  }

  @Test
  fun `atiende solo las solicitudes de su fuente`() {
    val transport =
        FakeTransport(
            listOf(
                LiveViewRequest("g1", "glasses", "pending"),
                LiveViewRequest("p1", "phone", "pending"),
            ),
        )
    val session = LiveViewSession(transport, "phone")
    val request = session.poll(1_000)
    assertNotNull(request)
    assertEquals("p1", request!!.id)
    assertEquals("p1", session.activeId)
  }

  @Test
  fun `mantiene la solicitud activa aunque el backend ya la marque active`() {
    val transport = FakeTransport(listOf(LiveViewRequest("p1", "phone", "active")))
    val session = LiveViewSession(transport, "phone")
    assertEquals("p1", session.poll(1_000)!!.id)
    assertTrue(session.frame(byteArrayOf(1, 2, 3), 1_500))
    assertEquals("p1" to 3, transport.uploaded.single())
  }

  @Test
  fun `sin solicitudes no captura nada`() {
    val session = LiveViewSession(FakeTransport(), "phone")
    assertNull(session.poll(1_000))
    assertFalse(session.frame(byteArrayOf(1), 1_100))
    assertFalse(session.close())
  }

  @Test
  fun `cierra la solicitud cuando el portal la cancela`() {
    val transport = FakeTransport(listOf(LiveViewRequest("p1", "phone", "pending")))
    val session = LiveViewSession(transport, "phone")
    session.poll(1_000)
    transport.requests = emptyList()
    assertNull(session.poll(2_000))
    assertEquals("p1" to "completed", transport.finished.single())
  }

  @Test
  fun `abandona una sesion sin fotogramas tras el tiempo maximo`() {
    val transport = FakeTransport(listOf(LiveViewRequest("p1", "phone", "pending")))
    val session = LiveViewSession(transport, "phone", maxIdleMs = 5_000)
    session.poll(1_000)
    assertNull(session.poll(7_000))
    assertEquals("p1" to "completed", transport.finished.single())
    assertFalse(session.isActive)
  }

  @Test
  fun `un fallo de subida no cierra la sesion`() {
    val transport = FakeTransport(listOf(LiveViewRequest("p1", "phone", "pending")))
    transport.uploadResult = false
    val session = LiveViewSession(transport, "phone")
    session.poll(1_000)
    assertFalse(session.frame(byteArrayOf(9), 1_200))
    assertTrue(session.isActive)
    assertEquals("p1", session.poll(1_300)!!.id)
  }
}
