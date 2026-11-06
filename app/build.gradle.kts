/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

val localProperties =
    Properties().apply {
      val file = rootProject.file("local.properties")
      if (file.exists()) file.inputStream().use(::load)
    }

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.jetbrains.kotlin.android)
  alias(libs.plugins.compose.compiler)
}

android {
  namespace = "com.meta.wearable.dat.externalsampleapps.cameraaccess"
  compileSdk = 36

  buildFeatures { buildConfig = true }

  defaultConfig {
    applicationId = "com.meta.wearable.dat.externalsampleapps.cameraaccess"
    minSdk = 31
    targetSdk = 36
    versionCode = 1
    versionName = "1.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    // Meta Wearables Device Access Toolkit Setup
    // Developer Mode (0/0): conecta a traves de la app Meta AI (app habilitada por el usuario).
    manifestPlaceholders["mwdat_application_id"] = "0"
    manifestPlaceholders["mwdat_client_token"] = "0"
    val auraBackendUrl =
        providers.environmentVariable("AURA_BACKEND_URL").orNull
            ?: localProperties.getProperty("aura_backend_url")
            ?: "https://d2n7ih9kfxbzvd.cloudfront.net"
    val auraAccessToken =
        providers.environmentVariable("AURA_ACCESS_TOKEN").orNull
            ?: localProperties.getProperty("aura_access_token")
            ?: "local-development-only"
    buildConfigField("String", "AURA_BACKEND_URL", "\"$auraBackendUrl\"")
    buildConfigField("String", "AURA_ACCESS_TOKEN", "\"$auraAccessToken\"")
    val realtimeBackendUrl =
        providers.environmentVariable("REALTIME_BACKEND_URL").orNull
            ?: localProperties.getProperty("realtime_backend_url")
            ?: "wss://d2n7ih9kfxbzvd.cloudfront.net/realtime"
    buildConfigField("String", "REALTIME_BACKEND_URL", "\"$realtimeBackendUrl\"")
  }

  buildTypes {
    debug {
      versionNameSuffix = "-faro-validated+kan191"
    }
    release {
      isMinifyEnabled = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs.getByName("debug")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
  signingConfigs {
    getByName("debug") {
      storeFile = file("sample.keystore")
      storePassword = "sample"
      keyAlias = "sample"
      keyPassword = "sample"
    }
  }
}

kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_17 } }

dependencies {
  testImplementation("junit:junit:4.13.2")
  implementation(libs.androidx.activity.compose)
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.exifinterface)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.material.icons.extended)
  implementation(libs.androidx.material3)
  implementation(libs.kotlinx.collections.immutable)
  implementation(libs.mwdat.core)
  implementation(libs.mwdat.camera)
  implementation(libs.mwdat.mockdevice)
  implementation(libs.mlkit.face.detection)
  implementation(libs.okhttp)
  androidTestImplementation(libs.androidx.ui.test.junit4)
  androidTestImplementation(libs.androidx.test.uiautomator)
  androidTestImplementation(libs.androidx.test.rules)
}

