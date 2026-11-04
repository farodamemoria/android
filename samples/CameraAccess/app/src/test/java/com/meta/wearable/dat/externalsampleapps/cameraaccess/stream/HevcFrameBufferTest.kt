package com.meta.wearable.dat.externalsampleapps.cameraaccess.stream

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** KAN-202 - Buffer and parsing rules for offscreen glasses stills. */
class HevcFrameBufferTest {

  private fun nal(type: Int, marker: Int): ByteArray =
      byteArrayOf(0, 0, 0, 1, ((type shl 1) and 0x7E).toByte(), 1, marker.toByte())

  private fun markerOf(frame: BufferedHevcFrame): Int = frame.data.last().toInt()

  @Test
  fun ignores_frames_before_the_first_keyframe() {
    val buffer = HevcFrameBuffer()
    buffer.add(nal(1, 1), 1L, isCodecConfig = false)
    buffer.add(nal(1, 2), 2L, isCodecConfig = false)
    assertFalse(buffer.hasCapturableFrame())
    assertEquals(0, buffer.snapshot().size)
  }

  @Test
  fun keeps_only_the_newest_gop() {
    val buffer = HevcFrameBuffer()
    buffer.add(nal(19, 1), 1L, isCodecConfig = false)
    buffer.add(nal(1, 2), 2L, isCodecConfig = false)
    buffer.add(nal(19, 3), 3L, isCodecConfig = false)
    buffer.add(nal(1, 4), 4L, isCodecConfig = false)

    val frames = buffer.snapshot()

    assertEquals(2, frames.size)
    assertEquals(3, markerOf(frames[0]))
    assertEquals(4, markerOf(frames[1]))
    assertTrue(frames.all { !it.isConfig })
  }

  @Test
  fun keeps_parameter_sets_before_the_frames() {
    val buffer = HevcFrameBuffer()
    buffer.add(nal(32, 9), 0L, isCodecConfig = true)
    buffer.add(nal(19, 1), 1L, isCodecConfig = false)

    val frames = buffer.snapshot()

    assertEquals(2, frames.size)
    assertTrue(frames[0].isConfig)
    assertEquals(9, markerOf(frames[0]))
    assertTrue(frames[1].isKeyFrame)
  }

  @Test
  fun keeps_the_keyframe_when_the_gop_is_full() {
    val buffer = HevcFrameBuffer(maxGopFrames = 2)
    buffer.add(nal(19, 1), 1L, isCodecConfig = false)
    buffer.add(nal(1, 2), 2L, isCodecConfig = false)
    buffer.add(nal(1, 3), 3L, isCodecConfig = false)

    val frames = buffer.snapshot()

    assertEquals(2, frames.size)
    assertEquals(1, markerOf(frames[0]))
    assertTrue(frames[0].isKeyFrame)
    assertEquals(2, markerOf(frames[1]))
  }

  @Test
  fun parser_recognises_irp_and_parameter_set_nal_types() {
    assertTrue(HevcNalParser.isKeyFrame(nal(19, 0)))
    assertTrue(HevcNalParser.isKeyFrame(nal(21, 0)))
    assertFalse(HevcNalParser.isKeyFrame(nal(1, 0)))
    assertTrue(HevcNalParser.isCodecConfig(nal(32, 0)))
    assertTrue(HevcNalParser.isCodecConfig(nal(33, 0)))
    assertTrue(HevcNalParser.isCodecConfig(nal(34, 0)))
    assertFalse(HevcNalParser.isCodecConfig(nal(19, 0)))
  }

  @Test
  fun parser_skips_the_sdk_double_start_code() {
    val vpsWithDoubleStartCode =
        byteArrayOf(0, 0, 0, 1, 0, 0, 0, 1, ((32 shl 1) and 0x7E).toByte(), 1)
    assertTrue(HevcNalParser.isCodecConfig(vpsWithDoubleStartCode))
    assertFalse(HevcNalParser.isKeyFrame(vpsWithDoubleStartCode))
  }

  @Test
  fun converts_padded_yuv420_to_nv21() {
    // 4x2 frame: Y row stride 6, planar U/V with pixel stride 2 and row stride 4.
    val yPlane = byteArrayOf(1, 2, 3, 4, 0, 0, 5, 6, 7, 8, 0, 0)
    val uPlane = byteArrayOf(10, 0, 11, 0)
    val vPlane = byteArrayOf(20, 0, 21, 0)

    val nv21 =
        Yuv420Nv21.convert(
            width = 4,
            height = 2,
            yPlane = yPlane,
            yRowStride = 6,
            uPlane = uPlane,
            uRowStride = 4,
            uPixelStride = 2,
            vPlane = vPlane,
            vRowStride = 4,
            vPixelStride = 2,
        )

    val expected =
        byteArrayOf(
            1, 2, 3, 4,
            5, 6, 7, 8,
            20, 10, 21, 11,
        )
    assertArrayEquals(expected, nv21)
  }
}
