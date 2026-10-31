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
