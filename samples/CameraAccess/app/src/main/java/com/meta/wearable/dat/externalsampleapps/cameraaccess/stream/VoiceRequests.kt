/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.stream

/** A `CAMERA_REQUEST:` from the realtime backend: capture a glasses frame for [callId]. */
data class CameraCaptureRequest(val callId: String, val safety: Boolean)

/**
 * A `FAMILY_HELP_REQUEST:` from the realtime backend: alert the care network about [callId].
 * [kind] is `episode` or `hazard`; [spokenMessage] is the message to confirm to the patient.
 */
data class FamilyHelpRequest(val callId: String, val kind: String, val spokenMessage: String)

/** A `FACE_RECOGNITION_REQUEST:` from the realtime backend for [callId]. */
data class FaceRecognitionRequest(val callId: String)

/** A `LOCATION_ANSWER_REQUEST:` from the realtime backend for [callId]. */
data class CurrentLocationRequest(val callId: String)
