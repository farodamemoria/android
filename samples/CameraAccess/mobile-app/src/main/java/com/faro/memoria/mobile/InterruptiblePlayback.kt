package com.faro.memoria.mobile

/** All sink access is serialized; writes MUST be nonblocking, including when the device is full. */
internal class InterruptiblePlayback(private val sink: Sink, private val maxBytes: Int = 480_000, private val onError: (Throwable) -> Unit = {}) {
  interface Sink {
    fun write(data: ByteArray, offset: Int, count: Int): Int
    fun playedFrames(): Long
    fun flush()
    fun release()
  }
  data class Position(val itemId: String, val contentIndex: Int, val audioEndMs: Long)
  private val lock = Object()
  private val queue = ArrayDeque<ByteArray>()
  private var offset = 0
  private var queuedBytes = 0
  private var closed = false
  private data class Marker(val id: String, val index: Int, val start: Long)
  private val markers = ArrayDeque<Marker>()
  private var itemId = ""
  private var contentIndex = 0
  private var submittedFrames = 0L
  private var itemStartFrame = 0L
  private val worker = Thread({
    runCatching { drain() }.onFailure { error ->
      runCatching { close() }
      onError(error)
    }
  }, "Faro-Playback").apply { start() }

  fun begin(id: String, index: Int) = synchronized(lock) {
    if (closed) return@synchronized
    itemId = id
    contentIndex = index
    // Include pending software audio in the next item's position on the track timeline.
    itemStartFrame = submittedFrames + queuedBytes / 2
    if (markers.size == 128) markers.removeFirst()
    markers.addLast(Marker(id, index, itemStartFrame))
  }

  fun enqueue(data: ByteArray): Boolean = synchronized(lock) {
    if (closed || data.size % 2 != 0 || queuedBytes + data.size > maxBytes) return false
    if (data.isNotEmpty()) {
      queue.addLast(data)
      queuedBytes += data.size
      lock.notifyAll()
    }
    true
  }

  fun clear(): Position = clearAll().lastOrNull() ?: Position("", 0, 0)

  fun clearAll(): List<Position> = synchronized(lock) {
    val played = if (closed) 0 else sink.playedFrames()
    val timeline = markers.toList()
    val positions = timeline.mapIndexed { index, marker ->
      val end = timeline.getOrNull(index + 1)?.start ?: (submittedFrames + queuedBytes / 2)
      val heard = (minOf(played, end) - marker.start).coerceAtLeast(0)
      Position(marker.id, marker.index, heard * 1000 / 24_000)
    }
    markers.clear()
    queue.clear()
    queuedBytes = 0
    offset = 0
    submittedFrames = 0
    itemStartFrame = 0
    itemId = ""
    if (!closed) sink.flush()
    positions
  }

  fun close() = synchronized(lock) {
    if (!closed) {
      closed = true
      queue.clear()
      queuedBytes = 0
      try { sink.release() } finally { lock.notifyAll() }
    }
  }

  private fun drain() {
    synchronized(lock) {
      while (!closed) {
        if (queue.isEmpty()) {
          lock.wait()
          continue
        }
        val data = queue.first()
        val count = sink.write(data, offset, minOf(data.size - offset, 2400))
        if (count < 0) {
          throw IllegalStateException("Audio output write failed: $count")
        } else if (count > 0) {
          offset += count
          queuedBytes -= count
          submittedFrames += count / 2
          if (offset == data.size) {
            queue.removeFirst()
            offset = 0
          }
        }
        // Releases the lock even when writes succeed: clear/close always get a turn.
        lock.wait(2)
      }
    }
  }
}
