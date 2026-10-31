package com.meta.wearable.dat.externalsampleapps.cameraaccess.faro

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * KAN-112: the Faro backend and the realtime bridge live behind the stable `api.loginea.com`
 * CNAME. The old CloudFront distribution domain stopped resolving after the AWS migration, so the
 * client must not point at it (dialog and voice would fail before any request is made).
 */
class FaroEndpointTest {
  private val sourceRoot = File("src/main/java")
  private val deadDomain = "d2n7ih9kfxbzvd.cloudfront.net"

  private fun read(relative: String) = File(sourceRoot, relative).readText()

  private fun kotlinSources(): List<File> =
      sourceRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

  @Test
  fun noSourceFileUsesTheDeadCloudFrontDistribution() {
    val offenders = kotlinSources().filter { it.readText().contains(deadDomain) }
    assertTrue("Ficheros con el dominio muerto: $offenders", offenders.isEmpty())
  }

  @Test
  fun restApiUsesTheStableFaroHost() {
    val source =
        read("com/meta/wearable/dat/externalsampleapps/cameraaccess/faro/FaroApi.kt")
    assertTrue(source.contains("const val BASE = \"https://api.loginea.com\""))
  }

  @Test
  fun realtimeUsesTheStableFaroHost() {
    val source =
        read(
            "com/meta/wearable/dat/externalsampleapps/cameraaccess/stream/OpenAiVoiceController.kt")
    assertTrue(source.contains("\"wss://api.loginea.com/realtime\""))
    assertFalse(source.contains("REALTIME_URL = \"wss://d2n7"))
  }
}
