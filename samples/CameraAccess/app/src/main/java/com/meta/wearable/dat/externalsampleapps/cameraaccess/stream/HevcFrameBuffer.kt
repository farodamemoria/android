/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

// KAN-202 - Last-GOP buffer for on-demand glasses stills.
//
// The glasses stream is HEVC and typically only carries VPS/SPS/PPS once at start. When the
// backend asks for a photo (look_through_glasses / safety evidence) the phone UI may have no live
// preview Surface (app backgrounded, screen off, external surface destroyed), which makes both the
// DAT still capture and the PixelCopy fallback fail. Keeping the newest GOP plus the parameter
// sets lets the app decode a still offscreen, independently of the UI.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.stream

/** One HEVC access unit kept for offscreen decoding. */
data class BufferedHevcFrame(
    val data: ByteArray,
    val presentationTimeUs: Long,
    val isConfig: Boolean,
    val isKeyFrame: Boolean,
)

/**
 * Thread-safe ring buffer with the newest GOP (frames since the last IRAP keyframe) and the most
 * recent VPS/SPS/PPS sets. Frames arriving before the first keyframe are ignored: decoding them
 * would only produce garbage.
 */
class HevcFrameBuffer(private val maxGopFrames: Int = 48) {

  private val lock = Any()
  private val configs = ArrayDeque<ByteArray>()
  private val gop = ArrayDeque<BufferedHevcFrame>()

  fun add(data: ByteArray, presentationTimeUs: Long, isCodecConfig: Boolean) {
    if (data.isEmpty()) return
    val config = isCodecConfig || HevcNalParser.isCodecConfig(data)
    val keyFrame = !isCodecConfig && HevcNalParser.isKeyFrame(data)
    synchronized(lock) {
      if (config) {
        configs.addLast(data.copyOf())
        while (configs.size > MAX_CONFIG_SETS) configs.removeFirst()
        if (!keyFrame) return
      }
      if (keyFrame) gop.clear()
      if (gop.isEmpty() && !keyFrame) return
      // Never evict the keyframe: a GOP without its IRAP frame cannot be decoded. When the cap is
      // reached the newest frames are dropped instead, so the still may be slightly older but is
      // always decodable; a new keyframe resets the buffer.
      if (gop.size >= maxGopFrames) return
      gop.addLast(BufferedHevcFrame(data.copyOf(), presentationTimeUs, false, keyFrame))
    }
  }

  /** Parameter sets first, then the buffered GOP in decode order. */
  fun snapshot(): List<BufferedHevcFrame> =
      synchronized(lock) {
        configs.map { BufferedHevcFrame(it, 0L, isConfig = true, isKeyFrame = false) } + gop.toList()
      }

  fun hasCapturableFrame(): Boolean = synchronized(lock) { gop.isNotEmpty() }

  fun clear() {
    synchronized(lock) {
      configs.clear()
      gop.clear()
    }
  }

  private companion object {
    const val MAX_CONFIG_SETS = 3
  }
}

/** Minimal Annex-B HEVC NAL scanner shared by the capture buffer and its unit tests. */
object HevcNalParser {

  // IRAP NAL types: BLA 16-18, IDR 19-20, CRA 21.
  private const val IRAP_FIRST = 16
  private const val IRAP_LAST = 21
  private const val VPS = 32
  private const val SPS = 33
  private const val PPS = 34

  fun isKeyFrame(data: ByteArray): Boolean = anyNal(data) { it in IRAP_FIRST..IRAP_LAST }

  fun isCodecConfig(data: ByteArray): Boolean = anyNal(data) { it == VPS || it == SPS || it == PPS }

  private inline fun anyNal(data: ByteArray, predicate: (Int) -> Boolean): Boolean {
    var index = 0
    while (index + 2 < data.size) {
      val startCodeLength = startCodeLengthAt(data, index)
      if (startCodeLength == 0) {
        index++
        continue
      }
      val headerOffset = index + startCodeLength
      if (headerOffset >= data.size) return false
      // The SDK prefixes the VPS with a double start code (00000001 00000001); skip it so the
      // real NAL header is read instead of the second start code (misparsed as type 0).
      if (startCodeLengthAt(data, headerOffset) != 0) {
        index = headerOffset
        continue
      }
      if (predicate((data[headerOffset].toInt() and 0x7E) shr 1)) return true
      index = headerOffset + 1
    }
    return false
  }

  private fun startCodeLengthAt(data: ByteArray, offset: Int): Int {
    if (offset + 2 >= data.size) return 0
    if (data[offset] != 0.toByte() || data[offset + 1] != 0.toByte()) return 0
    if (data[offset + 2] == 1.toByte()) return 3
    if (offset + 3 < data.size && data[offset + 2] == 0.toByte() && data[offset + 3] == 1.toByte()) {
      return 4
    }
    return 0
  }
}

/** YUV_420_888 (possibly padded, possibly interleaved) to NV21, without Android dependencies. */
object Yuv420Nv21 {

  fun convert(
      width: Int,
      height: Int,
      yPlane: ByteArray,
      yRowStride: Int,
      uPlane: ByteArray,
      uRowStride: Int,
      uPixelStride: Int,
      vPlane: ByteArray,
      vRowStride: Int,
      vPixelStride: Int,
  ): ByteArray {
    require(width > 0 && height > 0) { "Invalid frame size" }
    val nv21 = ByteArray(width * height * 3 / 2)
    var out = 0
    for (row in 0 until height) {
      val rowStart = row * yRowStride
      for (column in 0 until width) {
        val source = rowStart + column
        nv21[out++] = if (source < yPlane.size) yPlane[source] else 0
      }
    }
    val chromaHeight = height / 2
    val chromaWidth = width / 2
    for (row in 0 until chromaHeight) {
      val uRowStart = row * uRowStride
      val vRowStart = row * vRowStride
      for (column in 0 until chromaWidth) {
        val u = uRowStart + column * uPixelStride
        val v = vRowStart + column * vPixelStride
        // NV21 interleaves V before U.
        nv21[out++] = if (v < vPlane.size) vPlane[v] else 0
        nv21[out++] = if (u < uPlane.size) uPlane[u] else 0
      }
    }
    return nv21
  }
}
