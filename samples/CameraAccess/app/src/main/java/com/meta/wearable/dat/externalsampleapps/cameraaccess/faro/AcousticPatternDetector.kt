package com.meta.wearable.dat.externalsampleapps.cameraaccess.faro

/**
 * KAN-35 - Detector local de patrones acusticos en las gafas.
 *
 * Convierte los niveles RMS de cada trama de audio (20 ms) en detecciones puntuales de tos o de
 * golpe/caida. No guarda audio: solo trabaja con energia en memoria. El motor del backend sigue
 * siendo el que decide cuando preguntar o escalar; aqui solo se evita enviar ruido y rafagas
 * duplicadas (una rafaga de tos cuenta como una sola deteccion).
 *
 * Umbrales configurables:
 *  - [coughRms] / [impactRms]: energia minima para considerar tos o golpe.
 *  - [minRms]: suelo absoluto (~-30 dBFS) para descartar transitorios casi inaudibles.
 *  - [ambientFactor]: el golpe debe superar el ruido de fondo por este factor.
 *  - [burstGapMs]: silencio minimo que separa dos rafagas de tos.
 *  - [detectionCooldownMs] / [impactCooldownMs]: anti-repeticion local.
 */
class AcousticPatternDetector(
    private val coughRms: Double = 3000.0,
    private val impactRms: Double = 9000.0,
    private val minRms: Double = 1035.0,
    private val ambientFactor: Double = 3.0,
    private val burstGapMs: Long = 600L,
    private val detectionCooldownMs: Long = 1_500L,
    private val impactCooldownMs: Long = 30_000L,
) {
  data class Detection(val signal: String, val confidence: Double, val atMillis: Long)

  private var ambient = 100.0
  private var lastLoudAt = 0L
  private var inBurst = false
  private var lastCoughAt: Long? = null
  private var lastImpactAt: Long? = null

  /** Procesa una trama y devuelve la deteccion que debe enviarse al backend, si procede. */
  fun feed(rms: Double, atMillis: Long): Detection? {
    ambient = ambient * 0.98 + rms * 0.02
    if (rms >= impactRms && rms >= ambient * ambientFactor && rms >= minRms) {
      inBurst = false
      if (lastImpactAt?.let { atMillis - it < impactCooldownMs } != true) {
        lastImpactAt = atMillis
        lastLoudAt = atMillis
        return Detection(SIGNAL_FALL, confidence(rms, impactRms), atMillis)
      }
      return null
    }
    if (rms >= coughRms) {
      val startsBurst = !inBurst || atMillis - lastLoudAt > burstGapMs
      lastLoudAt = atMillis
      inBurst = true
      if (!startsBurst) return null
      if (lastCoughAt?.let { atMillis - it < detectionCooldownMs } == true) return null
      lastCoughAt = atMillis
      return Detection(SIGNAL_COUGH, confidence(rms, coughRms), atMillis)
    }
    inBurst = false
    return null
  }

  private fun confidence(rms: Double, threshold: Double): Double {
    val extra = ((rms - threshold) / threshold).coerceIn(0.0, 2.0)
    return (0.6 + extra * 0.15).coerceIn(0.6, 0.95)
  }

  companion object {
    const val SIGNAL_COUGH = "cough"
    const val SIGNAL_FALL = "fall"
  }
}
