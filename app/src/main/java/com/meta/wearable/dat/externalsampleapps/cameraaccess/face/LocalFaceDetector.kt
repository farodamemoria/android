package com.meta.wearable.dat.externalsampleapps.cameraaccess.face

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/** Detects face regions locally. This class does not identify people or persist biometric data. */
class LocalFaceDetector(
    private val config: FaceRecognitionConfig = FaceRecognitionConfig(),
) : AutoCloseable {
  private val detector =
      FaceDetection.getClient(
          FaceDetectorOptions.Builder()
              .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
              .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
              .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
              .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
              .setMinFaceSize(0.15f)
              .enableTracking()
              .build()
      )

  suspend fun detect(bitmap: Bitmap): Result<List<DetectedFace>> =
      suspendCancellableCoroutine { continuation ->
        detector
            .process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { faces ->
              if (continuation.isActive) {
                continuation.resume(
                    Result.success(
                        faces.mapNotNull { face ->
                          val bounds = face.boundingBox.intersectedWith(bitmap.width, bitmap.height)
                          if (
                              bounds.width() < config.minimumFaceSizePx ||
                                  bounds.height() < config.minimumFaceSizePx
                          ) {
                            null
                          } else {
                            DetectedFace(bounds = bounds, trackingId = face.trackingId)
                          }
                        }
                    )
                )
              }
            }
            .addOnFailureListener { error ->
              if (continuation.isActive) continuation.resume(Result.failure(error))
            }
      }

  override fun close() {
    detector.close()
  }
}

data class DetectedFace(
    val bounds: Rect,
    val trackingId: Int?,
)

private fun Rect.intersectedWith(bitmapWidth: Int, bitmapHeight: Int): Rect {
  val clipped = Rect(this)
  return if (clipped.intersect(0, 0, bitmapWidth, bitmapHeight)) clipped else Rect()
}
