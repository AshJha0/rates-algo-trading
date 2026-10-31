package io.ratesalgo.journal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.common.time.ManualClock;
import io.ratesalgo.messages.sbe.HeartbeatDecoder;
import io.ratesalgo.messages.sbe.HeartbeatEncoder;
import io.ratesalgo.messages.sbe.MessageHeaderDecoder;
import io.ratesalgo.messages.sbe.MessageHeaderEncoder;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JournalRoundTripTest {

  @TempDir Path dir;

  private static UnsafeBuffer payload(final int seed, final int length) {
    final byte[] b = new byte[length];
    for (int i = 0; i < length; i++) {
      b[i] = (byte) (seed * 31 + i);
    }
    return new UnsafeBuffer(b);
  }

  private record Frame(long seq, long ts, int template, int version, List<Byte> bytes) {}

  private static FrameHandler collector(final List<Frame> into) {
    return (seq, ts, template, version, buffer, offset, length) -> {
      final List<Byte> copy = new ArrayList<>(length);
      for (int i = 0; i < length; i++) {
        copy.add(buffer.getByte(offset + i));
      }
      into.add(new Frame(seq, ts, template, version, copy));
    };
  }

  @Test
  void writesThenReadsBackEveryFrameWithStampsAndChain() {
    final Path path = dir.resolve("a.raj");
    final ManualClock clock = new ManualClock(1_000L);
    final long finalHash;
    try (JournalWriter w = JournalWriter.create(path, 64 * 1024, clock)) {
      assertEquals(1L, w.nextSequence());
      assertEquals(JournalFormat.GENESIS_HASH, w.chainHash());
      for (int i = 0; i < 100; i++) {
        clock.advance(7L);
        final UnsafeBuffer p = payload(i, i % 37); // lengths 0..36, exercising padding
        assertEquals(i + 1L, w.append(10 + (i % 3), 0, p, 0, p.capacity()));
      }
      assertEquals(101L, w.nextSequence());
      finalHash = w.chainHash();
      w.sync();
    }

    final List<Frame> frames = new ArrayList<>();
    try (JournalReader r = JournalReader.open(path)) {
      while (r.next(collector(frames))) {
        // collecting
      }
      assertEquals(finalHash, r.chainHash());
      assertEquals(101L, r.expectedSequence());
    }
    assertEquals(100, frames.size());
    for (int i = 0; i < 100; i++) {
      final Frame f = frames.get(i);
      assertEquals(i + 1L, f.seq());
      assertEquals(1_000L + 7L * (i + 1), f.ts());
      assertEquals(10 + (i % 3), f.template());
      assertEquals(0, f.version());
      final UnsafeBuffer expected = payload(i, i % 37);
      assertEquals(expected.capacity(), f.bytes().size());
      for (int k = 0; k < f.bytes().size(); k++) {
        assertEquals(expected.getByte(k), f.bytes().get(k));
      }
    }
    assertEquals(finalHash, Replayer.finalHash(path));
  }

  @Test
  void emptyJournalReadsAsEmpty() {
    final Path path = dir.resolve("empty.raj");
    try (JournalWriter w = JournalWriter.create(path, 4096, new ManualClock())) {
      assertEquals(4096 - JournalFormat.FILE_HEADER_LENGTH - 4, w.remaining());
    }
    try (JournalReader r = JournalReader.open(path)) {
      assertFalse(r.next((s, t, id, v, b, o, l) -> {}));
      assertEquals(JournalFormat.GENESIS_HASH, r.chainHash());
      assertEquals(JournalFormat.FILE_HEADER_LENGTH, r.position());
    }
  }

  @Test
  void reopenContinuesSequenceAndChain() {
    final Path path = dir.resolve("b.raj");
    final ManualClock clock = new ManualClock();
    final long hashAfterFirst;
    try (JournalWriter w = JournalWriter.create(path, 8192, clock)) {
      w.append(1, 0, payload(1, 8), 0, 8);
      w.append(1, 0, payload(2, 9), 0, 9);
      hashAfterFirst = w.chainHash();
    }
    try (JournalWriter w = JournalWriter.open(path, clock)) {
      assertEquals(3L, w.nextSequence());
      assertEquals(hashAfterFirst, w.chainHash());
      assertEquals(3L, w.append(1, 0, payload(3, 10), 0, 10));
    }
    final List<Frame> frames = new ArrayList<>();
    try (JournalReader r = JournalReader.open(path)) {
      while (r.next(collector(frames))) {
        // collecting
      }
    }
    assertEquals(3, frames.size());
    assertEquals(3L, frames.get(2).seq());
    assertEquals(10, frames.get(2).bytes().size());
  }

  @Test
  void fullJournalRefusesTheFrameAndLeavesTheFileReadable() {
    final Path path = dir.resolve("full.raj");
    try (JournalWriter w = JournalWriter.create(path, 256, new ManualClock())) {
      // header 32 + 3 frames of 64 (32 header + 32 payload) = 224, leaving 32: a 4th frame of
      // 64 does not fit once the 4-byte terminator is reserved
      w.append(1, 0, payload(1, 32), 0, 32);
      w.append(1, 0, payload(2, 32), 0, 32);
      w.append(1, 0, payload(3, 32), 0, 32);
      assertEquals(28, w.remaining());
      assertThrows(JournalFullException.class, () -> w.append(1, 0, payload(4, 32), 0, 32));
      assertEquals(4L, w.nextSequence());
    }
    final List<Frame> frames = new ArrayList<>();
    try (JournalReader r = JournalReader.open(path)) {
      while (r.next(collector(frames))) {
        // collecting
      }
    }
    assertEquals(3, frames.size());
  }

  @Test
  void writerRejectsBadUse() {
    final Path path = dir.resolve("c.raj");
    assertThrows(
        IllegalArgumentException.class, () -> JournalWriter.create(path, 10, new ManualClock()));
    final JournalWriter w = JournalWriter.create(path, 4096, new ManualClock());
    assertThrows(
        IllegalArgumentException.class, () -> JournalWriter.create(path, 4096, new ManualClock()));
    assertThrows(IllegalArgumentException.class, () -> w.append(1, 0, payload(1, 4), 0, -1));
    w.close();
    w.close(); // idempotent
    assertThrows(IllegalStateException.class, () -> w.append(1, 0, payload(1, 4), 0, 4));
    assertThrows(IllegalStateException.class, w::sync);
  }

  @Test
  void replayerDrivesTheClockToEachFrameStamp() throws IOException {
    final Path path = dir.resolve("replay.raj");
    final ManualClock live = new ManualClock(5_000L);
    final UnsafeBuffer buf = new UnsafeBuffer(ByteBuffer.allocateDirect(64));
    final MessageHeaderEncoder header = new MessageHeaderEncoder();
    final HeartbeatEncoder hb = new HeartbeatEncoder();
    try (JournalWriter w = JournalWriter.create(path, 4096, live)) {
      for (int i = 1; i <= 5; i++) {
        live.advance(1_000L);
        hb.wrapAndApplyHeader(buf, 0, header).sourceId(i).timestampNanos(live.epochNanos());
        final int length = MessageHeaderEncoder.ENCODED_LENGTH + HeartbeatEncoder.BLOCK_LENGTH;
        w.append(HeartbeatEncoder.TEMPLATE_ID, HeartbeatEncoder.SCHEMA_VERSION, buf, 0, length);
      }
    }
    assertTrue(Files.size(path) == 4096);

    final ManualClock replayClock = new ManualClock(0L);
    final List<Long> seenClock = new ArrayList<>();
    final List<Integer> sources = new ArrayList<>();
    final MessageHeaderDecoder hd = new MessageHeaderDecoder();
    final HeartbeatDecoder dec = new HeartbeatDecoder();
    final long count =
        new Replayer(replayClock)
            .replay(
                path,
                (seq, ts, template, version, buffer, offset, length) -> {
                  assertEquals(HeartbeatDecoder.TEMPLATE_ID, template);
                  seenClock.add(replayClock.epochNanos());
                  dec.wrapAndApplyHeader(buffer, offset, hd.wrap(buffer, offset));
                  sources.add(dec.sourceId());
                  assertEquals(ts, dec.timestampNanos());
                });
    assertEquals(5L, count);
    assertEquals(List.of(6_000L, 7_000L, 8_000L, 9_000L, 10_000L), seenClock);
    assertEquals(List.of(1, 2, 3, 4, 5), sources);
    assertEquals(10_000L, replayClock.epochNanos());
  }

  @Test
  void replayerNeverMovesTheClockBackwards() {
    final Path path = dir.resolve("mono.raj");
    final ManualClock live = new ManualClock(9_000L);
    try (JournalWriter w = JournalWriter.create(path, 4096, live)) {
      w.append(1, 0, payload(1, 1), 0, 1);
    }
    final ManualClock ahead = new ManualClock(50_000L);
    new Replayer(ahead).replay(path, (s, t, id, v, b, o, l) -> {});
    assertEquals(50_000L, ahead.epochNanos());
  }
}
