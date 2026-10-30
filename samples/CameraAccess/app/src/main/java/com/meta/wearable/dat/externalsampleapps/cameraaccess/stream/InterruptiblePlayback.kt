/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.stream

import java.util.ArrayDeque

/**
 * Bounded playback queue for realtime audio with item/content markers, so the backend can clear
 * what hasn't been heard yet and learn (via [Position.audioEndMs]) how much of each item actually
 * played. Ported from the original Faro APK's `InterruptiblePlayback`.
 */
class InterruptiblePlayback(
    private val sink: Sink,
    private val maxBytes: Int = DEFAULT_MAX_BYTES,
    private val onError: (Throwable) -> Unit = {},
) {

  /** Output sink: an [android.media.AudioTrack]-backed writer. */
  interface Sink {
    fun write(data: ByteArray, offset: Int, count: Int): Int

    fun playedFrames(): Long

    fun flush()

    fun release()
  }

  /** How much of an item was heard when playback was cleared. */
  data class Position(val itemId: String, val contentIndex: Int, val audioEndMs: Long)

  private data class Marker(val id: String, val index: Int, val start: Long)

  private val lock = java.lang.Object()
  private val queue = ArrayDeque<ByteArray>()
  private val markers = ArrayDeque<Marker>()
  private var itemId = ""
  private var contentIndex = 0
  private var itemStartFrame = 0L
  private var submittedFrames = 0L
  private var queuedBytes = 0
  private var offset = 0
  private var closed = false

  private val worker =
      Thread(
          {
            try {
              drain()
            } catch (error: Throwable) {
              runCatching { close() }
              onError(error)
            }
          },
          "Faro-Playback",
      )

  init {
    worker.start()
  }

  /** Marks the start of a new server item at [index]; audio enqueued after this belongs to it. */
  fun begin(id: String, index: Int) {
    synchronized(lock) {
      if (!closed) {
        itemId = id
        contentIndex = index
        itemStartFrame = submittedFrames + queuedBytes / 2L
        if (markers.size == MAX_MARKERS) {
          markers.removeFirst()
        }
        markers.addLast(Marker(id, index, itemStartFrame))
      }
    }
  }

  /** Enqueues raw PCM bytes. Returns false when closed, odd-sized, or over the buffer limit. */
  fun enqueue(data: ByteArray): Boolean {
    synchronized(lock) {
      if (closed || data.size % 2 != 0 || queuedBytes + data.size > maxBytes) {
        return false
      }
      if (data.isNotEmpty()) {
        queue.addLast(data)
        queuedBytes += data.size
        lock.notifyAll()
      }
      return true
    }
  }

  /** Clears the queue and returns how much of each marker had been heard. */
  fun clearAll(): List<Position> {
    synchronized(lock) {
      val playedFrames = if (closed) 0L else sink.playedFrames()
      val snapshot = markers.toList()
      val positions =
          snapshot.mapIndexed { index, marker ->
            val next = snapshot.getOrNull(index + 1)
            val endFrame = minOf(playedFrames, next?.start ?: (submittedFrames + queuedBytes / 2L))
            Position(
                marker.id,
                marker.index,
                1000L * maxOf(endFrame - marker.start, 0L) / OpenAiVoiceController.SAMPLE_RATE,
            )
          }
      markers.clear()
      queue.clear()
      queuedBytes = 0
      offset = 0
      submittedFrames = 0L
      itemStartFrame = 0L
      itemId = ""
      if (!closed) {
        sink.flush()
      }
      return positions
    }
  }

  fun clear(): Position = clearAll().lastOrNull() ?: Position("", 0, 0L)

  fun close() {
    synchronized(lock) {
      if (!closed) {
        closed = true
        queue.clear()
        queuedBytes = 0
        try {
          sink.release()
        } finally {
          lock.notifyAll()
        }
      }
    }
  }

  private fun drain() {
    synchronized(lock) {
      while (!closed) {
        if (queue.isEmpty()) {
          lock.wait()
        } else {
          val first = queue.first()
          val written = sink.write(first, offset, minOf(first.size - offset, WRITE_CHUNK_BYTES))
          if (written < 0) {
            throw IllegalStateException("Audio output write failed: $written")
          }
          if (written > 0) {
            offset += written
            queuedBytes -= written
            submittedFrames += written / 2
            if (offset == first.size) {
              queue.removeFirst()
              offset = 0
            }
          }
          lock.wait(POLL_MS)
        }
      }
    }
  }

  companion object {
    /** ~10 s of 24 kHz mono 16-bit PCM. */
    const val DEFAULT_MAX_BYTES = 480000

    private const val MAX_MARKERS = 128
    private const val WRITE_CHUNK_BYTES = 2400
    private const val POLL_MS = 2L
  }
}
