package com.meta.wearable.dat.externalsampleapps.cameraaccess.faro

import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.Collections
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * KAN-35 - Contrato HTTP real del reporter acustico de las gafas.
 *
 * Se levanta un servidor HTTP minimo sobre `java.net.ServerSocket` (disponible en el classpath de
 * pruebas unitarias de Android) para comprobar metodo, ruta, credencial, cuerpo y lectura de la
 * respuesta del backend sin depender de `com.sun.net.httpserver`.
 */
class AcousticReporterTest {
  private data class CapturedRequest(
      val method: String,
      val path: String,
      val authorization: String?,
      val contentType: String?,
      val body: String,
  )

  private class OneShotHttpServer(
      private val status: Int,
      private val responseBody: String,
  ) : AutoCloseable {
    private val socket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    val requests = Collections.synchronizedList(mutableListOf<CapturedRequest>())

    val baseUrl: String
      get() = "http://127.0.0.1:${socket.localPort}"

    private val worker = thread(isDaemon = true, name = "kan35-test-http") {
      runCatching {
        socket.accept().use { client ->
          client.soTimeout = 5_000
          val input = client.getInputStream().buffered()
          val requestLine = readLine(input) ?: return@use
          val parts = requestLine.split(" ")
          val headers = mutableMapOf<String, String>()
          var contentLength = 0
          while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val separator = line.indexOf(':')
            if (separator <= 0) continue
            val name = line.substring(0, separator).trim().lowercase()
            val value = line.substring(separator + 1).trim()
            headers[name] = value
            if (name == "content-length") contentLength = value.toIntOrNull() ?: 0
          }
          val bodyBytes = ByteArray(contentLength)
          var read = 0
          while (read < contentLength) {
            val chunk = input.read(bodyBytes, read, contentLength - read)
            if (chunk < 0) break
            read += chunk
          }
          requests.add(
              CapturedRequest(
                  method = parts.getOrElse(0) { "" },
                  path = parts.getOrElse(1) { "" },
                  authorization = headers["authorization"],
                  contentType = headers["content-type"],
                  body = String(bodyBytes, 0, read, StandardCharsets.UTF_8),
              )
          )
          val payload = responseBody.toByteArray(StandardCharsets.UTF_8)
          val reason = if (status in 200..299) "OK" else "Error"
          val head = "HTTP/1.1 $status $reason\r\n" +
              "Content-Type: application/json; charset=utf-8\r\n" +
              "Content-Length: ${payload.size}\r\n" +
              "Connection: close\r\n\r\n"
          client.getOutputStream().apply {
            write(head.toByteArray(StandardCharsets.UTF_8))
            write(payload)
            flush()
          }
        }
      }
    }

    override fun close() {
      runCatching { socket.close() }
      worker.join(2_000)
    }

    private fun readLine(input: InputStream): String? {
      val bytes = ArrayList<Byte>(128)
      while (true) {
        val value = input.read()
        if (value < 0) return if (bytes.isEmpty()) null else String(bytes.toByteArray())
        if (value == '\n'.code) break
        if (value != '\r'.code) bytes.add(value.toByte())
      }
      return String(bytes.toByteArray(), StandardCharsets.UTF_8)
    }
  }

  @Test
  fun cough_detection_is_posted_with_token_and_parsed() {
    OneShotHttpServer(200, "{\"action\":\"ask\",\"question\":\"\u00bfEst\u00e1s bien?\",\"episode_id\":\"abc\"}")
        .use { server ->
          val reporter = AcousticReporter(server.baseUrl, "token-de-prueba-1234567890")

          val outcome = reporter.report(AcousticPatternDetector.SIGNAL_COUGH, 0.9)

          assertNotNull(outcome)
          assertEquals("ask", outcome!!.action)
          assertEquals("\u00bfEst\u00e1s bien?", outcome.question)
          val request = server.requests.single()
          assertEquals("POST", request.method)
          assertEquals("/v1/acoustic-events", request.path)
          assertEquals("Bearer token-de-prueba-1234567890", request.authorization)
          assertTrue(request.contentType.orEmpty().startsWith("application/json"))
          assertTrue(request.body.contains("\"signal\":\"cough\""))
          assertTrue(request.body.contains("\"confidence\":0.90"))
        }
  }

  @Test
  fun tick_is_posted_to_its_own_endpoint() {
    OneShotHttpServer(200, "{\"action\":\"idle\"}").use { server ->
      val reporter = AcousticReporter(server.baseUrl, "token-de-prueba-1234567890")

      val outcome = reporter.tick()

      assertNotNull(outcome)
      assertEquals("idle", outcome!!.action)
      assertNull(outcome.question)
      val request = server.requests.single()
      assertEquals("POST", request.method)
      assertEquals("/v1/acoustic-events/tick", request.path)
      assertEquals("{}", request.body)
    }
  }

  @Test
  fun unauthorized_response_is_reported_as_null() {
    OneShotHttpServer(401, "{\"detail\":\"Unauthorized\"}").use { server ->
      val reporter = AcousticReporter(server.baseUrl, "token-caducado-1234567890")

      assertNull(reporter.report(AcousticPatternDetector.SIGNAL_FALL, 0.8))
    }
  }

  @Test
  fun unpaired_device_never_calls_the_backend() {
    OneShotHttpServer(200, "{\"action\":\"ask\"}").use { server ->
      val reporter = AcousticReporter(server.baseUrl, "")

      assertNull(reporter.report(AcousticPatternDetector.SIGNAL_COUGH, 0.9))
      assertNull(reporter.tick())
      assertTrue(server.requests.isEmpty())
    }
  }

  @Test
  fun network_failure_is_swallowed_without_throwing() {
    val reporter = AcousticReporter("http://127.0.0.1:1", "token-de-prueba-1234567890")

    assertNull(reporter.report(AcousticPatternDetector.SIGNAL_COUGH, 0.9))
  }
}
