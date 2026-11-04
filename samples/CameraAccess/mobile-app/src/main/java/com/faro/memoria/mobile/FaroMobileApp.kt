package com.faro.memoria.mobile

import android.app.Application

/** KAN-191: registra el manejador global de errores de Faro Movil. */
class FaroMobileApp : Application() {
  override fun onCreate() {
    super.onCreate()
    FaroErrorReporter.install(this)
  }
}
