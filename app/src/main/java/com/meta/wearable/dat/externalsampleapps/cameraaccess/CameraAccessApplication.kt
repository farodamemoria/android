/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess

import android.app.Application
import android.util.Log
import com.meta.wearable.dat.core.Wearables

class CameraAccessApplication : Application() {
  override fun onCreate() {
    super.onCreate()
    Wearables.initialize(this)
        .onFailure { error, _ -> Log.e(TAG, "Failed to initialize DAT: ${error.description}") }
  }

  private companion object {
    const val TAG = "CameraAccessApp"
  }
}
