plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.jetbrains.kotlin.android)
}

android {
  namespace = "com.faro.memoria.mobile"
  compileSdk = 36
  defaultConfig {
    applicationId = "com.faro.memoria.mobile"
    minSdk = 31
    targetSdk = 36
    versionCode = 9
    versionName = "0.1-mvp+kan191"
    val realtimeUrl = providers.environmentVariable("FARO_REALTIME_URL").orNull
        ?: "wss://api.loginea.com/realtime"
    val apiUrl = providers.environmentVariable("FARO_API_URL").orNull
        ?: "https://api.loginea.com"
    buildConfigField("String", "REALTIME_URL", "\"$realtimeUrl\"")
    buildConfigField("String", "API_URL", "\"$apiUrl\"")
  }
  buildFeatures { buildConfig = true }
  compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
  kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
  signingConfigs { getByName("debug") { storeFile = file("../app/sample.keystore"); storePassword = "sample"; keyAlias = "sample"; keyPassword = "sample" } }
  buildTypes { release { isMinifyEnabled = false; signingConfig = signingConfigs.getByName("debug") } }
}

dependencies {
  implementation(libs.androidx.activity.compose)
  implementation("com.squareup.okhttp3:okhttp:4.12.0")
  implementation("androidx.camera:camera-core:1.4.2")
  implementation("androidx.camera:camera-camera2:1.4.2")
  implementation("androidx.camera:camera-lifecycle:1.4.2")
  implementation("androidx.camera:camera-view:1.4.2")
  testImplementation("junit:junit:4.13.2")
  testImplementation("org.json:json:20250517")
}

