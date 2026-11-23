package io.ratesalgo.journal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.nio.charset.StandardCharsets;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;

/**
 * Known answers computed independently in Python from the definition in API_SPEC.md Section 1
 * (data/golden/chain_hash_vectors.json holds the same vectors for the Python package).
 */
class ChainHashTest {

  private static UnsafeBuffer bytes(final String s) {
    return new UnsafeBuffer(s.getBytes(StandardCharsets.US_ASCII));
  }

  @Test
  void emptyPayloadFromGenesis() {
    assertEquals(0xa8c7f832281a39c5L, ChainHash.next(0L, bytes(""), 0, 0));
  }

  @Test
  void helloFromGenesis() {
    assertEquals(0x8fe9535b8a55c92bL, ChainHash.next(0L, bytes("hello"), 0, 5));
  }

  @Test
  void worldChainedAfterHello() {
    final long afterHello = ChainHash.next(0L, bytes("hello"), 0, 5);
    assertEquals(0xb71f85c4c4115848L, ChainHash.next(afterHello, bytes("world"), 0, 5));
  }

  @Test
  void offsetAndLengthSelectTheWindow() {
    final UnsafeBuffer b = bytes("xxhelloyy");
    assertEquals(0x8fe9535b8a55c92bL, ChainHash.next(0L, b, 2, 5));
  }

  // WORD64 known answers, computed independently in Python from the definition in ChainHash#word64

  @Test
  void word64EmptyPayloadStillMovesTheHash() {
    assertEquals(0x9ca066f1a4ab2eeaL, ChainHash.word64(0L, bytes(""), 0, 0));
    assertNotEquals(JournalFormat.GENESIS_HASH, ChainHash.word64(0L, bytes(""), 0, 0));
  }

  @Test
  void word64KnownAnswers() {
    assertEquals(0x87905808ce31d4bdL, ChainHash.word64(0L, bytes("hello"), 0, 5));
    final long afterHello = ChainHash.word64(0L, bytes("hello"), 0, 5);
    assertEquals(0x6eb5229b7e3c2440L, ChainHash.word64(afterHello, bytes("world"), 0, 5));
    assertEquals(0x12972066042933ceL, ChainHash.word64(0L, bytes("0123456789abcdef"), 0, 16));
    assertEquals(
        0xcc9160d180a1b3b4L, ChainHash.word64(0x0123456789abcdefL, bytes("0123456789abcdefg"), 0, 17));
    assertEquals(0x432cf86abc946611L, ChainHash.word64(0L, new UnsafeBuffer(new byte[] {'h', 'e', 'l', 'l', 'o', 0}), 0, 6));
  }

  @Test
  void word64ReadsOnlyTheWindow() {
    final UnsafeBuffer b = bytes("xx0123456789abcdefgyy");
    assertEquals(0xcc9160d180a1b3b4L, ChainHash.word64(0x0123456789abcdefL, b, 2, 17));
  }

  @Test
  void algorithmsAreDispatchedByIdAndNamed() {
    final UnsafeBuffer b = bytes("hello");
    assertEquals(ChainHash.next(7L, b, 0, 5), ChainHash.next(ChainHash.FNV1A, 7L, b, 0, 5));
    assertEquals(ChainHash.word64(7L, b, 0, 5), ChainHash.next(ChainHash.WORD64, 7L, b, 0, 5));
    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalArgumentException.class, () -> ChainHash.next(3, 7L, b, 0, 5));
    assertEquals(ChainHash.FNV1A, ChainHash.DEFAULT);
    assertEquals(ChainHash.WORD64, ChainHash.byName(ChainHash.name(ChainHash.WORD64)));
    assertEquals(ChainHash.FNV1A, ChainHash.byName(ChainHash.name(ChainHash.FNV1A)));
    org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> ChainHash.byName("crc32c"));
    org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> ChainHash.name(0));
    org.junit.jupiter.api.Assertions.assertTrue(ChainHash.isKnown(ChainHash.WORD64));
    org.junit.jupiter.api.Assertions.assertFalse(ChainHash.isKnown(0));
  }

  @Property(tries = 200)
  void word64PreviousHashChangesTheResult(
      @ForAll final long previousA, @ForAll final long previousB, @ForAll final byte[] payload) {
    final UnsafeBuffer b = new UnsafeBuffer(payload);
    final long ha = ChainHash.word64(previousA, b, 0, payload.length);
    final long hb = ChainHash.word64(previousB, b, 0, payload.length);
    if (previousA == previousB) {
      assertEquals(ha, hb);
    } else {
      assertNotEquals(ha, hb);
    }
  }

  @Property(tries = 200)
  void word64SeesEveryByte(@ForAll final byte[] payload, @ForAll final int where, @ForAll final byte flip) {
    if (payload.length == 0 || flip == 0) {
      return;
    }
    final byte[] other = payload.clone();
    final int i = Math.floorMod(where, payload.length);
    other[i] ^= flip;
    assertNotEquals(
        ChainHash.word64(5L, new UnsafeBuffer(payload), 0, payload.length),
        ChainHash.word64(5L, new UnsafeBuffer(other), 0, other.length));
  }

  @Property(tries = 200)
  void previousHashChangesTheResult(
      @ForAll final long previousA, @ForAll final long previousB, @ForAll final byte[] payload) {
    final UnsafeBuffer b = new UnsafeBuffer(payload);
    final long ha = ChainHash.next(previousA, b, 0, payload.length);
    final long hb = ChainHash.next(previousB, b, 0, payload.length);
    if (previousA == previousB) {
      assertEquals(ha, hb);
    } else {
      assertNotEquals(ha, hb);
    }
  }

  @Property(tries = 200)
  void deterministic(@ForAll final long previous, @ForAll final byte[] payload) {
    final UnsafeBuffer b = new UnsafeBuffer(payload);
    assertEquals(
        ChainHash.next(previous, b, 0, payload.length),
        ChainHash.next(previous, b, 0, payload.length));
  }
}
