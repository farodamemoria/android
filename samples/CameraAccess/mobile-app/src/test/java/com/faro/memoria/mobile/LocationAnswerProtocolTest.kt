package com.faro.memoria.mobile

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocationAnswerProtocolTest {
  @Test fun parsesPrefixedRequest() {
    assertEquals(
        "call-1",
        LocationAnswerProtocol.requestCallId("LOCATION_ANSWER_REQUEST:{\"call_id\":\"call-1\"}"),
    )
  }

  @Test fun parsesJsonRequestAndRejectsOtherEvents() {
    assertEquals(
        "call-2",
        LocationAnswerProtocol.requestCallId(
            "{\"type\":\"location_answer_request\",\"call_id\":\"call-2\"}",
        ),
    )
    assertNull(LocationAnswerProtocol.requestCallId("{\"type\":\"camera_request\",\"call_id\":\"call-3\"}"))
    assertNull(LocationAnswerProtocol.requestCallId("LOCATION_ANSWER_REQUEST:{\"call_id\":\"\"}"))
  }

  @Test fun serializesRequiredAndOptionalResultFields() {
    val json = JSONObject(
        LocationAnswerResult(
            "call-4", "at_home", "Estás en casa.", "Casa", 4.2, 12.0,
        ).toWireMessage(),
    )
    assertEquals("location_answer_result", json.getString("type"))
    assertEquals("call-4", json.getString("call_id"))
    assertEquals("at_home", json.getString("status"))
    assertEquals("Estás en casa.", json.getString("message"))
    assertEquals("Casa", json.getString("place_label"))
    assertEquals(4.2, json.getDouble("distance_meters"), 0.0)
    assertEquals(12.0, json.getDouble("accuracy_meters"), 0.0)
  }

  @Test fun mapsBackendResponseToWireResult() {
    val result = LocationAnswerResult.fromBackend(
        "call-5",
        "{\"status\":\"refresh_required\",\"message\":\"Ubicación aproximada\",\"accuracy_meters\":240}",
    )
    assertEquals("call-5", result.callId)
    assertEquals("refresh_required", result.status)
    assertEquals("Ubicación aproximada", result.message)
    assertEquals(240.0, result.accuracyMeters!!, 0.0)
  }
}
