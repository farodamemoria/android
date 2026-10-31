/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.stream

/** A text message sent by the Faro realtime backend to the glasses. */
sealed interface VoiceEvent {
  /** Starts a new server audio item; [payload] is the JSON `{item_id, content_index}`. */
  data class AudioStart(val payload: String) : VoiceEvent

  /** Drops the audio currently playing; the device answers with `audio_playback_cleared`. */
  data object AudioClear : VoiceEvent

  /** A session error surfaced by the backend. */
  data class Error(val message: String) : VoiceEvent

  /** Asks for a glasses image; [payload] is `{call_id, purpose}` or a bare call id. */
  data class CameraRequest(val payload: String) : VoiceEvent

  /** Asks to alert the care network; [payload] is `{call_id, kind, spoken_message}`. */
  data class FamilyHelpRequest(val payload: String) : VoiceEvent

  /** Asks for the current location; [payload] is `{call_id}`. */
  data class LocationAnswerRequest(val payload: String) : VoiceEvent

  /** Asks to identify the person in front; [payload] is `{call_id}`. */
  data class FaceRecognitionRequest(val payload: String) : VoiceEvent

  /** Anything the device does not handle. */
  data object Ignored : VoiceEvent
}

/**
 * Server-to-device protocols of the Faro realtime bridge (`faro-realtime`, `realtime_bridge.py`):
 * `AUDIO_START:{json}`, `AUDIO_CLEAR`, `ERROR:<message>`, `CAMERA_REQUEST:{json}`,
 * `FAMILY_HELP_REQUEST:{json}`, `LOCATION_ANSWER_REQUEST:{json}` and
 * `FACE_RECOGNITION_REQUEST:{json}`.
 */
object VoiceProtocol {
  const val AUDIO_START_PREFIX = "AUDIO_START:"
  const val AUDIO_CLEAR = "AUDIO_CLEAR"
  const val ERROR_PREFIX = "ERROR:"
  const val CAMERA_REQUEST_PREFIX = "CAMERA_REQUEST:"
  const val FAMILY_HELP_REQUEST_PREFIX = "FAMILY_HELP_REQUEST:"
  const val LOCATION_ANSWER_REQUEST_PREFIX = "LOCATION_ANSWER_REQUEST:"
  const val FACE_RECOGNITION_REQUEST_PREFIX = "FACE_RECOGNITION_REQUEST:"

  fun parse(text: String): VoiceEvent =
      when {
        text == AUDIO_CLEAR -> VoiceEvent.AudioClear
        text.startsWith(AUDIO_START_PREFIX) ->
            VoiceEvent.AudioStart(text.removePrefix(AUDIO_START_PREFIX))
        text.startsWith(ERROR_PREFIX) -> VoiceEvent.Error(text.removePrefix(ERROR_PREFIX))
        text.startsWith(CAMERA_REQUEST_PREFIX) ->
            VoiceEvent.CameraRequest(text.removePrefix(CAMERA_REQUEST_PREFIX).trim())
        text.startsWith(FAMILY_HELP_REQUEST_PREFIX) ->
            VoiceEvent.FamilyHelpRequest(text.removePrefix(FAMILY_HELP_REQUEST_PREFIX))
        text.startsWith(LOCATION_ANSWER_REQUEST_PREFIX) ->
            VoiceEvent.LocationAnswerRequest(text.removePrefix(LOCATION_ANSWER_REQUEST_PREFIX))
        text.startsWith(FACE_RECOGNITION_REQUEST_PREFIX) ->
            VoiceEvent.FaceRecognitionRequest(
                text.removePrefix(FACE_RECOGNITION_REQUEST_PREFIX))
        else -> VoiceEvent.Ignored
      }
}
