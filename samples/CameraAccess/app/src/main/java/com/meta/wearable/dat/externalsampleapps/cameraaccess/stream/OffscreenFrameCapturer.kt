/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

// KAN-202 - Offscreen still capture from the glasses HEVC stream.
//
// Decodes the buffered GOP into an ImageReader surface (no UI Surface involved), converts the
// YUV_420_888 image to NV21 and compresses it to JPEG. This keeps alert photos working when the
// phone app is backgrounded or its preview surface was destroyed.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.stream

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import android.media.ImageReader
import android.os.SystemClock
import android.util.Log
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class OffscreenFrameCapturer(
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val jpegQuality: Int = DEFAULT_JPEG_QUALITY,
) {

  suspend fun capture(
      width: Int,
      height: Int,
      frames: List<BufferedHevcFrame>,
  ): ByteArray? =
      withContext(Dispatchers.Default) {
        if (width <= 0 || height <= 0 || frames.isEmpty()) return@withContext null
        val reader = ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, 3)
        val decoder = HevcDecoder()
        try {
          decoder.start(width, height, reader.surface)
          frames.forEach { frame -> decoder.decodeFrame(frame.data, frame.presentationTimeUs) }
          val deadline = SystemClock.elapsedRealtime() + timeoutMs
          while (SystemClock.elapsedRealtime() < deadline) {
            val image = reader.acquireLatestImage()
            if (image != null) {
              val jpeg = try { imageToJpeg(image) } finally { image.close() }
              if (jpeg != null) {
                Log.i(TAG, "Offscreen still decoded ${width}x$height; jpegBytes=${jpeg.size}")
                return@withContext jpeg
              }
            }
            delay(POLL_INTERVAL_MS)
          }
          Log.w(TAG, "No offscreen frame decoded within ${timeoutMs}ms")
          null
        } catch (error: Throwable) {
          Log.w(TAG, "Offscreen still capture failed: ${error.message}")
          null
        } finally {
          decoder.stop()
          reader.close()
        }
      }

  private fun imageToJpeg(image: Image): ByteArray? {
    if (image.width <= 0 || image.height <= 0 || image.planes.size < 3) return null
    val y = image.planes[0]
    val u = image.planes[1]
    val v = image.planes[2]
    val nv21 =
        Yuv420Nv21.convert(
            width = image.width,
            height = image.height,
            yPlane = planeBytes(y),
            yRowStride = y.rowStride,
            uPlane = planeBytes(u),
            uRowStride = u.rowStride,
            uPixelStride = u.pixelStride,
            vPlane = planeBytes(v),
            vRowStride = v.rowStride,
            vPixelStride = v.pixelStride,
        )
    val jpeg = ByteArrayOutputStream()
    val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
    val compressed =
        yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), jpegQuality, jpeg)
    return if (compressed) jpeg.toByteArray() else null
  }

  private fun planeBytes(plane: Image.Plane): ByteArray {
    val buffer = plane.buffer.duplicate()
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)
    return bytes
  }

  private companion object {
    const val TAG = "CameraAccess:OffscreenCapture"
    const val DEFAULT_TIMEOUT_MS = 2500L
    const val DEFAULT_JPEG_QUALITY = 85
    const val POLL_INTERVAL_MS = 25L
  }
}
