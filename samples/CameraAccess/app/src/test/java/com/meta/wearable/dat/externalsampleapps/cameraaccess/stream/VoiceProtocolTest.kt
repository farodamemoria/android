package com.meta.wearable.dat.externalsampleapps.cameraaccess.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * KAN-112: the glasses must understand every text message the `faro-realtime` bridge sends
 * (`realtime_bridge.py`). The parser is pure Kotlin, so it is covered by local unit tests.
 */
class VoiceProtocolTest {
  @Test
  fun parsesAudioStartWithItsJsonPayload() {
    val event = VoiceProtocol.parse("""AUDIO_START:{"item_id":"item-1","content_index":0}""")
    assertEquals(
        VoiceEvent.AudioStart("""{"item_id":"item-1","content_index":0}"""),
        event,
    )
  }

  @Test
  fun parsesAudioClear() {
    assertEquals(VoiceEvent.AudioClear, VoiceProtocol.parse("AUDIO_CLEAR"))
  }

  @Test
  fun parsesBackendError() {
    assertEquals(
        VoiceEvent.Error("No ha sido posible conectar con Faro"),
        VoiceProtocol.parse("ERROR:No ha sido posible conectar con Faro"),
    )
  }

  @Test
  fun parsesCameraRequestWithJsonPayload() {
    val event =
        VoiceProtocol.parse("""CAMERA_REQUEST:{"call_id":"call-7","purpose":"safety"}""")
    assertTrue(event is VoiceEvent.CameraRequest)
    assertTrue((event as VoiceEvent.CameraRequest).payload.contains("call-7"))
  }

  @Test
  fun parsesCameraRequestWithBareCallId() {
    assertEquals(VoiceEvent.CameraRequest("call-9"), VoiceProtocol.parse("CAMERA_REQUEST:call-9"))
  }

  @Test
  fun parsesFamilyHelpRequest() {
    val event =
        VoiceProtocol.parse(
            """FAMILY_HELP_REQUEST:{"call_id":"call-3","kind":"episode","spoken_message":"Axuda"}""")
    assertTrue(event is VoiceEvent.FamilyHelpRequest)
    assertTrue((event as VoiceEvent.FamilyHelpRequest).payload.contains("\"kind\":\"episode\""))
  }

  @Test
  fun parsesLocationAnswerRequest() {
    assertEquals(
        VoiceEvent.LocationAnswerRequest("""{"call_id":"call-4"}"""),
        VoiceProtocol.parse("""LOCATION_ANSWER_REQUEST:{"call_id":"call-4"}"""),
    )
  }

  @Test
  fun parsesFaceRecognitionRequest() {
    assertEquals(
        VoiceEvent.FaceRecognitionRequest("""{"call_id":"call-5"}"""),
        VoiceProtocol.parse("""FACE_RECOGNITION_REQUEST:{"call_id":"call-5"}"""),
    )
  }

  @Test
  fun ignoresMessagesTheDeviceDoesNotHandle() {
    assertEquals(VoiceEvent.Ignored, VoiceProtocol.parse("""{"type":"hello"}"""))
    assertEquals(VoiceEvent.Ignored, VoiceProtocol.parse(""))
    assertEquals(VoiceEvent.Ignored, VoiceProtocol.parse("SOMETHING_ERROR:inside"))
  }
}
