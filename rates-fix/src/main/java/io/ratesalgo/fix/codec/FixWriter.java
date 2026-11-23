package io.ratesalgo.fix.codec;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;

/**
 * Encodes one FIX 4.4 message into a pre-allocated buffer. The body (everything from MsgType to
 * the last field before CheckSum) is written behind room reserved for BeginString and the widest
 * BodyLength the buffer can need; {@link #finish()} then writes BeginString and the exact
 * BodyLength right-aligned against the body, so the message starts a few bytes into the buffer
 * ({@link #messageOffset()}) and the body is never copied (review finding L11). CheckSum is summed
 * eight bytes at a time. Nothing is allocated after construction.
 *
 * <p>Fields are written in call order, so callers write header fields first (the session does
 * this) and group fields in the order the FIX 4.4 dictionary defines.
 */
public final class FixWriter {

  private static final byte[] BEGIN = "8=FIX.4.4\u00019=".getBytes(java.nio.charset.StandardCharsets.US_ASCII);

  /** {@code 10=nnn<SOH>}. */
  private static final int TRAILER = 7;

  private static final long LOW_BYTES = 0x00FF00FF00FF00FFL;

  private final UnsafeBuffer buffer;
  /** The body's view of {@link #buffer}, from {@link #bodyStart}. */
  private final UnsafeBuffer body;
  private final int bodyStart;
  private final FixTime.DayCache day = new FixTime.DayCache();
  private int position;
  private int messageOffset;
  private int messageLength;

  /** Creates a writer whose body may be up to {@code capacity} bytes. */
  public FixWriter(final int capacity) {
    this.bodyStart = BEGIN.length + digits(capacity) + 1;
    this.buffer = new UnsafeBuffer(ByteBuffer.allocateDirect(bodyStart + capacity + TRAILER));
    this.body = new UnsafeBuffer(buffer, bodyStart, capacity + TRAILER);
  }

  /** Starts a new message with the given MsgType. */
  public FixWriter begin(final int msgType) {
    position = 0;
    putTag(FixTags.MSG_TYPE);
    if (msgType > 0xFF) {
      body.putByte(position++, (byte) (msgType >> 8));
    }
    body.putByte(position++, (byte) (msgType & 0xFF));
    body.putByte(position++, FixParser.SOH);
    return this;
  }

  private void putTag(final int tag) {
    position += AsciiNumbers.writeNatural(body, position, tag);
    body.putByte(position++, (byte) '=');
  }

  private void end() {
    body.putByte(position++, FixParser.SOH);
  }

  /** Integer field. */
  public FixWriter put(final int tag, final long value) {
    putTag(tag);
    position += AsciiNumbers.writeDecimal(body, position, value, 0);
    end();
    return this;
  }

  /** Decimal field from a value scaled by {@code 10^scale}; trailing zeros are dropped. */
  public FixWriter putDecimal(final int tag, final long scaled, final int scale) {
    putTag(tag);
    position += AsciiNumbers.writeDecimal(body, position, scaled, scale);
    end();
    return this;
  }

  /** Single-character field. */
  public FixWriter putChar(final int tag, final byte value) {
    putTag(tag);
    body.putByte(position++, value);
    end();
    return this;
  }

  /** Two-character field such as {@code TA}. */
  public FixWriter putChars(final int tag, final byte first, final byte second) {
    putTag(tag);
    body.putByte(position++, first);
    body.putByte(position++, second);
    end();
    return this;
  }

  /** Boolean field ({@code Y} or {@code N}). */
  public FixWriter putBool(final int tag, final boolean value) {
    return putChar(tag, value ? (byte) 'Y' : (byte) 'N');
  }

  /** ASCII field from a byte array. */
  public FixWriter putAscii(final int tag, final byte[] value) {
    putTag(tag);
    body.putBytes(position, value);
    position += value.length;
    end();
    return this;
  }

  /** ASCII field copied from a buffer region. */
  public FixWriter putAscii(final int tag, final DirectBuffer src, final int offset, final int length) {
    putTag(tag);
    body.putBytes(position, src, offset, length);
    position += length;
    end();
    return this;
  }

  /** ASCII field from a CharSequence (no allocation; characters must be ASCII). */
  public FixWriter putText(final int tag, final CharSequence value) {
    putTag(tag);
    for (int i = 0; i < value.length(); i++) {
      body.putByte(position++, (byte) value.charAt(i));
    }
    end();
    return this;
  }

  /**
   * UTCTimestamp field with millisecond precision. The date part is kept from the last stamp of
   * the same day, so most stamps skip the civil-date arithmetic.
   */
  public FixWriter putTimestamp(final int tag, final long epochNanos) {
    putTag(tag);
    position += FixTime.writeMillis(body, position, epochNanos, day);
    end();
    return this;
  }

  /**
   * Overwrites everything written so far, header, body and trailer, with zeros and empties the
   * writer. The session calls it after a Logon that carried a password, so the password does not
   * stay in the buffer.
   */
  public void wipe() {
    buffer.setMemory(0, bodyStart + position + TRAILER, (byte) 0);
    position = 0;
    messageOffset = 0;
    messageLength = 0;
  }

  /** Bytes of body written so far. */
  public int bodyLength() {
    return position;
  }

  /**
   * Completes the message in place: BeginString and BodyLength in front of the body, CheckSum
   * behind it. The message is then {@link #messageLength()} bytes of {@link #buffer()} from {@link
   * #messageOffset()}, until the next {@link #begin}.
   *
   * @return the message's total length
   */
  public int finish() {
    final int start = bodyStart - 1 - digits(position) - BEGIN.length;
    buffer.putBytes(start, BEGIN);
    AsciiNumbers.writeNatural(buffer, start + BEGIN.length, position);
    buffer.putByte(bodyStart - 1, FixParser.SOH);
    final int end = bodyStart + position;
    final int sum = checksum(buffer, start, end);
    buffer.putByte(end, (byte) '1');
    buffer.putByte(end + 1, (byte) '0');
    buffer.putByte(end + 2, (byte) '=');
    AsciiNumbers.writePadded(buffer, end + 3, sum, 3);
    buffer.putByte(end + 6, FixParser.SOH);
    messageOffset = start;
    messageLength = end + TRAILER - start;
    return messageLength;
  }

  /**
   * Writes the complete message (BeginString, BodyLength, body, CheckSum) into {@code out}: {@link
   * #finish()} and one copy, for callers that need the bytes in their own buffer.
   *
   * @return total length written
   */
  public int finish(final MutableDirectBuffer out, final int offset) {
    final int length = finish();
    out.putBytes(offset, buffer, messageOffset, length);
    return length;
  }

  /** The buffer the finished message is in. */
  public DirectBuffer buffer() {
    return buffer;
  }

  /** Where the finished message starts in {@link #buffer()}. */
  public int messageOffset() {
    return messageOffset;
  }

  /** The finished message's length; 0 before the first {@link #finish()}. */
  public int messageLength() {
    return messageLength;
  }

  /**
   * The FIX CheckSum of {@code [from, to)}: the byte sum modulo 256. Eight bytes are added per
   * step as four 16-bit lanes (each step adds at most 510 to a lane), folded every 128 steps
   * before a lane can overflow.
   */
  static int checksum(final DirectBuffer b, final int from, final int to) {
    long sum = 0;
    long lanes = 0;
    int steps = 0;
    int i = from;
    while (i + Long.BYTES <= to) {
      final long w = b.getLong(i, ByteOrder.LITTLE_ENDIAN);
      lanes += (w & LOW_BYTES) + ((w >>> 8) & LOW_BYTES);
      i += Long.BYTES;
      if (++steps == 128) {
        sum += fold(lanes);
        lanes = 0;
        steps = 0;
      }
    }
    sum += fold(lanes);
    while (i < to) {
      sum += b.getByte(i++) & 0xFF;
    }
    return (int) (sum & 0xFF);
  }

  private static long fold(final long lanes) {
    return (lanes & 0xFFFF) + ((lanes >>> 16) & 0xFFFF) + ((lanes >>> 32) & 0xFFFF) + (lanes >>> 48);
  }

  /** Decimal digits of a non-negative int. */
  static int digits(final int v) {
    int n = 1;
    for (long limit = 10; limit <= v; limit *= 10) {
      n++;
    }
    return n;
  }
}
