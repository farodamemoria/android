package com.meta.wearable.dat.externalsampleapps.cameraaccess.face

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.ByteBuffer
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONArray
import org.json.JSONObject

/** Stores biometric templates encrypted by a non-exportable Android Keystore key. */
class EncryptedFaceTemplateStore(context: Context) {
  private val preferences =
      context.getSharedPreferences("local_face_templates", Context.MODE_PRIVATE)

  @Synchronized
  fun loadAll(): List<StoredFaceTemplate> {
    val encoded = preferences.getString(DATA_KEY, null) ?: return emptyList()
    val payload = runCatching { decrypt(Base64.decode(encoded, Base64.NO_WRAP)) }.getOrNull()
        ?: return emptyList()
    val array = JSONArray(payload.toString(Charsets.UTF_8))
    return buildList {
      for (index in 0 until array.length()) {
        val item = array.getJSONObject(index)
        val vectorJson = item.getJSONArray("vector")
        add(
            StoredFaceTemplate(
                id = item.getString("id"),
                displayName = item.getString("displayName"),
                relationship = item.getString("relationship"),
                consentRecordedAtEpochMs = item.getLong("consentRecordedAtEpochMs"),
                vector = FloatArray(vectorJson.length()) { vectorJson.getDouble(it).toFloat() },
            )
        )
      }
    }
  }

  @Synchronized
  fun save(
      displayName: String,
      relationship: String,
      consentRecordedAtEpochMs: Long,
      vector: FloatArray,
  ): StoredFaceTemplate {
    val template =
        StoredFaceTemplate(
            id = UUID.randomUUID().toString(),
            displayName = displayName,
            relationship = relationship,
            consentRecordedAtEpochMs = consentRecordedAtEpochMs,
            vector = vector.copyOf(),
        )
    write(loadAll() + template)
    return template
  }

  @Synchronized
  fun delete(id: String): Boolean {
    val existing = loadAll()
    val retained = existing.filterNot { it.id == id }
    if (retained.size == existing.size) return false
    write(retained)
    return true
  }

  private fun write(templates: List<StoredFaceTemplate>) {
    val array = JSONArray()
    templates.forEach { template ->
      array.put(
          JSONObject()
              .put("id", template.id)
              .put("displayName", template.displayName)
              .put("relationship", template.relationship)
              .put("consentRecordedAtEpochMs", template.consentRecordedAtEpochMs)
              .put("vector", JSONArray(template.vector.toList()))
      )
    }
    val encoded = Base64.encodeToString(encrypt(array.toString().toByteArray()), Base64.NO_WRAP)
    check(preferences.edit().putString(DATA_KEY, encoded).commit()) {
      "Encrypted face template could not be persisted"
    }
  }

  private fun encrypt(plaintext: ByteArray): ByteArray {
    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
    val encrypted = cipher.doFinal(plaintext)
    return ByteBuffer.allocate(1 + cipher.iv.size + encrypted.size)
        .put(cipher.iv.size.toByte())
        .put(cipher.iv)
        .put(encrypted)
        .array()
  }

  private fun decrypt(payload: ByteArray): ByteArray {
    val buffer = ByteBuffer.wrap(payload)
    val iv = ByteArray(buffer.get().toInt() and 0xff)
    buffer.get(iv)
    val ciphertext = ByteArray(buffer.remaining())
    buffer.get(ciphertext)
    return Cipher.getInstance(TRANSFORMATION).run {
      init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
      doFinal(ciphertext)
    }
  }

  private fun getOrCreateKey(): SecretKey {
    val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
    return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
      init(
          KeyGenParameterSpec.Builder(
                  KEY_ALIAS,
                  KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
              )
              .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
              .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
              .setKeySize(256)
              .build()
      )
      generateKey()
    }
  }

  private companion object {
    const val DATA_KEY = "templates_v1"
    const val KEY_ALIAS = "aura_local_face_templates_v1"
    const val TRANSFORMATION = "AES/GCM/NoPadding"
  }
}

data class StoredFaceTemplate(
    val id: String,
    val displayName: String,
    val relationship: String,
    val consentRecordedAtEpochMs: Long,
    val vector: FloatArray,
)

