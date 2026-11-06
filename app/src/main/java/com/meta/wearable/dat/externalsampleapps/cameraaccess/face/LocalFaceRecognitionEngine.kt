package com.meta.wearable.dat.externalsampleapps.cameraaccess.face

import android.graphics.Bitmap

/** Consent-gated enrollment and conservative local matching policy. */
class LocalFaceRecognitionEngine(
    private val detector: LocalFaceDetector,
    private val extractor: FaceTemplateExtractor,
    private val store: EncryptedFaceTemplateStore,
    private val config: FaceRecognitionConfig = FaceRecognitionConfig(),
) {
  suspend fun enroll(
      request: FaceEnrollmentRequest,
      samples: List<Bitmap>,
  ): Result<StoredFaceTemplate> = runCatching {
    request.validate().getOrThrow()
    require(samples.size >= config.enrollmentSampleCount) {
      "At least ${config.enrollmentSampleCount} face samples are required"
    }
    val vectors =
        samples.take(config.enrollmentSampleCount).mapIndexed { index, bitmap ->
          val faces = detector.detect(bitmap).getOrThrow()
          require(faces.size == config.maximumFacesPerEnrollmentImage) {
            "Enrollment sample ${index + 1} must contain exactly one eligible face"
          }
          extractor.extract(bitmap, faces.single().bounds)
        }
    store.save(
        displayName = request.displayName.trim(),
        relationship = request.relationship.trim(),
        consentRecordedAtEpochMs = System.currentTimeMillis(),
        vector = averageAndNormalize(vectors),
    )
  }

  suspend fun recognize(bitmap: Bitmap): FaceRecognitionResult {
    val faces = detector.detect(bitmap).getOrElse { return FaceRecognitionResult.Error(it.message) }
    if (faces.isEmpty()) return FaceRecognitionResult.NoFace
    if (faces.size > 1) return FaceRecognitionResult.Ambiguous("More than one face is visible")
    val templates = store.loadAll()
    if (templates.isEmpty()) return FaceRecognitionResult.Unknown
    val candidate = extractor.extract(bitmap, faces.single().bounds)
    val ranked =
        templates.map { it to cosine(candidate, it.vector) }.sortedByDescending { it.second }
    val best = ranked.first()
    val runnerUp = ranked.getOrNull(1)?.second ?: -1f
    if (
        best.second < config.recognitionSimilarityThreshold ||
            best.second - runnerUp < MINIMUM_AMBIGUITY_MARGIN
    ) {
      return FaceRecognitionResult.Unknown
    }
    return FaceRecognitionResult.Match(best.first, best.second)
  }

  private fun averageAndNormalize(vectors: List<FloatArray>): FloatArray {
    val average = FloatArray(vectors.first().size)
    vectors.forEach { vector -> vector.forEachIndexed { index, value -> average[index] += value } }
    average.indices.forEach { average[it] /= vectors.size }
    val norm = kotlin.math.sqrt(average.sumOf { (it * it).toDouble() }).toFloat().coerceAtLeast(1e-6f)
    average.indices.forEach { average[it] /= norm }
    return average
  }

  private fun cosine(left: FloatArray, right: FloatArray): Float {
    if (left.size != right.size) return -1f
    var score = 0f
    left.indices.forEach { score += left[it] * right[it] }
    return score
  }

  private companion object {
    const val MINIMUM_AMBIGUITY_MARGIN = 0.08f
  }
}

sealed interface FaceRecognitionResult {
  data object NoFace : FaceRecognitionResult
  data object Unknown : FaceRecognitionResult
  data class Match(val template: StoredFaceTemplate, val similarity: Float) : FaceRecognitionResult
  data class Ambiguous(val reason: String) : FaceRecognitionResult
  data class Error(val reason: String?) : FaceRecognitionResult
}

