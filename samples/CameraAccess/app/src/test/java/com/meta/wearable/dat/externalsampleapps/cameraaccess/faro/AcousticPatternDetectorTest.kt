package com.meta.wearable.dat.externalsampleapps.cameraaccess.faro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** KAN-35 · Comportamiento del detector local de tos y golpes. */
class AcousticPatternDetectorTest {
  private val quiet = 200.0

  private fun feedSilence(
      detector: AcousticPatternDetector,
      fromMs: Long,
      toMs: Long,
      stepMs: Long = 20L,
      rms: Double = quiet
  ): Long {
    var at = fromMs
    while (at < toMs) {
      detector.feed(rms, at)
      at += stepMs
    }
    return at
  }

  @Test
  fun isolated_cough_is_reported_once() {
    val detector = AcousticPatternDetector()
    var at = feedSilence(detector, 0L, 1_000L)

    val detection = detector.feed(4_000.0, at)

    assertNotNull(detection)
    assertEquals(AcousticPatternDetector.SIGNAL_COUGH, detection!!.signal)
    assertTrue(detection.confidence in 0.6..0.95)
  }

  @Test
  fun continuous_cough_frames_become_a_single_detection() {
    val detector = AcousticPatternDetector()
    var at = feedSilence(detector, 0L, 1_000L)
    var detections = 0
    var first: AcousticPatternDetector.Detection? = null

    repeat(20) {
      val detection = detector.feed(4_500.0, at)
      if (detection != null) {
        detections++
        if (first == null) first = detection
      }
      at += 20L
    }

    assertEquals(1, detections)
    assertEquals(AcousticPatternDetector.SIGNAL_COUGH, first!!.signal)
  }

  @Test
  fun very_quiet_transient_is_not_a_cough() {
    val detector = AcousticPatternDetector()
    var at = feedSilence(detector, 0L, 1_000L)

    val detection = detector.feed(820.0, at)

    assertNull(detection)
  }

  @Test
  fun bursts_inside_the_cooldown_are_suppressed() {
    val detector = AcousticPatternDetector()
    var at = feedSilence(detector, 0L, 1_000L)
    assertNotNull(detector.feed(4_000.0, at))
    at += 200L
    at = feedSilence(detector, at, at + 800L)

    assertNull(detector.feed(4_000.0, at))
  }

  @Test
  fun separate_cough_bursts_are_reported_again() {
    val detector = AcousticPatternDetector()
    var at = feedSilence(detector, 0L, 1_000L)
    assertNotNull(detector.feed(4_000.0, at))
    at += 400L
    at = feedSilence(detector, at, at + 2_000L)

    val second = detector.feed(4_200.0, at)

    assertNotNull(second)
    assertEquals(AcousticPatternDetector.SIGNAL_COUGH, second!!.signal)
  }

  @Test
  fun impact_is_reported_as_a_fall() {
    val detector = AcousticPatternDetector()
    val at = feedSilence(detector, 0L, 1_000L)

    val detection = detector.feed(12_000.0, at)

    assertNotNull(detection)
    assertEquals(AcousticPatternDetector.SIGNAL_FALL, detection!!.signal)
  }

  @Test
  fun second_impact_inside_the_cooldown_is_ignored() {
    val detector = AcousticPatternDetector()
    var at = feedSilence(detector, 0L, 1_000L)
    assertNotNull(detector.feed(12_000.0, at))
    at += 200L

    assertNull(detector.feed(12_500.0, at))
  }

  @Test
  fun impact_must_stand_out_from_a_noisy_background() {
    val detector = AcousticPatternDetector()
    var at = feedSilence(detector, 0L, 3_000L, rms = 4_000.0)

    val detection = detector.feed(9_500.0, at)

    assertNull(detection)
  }
}
