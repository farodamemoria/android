package com.meta.wearable.dat.externalsampleapps.cameraaccess.face

/**
 * Safety and quality defaults for consent-based face enrollment.
 *
 * Detection and recognition remain local to the phone. A profile must never be enrolled without
 * an explicit display name, relationship and consent confirmation from an authorized caregiver.
 */
data class FaceRecognitionConfig(
    val enrollmentSampleCount: Int = 5,
    val minimumFaceSizePx: Int = 120,
    val maximumFacesPerEnrollmentImage: Int = 1,
    val recognitionSimilarityThreshold: Float = 0.82f,
    val consecutiveMatchesRequired: Int = 3,
)

data class FaceEnrollmentRequest(
    val displayName: String,
    val relationship: String,
    val consentConfirmed: Boolean,
) {
  fun validate(): Result<Unit> =
      when {
        displayName.isBlank() -> Result.failure(IllegalArgumentException("Display name is required"))
        relationship.isBlank() -> Result.failure(IllegalArgumentException("Relationship is required"))
        !consentConfirmed ->
            Result.failure(IllegalStateException("Explicit consent is required for enrollment"))
        else -> Result.success(Unit)
      }
}
