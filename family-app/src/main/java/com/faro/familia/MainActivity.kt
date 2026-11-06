package com.faro.familia

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.graphics.Color
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView

class MainActivity : Activity() {
  private var pendingUpload: ValueCallback<Array<Uri>>? = null
  private var pendingCameraUri: Uri? = null
  private val pickerCode = 41

  override fun onCreate(state: Bundle?) {
    super.onCreate(state)
    window.statusBarColor = Color.rgb(15, 61, 49)
    window.navigationBarColor = Color.rgb(246, 248, 245)
    window.decorView.systemUiVisibility = 0
    val loading = TextView(this).apply {
      text = "Cargando Faro Familia…"
      setTextColor(Color.rgb(23, 99, 78))
      textSize = 16f
      gravity = Gravity.CENTER
      setPadding(32, 32, 32, 32)
      setBackgroundColor(Color.rgb(246, 248, 245))
    }
    val spinner = ProgressBar(this).apply {
      isIndeterminate = true
    }
    val web = WebView(this).apply {
      settings.javaScriptEnabled = true
      settings.domStorageEnabled = true
      settings.allowFileAccess = false
      settings.mediaPlaybackRequiresUserGesture = false
      overScrollMode = WebView.OVER_SCROLL_NEVER
      isVerticalScrollBarEnabled = false
      CookieManager.getInstance().setAcceptCookie(true)
      webViewClient = object : WebViewClient() {
        override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
          loading.visibility = View.VISIBLE
          spinner.visibility = View.VISIBLE
        }
        override fun onPageFinished(view: WebView, url: String) {
          loading.visibility = View.GONE
          spinner.visibility = View.GONE
        }
        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
          if (request.isForMainFrame) {
            loading.text = "Non se puido conectar con Faro Familia.\nComproba a conexión e toca para reintentar."
            loading.visibility = View.VISIBLE
            spinner.visibility = View.GONE
            loading.setOnClickListener { view.reload() }
          }
        }
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = false
      }
      webChromeClient = object : WebChromeClient() {
        override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
          pendingUpload = callback
          val intent = if (params.isCaptureEnabled) {
            pendingCameraUri = createCameraUri()
            Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply { putExtra(MediaStore.EXTRA_OUTPUT, pendingCameraUri); addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION) }
          } else params.createIntent().apply { putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true) }
          return runCatching { startActivityForResult(intent, pickerCode); true }.getOrElse {
            pendingUpload = null
            false
          }
        }
      }
      loadUrl(BuildConfig.PORTAL_URL)
    }
    setContentView(FrameLayout(this).apply {
      addView(web, FrameLayout.LayoutParams(-1, -1))
      addView(loading, FrameLayout.LayoutParams(-1, -1))
      addView(spinner, FrameLayout.LayoutParams(64, 64, Gravity.CENTER))
    })
  }

  @Deprecated("Activity result API kept compatible with the minimum SDK")
  override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    if (requestCode != pickerCode) {
      super.onActivityResult(requestCode, resultCode, data)
      return
    }
    val picked = data?.let {
      val clip = it.clipData
      if (clip != null) Array(clip.itemCount) { index -> clip.getItemAt(index).uri }
      else it.data?.let { uri -> arrayOf(uri) }
    }
    val cameraUsed = resultCode == RESULT_OK && picked == null && pendingCameraUri != null
    val results = if (resultCode != RESULT_OK) null else picked ?: pendingCameraUri?.let { arrayOf(it) }
    pendingCameraUri?.let { uri ->
      if (cameraUsed) contentResolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
      else contentResolver.delete(uri, null, null)
    }
    pendingCameraUri = null
    val upload = pendingUpload
    pendingUpload = null
    runCatching { upload?.onReceiveValue(results) }
  }

  private fun createCameraUri(): Uri? = contentResolver.insert(
      MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
      ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "faro-paciente-${System.currentTimeMillis()}.jpg")
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Faro")
        put(MediaStore.Images.Media.IS_PENDING, 1)
      },
  )
}
