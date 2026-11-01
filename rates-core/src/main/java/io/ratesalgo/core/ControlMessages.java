package io.ratesalgo.core;

import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;

/**
 * The core control schema (schema id 100, version 0): three fixed-length messages that the loop
 * and the gateways exchange about the loop itself, framed with the standard 8-byte SBE message
 * header like every schema-1 message. Template ids start at 65001 so that a frame's template id
 * alone never collides with schema 1 (API_SPEC.md Section 9.6).
 *
 * <pre>
 * LoopError       65001  block 256  0 timestampNanos int64, 8 inboundCount int64,
 *                                   16 textLength uint16, 18 text char[238] ASCII
 * Backpressure    65002  block 24   0 timestampNanos int64, 8 droppedCount int64,
 *                                   16 gatewayId int32, 20 firstTemplateId uint16, 22 pad
 * GatewayStatus   65003  block 16   0 timestampNanos int64, 8 gatewayId int32,
 *                                   12 status uint8 (1 connected, 2 disconnected, 3 reconnecting),
 *                                   13 attempt uint8 (reconnecting only, else 0), 14 pad
 * </pre>
 *
 * <p>LoopError and Backpressure are written by the loop into its inbound journal and are never
 * accepted from a ring. GatewayStatus is published by a gateway on its inbound ring.
 */
public final class ControlMessages {

  public static final int SCHEMA_ID = 100;
  public static final int SCHEMA_VERSION = 0;
  public static final int HEADER_LENGTH = 8;

  public static final int LOOP_ERROR_TEMPLATE_ID = 65001;
  public static final int LOOP_ERROR_BLOCK_LENGTH = 256;
  public static final int LOOP_ERROR_TEXT_CAPACITY = 238;

  public static final int BACKPRESSURE_TEMPLATE_ID = 65002;
  public static final int BACKPRESSURE_BLOCK_LENGTH = 24;

  public static final int GATEWAY_STATUS_TEMPLATE_ID = 65003;
  public static final int GATEWAY_STATUS_BLOCK_LENGTH = 16;

  public static final byte STATUS_CONNECTED = 1;
  public static final byte STATUS_DISCONNECTED = 2;

  /**
   * A gateway is retrying a connection whose Logon was not accepted (DR-INT-09); the attempt
   * number is in byte 13. The session is down: a consumer treats it as disconnected.
   */
  public static final byte STATUS_RECONNECTING = 3;

  private static final ByteOrder LE = ByteOrder.LITTLE_ENDIAN;

  private ControlMessages() {}

  private static int header(
      final MutableDirectBuffer b, final int offset, final int blockLength, final int templateId) {
    b.putShort(offset, (short) blockLength, LE);
    b.putShort(offset + 2, (short) templateId, LE);
    b.putShort(offset + 4, (short) SCHEMA_ID, LE);
    b.putShort(offset + 6, (short) SCHEMA_VERSION, LE);
    return offset + HEADER_LENGTH;
  }

  /** Encodes a Backpressure message; returns its length including the header. */
  public static int encodeBackpressure(
      final MutableDirectBuffer b,
      final int offset,
      final long timestampNanos,
      final long droppedCount,
      final int gatewayId,
      final int firstTemplateId) {
    final int body = header(b, offset, BACKPRESSURE_BLOCK_LENGTH, BACKPRESSURE_TEMPLATE_ID);
    b.putLong(body, timestampNanos, LE);
    b.putLong(body + 8, droppedCount, LE);
    b.putInt(body + 16, gatewayId, LE);
    b.putShort(body + 20, (short) firstTemplateId, LE);
    b.putShort(body + 22, (short) 0, LE);
    return HEADER_LENGTH + BACKPRESSURE_BLOCK_LENGTH;
  }

  /** Encodes a GatewayStatus message; returns its length including the header. */
  public static int encodeGatewayStatus(
      final MutableDirectBuffer b,
      final int offset,
      final long timestampNanos,
      final int gatewayId,
      final byte status) {
    final int body = header(b, offset, GATEWAY_STATUS_BLOCK_LENGTH, GATEWAY_STATUS_TEMPLATE_ID);
    b.putLong(body, timestampNanos, LE);
    b.putInt(body + 8, gatewayId, LE);
    b.putByte(body + 12, status);
    b.setMemory(body + 13, 3, (byte) 0);
    return HEADER_LENGTH + GATEWAY_STATUS_BLOCK_LENGTH;
  }

  /** Encodes a GatewayStatus RECONNECTING with its attempt number (1 to 255, saturating). */
  public static int encodeGatewayReconnecting(
      final MutableDirectBuffer b,
      final int offset,
      final long timestampNanos,
      final int gatewayId,
      final int attempt) {
    final int length = encodeGatewayStatus(b, offset, timestampNanos, gatewayId, STATUS_RECONNECTING);
    b.putByte(offset + HEADER_LENGTH + 13, (byte) Math.min(255, Math.max(1, attempt)));
    return length;
  }

  /**
   * Encodes a LoopError message with {@code text} cut to {@link #LOOP_ERROR_TEXT_CAPACITY} ASCII
   * bytes (non-ASCII characters become '?'); returns its length including the header.
   */
  public static int encodeLoopError(
      final MutableDirectBuffer b,
      final int offset,
      final long timestampNanos,
      final long inboundCount,
      final String text) {
    final int body = header(b, offset, LOOP_ERROR_BLOCK_LENGTH, LOOP_ERROR_TEMPLATE_ID);
    b.putLong(body, timestampNanos, LE);
    b.putLong(body + 8, inboundCount, LE);
    final byte[] ascii = text.getBytes(StandardCharsets.US_ASCII);
    final int n = Math.min(ascii.length, LOOP_ERROR_TEXT_CAPACITY);
    b.putShort(body + 16, (short) n, LE);
    b.putBytes(body + 18, ascii, 0, n);
    b.setMemory(body + 18 + n, LOOP_ERROR_TEXT_CAPACITY - n, (byte) 0);
    return HEADER_LENGTH + LOOP_ERROR_BLOCK_LENGTH;
  }

  /** Timestamp of any control message at {@code offset} (its first body field). */
  public static long timestampNanos(final DirectBuffer b, final int offset) {
    return b.getLong(offset + HEADER_LENGTH, LE);
  }

  /** Backpressure: dropped count. */
  public static long backpressureDropped(final DirectBuffer b, final int offset) {
    return b.getLong(offset + HEADER_LENGTH + 8, LE);
  }

  /** Backpressure: gateway id. */
  public static int backpressureGateway(final DirectBuffer b, final int offset) {
    return b.getInt(offset + HEADER_LENGTH + 16, LE);
  }

  /** Backpressure: template id of the first dropped message. */
  public static int backpressureFirstTemplate(final DirectBuffer b, final int offset) {
    return b.getShort(offset + HEADER_LENGTH + 20, LE) & 0xFFFF;
  }

  /** GatewayStatus: gateway id. */
  public static int gatewayStatusGateway(final DirectBuffer b, final int offset) {
    return b.getInt(offset + HEADER_LENGTH + 8, LE);
  }

  /** GatewayStatus: status byte. */
  public static byte gatewayStatus(final DirectBuffer b, final int offset) {
    return b.getByte(offset + HEADER_LENGTH + 12);
  }

  /** GatewayStatus: reconnect attempt number, 0 unless the status is RECONNECTING. */
  public static int gatewayStatusAttempt(final DirectBuffer b, final int offset) {
    return b.getByte(offset + HEADER_LENGTH + 13) & 0xFF;
  }

  /** LoopError: inbound messages processed before the error. */
  public static long loopErrorInboundCount(final DirectBuffer b, final int offset) {
    return b.getLong(offset + HEADER_LENGTH + 8, LE);
  }

  /** LoopError: the recorded text. Allocates; for tools and tests. */
  public static String loopErrorText(final DirectBuffer b, final int offset) {
    final int n = b.getShort(offset + HEADER_LENGTH + 16, LE) & 0xFFFF;
    final byte[] ascii = new byte[n];
    b.getBytes(offset + HEADER_LENGTH + 18, ascii);
    return new String(ascii, StandardCharsets.US_ASCII);
  }
}
