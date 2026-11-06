/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

// AudioInputHandler - Phone Microphone Audio Input
//
// Captures raw PCM audio from the phone microphone for sound-in-video recording and
// forwards it to a consumer via a callback. Initialization is guarded so a device with
// no usable microphone (no permission, no input) degrades to video-only instead of
// crashing.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.stream

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AudioInputHandler(context: Context) {
  // Stored as the application context so this non-lifecycle class never retains an Activity.
  private val context: Context = context.applicationContext

  companion object {
    private const val TAG = "AudioInputHandler"
    const val SAMPLE_RATE = 44100
    private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    private const val BUFFER_SIZE_FACTOR = 2
  }

  // PCM data is delivered to this callback (set by VideoRecorder to feed the AAC encoder).
  @Volatile var pcmDataCallback: ((ByteArray, Int, Int) -> Unit)? = null

  // Reference assigned on the caller thread (initializeAudioRecord/cleanup) and read on the
  // recording thread; @Volatile publishes those reads/writes safely.
  @Volatile private var audioRecord: AudioRecord? = null

  private val _isRecording = MutableStateFlow(false)
  val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

  // Set when capture stops unexpectedly (e.g. a phone call grabs the mic) so the caller can
  // tear the recording down gracefully.
  private val _wasInterrupted = MutableStateFlow(false)
  val wasInterrupted: StateFlow<Boolean> = _wasInterrupted.asStateFlow()

  private val _isHfpConnected = MutableStateFlow(queryInitialHfpConnection())
  val isHfpConnected: StateFlow<Boolean> = _isHfpConnected.asStateFlow()

  private val _isHfpAudioConnected = MutableStateFlow(false)
  val isHfpAudioConnected: StateFlow<Boolean> = _isHfpAudioConnected.asStateFlow()

  private var hfpReceiverRegistered = false
  private val hfpStateReceiver =
      object : BroadcastReceiver() {
        override fun onReceive(receiverContext: Context, intent: Intent) {
          when (intent.action) {
            BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED -> {
              val state =
                  intent.getIntExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_DISCONNECTED)
              _isHfpConnected.value = state == BluetoothProfile.STATE_CONNECTED
              if (state == BluetoothProfile.STATE_DISCONNECTED) {
                _isHfpAudioConnected.value = false
              }
              Log.d(TAG, "HFP connection state changed: ${profileStateName(state)}")
            }
            BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED -> {
              val state =
                  intent.getIntExtra(
                      BluetoothProfile.EXTRA_STATE,
                      BluetoothHeadset.STATE_AUDIO_DISCONNECTED,
                  )
              _isHfpAudioConnected.value = state == BluetoothHeadset.STATE_AUDIO_CONNECTED
              Log.d(TAG, "HFP audio state changed: ${audioStateName(state)}")
            }
          }
        }
      }

  init {
    registerHfpStateReceiver()
  }

  @Volatile private var recordingThreadActive = false

  // Recording thread — guarded by `threadLock`
  private val threadLock = Any()
  private var recordingThread: Thread? = null

  private val handler = Handler(Looper.getMainLooper())

  private fun queryInitialHfpConnection(): Boolean {
    if (
        ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED
    ) {
      return false
    }
    return try {
      val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
      bluetoothManager?.adapter?.getProfileConnectionState(BluetoothProfile.HEADSET) ==
          BluetoothAdapter.STATE_CONNECTED
    } catch (e: SecurityException) {
      Log.w(TAG, "Cannot query initial HFP state", e)
      false
    }
  }

  private fun registerHfpStateReceiver() {
    if (hfpReceiverRegistered) return

    val filter =
        IntentFilter().apply {
          addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
          addAction(BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED)
        }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      context.registerReceiver(hfpStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
    } else {
      @Suppress("UnspecifiedRegisterReceiverFlag")
      context.registerReceiver(hfpStateReceiver, filter)
    }
    hfpReceiverRegistered = true
  }

  private fun profileStateName(state: Int): String =
      when (state) {
        BluetoothProfile.STATE_CONNECTED -> "CONNECTED"
        BluetoothProfile.STATE_CONNECTING -> "CONNECTING"
        BluetoothProfile.STATE_DISCONNECTING -> "DISCONNECTING"
        BluetoothProfile.STATE_DISCONNECTED -> "DISCONNECTED"
        else -> "UNKNOWN($state)"
      }

  private fun audioStateName(state: Int): String =
      when (state) {
        BluetoothHeadset.STATE_AUDIO_CONNECTED -> "CONNECTED"
        BluetoothHeadset.STATE_AUDIO_CONNECTING -> "CONNECTING"
        BluetoothHeadset.STATE_AUDIO_DISCONNECTED -> "DISCONNECTED"
        else -> "UNKNOWN($state)"
      }

  private fun initializeAudioRecord(): Boolean {
    if (audioRecord != null) {
      return true
    }

    if (
        ActivityCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
    ) {
      Log.w(TAG, "Audio recording permission not granted")
      return false
    }

    val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
    if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) {
      Log.w(TAG, "Invalid buffer size for AudioRecord")
      return false
    }

    try {
      val record = AudioRecord(
          MediaRecorder.AudioSource.MIC,
          SAMPLE_RATE,
          CHANNEL_CONFIG,
          AUDIO_FORMAT,
          bufferSize * BUFFER_SIZE_FACTOR,
      )

      if (record.state != AudioRecord.STATE_INITIALIZED) {
        Log.w(TAG, "AudioRecord initialization failed")
        record.release()
        return false
      }

      audioRecord = record
      Log.d(TAG, "AudioRecord initialized successfully")
      return true
    } catch (e: Exception) {
      Log.w(TAG, "Exception initializing AudioRecord", e)
      audioRecord?.release()
      audioRecord = null
      return false
    }
  }

  /**
   * Starts phone-mic capture. Returns false — without throwing — when there is no usable microphone
   * (no RECORD_AUDIO permission, bad buffer size, or AudioRecord won't initialize) so the caller
   * can record video-only instead of crashing.
   */
  fun startRecording(): Boolean {
    if (_isRecording.value) {
      return true
    }

    if (!initializeAudioRecord()) {
      Log.w(TAG, "Microphone unavailable; not starting audio capture")
      return false
    }

    _isRecording.value = true
    _wasInterrupted.value = false
    recordingThreadActive = true

    synchronized(threadLock) {
      recordingThread =
          Thread {
            val localAudioRecord = audioRecord
            if (localAudioRecord == null) {
              Log.e(TAG, "AudioRecord is null, cannot start recording")
              handler.post {
                _isRecording.value = false
                recordingThreadActive = false
              }
              return@Thread
            }

            var wasInterruptedBySystem = false
            try {
              localAudioRecord.startRecording()
              val buffer =
                  ByteArray(AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT))

              while (recordingThreadActive && !Thread.currentThread().isInterrupted) {
                val bytesRead = localAudioRecord.read(buffer, 0, buffer.size)
                when {
                  bytesRead > 0 -> {
                    if (recordingThreadActive) {
                      pcmDataCallback?.invoke(buffer, 0, bytesRead)
                    }
                  }
                  bytesRead == AudioRecord.ERROR_DEAD_OBJECT ||
                      bytesRead == AudioRecord.ERROR_INVALID_OPERATION ||
                      bytesRead == AudioRecord.ERROR_BAD_VALUE ||
                      bytesRead == AudioRecord.ERROR -> {
                    Log.e(TAG, "Audio recording interrupted (code=$bytesRead)")
                    wasInterruptedBySystem = true
                    break
                  }
                  else -> {
                    // bytesRead == 0: no data this round (common on an emulator's silent mic).
                    // Yield instead of spinning; the recorder's audio watchdog falls back to
                    // video-only when no PCM ever arrives.
                    Thread.sleep(10)
                  }
                }
              }
            } catch (e: InterruptedException) {
              Log.d(TAG, "Recording thread interrupted")
            } catch (e: IllegalStateException) {
              Log.e(TAG, "AudioRecord illegal state - likely interrupted by system", e)
              wasInterruptedBySystem = true
            } catch (e: Exception) {
              Log.e(TAG, "Error during audio recording", e)
              wasInterruptedBySystem = true
            } finally {
              try {
                if (localAudioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                  localAudioRecord.stop()
                }
              } catch (e: Exception) {
                Log.e(TAG, "Error stopping audio record", e)
              }
              val interrupted = wasInterruptedBySystem
              handler.post {
                _isRecording.value = false
                if (interrupted) {
                  _wasInterrupted.value = true
                }
              }
            }
          }
              .also { it.start() }
    }

    Log.d(TAG, "Audio recording started")
    return true
  }

  fun stopRecording() {
    if (!_isRecording.value) {
      return
    }

    recordingThreadActive = false
    _isRecording.value = false

    val thread =
        synchronized(threadLock) {
          recordingThread?.interrupt()
          recordingThread
        }

    thread?.let { audioThread ->
      audioThread.join(1000)
      if (audioThread.isAlive) {
        Log.w(TAG, "Recording thread did not terminate within timeout")
      }
    }
  }

  fun cleanup() {
    pcmDataCallback = null
    stopRecording()
    _wasInterrupted.value = false

    audioRecord?.release()
    audioRecord = null

    if (hfpReceiverRegistered) {
      try {
        context.unregisterReceiver(hfpStateReceiver)
      } catch (e: IllegalArgumentException) {
        Log.w(TAG, "HFP receiver was already unregistered", e)
      }
      hfpReceiverRegistered = false
    }
  }
}
