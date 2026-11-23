package io.ratesalgo.fix.net;

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
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import org.agrona.concurrent.UnsafeBuffer;

/**
 * A small venue end for transport tests, on a real loopback socket, plain or TLS (JDK only). It
 * takes one connection. In FIX mode it answers a Logon with a Logon, records what it receives and
 * sends what the test asks for; in SILENT mode it accepts the TCP connection and says nothing; in
 * DEAF mode it completes the TLS handshake and then never reads.
 */
public final class TestAcceptor implements AutoCloseable {

  /** What the acceptor does with its connection. */
  public enum Mode {
    FIX,
    SILENT,
    DEAF
  }

  private static final long EPOCH_NANOS = 1_793_629_800_000_000_000L;

  /** Messages received, as the parser prints them (fields separated by {@code |}). */
  public final List<String> received = new CopyOnWriteArrayList<>();

  private final ServerSocket server;
  private final TlsConfig tls;
  private final Mode mode;
  private final CountDownLatch ready = new CountDownLatch(1);
  private final CountDownLatch ended = new CountDownLatch(1);
  private final CountDownLatch release = new CountDownLatch(1);
  private final AtomicLong receivedCount = new AtomicLong();
  private final Object writeLock = new Object();
  private final FixWriter writer = new FixWriter(4096);
  private final UnsafeBuffer frame = new UnsafeBuffer(new byte[8192]);
  private volatile Socket socket;
  private volatile OutputStream out;
  private volatile Exception failure;
  private volatile boolean recording = true;
  private volatile boolean closed;
  private long seq = 1;

  /** Listens on 127.0.0.1. {@code tls} null means plain TCP. */
  public TestAcceptor(final TlsConfig tls, final Mode mode) {
    this(tls, mode, InetAddress.getLoopbackAddress());
  }

  /** Listens on the given address. */
  public TestAcceptor(final TlsConfig tls, final Mode mode, final InetAddress bind) {
    this.tls = tls;
    this.mode = mode;
    try {
      this.server = new ServerSocket(0, 1, bind);
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
    final Thread thread = new Thread(this::run, "test-acceptor");
    thread.setDaemon(true);
    thread.start();
  }

  public int port() {
    return server.getLocalPort();
  }

  /** The handshake or read failure that ended the connection, or null. */
  public Exception failure() {
    return failure;
  }

  /** Messages received so far. */
  public long receivedCount() {
    return receivedCount.get();
  }

  /** Stops keeping received messages as strings (allocation test). */
  public void quiet() {
    recording = false;
  }

  /** Waits until the connection is accepted and, under TLS, the handshake has ended either way. */
  public boolean awaitReady() throws InterruptedException {
    return ready.await(20, TimeUnit.SECONDS);
  }

  /** Waits until the connection has ended. */
  public boolean awaitEnd() throws InterruptedException {
    return ended.await(20, TimeUnit.SECONDS);
  }

  private void run() {
    try (Socket accepted = server.accept()) {
      accepted.setTcpNoDelay(true);
      Socket s = accepted;
      if (mode != Mode.SILENT && tls != null) {
        final SSLSocket ssl = (SSLSocket) tls.context().getSocketFactory().createSocket(accepted, null, true);
        final SSLParameters p = ssl.getSSLParameters();
        p.setProtocols(tls.protocols().toArray(new String[0]));
        p.setNeedClientAuth(tls.requireClientCertificate());
        ssl.setSSLParameters(p);
        ssl.startHandshake();
        s = ssl;
      }
      socket = s;
      out = s.getOutputStream();
      ready.countDown();
      if (mode == Mode.FIX) {
        read(s.getInputStream());
      } else {
        // hold the connection open without reading until the test closes us
        release.await();
      }
    } catch (final IOException e) {
      if (!closed) {
        failure = e;
      }
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
    } finally {
      ready.countDown();
      ended.countDown();
    }
  }

  private void read(final InputStream in) throws IOException {
    final byte[] bytes = new byte[1 << 20];
    final UnsafeBuffer buffer = new UnsafeBuffer(bytes);
    final FixParser parser = new FixParser();
    int held = 0;
    while (true) {
      final int n = in.read(bytes, held, bytes.length - held);
      if (n < 0) {
        return;
      }
      held += n;
      int start = 0;
      while (start < held) {
        final int length = FixFramer.frame(buffer, start, held - start);
        if (length <= 0) {
          break;
        }
        if (parser.wrap(buffer, start, length) != FixParser.OK) {
          throw new IOException("the engine sent a malformed message");
        }
        onMessage(parser);
        start += length;
      }
      System.arraycopy(bytes, start, bytes, 0, held - start);
      held -= start;
    }
  }

  private void onMessage(final FixParser parser) throws IOException {
    receivedCount.incrementAndGet();
    if (recording) {
      received.add(parser.toString());
    }
    if (parser.msgType() == MsgType.LOGON) {
      final boolean reset = parser.isYes(FixTags.RESET_SEQ_NUM_FLAG);
      send(MsgType.LOGON, w -> {
        w.put(FixTags.ENCRYPT_METHOD, 0).put(FixTags.HEART_BT_INT, 30);
        if (reset) {
          w.putBool(FixTags.RESET_SEQ_NUM_FLAG, true);
        }
      });
    }
  }

  /** Sends one message with the next sequence number; {@code body} adds the fields after the header. */
  public void send(final int msgType, final Consumer<FixWriter> body) throws IOException {
    synchronized (writeLock) {
      final int length = build(msgType, body);
      out.write(frame.byteArray(), 0, length);
      out.flush();
    }
  }

  /**
   * Starts a thread that sends {@code count} copies of a message (each with its own sequence
   * number) in one write, so that under TLS they travel in full-size records. A thread, because
   * the write blocks until the engine under test reads.
   */
  public Thread sendBatch(final int msgType, final Consumer<FixWriter> body, final int count) {
    return start(() -> {
      synchronized (writeLock) {
        final byte[] batch = new byte[count * 512];
        int at = 0;
        for (int i = 0; i < count; i++) {
          final int length = build(msgType, body);
          System.arraycopy(frame.byteArray(), 0, batch, at, length);
          at += length;
        }
        out.write(batch, 0, at);
        out.flush();
      }
    });
  }

  private interface Sending {
    void run() throws IOException;
  }

  private Thread start(final Sending sending) {
    final Thread t = new Thread(() -> {
      try {
        sending.run();
      } catch (final IOException e) {
        failure = e;
      }
    }, "test-acceptor-send");
    t.setDaemon(true);
    t.start();
    return t;
  }

  /** Starts a thread that sends {@code count} messages, one write and one TLS record each. */
  public Thread stream(final int msgType, final Consumer<FixWriter> body, final int count) {
    return start(() -> {
      for (int i = 0; i < count; i++) {
        send(msgType, body);
      }
    });
  }

  private int build(final int msgType, final Consumer<FixWriter> body) {
    writer.begin(msgType)
        .putText(FixTags.SENDER_COMP_ID, "VENUE")
        .putText(FixTags.TARGET_COMP_ID, "ENGINE")
        .put(FixTags.MSG_SEQ_NUM, seq++)
        .putTimestamp(FixTags.SENDING_TIME, EPOCH_NANOS);
    body.accept(writer);
    return writer.finish(frame, 0);
  }

  /** Closes the connection the way a venue ends a session: under TLS with close_notify. */
  public void closeConnection() throws IOException {
    final Socket s = socket;
    if (s != null) {
      s.close();
    }
  }

  @Override
  public void close() {
    closed = true;
    release.countDown();
    try {
      final Socket s = socket;
      if (s != null) {
        s.close();
      }
      server.close();
    } catch (final IOException ignored) {
      // closing a test socket
    }
  }
}
