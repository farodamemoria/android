package com.faro.memoria.mobile

/**
 * KAN-68: una sola captura de cámara a la vez.
 *
 * Las peticiones extra del backend se responden con un camera_error en vez de reabrir la cámara,
 * y la cámara nunca se relanza automáticamente al terminar un análisis.
 */
class CameraCaptureGate {
  private var inFlight = false
  private var pendingCallId: String? = null
  private var startedAtMs = 0L

  val isInFlight: Boolean
    get() = inFlight

  private fun stale(): Boolean = System.currentTimeMillis() - startedAtMs > 20_000

  /** Petición del backend: true cuando la cámara debe abrirse ahora. */
  fun beginAuto(callId: String): Boolean {
    if (inFlight && !stale()) return false
    pendingCallId = callId
    inFlight = true
    startedAtMs = System.currentTimeMillis()
    return true
  }

  /** Captura pedida por el usuario (botón): true cuando la cámara debe abrirse ahora. */
  fun beginUser(): Boolean {
    if (inFlight && !stale()) return false
    pendingCallId = null
    inFlight = true
    startedAtMs = System.currentTimeMillis()
    return true
  }

  /** Captura terminada (o fallida): devuelve el call id que hay que responder, si lo hay. */
  fun finish(): String? {
    inFlight = false
    val id = pendingCallId
    pendingCallId = null
    return id
  }
}
