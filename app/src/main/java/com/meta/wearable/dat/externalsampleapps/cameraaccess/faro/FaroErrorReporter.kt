package com.meta.wearable.dat.externalsampleapps.cameraaccess.faro

import android.content.Context
import com.meta.wearable.dat.externalsampleapps.cameraaccess.BuildConfig
import java.io.PrintWriter
import java.io.StringWriter
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/**
 * KAN-191: envia los errores de la app al log central de Faro antes de que la app se cierre, para
 * poder localizar el fallo exacto. Usa el token de backend compilado en la app.
 */
object FaroErrorReporter {
  private const val BASE = "https://api.loginea.com"
  private const val APP = "camera-access"
  private const val PREFS_VERSION = 1

  @Volatile private var installed = false

  fun install(context: Context) {
    if (installed) return
    installed = true
    val appContext = context.applicationContext
    val previous = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, error ->
      runCatching { report(appContext, error, "uncaught:${thread.name}") }
      previous?.uncaughtException(thread, error)
    }
  }

  fun report(context: Context, error: Throwable, screen: String): Boolean {
    val trace = StringWriter().also { writer -> error.printStackTrace(PrintWriter(writer)) }.toString()
    val message = error.message ?: error.javaClass.name
    // En el manejador de crash estamos en el hilo principal y Android prohibe la red ahi:
    // se envia en segundo plano y se espera brevemente antes de dejar morir el proceso.
    val sent = java.util.concurrent.atomic.AtomicBoolean(false)
    val worker = Thread {
      sent.set(post(context, message, "screen=$screen", trace))
    }
    worker.start()
    runCatching { worker.join(3500) }
    return sent.get()
  }

  fun post(context: Context, message: String, contextText: String, traceback: String): Boolean {
    val token = BuildConfig.AURA_ACCESS_TOKEN
    if (token.isBlank()) return false
    return runCatching {
      val body = payload(APP, versionName(context), message, contextText, traceback)
      val connection = URL("$BASE/v1/app-errors").openConnection() as HttpURLConnection
      connection.requestMethod = "POST"
      connection.connectTimeout = 3000
      connection.readTimeout = 3000
      connection.doOutput = true
      connection.setRequestProperty("Authorization", "Bearer $token")
      connection.setRequestProperty("Content-Type", "application/json")
      connection.outputStream.use { it.write(body.toByteArray()) }
      val code = connection.responseCode
      connection.disconnect()
      code in 200..299
    }.getOrDefault(false)
  }

  fun payload(
      app: String,
      version: String,
      message: String,
      contextText: String,
      traceback: String,
  ): String =
      JSONObject()
          .put("app", app)
          .put("version", version)
          .put("message", message.take(2000))
          .put("context", contextText.take(4000))
          .put("traceback_text", traceback.take(20000))
          .toString()

  fun versionName(context: Context): String =
      runCatching {
            context.packageManager.getPackageInfo(context.packageName, PREFS_VERSION).versionName
          }
          .getOrNull()
          ?.takeIf { it.isNotBlank() }
          ?: "desconocida"
}
