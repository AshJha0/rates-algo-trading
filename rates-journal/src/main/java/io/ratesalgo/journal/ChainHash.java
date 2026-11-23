package io.ratesalgo.journal;

import java.nio.ByteOrder;
import org.agrona.DirectBuffer;

/**
 * The journal's chained hash, in one of two algorithms. A journal says which in its segment
 * headers (API_SPEC.md Section 1, {@link JournalFormat}), and every frame of a journal uses the same
 * one.
 *
 * <ul>
 *   <li>{@link #FNV1A} (format version 1): FNV-1a 64 over the previous frame's hash (as eight
 *       little-endian bytes) followed by the payload bytes, one byte per step. Every journal written
 *       before round 6 uses it, and so does every journal created without naming an algorithm, so
 *       that recorded replay hashes and ledger runs reproduce bit for bit.
 *   <li>{@link #WORD64} (format version 2): eight payload bytes per step, for journals on the hot
 *       path (review finding L8). Defined in {@link #word64}.
 * </ul>
 *
 * <p>Neither is a cryptographic hash. Their job is to detect truncation, reordering and bit rot,
 * and to tie each frame to everything before it, so that two journals with equal final hashes
 * hold equal event streams. Both keep 64 bits of state, so two different streams collide with odds
 * of about one in 2^64 for accidental differences; neither resists a deliberate forgery. Tamper
 * evidence is out of scope (DECISIONS.md DR-R6-L03 says why CRC32C was not used for the chain).
 */
public final class ChainHash {

  /** Algorithm id of FNV-1a 64, one byte per step; the default. */
  public static final int FNV1A = 1;

  /** Algorithm id of the eight-byte-stride hash of {@link #word64}. */
  public static final int WORD64 = 2;

  /** The algorithm a journal gets when its creator names none. */
  public static final int DEFAULT = FNV1A;

  public static final long OFFSET_BASIS = 0xcbf29ce484222325L;
  public static final long PRIME = 0x100000001b3L;

  private static final long LENGTH_MIX = 0x9e3779b97f4a7c15L;
  private static final long C1 = 0x87c37b91114253d5L;
  private static final long C2 = 0x4cf5ad432745937fL;

  private ChainHash() {}

  /** FNV-1a hash of {@code length} bytes of {@code payload} at {@code offset}, chained to {@code previous}. */
  public static long next(
      final long previous, final DirectBuffer payload, final int offset, final int length) {
    long h = OFFSET_BASIS;
    for (int i = 0; i < 8; i++) {
      h ^= (previous >>> (8 * i)) & 0xFF;
      h *= PRIME;
    }
    for (int i = 0; i < length; i++) {
      h ^= payload.getByte(offset + i) & 0xFF;
      h *= PRIME;
    }
    return h;
  }

  /**
   * The hash of {@code algorithm} ({@link #FNV1A} or {@link #WORD64}).
   *
   * @throws IllegalArgumentException for an unknown algorithm id
   */
  public static long next(
      final int algorithm, final long previous, final DirectBuffer payload, final int offset, final int length) {
    if (algorithm == WORD64) {
      return word64(previous, payload, offset, length);
    }
    if (algorithm == FNV1A) {
      return next(previous, payload, offset, length);
    }
    throw unknown(algorithm);
  }

  /**
   * The eight-byte-stride chain hash. With {@code n} the payload length and all arithmetic on
   * unsigned 64-bit integers modulo 2^64:
   *
   * <pre>
   * h = previous XOR ((n + 1) * 0x9e3779b97f4a7c15)
   * for each whole 8-byte word k of the payload, read little-endian, then for the 1 to 7 bytes
   * left (if any) read little-endian and zero-extended to 8 bytes:
   *   k = rotl(k * 0x87c37b91114253d5, 31) * 0x4cf5ad432745937f
   *   h = rotl(h XOR k, 27) * 5 + 0x52dce729
   * h ^= h >> 33; h *= 0xff51afd7ed558ccd; h ^= h >> 33; h *= 0xc4ceb9fe1a85ec53; h ^= h >> 33
   * </pre>
   *
   * <p>The word mix and the finaliser are MurmurHash3's (x64). The length enters first, so a
   * payload and the same payload with trailing zero bytes differ; it enters as {@code n + 1} so
   * that an empty payload still moves the hash (from a zero previous hash it would otherwise stay
   * zero, and an empty first frame would vanish from the chain). Every step is a bijection of
   * {@code h}, so two different previous hashes never give the same result for one payload.
   */
  public static long word64(
      final long previous, final DirectBuffer payload, final int offset, final int length) {
    long h = previous ^ ((length + 1L) * LENGTH_MIX);
    int i = 0;
    final int whole = length & ~7;
    while (i < whole) {
      h = step(h, payload.getLong(offset + i, ByteOrder.LITTLE_ENDIAN));
      i += 8;
    }
    if (i < length) {
      long k = 0;
      for (int b = length - 1; b >= i; b--) {
        k = (k << 8) | (payload.getByte(offset + b) & 0xFFL);
      }
      h = step(h, k);
    }
    h ^= h >>> 33;
    h *= 0xff51afd7ed558ccdL;
    h ^= h >>> 33;
    h *= 0xc4ceb9fe1a85ec53L;
    h ^= h >>> 33;
    return h;
  }

  private static long step(final long h, final long word) {
    final long k = Long.rotateLeft(word * C1, 31) * C2;
    return Long.rotateLeft(h ^ k, 27) * 5 + 0x52dce729L;
  }

  /** True for an algorithm id this code can verify. */
  public static boolean isKnown(final int algorithm) {
    return algorithm == FNV1A || algorithm == WORD64;
  }

  /** The algorithm's name in configuration: {@code fnv1a} or {@code word64}. */
  public static String name(final int algorithm) {
    return switch (algorithm) {
      case FNV1A -> "fnv1a";
      case WORD64 -> "word64";
      default -> throw unknown(algorithm);
    };
  }

  /**
   * The algorithm id for a configuration name.
   *
   * @throws IllegalArgumentException for any name but {@code fnv1a} and {@code word64}
   */
  public static int byName(final String name) {
    return switch (name) {
      case "fnv1a" -> FNV1A;
      case "word64" -> WORD64;
      default -> throw new IllegalArgumentException("unknown chain hash '" + name + "'; use fnv1a or word64");
    };
  }

  private static IllegalArgumentException unknown(final int algorithm) {
    return new IllegalArgumentException("unknown chain hash algorithm " + algorithm);
  }
}
