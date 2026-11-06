package com.meta.wearable.dat.externalsampleapps.cameraaccess.faro

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.meta.wearable.dat.externalsampleapps.cameraaccess.BuildConfig

/**
 * KAN-191: disparador de prueba (solo builds debug). Al recibir la accion provoca una excepcion
 * no controlada para verificar que el reporter envia el error al log central.
 */
class CrashTestReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (!BuildConfig.DEBUG) return
    if (intent.action == ACTION) {
      throw IllegalStateException("KAN-191 prueba controlada de crash en Faro Camara")
    }
  }

  companion object {
    const val ACTION = "com.faro.memoria.CRASH_TEST"
  }
}
