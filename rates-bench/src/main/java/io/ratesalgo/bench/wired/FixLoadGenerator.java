package io.ratesalgo.bench.wired;

import io.ratesalgo.common.security.TlsConfig;
import io.ratesalgo.fix.codec.FixFramer;
import io.ratesalgo.fix.codec.FixParser;
import io.ratesalgo.fix.codec.FixTags;
import io.ratesalgo.fix.codec.FixWriter;
import io.ratesalgo.fix.codec.MsgType;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.agrona.concurrent.ringbuffer.OneToOneRingBuffer;
import org.agrona.concurrent.ringbuffer.RingBufferDescriptor;

/**
 * The client side of the D2C venue for the wired-desk benchmark: a FIX acceptor on loopback
 * (plain, or TLS with the desk's own TLS code on the other end) that logs the desk's D2C session on
 * and sends it QuoteRequests on a fixed schedule.
 *
 * <p>Two threads. The generator thread (the caller of {@link #run}) owns the socket's write side:
 * it waits for each scheduled time T0 by spinning, writes the QuoteRequest and stamps when the
 * write returned; between requests it writes whatever the reader asked for. The reader thread
 * reads the desk's messages: it answers Logon and TestRequest, and for each Quote stamps when it
 * arrived (T5) and asks for a QuoteResponse "pass" (694=6) so the desk's quote ends at once and
 * live quotes do not pile up. One writer keeps the outbound sequence numbers in order; the two
 * threads talk through a single-producer ring, so neither allocates per message on the plain
 * path. Under TLS the JDK's socket allocates inside its engine, as the desk's does.
 *
 * <p>The schedule never waits for an answer: a desk that falls behind meets requests queued at its
 * socket, and each is still measured from its scheduled time (no coordinated omission). A
 * generator that falls behind its own schedule is not hidden either: its lateness (write return
 * minus T0) is recorded per request and reported beside the latencies.
 */
final class FixLoadGenerator implements AutoCloseable {

  /** QuoteRequest ids start here; the benchmark indexes its arrays by id minus this. */
  static final long FIRST_RFQ_ID = 1_000_001L;

  private static final byte[] SENDER = "D2C".getBytes(StandardCharsets.US_ASCII);
  private static final int REPLY_PASS = 1;
  private static final int REPLY_HEARTBEAT = 2;
  private static final int REPLY_LOGON = 3;

  private final ServerSocket server;
  private final byte[] target;
  private final String[] securityIds;
  private final LongSupplier epochNanos;
  private final FixWriter writer = new FixWriter(4096);
  private final byte[] outBytes = new byte[8192];
  private final OneToOneRingBuffer replies =
      new OneToOneRingBuffer(new UnsafeBuffer(ByteBuffer.allocateDirect((1 << 20) + RingBufferDescriptor.TRAILER_LENGTH)));
  private final UnsafeBuffer reply = new UnsafeBuffer(ByteBuffer.allocateDirect(256));
  private final org.agrona.concurrent.MessageHandler replyHandler = this::onReply;
  private final long[] sentNanos;
  private final long[] receivedNanos;
  private final int capacity;
  private volatile Socket socket;
  private volatile boolean loggedOn;
  private volatile boolean stopping;
  private volatile Throwable readerFailure;
  private OutputStream out;
  private long nextSeq = 1;
  private int heartbeatSeconds = 30;
  private long quotes;
  private long duplicateQuotes;
  private Thread reader;

  /**
   * An acceptor on an ephemeral loopback port.
   *
   * @param tls server-side TLS settings, or null for plain TCP
   * @param targetCompId the desk's CompID
   * @param securityIds the instruments requests are spread over, in turn
   * @param epochNanos the desk's simulated wall clock, for SendingTime and ExpireTime
   * @param capacity requests the run may send
   */
  FixLoadGenerator(
      final TlsConfig tls, final String targetCompId, final String[] securityIds, final LongSupplier epochNanos,
      final int capacity) throws IOException {
    this.server =
        tls == null
            ? new ServerSocket(0, 1, InetAddress.getLoopbackAddress())
            : tls.context().getServerSocketFactory().createServerSocket(0, 1, InetAddress.getLoopbackAddress());
    this.target = targetCompId.getBytes(StandardCharsets.US_ASCII);
    this.securityIds = securityIds.clone();
    this.epochNanos = epochNanos;
    this.capacity = capacity;
    this.sentNanos = new long[capacity];
    this.receivedNanos = new long[capacity];
  }

  /** The port the desk's D2C session must connect to. */
  int port() {
    return server.getLocalPort();
  }

  /** Accepts the desk's connection and starts the reader; call before the desk connects or while it does. */
  void acceptInBackground() {
    final Thread acceptor =
        new Thread(
            () -> {
              try {
                final Socket s = server.accept();
                s.setTcpNoDelay(true);
                out = s.getOutputStream();
                socket = s;
                read(s.getInputStream());
              } catch (final IOException | RuntimeException e) {
                if (!stopping) {
                  readerFailure = e;
                }
              }
            },
            "loadgen-reader");
    acceptor.setDaemon(true);
    reader = acceptor;
    acceptor.start();
  }

  /**
   * Answers the desk's Logon and keeps the session alive until it is logged on; returns false if
   * that does not happen within {@code timeoutNanos}. Generator thread.
   */
  boolean awaitLogon(final long timeoutNanos) {
    final long deadline = System.nanoTime() + timeoutNanos;
    while (!loggedOn) {
      checkReader();
      drainReplies();
      if (System.nanoTime() - deadline >= 0) {
        return false;
      }
      Thread.onSpinWait();
    }
    return true;
  }

  /**
   * Keeps answering the desk (TestRequests, passes) for {@code nanos} without sending requests,
   * for the desk to become ready. Generator thread.
   */
  void idle(final long nanos) {
    final long until = System.nanoTime() + nanos;
    while (System.nanoTime() - until < 0) {
      checkReader();
      drainReplies();
      java.util.concurrent.locks.LockSupport.parkNanos(100_000L);
    }
  }

  /**
   * Sends requests {@code from} to {@code to - 1} (indices from {@link #FIRST_RFQ_ID}), request
   * {@code k} at {@code startNanos + k * intervalNanos}; fills {@code scheduled} with each T0.
   * Generator thread.
   */
  void run(final int from, final int to, final long startNanos, final long intervalNanos, final long[] scheduled) {
    for (int k = from; k < to; k++) {
      final long t0 = startNanos + (k - from) * intervalNanos;
      scheduled[k] = t0;
      // encode ahead of time, so the wait ends in a write
      final int length = encodeRequest(k);
      while (System.nanoTime() - t0 < 0) {
        Thread.onSpinWait();
      }
      write(length);
      sentNanos[k] = System.nanoTime();
      drainReplies();
    }
  }

  private int encodeRequest(final int k) {
    final long now = epochNanos.getAsLong();
    final String sec = securityIds[k % securityIds.length];
    header(MsgType.QUOTE_REQUEST)
        .put(FixTags.QUOTE_REQ_ID, FIRST_RFQ_ID + k)
        .put(FixTags.NO_RELATED_SYM, 1)
        .putText(FixTags.SYMBOL, sec)
        .putText(FixTags.SECURITY_ID, sec)
        .putChar(FixTags.SECURITY_ID_SOURCE, (byte) '4')
        .putChar(FixTags.SIDE, k % 2 == 0 ? (byte) '1' : (byte) '2')
        .put(FixTags.ORDER_QTY, 10_000_000L)
        .putText(FixTags.CURRENCY, "USD")
        .putTimestamp(FixTags.EXPIRE_TIME, now + TimeUnit.SECONDS.toNanos(30))
        .put(FixTags.NO_PARTY_IDS, 1)
        .put(FixTags.PARTY_ID, 101 + k % 8)
        .putChar(FixTags.PARTY_ID_SOURCE, (byte) 'D')
        .put(FixTags.PARTY_ROLE, 3)
        .put(FixTags.DEALER_COUNT, 4);
    return finish();
  }

  private FixWriter header(final int msgType) {
    return writer
        .begin(msgType)
        .putAscii(FixTags.SENDER_COMP_ID, SENDER)
        .putAscii(FixTags.TARGET_COMP_ID, target)
        .put(FixTags.MSG_SEQ_NUM, nextSeq++)
        .putTimestamp(FixTags.SENDING_TIME, epochNanos.getAsLong());
  }

  private int finish() {
    final int length = writer.finish();
    writer.buffer().getBytes(writer.messageOffset(), outBytes, 0, length);
    return length;
  }

  private void write(final int length) {
    try {
      out.write(outBytes, 0, length);
      out.flush();
    } catch (final IOException e) {
      throw new UncheckedIOException("the desk closed the D2C connection", e);
    }
  }

  /** Writes what the reader asked for. Generator thread. */
  private void drainReplies() {
    replies.read(replyHandler, 64);
  }

  private void onReply(final int type, final MutableDirectBuffer b, final int index, final int length) {
    if (out == null) {
      return;
    }
    switch (type) {
      case REPLY_LOGON -> {
        nextSeq = 1;
        header(MsgType.LOGON)
            .put(FixTags.ENCRYPT_METHOD, 0)
            .put(FixTags.HEART_BT_INT, heartbeatSeconds)
            .putChar(FixTags.RESET_SEQ_NUM_FLAG, (byte) 'Y');
        write(finish());
        loggedOn = true;
      }
      case REPLY_PASS -> {
        header(MsgType.QUOTE_RESPONSE)
            .put(FixTags.QUOTE_REQ_ID, b.getLong(index))
            .put(FixTags.QUOTE_ID, b.getLong(index + Long.BYTES))
            .put(FixTags.QUOTE_RESP_TYPE, 6);
        write(finish());
      }
      default -> {
        final FixWriter w = header(MsgType.HEARTBEAT);
        if (length > 0) {
          w.putAscii(FixTags.TEST_REQ_ID, b, index, length);
        }
        write(finish());
      }
    }
  }

  /** Reader thread: frames the desk's messages and acts on each. */
  private void read(final InputStream in) throws IOException {
    final byte[] bytes = new byte[1 << 16];
    final UnsafeBuffer view = new UnsafeBuffer(bytes);
    final FixParser parser = new FixParser();
    int filled = 0;
    while (!stopping) {
      final int n = in.read(bytes, filled, bytes.length - filled);
      if (n < 0) {
        return;
      }
      final long arrived = System.nanoTime();
      filled += n;
      int start = 0;
      while (true) {
        final int frame = FixFramer.frame(view, start, filled - start);
        if (frame == 0) {
          break;
        }
        if (frame < 0) {
          throw new IOException("garbage on the D2C connection");
        }
        if (parser.wrap(view, start, frame) == FixParser.OK) {
          onMessage(parser, arrived);
        }
        start += frame;
      }
      System.arraycopy(bytes, start, bytes, 0, filled - start);
      filled -= start;
    }
  }

  private void onMessage(final FixParser p, final long arrived) {
    switch (p.msgType()) {
      case MsgType.LOGON -> {
        heartbeatSeconds = (int) p.getLong(FixTags.HEART_BT_INT, 30);
        offerReply(REPLY_LOGON, 0);
      }
      case MsgType.TEST_REQUEST -> {
        final int i = p.indexOf(FixTags.TEST_REQ_ID);
        final int len = i < 0 ? 0 : Math.min(p.valueLength(i), reply.capacity());
        if (len > 0) {
          reply.putBytes(0, p.buffer(), p.valueOffset(i), len);
        }
        offerReply(REPLY_HEARTBEAT, len);
      }
      case MsgType.QUOTE -> {
        final long rfqId = p.getLong(FixTags.QUOTE_REQ_ID, 0);
        final long i = rfqId - FIRST_RFQ_ID;
        if (i >= 0 && i < capacity) {
          if (receivedNanos[(int) i] == 0) {
            receivedNanos[(int) i] = arrived;
            quotes++;
          } else {
            duplicateQuotes++;
          }
        }
        reply.putLong(0, rfqId);
        reply.putLong(Long.BYTES, p.getLong(FixTags.QUOTE_ID, 0));
        offerReply(REPLY_PASS, 2 * Long.BYTES);
      }
      default -> {
        // heartbeats, quote cancels and status traffic need no answer
      }
    }
  }

  private void offerReply(final int type, final int length) {
    while (!replies.write(type, reply, 0, Math.max(length, 0)) && !stopping) {
      Thread.onSpinWait();
    }
  }

  private void checkReader() {
    final Throwable t = readerFailure;
    if (t != null) {
      throw new IllegalStateException("load generator reader failed", t);
    }
  }

  /** When request {@code k}'s write returned, or 0. */
  long sentNanos(final int k) {
    return sentNanos[k];
  }

  /** When the Quote answering request {@code k} arrived, or 0. Read after the run. */
  long receivedNanos(final int k) {
    return receivedNanos[k];
  }

  /** Quotes received for requests of this run. Reader thread's count; read after the run. */
  long quotes() {
    return quotes;
  }

  /** Quotes for a request already answered (a requote). */
  long duplicateQuotes() {
    return duplicateQuotes;
  }

  /** The reader's view of a framed message buffer, for tests. */
  static String text(final DirectBuffer b, final int offset, final int length) {
    return b.getStringWithoutLengthAscii(offset, length).replace('\u0001', '|');
  }

  @Override
  public void close() {
    stopping = true;
    try {
      final Socket s = socket;
      if (s != null) {
        s.close();
      }
      server.close();
      if (reader != null) {
        reader.join(5_000L);
      }
    } catch (final IOException e) {
      // closing: nothing to do about it
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
