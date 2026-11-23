package io.ratesalgo.fix.codec;

import org.agrona.DirectBuffer;

/**
 * Finds the end of one FIX message in a byte stream from its BodyLength. A message is
 * {@code 8=...<SOH>9=<n><SOH>} then {@code n} body bytes then {@code 10=ccc<SOH>}.
 */
public final class FixFramer {

  /** Returned when the bytes at the start are not the beginning of a FIX message. */
  public static final int GARBAGE = -1;

  /**
   * Largest BodyLength accepted by default. The engine's own messages are under 64 KiB; a larger
   * declared length is a corrupt or hostile stream, and waiting for that many bytes would stall the
   * session until the read buffer filled.
   */
  public static final int MAX_BODY_LENGTH = 256 * 1024;

  private FixFramer() {}

  /**
   * Length of the complete message starting at {@code offset}, 0 if more bytes are needed, or
   * {@link #GARBAGE}. A BodyLength above {@link #MAX_BODY_LENGTH} is garbage.
   */
  public static int frame(final DirectBuffer buffer, final int offset, final int available) {
    return frame(buffer, offset, available, MAX_BODY_LENGTH);
  }

  /**
   * As {@link #frame(DirectBuffer, int, int)} with the caller's limit on BodyLength: a declared
   * length above {@code maxBodyLength} is {@link #GARBAGE} as soon as its digits are read, before
   * any of the body arrives.
   */
  public static int frame(
      final DirectBuffer buffer, final int offset, final int available, final int maxBodyLength) {
    if (available < 2) {
      return 0;
    }
    if (buffer.getByte(offset) != '8' || buffer.getByte(offset + 1) != '=') {
      return GARBAGE;
    }
    int p = offset + 2;
    final int end = offset + available;
    while (p < end && buffer.getByte(p) != FixParser.SOH) {
      p++;
      if (p - offset > 32) {
        return GARBAGE;
      }
    }
    if (p + 3 > end) {
      return 0;
    }
    p++;
    if (buffer.getByte(p) != '9' || buffer.getByte(p + 1) != '=') {
      return GARBAGE;
    }
    p += 2;
    int bodyLength = 0;
    int digits = 0;
    while (p < end) {
      final byte c = buffer.getByte(p);
      if (c == FixParser.SOH) {
        break;
      }
      if (c < '0' || c > '9' || ++digits > 7) {
        return GARBAGE;
      }
      bodyLength = bodyLength * 10 + (c - '0');
      if (bodyLength > maxBodyLength) {
        return GARBAGE;
      }
      p++;
    }
    if (p >= end) {
      return 0;
    }
    if (digits == 0) {
      return GARBAGE;
    }
    final int total = p + 1 - offset + bodyLength + 7;
    return total <= available ? total : 0;
  }
}
