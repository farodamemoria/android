package com.meta.wearable.dat.externalsampleapps.cameraaccess

import android.app.Application
import com.meta.wearable.dat.externalsampleapps.cameraaccess.faro.FaroErrorReporter

/** KAN-191: registra el manejador global de errores de Faro Camara. */
class FaroApp : Application() {
  override fun onCreate() {
    super.onCreate()
    FaroErrorReporter.install(this)
  }
}
