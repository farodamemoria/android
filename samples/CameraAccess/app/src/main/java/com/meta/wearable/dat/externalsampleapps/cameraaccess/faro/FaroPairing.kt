/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.faro

import android.content.Context

/**
 * Device pairing with the Faro care circle. The 8-character code (from Faro Familia) is claimed
 * through [FaroApi.claim]; the returned `device_credential` authenticates the realtime voice
 * session (WebSocket Bearer) and is kept locally in preferences.
 */
object FaroPairing {
  private const val PREFS = "faro_pairing"
  private const val KEY_CREDENTIAL = "device_credential"
  private const val KEY_CIRCLE = "care_circle_id"
  private const val KEY_ROLE = "role"

  private fun prefs(context: Context) =
      context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  fun credential(context: Context): String =
      prefs(context).getString(KEY_CREDENTIAL, "").orEmpty()

  fun isPaired(context: Context): Boolean = credential(context).isNotBlank()

  fun careCircleId(context: Context): String = prefs(context).getString(KEY_CIRCLE, "").orEmpty()

  fun role(context: Context): String = prefs(context).getString(KEY_ROLE, "").orEmpty()

  private fun save(context: Context, credential: String, careCircleId: String, role: String) {
    prefs(context)
        .edit()
        .putString(KEY_CREDENTIAL, credential)
        .putString(KEY_CIRCLE, careCircleId)
        .putString(KEY_ROLE, role)
        .apply()
  }

  fun clear(context: Context) {
    prefs(context).edit().clear().apply()
  }

  /**
   * Claims [code] (8 characters) and stores the returned credential. Returns null on success or a
   * user-facing error message on failure. Blocking: call from a background dispatcher.
   */
  fun claim(context: Context, code: String): String? {
    val result = FaroApi.claim(code.trim()) ?: return "Non foi posible validar o código"
    val credential = result.optString("device_credential")
    if (credential.isBlank()) return "O servidor non devolveu credencial"
    save(context, credential, result.optString("care_circle_id"), result.optString("role"))
    return null
  }
}
