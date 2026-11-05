package com.faro.memoria.mobile

/**
 * KAN-209: contrato del directo para Faro Movil.
 *
 * El portal crea la solicitud; esta app la encuentra, envia fotogramas y la cierra. La logica
 * (fuente, caducidad y cierre) vive aqui para poder probarla sin camara ni red.
 */
data class LiveViewRequest(val id: String, val source: String, val status: String)

interface LiveViewTransport {
  fun pending(source: String): List<LiveViewRequest>

  fun uploadFrame(viewId: String, jpeg: ByteArray): Boolean

  fun finish(viewId: String, status: String): Boolean
}

class LiveViewSession(
  private val transport: LiveViewTransport,
  private val source: String = "phone",
  private val maxIdleMs: Long = 120_000,
) {
  var activeId: String? = null
    private set

  private var lastActivityMs = 0L

  val isActive: Boolean
    get() = activeId != null

  /** Devuelve la solicitud que la camara debe atender ahora, o null si no hay ninguna. */
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

  /** Sube un fotograma de la solicitud activa; false si no hay sesion o el envio fallo. */
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
