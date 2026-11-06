plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.jetbrains.kotlin.android)
}

android {
  namespace = "com.faro.familia"
  compileSdk = 36
  defaultConfig {
    applicationId = "com.faro.familia"
    minSdk = 31
    targetSdk = 36
    versionCode = 1
    versionName = "1.0-faro"
    val portalUrl = providers.environmentVariable("FARO_FAMILY_URL").orNull
        ?: "https://d2n7ih9kfxbzvd.cloudfront.net/"
    buildConfigField("String", "PORTAL_URL", "\"$portalUrl\"")
  }
  buildFeatures { buildConfig = true }
  compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
  kotlin {
    compilerOptions {
      jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
  }
  signingConfigs { getByName("debug") { storeFile = file("../app/sample.keystore"); storePassword = "sample"; keyAlias = "sample"; keyPassword = "sample" } }
  buildTypes { release { isMinifyEnabled = false; signingConfig = signingConfigs.getByName("debug") } }
}
