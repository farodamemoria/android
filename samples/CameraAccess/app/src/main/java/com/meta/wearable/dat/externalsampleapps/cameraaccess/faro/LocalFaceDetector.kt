package com.meta.wearable.dat.externalsampleapps.cameraaccess.faro

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

class LocalFaceDetector : AutoCloseable {
  private val detector =
      FaceDetection.getClient(
          FaceDetectorOptions.Builder()
              .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
              .setMinFaceSize(0.10f)
              .build()
      )

  fun hasFace(bitmap: Bitmap): Boolean = largestFace(bitmap) != null

  fun cropLargestFace(bitmap: Bitmap): ByteArray? {
    val box = largestFace(bitmap) ?: return null
    val padding = (box.width() * 0.20f).toInt()
    val left = (box.left - padding).coerceAtLeast(0)
    val top = (box.top - padding).coerceAtLeast(0)
    val right = (box.right + padding).coerceAtMost(bitmap.width)
    val bottom = (box.bottom + padding).coerceAtMost(bitmap.height)
    if (right <= left || bottom <= top) return null
    val crop = Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
    return try {
      ByteArrayOutputStream().use { out ->
        crop.compress(Bitmap.CompressFormat.JPEG, 90, out)
        out.toByteArray()
      }
    } finally {
      crop.recycle()
    }
  }

  private fun largestFace(bitmap: Bitmap): Rect? {
    if (bitmap.isRecycled) return null
    return try {
      val faces =
          Tasks.await(
              detector.process(InputImage.fromBitmap(bitmap, 0)),
              DETECTION_TIMEOUT_SECONDS,
              TimeUnit.SECONDS,
          )
      faces
          .filter { it.boundingBox.width() > 0 && it.boundingBox.height() > 0 }
          .maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
          ?.boundingBox
    } catch (error: Exception) {
      null
    }
  }

  override fun close() {
    detector.close()
  }

  private companion object {
    const val DETECTION_TIMEOUT_SECONDS = 5L
  }
}
