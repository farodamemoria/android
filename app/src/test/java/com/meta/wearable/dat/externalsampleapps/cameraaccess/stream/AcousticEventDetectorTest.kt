/*
 * Faro da Memoria — KAN-35
 * Tests unitarios del detector de tos (heurística determinista sobre PCM).
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class AcousticEventDetectorTest {
  private val sampleRate = 16000

  /** Genera silencio (ruido de fondo muy bajo). */
  private fun silenceFrames(frames: Int): ShortArray =
      ShortArray(frames * sampleRate / 50) { (sin(it * 0.01) * 300).toInt().toShort() }

  /** Genera una ráfaga tipo tos: energía alta de corta duración. */
  private fun burstFrames(durationMs: Int): ShortArray {
    val samples = durationMs * sampleRate / 1000
    return ShortArray(samples) {
      (sin(2 * PI * 900 * it / sampleRate) * 24000).toInt().toShort()
    }
  }

  private fun framesOf(vararg bursts: ShortArray): ShortArray {
    val total = bursts.sumOf { it.size }
    val out = ShortArray(total)
    var offset = 0
    for (burst in bursts) {
      burst.copyInto(out, offset)
      offset += burst.size
    }
    return out
  }

  @Test
  fun singleCoughDoesNotDetect() {
    val detector = AcousticEventDetector(sampleRate = sampleRate)
    val audio = framesOf(silenceFrames(20), burstFrames(200), silenceFrames(60))
    assertTrue(detector.process(audio).isEmpty())
  }

  @Test
  fun repeatedCoughsWithinWindowDetectsWithConfidence() {
    val detector = AcousticEventDetector(sampleRate = sampleRate)
    val audio = framesOf(
        burstFrames(200), silenceFrames(10), burstFrames(180), silenceFrames(10),
        burstFrames(220), silenceFrames(60))
    val detections = detector.process(audio)
    assertEquals(1, detections.size)
    assertEquals("cough", detections[0].signal)
    assertTrue("confianza >= 0.6", detections[0].confidence >= 0.6f)
  }

  @Test
  fun longSpeechDoesNotCountAsCough() {
    val detector = AcousticEventDetector(sampleRate = sampleRate)
    // Ráfaga de 3 s: supera burstMaxMs, no debe contar como tos.
    val audio = framesOf(burstFrames(3000), silenceFrames(40))
    assertTrue(detector.process(audio).isEmpty())
  }

  @Test
  fun lowLevelNoiseIsIgnored() {
    val detector = AcousticEventDetector(sampleRate = sampleRate)
    // Ruido suave sostenido nunca supera el umbral RMS.
    val audio = framesOf(silenceFrames(200))
    assertTrue(detector.process(audio).isEmpty())
  }

  @Test
  fun detectionsRespectCooldown() {
    val detector = AcousticEventDetector(sampleRate = sampleRate)
    val pattern = framesOf(
        burstFrames(200), silenceFrames(10), burstFrames(180), silenceFrames(10),
        burstFrames(220), silenceFrames(60))
    val first = detector.process(pattern)
    assertEquals(1, first.size)
    // Inmediatamente después (antes del cooldown) no debe reportar de nuevo.
    val second = detector.process(pattern)
    assertTrue(second.isEmpty())
  }

  @Test
  fun separatedCoughsOutsideWindowAreIgnored() {
    val detector = AcousticEventDetector(sampleRate = sampleRate)
    // Dos ráfagas separadas por 4 s (> pauseMaxMs): se reinicia el patrón.
    val audio = framesOf(
        burstFrames(200), silenceFrames(200), burstFrames(200), silenceFrames(60))
    assertTrue(detector.process(audio).isEmpty())
  }
}
