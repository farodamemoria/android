/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.stream

/** Connection state of the realtime voice session with the Faro backend. */
enum class VoiceConnectionState {
  STOPPED,
  CONNECTING,
  LISTENING,
  ERROR,
}
