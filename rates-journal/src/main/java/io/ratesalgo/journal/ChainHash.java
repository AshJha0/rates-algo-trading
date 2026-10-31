package io.ratesalgo.journal;

import org.agrona.DirectBuffer;

/**
 * The journal's chained hash: FNV-1a 64 over the previous frame's hash (as eight little-endian
 * bytes) followed by the payload bytes. Pinned in API_SPEC.md Section 1 so that other languages
 * (the Python research package) can verify a journal byte for byte.
 *
 * <p>FNV-1a is not a cryptographic hash. Its job is to detect truncation, reordering and bit rot,
 * and to tie each frame to everything before it, so that two journals with equal final hashes
 * hold equal event streams. Tamper evidence is out of scope.
 */
public final class ChainHash {

  public static final long OFFSET_BASIS = 0xcbf29ce484222325L;
  public static final long PRIME = 0x100000001b3L;

  private ChainHash() {}

  /** Hash of {@code length} bytes of {@code payload} at {@code offset}, chained to {@code previous}. */
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
}
