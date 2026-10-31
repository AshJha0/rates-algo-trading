package io.ratesalgo.fix.codec;

import java.nio.ByteBuffer;
import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;

/**
 * Encodes one FIX 4.4 message. The body (everything from MsgType to the last field before
 * CheckSum) is written into a pre-allocated scratch buffer; {@link #finish} then writes
 * BeginString and the exact BodyLength, copies the body and appends CheckSum. Nothing is
 * allocated after construction.
 *
 * <p>Fields are written in call order, so callers write header fields first (the session does
 * this) and group fields in the order the FIX 4.4 dictionary defines.
 */
public final class FixWriter {

  private static final byte[] BEGIN = "8=FIX.4.4\u00019=".getBytes(java.nio.charset.StandardCharsets.US_ASCII);

  private final UnsafeBuffer body;
  private int position;

  /** Creates a writer whose body may be up to {@code capacity} bytes. */
  public FixWriter(final int capacity) {
    this.body = new UnsafeBuffer(ByteBuffer.allocateDirect(capacity));
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

  /** UTCTimestamp field with millisecond precision. */
  public FixWriter putTimestamp(final int tag, final long epochNanos) {
    putTag(tag);
    position += FixTime.writeMillis(body, position, epochNanos);
    end();
    return this;
  }

  /** Bytes of body written so far. */
  public int bodyLength() {
    return position;
  }

  /**
   * Writes the complete message (BeginString, BodyLength, body, CheckSum) into {@code out}.
   *
   * @return total length written
   */
  public int finish(final MutableDirectBuffer out, final int offset) {
    int p = offset;
    out.putBytes(p, BEGIN);
    p += BEGIN.length;
    p += AsciiNumbers.writeNatural(out, p, position);
    out.putByte(p++, FixParser.SOH);
    out.putBytes(p, body, 0, position);
    p += position;
    int sum = 0;
    for (int i = offset; i < p; i++) {
      sum += out.getByte(i) & 0xFF;
    }
    out.putByte(p++, (byte) '1');
    out.putByte(p++, (byte) '0');
    out.putByte(p++, (byte) '=');
    p += AsciiNumbers.writePadded(out, p, sum & 0xFF, 3);
    out.putByte(p++, FixParser.SOH);
    return p - offset;
  }
}
