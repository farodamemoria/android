/*
 * Faro da Memoria — KAN-35
 * Detector heurístico de tos sobre PCM para la escucha ambiental de las gafas.
 * Determinista y sin dependencias: una tos es una ráfaga corta de energía;
 * el patrón repetido dentro de una ventana produce una detección con confianza.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.stream

import kotlin.math.sqrt

/** Resultado de analizar un bloque de muestras PCM. */
data class AcousticDetection(
    val signal: String,
    val confidence: Float,
    val atMillis: Long,
)

/**
 * Analiza PCM 16-bit mono y emite [AcousticDetection] para patrones de tos.
 *
 * Parámetros por defecto calibrados para micrófonos de gafas a 16 kHz:
 * ráfaga de 80–450 ms, pausa máxima entre ráfagas de 2,5 s, 3 ráfagas en 6 s.
 */
class AcousticEventDetector(
    private val sampleRate: Int = 16000,
    private val rmsThreshold: Float = 0.045f,
    private val burstMinMs: Int = 80,
    private val burstMaxMs: Int = 450,
    private val pauseMaxMs: Int = 2500,
    private val patternWindowMs: Int = 6000,
    private val minBursts: Int = 3,
    private val reportCooldownMs: Long = 10_000,
) {
  private val frameSamples = sampleRate / 50 // 20 ms
  private var buffer = ShortArray(0)
  private var burstStartMs = -1L
  private var lastBurstEndMs = -1L
  private val burstEndsMs = ArrayList<Long>()
  private var lastReportedAtMs = Long.MIN_VALUE
  private var clockMs = 0L

  fun process(pcm: ShortArray): List<AcousticDetection> {
    val samples = ShortArray(buffer.size + pcm.size).also {
      buffer.copyInto(it)
      pcm.copyInto(it, buffer.size)
    }
    buffer = ShortArray(0)

    val detections = mutableListOf<AcousticDetection>()
    var offset = 0
    while (samples.size - offset >= frameSamples) {
      val frame = samples.copyOfRange(offset, offset + frameSamples)
      offset += frameSamples
      clockMs += 20
      analyzeFrame(frame, detections)
    }
    if (offset < samples.size) buffer = samples.copyOfRange(offset, samples.size)
    return detections
  }

  private fun analyzeFrame(frame: ShortArray, out: MutableList<AcousticDetection>) {
    val rms = rms(frame)
    if (rms >= rmsThreshold) {
      if (burstStartMs < 0) {
        // La pausa entre ráfagas no puede superar pauseMaxMs.
        if (lastBurstEndMs > 0 && clockMs - lastBurstEndMs > pauseMaxMs) burstEndsMs.clear()
        burstStartMs = clockMs
      }
      return
    }

    if (burstStartMs >= 0) {
      val duration = clockMs - burstStartMs
      burstStartMs = -1
      if (duration < burstMinMs || duration > burstMaxMs) return
      burstEndsMs.add(clockMs)
      lastBurstEndMs = clockMs
      while (burstEndsMs.isNotEmpty() && clockMs - burstEndsMs[0] > patternWindowMs) {
        burstEndsMs.removeAt(0)
      }
      if (burstEndsMs.size >= minBursts) {
        val confidence = confidenceFor(burstEndsMs.size)
        if (clockMs - lastReportedAtMs >= reportCooldownMs) {
          out.add(AcousticDetection("cough", confidence, clockMs))
          lastReportedAtMs = clockMs
        }
        burstEndsMs.clear()
        lastBurstEndMs = -1
      }
    }
  }

  private fun rms(frame: ShortArray): Float {
    var sum = 0.0
    for (sample in frame) sum += sample.toDouble() * sample.toDouble()
    return (sqrt(sum / frame.size) / 32768.0).toFloat()
  }

  private fun confidenceFor(bursts: Int): Float = when {
    bursts >= 6 -> 0.95f
    bursts >= 5 -> 0.85f
    bursts >= 4 -> 0.72f
    else -> 0.6f
  }

  fun reset() {
    buffer = ShortArray(0)
    burstStartMs = -1
    lastBurstEndMs = -1
    burstEndsMs.clear()
  }
}
