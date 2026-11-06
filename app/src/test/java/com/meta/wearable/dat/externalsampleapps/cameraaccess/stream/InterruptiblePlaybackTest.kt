package com.meta.wearable.dat.externalsampleapps.cameraaccess.stream

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class InterruptiblePlaybackTest {
  private class FakeSink : InterruptiblePlayback.Sink {
    @Volatile var capacity = 0
    @Volatile var frames = 0L
    @Volatile var released = false
    val written = CopyOnWriteArrayList<Byte>()
    override fun write(data: ByteArray, offset: Int, count: Int): Int {
      check(!released)
      val accepted = minOf(capacity, count)
      written.addAll(data.slice(offset until offset + accepted))
      return accepted
    }
    override fun playedFrames() = frames
    override fun flush() { check(!released); frames = 0 }
    override fun release() { released = true }
  }
  private fun await(check: () -> Boolean) {
    val end = System.nanoTime() + 2_000_000_000
    while (!check() && System.nanoTime() < end) Thread.sleep(2)
    assertTrue(check())
  }

  @Test fun clearWhileDeviceFullDropsOldAudioAndReportsActuallyPlayed() {
    val sink = FakeSink()
    val playback = InterruptiblePlayback(sink, 100_000)
    try {
      playback.begin("item", 0)
      assertTrue(playback.enqueue(ByteArray(48_000) { 1 }))
      sink.frames = 2_400
      val start = System.nanoTime()
      val position = playback.clear()
      assertTrue("clear must not wait for output capacity", System.nanoTime() - start < 500_000_000)
      assertEquals(100L, position.audioEndMs)
      assertEquals("item", position.itemId)
      sink.capacity = 2
      playback.begin("next", 0)
      assertTrue(playback.enqueue(byteArrayOf(2, 2, 3, 3)))
      await { sink.written.size == 4 }
      assertEquals(listOf<Byte>(2, 2, 3, 3), sink.written.toList())
    } finally { playback.close() }
  }

  @Test fun boundAndCloseRejectAudioWithoutAccessingReleasedTrack() {
    val sink = FakeSink()
    val playback = InterruptiblePlayback(sink, 4)
    assertTrue(playback.enqueue(byteArrayOf(1, 1, 1, 1)))
    assertFalse(playback.enqueue(byteArrayOf(2, 2)))
    playback.close()
    playback.close()
    assertFalse(playback.enqueue(byteArrayOf(3, 3)))
    assertTrue(sink.released)
    assertEquals(0, sink.written.size)
  }

  @Test fun nextItemPositionExcludesPreviouslyQueuedFrames() {
    val sink = FakeSink()
    val playback = InterruptiblePlayback(sink)
    try {
      playback.begin("first", 0)
      playback.enqueue(ByteArray(48_000))
      playback.begin("second", 1)
      sink.frames = 12_000
      val positions = playback.clearAll()
      assertEquals(2, positions.size)
      assertEquals(500L, positions[0].audioEndMs)
      val position = positions[1]
      assertEquals("second", position.itemId)
      assertEquals(1, position.contentIndex)
      assertEquals(0L, position.audioEndMs)
    } finally { playback.close() }
  }
  @Test fun outputFailureReleasesSinkAndNotifiesOwner() {
    val notified = java.util.concurrent.CountDownLatch(1)
    val released = java.util.concurrent.atomic.AtomicBoolean(false)
    val playback = InterruptiblePlayback(object : InterruptiblePlayback.Sink {
      override fun write(data: ByteArray, offset: Int, count: Int): Int = error("device lost")
      override fun playedFrames() = 0L
      override fun flush() {}
      override fun release() { released.set(true) }
    }, onError = { notified.countDown() })
    playback.enqueue(byteArrayOf(1, 1))
    assertTrue(notified.await(2, java.util.concurrent.TimeUnit.SECONDS))
    assertTrue(released.get())
    assertFalse(playback.enqueue(byteArrayOf(2, 2)))
  }

}
