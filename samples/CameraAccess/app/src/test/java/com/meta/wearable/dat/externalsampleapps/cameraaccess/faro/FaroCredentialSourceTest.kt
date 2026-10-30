package com.meta.wearable.dat.externalsampleapps.cameraaccess.faro

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * KAN-31: the glasses client must never ship a hardcoded credential. Every authenticated call has
 * to use the device credential stored by [FaroPairing] after pairing.
 */
class FaroCredentialSourceTest {
  private val faroDir = File("src/main/java/com/meta/wearable/dat/externalsampleapps/cameraaccess/faro")
  private val legacyToken = "local" + "-development-only"

  private fun read(name: String) = File(faroDir, name).readText()

  @Test
  fun noSourceFileContainsTheLegacyDevelopmentToken() {
    val offenders =
        faroDir.listFiles { file -> file.extension == "kt" }.orEmpty().filter {
          it.readText().contains(legacyToken)
        }
    assertTrue("Ficheros con token heredado: $offenders", offenders.isEmpty())
  }

  @Test
  fun authorizationHeadersUseThePairedDeviceCredential() {
    listOf("FaroApi.kt", "FaroReminders.kt", "FaroAudioMonitor.kt").forEach { name ->
      val source = read(name)
      assertFalse("$name envía un token constante", source.contains("Bearer \$TOKEN"))
      if (source.contains("Authorization")) {
        assertTrue("$name no usa la credencial emparejada", source.contains("Bearer \$token"))
      }
    }
  }

  @Test
  fun apiTokenComesFromPairing() {
    assertTrue(read("FaroApi.kt").contains("FaroPairing.credential(context)"))
  }
}
