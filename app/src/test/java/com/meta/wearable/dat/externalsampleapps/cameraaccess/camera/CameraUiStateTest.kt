package com.meta.wearable.dat.externalsampleapps.cameraaccess.camera

import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class CameraUiStateTest {
  @Test
  fun `automatic face recognition is enabled for every new session`() {
    assertTrue(CameraUiState().automaticFaceRecognition)
  }

  @Test
  fun `session start in flight owns the session and blocks controls before sdk state changes`() {
    val state = CameraUiState(isStartingSession = true)

    assertTrue(state.hasSession)
    assertTrue(state.isSessionStarting)
    assertTrue(state.isBusy)
    assertFalse(state.isSessionActive)
  }
}
