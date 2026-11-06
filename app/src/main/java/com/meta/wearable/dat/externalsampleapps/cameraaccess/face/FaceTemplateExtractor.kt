package com.meta.wearable.dat.externalsampleapps.cameraaccess.face

import android.graphics.Bitmap
import android.graphics.Rect
import kotlin.math.sqrt

/**
 * Produces a compact local-only appearance descriptor for a normalized face crop.
 *
 * This is intentionally isolated behind a class so it can be replaced by a validated TFLite face
 * embedding model without changing enrollment, encrypted storage, or matching policy.
 */
class FaceTemplateExtractor {
  fun extract(bitmap: Bitmap, bounds: Rect): FloatArray {
    require(!bounds.isEmpty) { "Face bounds are empty" }
    val crop = Bitmap.createBitmap(bitmap, bounds.left, bounds.top, bounds.width(), bounds.height())
    val normalized = Bitmap.createScaledBitmap(crop, INPUT_SIZE, INPUT_SIZE, true)
    if (crop !== normalized) crop.recycle()

    return try {
      val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
      normalized.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
      val descriptor = FloatArray(GRID_SIZE * GRID_SIZE)
      val cellSize = INPUT_SIZE / GRID_SIZE
      for (cellY in 0 until GRID_SIZE) {
        for (cellX in 0 until GRID_SIZE) {
          var luminance = 0f
          for (y in 0 until cellSize) {
            for (x in 0 until cellSize) {
              val color = pixels[(cellY * cellSize + y) * INPUT_SIZE + cellX * cellSize + x]
              val red = color shr 16 and 0xff
              val green = color shr 8 and 0xff
              val blue = color and 0xff
              luminance += 0.299f * red + 0.587f * green + 0.114f * blue
            }
          }
          descriptor[cellY * GRID_SIZE + cellX] = luminance / (cellSize * cellSize)
        }
      }
      normalize(descriptor)
    } finally {
      normalized.recycle()
    }
  }

  private fun normalize(values: FloatArray): FloatArray {
    val mean = values.average().toFloat()
    var squaredNorm = 0f
    for (index in values.indices) {
      values[index] -= mean
      squaredNorm += values[index] * values[index]
    }
    val norm = sqrt(squaredNorm).coerceAtLeast(1e-6f)
    for (index in values.indices) values[index] /= norm
    return values
  }

  private companion object {
    const val INPUT_SIZE = 64
    const val GRID_SIZE = 8
  }
}

