/*
 * Faro da Memoria — KAN-96
 * Almacén local de credenciales de Meta (Wearables Developer Center).
 * Las credenciales pegadas por el usuario se guardan en preferencias privadas
 * de la app y se usan para mostrar el estado y guiar la conexión de las gafas.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess

import android.content.Context
import android.util.Log

class CredentialsStore(context: Context) {

  private val prefs =
      context.applicationContext.getSharedPreferences("faro_meta_credentials", Context.MODE_PRIVATE)

  companion object {
    private const val TAG = "CredentialsStore"
    private const val KEY_APPLICATION_ID = "mwdat_application_id"
    private const val KEY_CLIENT_TOKEN = "mwdat_client_token"
    private const val KEY_SETUP_COMPLETED = "setup_completed"
  }

  val applicationId: String?
    get() = prefs.getString(KEY_APPLICATION_ID, null)?.takeIf { it.isNotBlank() }

  val clientToken: String?
    get() = prefs.getString(KEY_CLIENT_TOKEN, null)?.takeIf { it.isNotBlank() }

  val setupCompleted: Boolean
    get() = prefs.getBoolean(KEY_SETUP_COMPLETED, false)

  val hasRealCredentials: Boolean
    get() = !applicationId.isNullOrBlank() && !clientToken.isNullOrBlank()

  fun saveCredentials(applicationId: String, clientToken: String) {
    prefs.edit()
        .putString(KEY_APPLICATION_ID, applicationId.trim())
        .putString(KEY_CLIENT_TOKEN, clientToken.trim())
        .apply()
    Log.i(TAG, "Credenciales guardadas")
  }

  fun markSetupCompleted() {
    prefs.edit().putBoolean(KEY_SETUP_COMPLETED, true).apply()
  }

  fun reset() {
    prefs.edit().clear().apply()
  }
}
