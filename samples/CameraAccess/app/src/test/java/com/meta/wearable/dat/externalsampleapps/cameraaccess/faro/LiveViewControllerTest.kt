package com.meta.wearable.dat.externalsampleapps.cameraaccess.faro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** KAN-209: las gafas atienden la solicitud del portal sin depender de Android. */
class LiveViewControllerTest {
  private class FakeTransport(var requests: List<LiveViewRequest> = emptyList()) :
      LiveViewTransport {
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
  fun `solo atiende la solicitud de las gafas`() {
    val transport =
        FakeTransport(
            listOf(
                LiveViewRequest("p1", "phone", "pending"),
                LiveViewRequest("g1", "glasses", "pending"),
            ),
        )
    val controller = LiveViewController(transport)
    val request = controller.poll(1_000)
    assertNotNull(request)
    assertEquals("g1", request!!.id)
    assertEquals("g1", controller.activeId)
  }

  @Test
  fun `sube fotogramas a la solicitud activa`() {
    val transport = FakeTransport(listOf(LiveViewRequest("g1", "glasses", "pending")))
    val controller = LiveViewController(transport)
    controller.poll(1_000)
    assertTrue(controller.frame(byteArrayOf(1, 2, 3, 4), 1_200))
    assertEquals("g1" to 4, transport.uploaded.single())
  }

  @Test
  fun `cierra la solicitud si el portal deja de pedirla`() {
    val transport = FakeTransport(listOf(LiveViewRequest("g1", "glasses", "pending")))
    val controller = LiveViewController(transport)
    controller.poll(1_000)
    transport.requests = emptyList()
    assertNull(controller.poll(2_000))
    assertEquals("g1" to "completed", transport.finished.single())
    assertFalse(controller.isActive)
  }

  @Test
  fun `abandona tras el tiempo maximo sin fotogramas`() {
    val transport = FakeTransport(listOf(LiveViewRequest("g1", "glasses", "pending")))
    val controller = LiveViewController(transport, maxIdleMs = 5_000)
    controller.poll(1_000)
    assertNull(controller.poll(7_000))
    assertEquals("g1" to "completed", transport.finished.single())
  }

  @Test
  fun `un fallo de red no cierra la solicitud`() {
    val transport = FakeTransport(listOf(LiveViewRequest("g1", "glasses", "pending")))
    transport.uploadResult = false
    val controller = LiveViewController(transport)
    controller.poll(1_000)
    assertFalse(controller.frame(byteArrayOf(9), 1_100))
    assertTrue(controller.isActive)
    assertEquals("g1", controller.poll(1_200)!!.id)
  }
}
