package com.meta.wearable.dat.externalsampleapps.cameraaccess.faro

/**
 * KAN-209: contrato del directo de las gafas sin depender de Android.
 *
 * El portal crea la solicitud; las gafas la localizan, suben fotogramas y la cierran cuando el
 * visor deja de pedirla o pasa el tiempo maximo. La red se inyecta para poder probarlo.
 */
data class LiveViewRequest(val id: String, val source: String, val status: String)

interface LiveViewTransport {
  fun pending(source: String): List<LiveViewRequest>

  fun uploadFrame(viewId: String, jpeg: ByteArray): Boolean

  fun finish(viewId: String, status: String): Boolean
}

class LiveViewController(
  private val transport: LiveViewTransport,
  private val source: String = "glasses",
  private val maxIdleMs: Long = 120_000,
) {
  var activeId: String? = null
    private set

  private var lastActivityMs = 0L

  val isActive: Boolean
    get() = activeId != null

  fun poll(nowMs: Long): LiveViewRequest? {
    if (activeId != null && nowMs - lastActivityMs > maxIdleMs) {
      close("completed")
      return null
    }
    val request =
        transport.pending(source).firstOrNull {
          it.source == source && (it.status == "pending" || it.status == "active")
        }
    if (request == null) {
      if (activeId != null) transport.finish(activeId!!, "completed")
      activeId = null
      return null
    }
    if (activeId != request.id) {
      activeId = request.id
      lastActivityMs = nowMs
    }
    return request
  }

  fun frame(jpeg: ByteArray, nowMs: Long): Boolean {
    val id = activeId ?: return false
    val uploaded = transport.uploadFrame(id, jpeg)
    if (uploaded) lastActivityMs = nowMs
    return uploaded
  }

  fun close(status: String = "completed"): Boolean {
    val id = activeId ?: return false
    activeId = null
    return transport.finish(id, status)
  }
}
