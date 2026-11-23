package io.ratesalgo.fix.net;

import io.ratesalgo.common.security.TlsConfig;
import io.ratesalgo.fix.codec.FixFramer;
import io.ratesalgo.fix.session.FixSession;
import io.ratesalgo.fix.session.FixTransport;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Locale;
import java.util.concurrent.locks.LockSupport;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLEngineResult.HandshakeStatus;
import javax.net.ssl.SSLEngineResult.Status;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;

/**
 * TCP connection for one engine-side session, in the clear on loopback or under TLS anywhere.
 * {@link #connect()} waits for the connection (and the TLS handshake) up to a deadline, then the
 * owner calls {@link #poll()} from its loop: it reads, frames complete messages, hands each to the
 * session and runs the session's timers. The channel is non-blocking throughout.
 *
 * <p>Nothing here waits without a bound, because the thread that owns the session also carries
 * its part of the kill path: a connect that is not established by its deadline fails, a handshake that is not
 * finished by the deadline of the {@link TlsConfig} fails, and a write the venue does not take
 * within the spin budget closes the connection and is counted ({@link #writeTimeouts()}). A venue
 * that has stopped reading is treated as down; its cancel-on-disconnect then applies.
 *
 * <p>TLS uses the JDK's {@link SSLEngine} over the same channel. Protocols, trust and the check
 * that the certificate names the host dialled all come from {@link TlsConfig#clientEngine}. A
 * plain connection is accepted only to a loopback address (simulators and tests); any other host
 * without TLS is refused when the initiator is built. Under TLS 1.3 a server gives its verdict on
 * the client's certificate after the client's own handshake has finished, so a refused client
 * certificate shows as a connection that closes before the Logon is answered; it is counted with
 * the handshake failures.
 *
 * <p>Copies. On the plain path a message held in a direct buffer (the session's {@code FixWriter}
 * buffer) is written to the socket from that buffer through a view made once per backing buffer,
 * so the bytes are not copied on the way out (review finding L11); any other buffer is copied into
 * this class's own direct buffer first. Under TLS the message is copied into the heap buffer the
 * engine encrypts from, as measured below.
 *
 * <p>Allocation. The plain path allocates nothing per message (measured: 0 bytes). Under TLS
 * this class reuses its buffers and allocates nothing of its own, but the JDK's engine does
 * allocate inside {@code wrap} and {@code unwrap}: a result object, and per-record cipher state
 * (nonce, parameter object, cipher working arrays). Measured on JDK 25, TLS 1.3 with
 * AES-256-GCM, one execution report of about 170 bytes per record, after warm-up
 * (TlsAllocationTest): about 1.3 KB per message sent and about 1.4 KB per message received, all
 * of it inside the engine. With direct buffers the same test gave 1.6 KB and 1.9 KB, which is why
 * the TLS buffers are heap buffers. It is short-lived garbage of constant size per record, so a
 * young collection clears it; a venue link that must be allocation-free end to end needs TLS
 * ended outside the JVM (a terminator on loopback) instead.
 *
 * <p>One thread owns an initiator, with one exception: {@link #abort()} may be called from any
 * thread to close the socket when the owner is stuck or dead.
 */
public final class TcpInitiator implements FixTransport, AutoCloseable {

  private static final ByteBuffer EMPTY = ByteBuffer.allocate(0);

  private final String host;
  private final int port;
  private final ByteBuffer inBytes;
  private final UnsafeBuffer in;
  private final ByteBuffer outBytes;
  private final UnsafeBuffer outPlain;
  /** The direct buffer the last message was sent from, and a view of it the channel writes from. */
  private ByteBuffer viewOf;
  private ByteBuffer view;
  private final long connectTimeoutNanos;
  private final int maxWriteSpins;
  private final int maxBodyLength;
  private final TlsConfig tls;
  /** Ciphertext read from the socket and not yet decrypted; null without TLS. Kept in write mode. */
  private final ByteBuffer netIn;
  /** Ciphertext on its way to the socket; null without TLS. */
  private final ByteBuffer netOut;
  private FixSession session;
  /** The owner's channel; the same channel is published to {@link #shared} for {@link #abort()}. */
  private SocketChannel channel;
  private final SharedChannel shared = new SharedChannel();
  /** The TLS engine of the current connection; touched by the owner only. */
  private SSLEngine engine;
  private boolean netPending;
  private boolean applicationDataSeen;
  private InboundFilter filter = InboundFilter.PASS;
  private long bytesRead;
  private long writeTimeouts;
  private long connectTimeouts;
  private long handshakeTimeouts;
  private long handshakeFailures;
  private long tlsErrors;

  /** Default connect deadline: two seconds. */
  public static final long DEFAULT_CONNECT_TIMEOUT_NANOS = 2_000_000_000L;

  /**
   * Default number of write attempts that may make no progress before the connection is closed.
   * Each attempt is a system call followed by a spin hint, so the budget is some tens of
   * milliseconds: far longer than a healthy venue leaves its receive window shut.
   */
  public static final int DEFAULT_MAX_WRITE_SPINS = 20_000;

  /**
   * Creates an unconnected plain-TCP initiator with buffers of the given size and the default
   * bounds. Loopback only; see the six-argument constructor.
   */
  public TcpInitiator(final String host, final int port, final int bufferBytes) {
    this(host, port, bufferBytes, DEFAULT_CONNECT_TIMEOUT_NANOS, DEFAULT_MAX_WRITE_SPINS);
  }

  /**
   * Creates an unconnected plain-TCP initiator. Loopback only; see the six-argument constructor.
   *
   * @param connectTimeoutNanos how long {@link #connect()} waits for the connection
   * @param maxWriteSpins write attempts without progress before {@link #send} gives up
   */
  public TcpInitiator(
      final String host,
      final int port,
      final int bufferBytes,
      final long connectTimeoutNanos,
      final int maxWriteSpins) {
    this(host, port, bufferBytes, connectTimeoutNanos, maxWriteSpins, null);
  }

  /**
   * Creates an unconnected initiator.
   *
   * @param connectTimeoutNanos how long {@link #connect()} waits for the TCP connection
   * @param maxWriteSpins write attempts without progress before {@link #send} gives up
   * @param tls TLS settings, or null for plain TCP, which is allowed to a loopback address only
   * @throws IllegalArgumentException if {@code tls} is null and the host is not loopback
   * @throws IllegalStateException if the key or trust store of {@code tls} cannot be opened
   */
  public TcpInitiator(
      final String host,
      final int port,
      final int bufferBytes,
      final long connectTimeoutNanos,
      final int maxWriteSpins,
      final TlsConfig tls) {
    if (connectTimeoutNanos <= 0 || maxWriteSpins < 1) {
      throw new IllegalArgumentException("connect timeout and write spins must be positive");
    }
    if (tls == null && !isLoopback(host)) {
      throw new IllegalArgumentException(
          "plain FIX is for loopback only: refusing a FIX connection to " + host + " without TLS");
    }
    this.host = host;
    this.port = port;
    this.tls = tls;
    if (tls == null) {
      this.netIn = null;
      this.netOut = null;
      this.inBytes = ByteBuffer.allocateDirect(bufferBytes);
      this.outBytes = ByteBuffer.allocateDirect(bufferBytes);
    } else {
      // The stores are opened here, so a wrong path or password fails at start-up, not at connect.
      final javax.net.ssl.SSLSession sizes = tls.context().createSSLEngine().getSession();
      // Heap buffers on purpose: the JDK's AES-GCM allocates about a quarter less per record when
      // both sides of wrap and unwrap are arrays (measured; see the class comment). The channel
      // copies through its own cached direct buffer, which costs a copy and no allocation.
      this.netIn = ByteBuffer.allocate(sizes.getPacketBufferSize());
      this.netOut = ByteBuffer.allocate(sizes.getPacketBufferSize());
      // room for one more decrypted record behind the largest partial frame, so that unwrap can
      // always make progress once complete frames have been handed on
      this.inBytes = ByteBuffer.allocate(bufferBytes + sizes.getApplicationBufferSize());
      this.outBytes = ByteBuffer.allocate(bufferBytes);
    }
    this.in = new UnsafeBuffer(inBytes);
    this.outPlain = new UnsafeBuffer(outBytes);
    this.connectTimeoutNanos = connectTimeoutNanos;
    this.maxWriteSpins = maxWriteSpins;
    // a frame must fit the read buffer, or the stream could never be framed again
    this.maxBodyLength = Math.min(FixFramer.MAX_BODY_LENGTH, Math.max(0, bufferBytes - 64));
  }

  /**
   * True if {@code host} is {@code localhost} or a loopback address literal (127.0.0.0/8, ::1).
   * No name is looked up: a name that merely resolves to loopback today is not loopback.
   */
  public static boolean isLoopback(final String host) {
    if (host == null || host.isEmpty()) {
      return false;
    }
    if ("localhost".equals(host.toLowerCase(Locale.ROOT))) {
      return true;
    }
    try {
      return InetAddress.ofLiteral(host).isLoopbackAddress();
    } catch (final IllegalArgumentException e) {
      return false;
    }
  }

  /** Attaches the session this connection carries. */
  public void attach(final FixSession fixSession) {
    this.session = fixSession;
  }

  /** Installs a filter that may drop inbound messages (chaos tests). */
  public void filter(final InboundFilter inboundFilter) {
    this.filter = inboundFilter;
  }

  /** True while the socket is open. */
  public boolean isConnected() {
    final SocketChannel c = channel;
    return c != null && c.isOpen();
  }

  /** True if this initiator speaks TLS. */
  public boolean isTls() {
    return tls != null;
  }

  /** Writes abandoned because the venue did not take the bytes within the spin budget. */
  public long writeTimeouts() {
    return writeTimeouts;
  }

  /** Connects abandoned at the deadline. */
  public long connectTimeouts() {
    return connectTimeouts;
  }

  /** TLS handshakes abandoned at the deadline. */
  public long handshakeTimeouts() {
    return handshakeTimeouts;
  }

  /**
   * TLS handshakes that failed: an untrusted, expired or wrongly named certificate, no protocol
   * in common, a peer that closed mid-handshake, or a fatal alert before any application data
   * (a server refusing our client certificate).
   */
  public long handshakeFailures() {
    return handshakeFailures;
  }

  /** Established TLS connections dropped on a TLS error (bad record, fatal alert). */
  public long tlsErrors() {
    return tlsErrors;
  }

  /** Total bytes read from the socket since construction (ciphertext under TLS). */
  public long bytesRead() {
    return bytesRead;
  }

  /**
   * Connects, completes the TLS handshake if configured, and sends the session's Logon.
   *
   * @throws UncheckedIOException if the connection is refused or not established by the deadline,
   *     or the TLS handshake fails or is not finished by its deadline
   */
  public void connect() {
    if (session == null) {
      throw new IllegalStateException("attach a session before connecting");
    }
    SocketChannel c = null;
    try {
      c = SocketChannel.open();
      c.configureBlocking(false);
      c.socket().setTcpNoDelay(true);
      if (!c.connect(new InetSocketAddress(host, port))) {
        final long deadline = System.nanoTime() + connectTimeoutNanos;
        while (!c.finishConnect()) {
          if (System.nanoTime() - deadline >= 0) {
            connectTimeouts++;
            throw new SocketTimeoutException("no connection within " + connectTimeoutNanos + " ns");
          }
          LockSupport.parkNanos(50_000L);
        }
      }
    } catch (IOException e) {
      closeQuietly(c);
      throw new UncheckedIOException("cannot connect to " + host + ":" + port, e);
    }
    inBytes.clear();
    SSLEngine negotiated = null;
    if (tls != null) {
      negotiated = handshakeOrClose(c);
    }
    engine = negotiated;
    applicationDataSeen = false;
    channel = c;
    shared.set(c);
    session.onConnected();
    // the Logon may have carried a password: do not leave its plain bytes in the staging buffer
    outPlain.setMemory(0, outBytes.limit(), (byte) 0);
  }

  private SSLEngine handshakeOrClose(final SocketChannel c) {
    boolean done = false;
    try {
      final SSLEngine negotiated = handshake(c);
      done = true;
      return negotiated;
    } catch (SocketTimeoutException e) {
      handshakeTimeouts++;
      throw new UncheckedIOException(
          "no TLS handshake with " + host + ":" + port + " within " + tls.handshakeTimeoutNanos() + " ns", e);
    } catch (IOException e) {
      // The cause says which check failed (trust, validity, name, protocol); it holds no key material.
      handshakeFailures++;
      throw new UncheckedIOException("TLS handshake with " + host + ":" + port + " failed", e);
    } finally {
      if (!done) {
        closeQuietly(c);
      }
    }
  }

  /** Runs the client handshake to its end or to the deadline. Allocates; it runs once per connect. */
  private SSLEngine handshake(final SocketChannel c) throws IOException {
    final SSLEngine e = tls.clientEngine(host, port);
    final long deadline = System.nanoTime() + tls.handshakeTimeoutNanos();
    netIn.clear();
    netPending = false;
    e.beginHandshake();
    HandshakeStatus hs = e.getHandshakeStatus();
    while (hs != HandshakeStatus.FINISHED && hs != HandshakeStatus.NOT_HANDSHAKING) {
      switch (hs) {
        case NEED_WRAP -> {
          netOut.clear();
          final SSLEngineResult r = e.wrap(EMPTY, netOut);
          if (r.getStatus() != Status.OK) {
            throw new SSLHandshakeException("engine " + r.getStatus() + " while writing the handshake");
          }
          netOut.flip();
          while (netOut.hasRemaining()) {
            if (c.write(netOut) == 0) {
              waitForPeer(deadline);
            }
          }
          hs = r.getHandshakeStatus();
        }
        case NEED_UNWRAP -> {
          netIn.flip();
          final SSLEngineResult r = e.unwrap(netIn, inBytes);
          netIn.compact();
          if (r.getStatus() == Status.BUFFER_UNDERFLOW) {
            final int n = c.read(netIn);
            if (n < 0) {
              throw new SSLHandshakeException("the peer closed the connection during the handshake");
            }
            if (n == 0) {
              waitForPeer(deadline);
            }
          } else if (r.getStatus() != Status.OK) {
            throw new SSLHandshakeException("engine " + r.getStatus() + " while reading the handshake");
          }
          hs = r.getHandshakeStatus();
        }
        case NEED_TASK -> {
          runTasks(e);
          hs = e.getHandshakeStatus();
        }
        default -> throw new SSLHandshakeException("unexpected handshake state " + hs);
      }
    }
    checkValidity(e);
    // anything read past the handshake (session tickets, early venue bytes) is decrypted by poll
    netPending = netIn.position() > 0;
    return e;
  }

  /**
   * Refuses a certificate outside its validity period. The JDK's trust manager skips this check
   * for a certificate that is itself in the trust store (a pinned venue certificate), which is
   * exactly how venue certificates are usually trusted, so it is made here for the whole chain.
   */
  private static void checkValidity(final SSLEngine e) throws SSLException {
    for (final Certificate certificate : e.getSession().getPeerCertificates()) {
      if (certificate instanceof X509Certificate x509) {
        try {
          x509.checkValidity();
        } catch (final CertificateException ex) {
          throw new SSLHandshakeException("the peer's certificate is outside its validity period", ex);
        }
      }
    }
  }

  private void waitForPeer(final long deadline) throws SocketTimeoutException {
    if (System.nanoTime() - deadline >= 0) {
      throw new SocketTimeoutException("handshake deadline passed");
    }
    LockSupport.parkNanos(50_000L);
  }

  private static void runTasks(final SSLEngine e) {
    Runnable task = e.getDelegatedTask();
    while (task != null) {
      task.run();
      task = e.getDelegatedTask();
    }
  }

  private static void closeQuietly(final SocketChannel c) {
    if (c != null) {
      try {
        c.close();
      } catch (IOException ignored) {
        // already failing; the connect error is the one reported
      }
    }
  }

  /** Reads and dispatches what is available, then runs the session timers. Returns messages. */
  public int poll() {
    int messages = 0;
    final SocketChannel current = channel;
    if (current != null && !current.isOpen()) {
      // closed under us by abort(): tell the session once, as a read failure would have
      channel = null;
      engine = null;
      if (session != null) {
        session.onDisconnected();
      }
    }
    if (isConnected()) {
      int n;
      try {
        n = channel.read(tls == null ? inBytes : netIn);
      } catch (IOException e) {
        n = -1;
      }
      if (n < 0) {
        disconnect();
        return 0;
      }
      bytesRead += n;
      if (tls == null) {
        messages = drain();
      } else if (n > 0 || netPending) {
        messages = decryptAndDrain();
      }
    }
    if (session != null) {
      session.poll();
    }
    return messages;
  }

  /**
   * Decrypts what {@link #netIn} holds into {@link #inBytes} and frames it. Post-handshake TLS 1.3
   * messages (session tickets, key updates) are consumed by the engine here; a key update that
   * asks for ours is answered at once. A close_notify ends the connection.
   */
  private int decryptAndDrain() {
    final SSLEngine e = engine;
    int messages = 0;
    netIn.flip();
    try {
      while (netIn.hasRemaining()) {
        final SSLEngineResult r = e.unwrap(netIn, inBytes);
        final Status status = r.getStatus();
        if (r.bytesProduced() > 0) {
          applicationDataSeen = true;
        }
        if (status == Status.BUFFER_UNDERFLOW) {
          break; // a partial record: wait for the rest
        }
        if (status == Status.BUFFER_OVERFLOW) {
          // hand complete frames on to make room; the buffer is sized so that this always does
          final int held = inBytes.position();
          messages += drain();
          if (!isConnected()) {
            return messages;
          }
          if (inBytes.position() == held) {
            throw new SSLException("no room to decrypt a record");
          }
          continue;
        }
        if (status == Status.CLOSED) {
          messages += drain();
          if (isConnected()) {
            disconnect();
          }
          return messages;
        }
        if (r.getHandshakeStatus() == HandshakeStatus.NEED_TASK) {
          runTasks(e);
        }
        if (e.getHandshakeStatus() == HandshakeStatus.NEED_WRAP && !flushEngine(e)) {
          return messages;
        }
        if (r.bytesConsumed() == 0 && r.bytesProduced() == 0) {
          break;
        }
      }
    } catch (SSLException ex) {
      // a bad record or a fatal alert: the stream cannot be trusted further
      if (applicationDataSeen) {
        tlsErrors++;
      } else {
        handshakeFailures++;
      }
      drop(false);
      return messages;
    } catch (IOException ex) {
      drop(false);
      return messages;
    }
    netIn.compact();
    netPending = false;
    return messages + drain();
  }

  /** Sends what the engine itself wants to say (a key update). False if the connection was dropped. */
  private boolean flushEngine(final SSLEngine e) throws IOException {
    netOut.clear();
    final SSLEngineResult r = e.wrap(EMPTY, netOut);
    if (r.getStatus() != Status.OK) {
      drop(false);
      return false;
    }
    netOut.flip();
    return writeFully(channel, netOut);
  }

  private int drain() {
    int start = 0;
    final int limit = inBytes.position();
    int messages = 0;
    while (start < limit) {
      final int length = FixFramer.frame(in, start, limit - start, maxBodyLength);
      if (length == 0) {
        break;
      }
      if (length == FixFramer.GARBAGE) {
        start = skipToNextBegin(start + 1, limit);
        continue;
      }
      if (filter.deliver(in, start, length)) {
        session.onMessage(in, start, length);
      }
      messages++;
      start += length;
      if (!isConnected()) {
        return messages;
      }
    }
    if (start > 0) {
      in.putBytes(0, in, start, limit - start);
      inBytes.position(limit - start);
    }
    return messages;
  }

  private int skipToNextBegin(final int from, final int limit) {
    for (int i = from; i + 1 < limit; i++) {
      if (in.getByte(i) == '8' && in.getByte(i + 1) == '=') {
        return i;
      }
    }
    return limit;
  }

  @Override
  public void send(final DirectBuffer buffer, final int offset, final int length) {
    if (!isConnected()) {
      return;
    }
    final SocketChannel c = channel;
    try {
      if (tls == null) {
        writeFully(c, plainBytes(buffer, offset, length));
        return;
      }
      outBytes.clear();
      buffer.getBytes(offset, outBytes, length);
      outBytes.flip();
      final SSLEngine e = engine;
      while (outBytes.hasRemaining()) {
        netOut.clear();
        final SSLEngineResult r = e.wrap(outBytes, netOut);
        if (r.getStatus() != Status.OK || (r.bytesConsumed() == 0 && r.bytesProduced() == 0)) {
          // the engine is closed or stuck: nothing more can be sent on this connection
          drop(false);
          return;
        }
        netOut.flip();
        if (!writeFully(c, netOut)) {
          return;
        }
      }
    } catch (IOException e) {
      drop(false);
    }
  }

  /**
   * The bytes to write on the plain path: a view of the caller's direct buffer when it has one, so
   * nothing is copied; otherwise a copy in this class's direct buffer. The view is made once per
   * backing buffer (a session sends from one buffer), so the steady state allocates nothing. The
   * caller's buffer is not changed; the view has its own position and limit.
   */
  // identity on purpose: a ByteBuffer's equals compares contents, and the view is of this buffer object
  @SuppressWarnings("ReferenceEquality")
  private ByteBuffer plainBytes(final DirectBuffer buffer, final int offset, final int length) {
    final ByteBuffer backing = buffer.byteBuffer();
    if (backing != null && backing.isDirect()) {
      if (backing != viewOf) {
        viewOf = backing;
        view = backing.duplicate();
      }
      final int from = buffer.wrapAdjustment() + offset;
      view.clear().position(from).limit(from + length);
      return view;
    }
    outBytes.clear();
    buffer.getBytes(offset, outBytes, length);
    outBytes.flip();
    return outBytes;
  }

  /**
   * Writes the whole buffer or gives up after the spin budget. False if the connection was closed.
   */
  private boolean writeFully(final SocketChannel c, final ByteBuffer bytes) throws IOException {
    int spins = 0;
    while (bytes.hasRemaining()) {
      if (c.write(bytes) > 0) {
        spins = 0;
      } else if (++spins > maxWriteSpins) {
        // The venue is not reading. Part of a message may be on the wire, so the stream is no
        // longer usable: close it and let the venue cancel on disconnect.
        writeTimeouts++;
        drop(false);
        return false;
      } else {
        Thread.onSpinWait();
      }
    }
    return true;
  }

  @Override
  public void disconnect() {
    drop(true);
  }

  /**
   * Closes the connection and tells the session.
   *
   * @param sayGoodbye under TLS, try once to send close_notify first; false when the write side
   *     is already known to be broken
   */
  private void drop(final boolean sayGoodbye) {
    final SSLEngine e = engine;
    engine = null;
    if (sayGoodbye && e != null && isConnected()) {
      closeNotify(e, channel);
    }
    abort();
    if (session != null) {
      session.onDisconnected();
    }
  }

  /** One non-blocking attempt to send close_notify; the socket is closed whether it fits or not. */
  private void closeNotify(final SSLEngine e, final SocketChannel c) {
    try {
      e.closeOutbound();
      netOut.clear();
      e.wrap(EMPTY, netOut);
      netOut.flip();
      c.write(netOut);
    } catch (IOException | RuntimeException ignored) {
      // best effort: the connection is going away
    }
  }

  /**
   * Closes the socket and nothing else. Safe from any thread: the supervisor uses it when the
   * owning thread is stuck or dead, so that the venue sees a disconnect and cancels what we have
   * working there. The owner finds the closed channel on its next poll and tells the session. The
   * TLS engine is left to the owner; no close_notify is sent on this path.
   */
  public void abort() {
    shared.close();
  }

  @Override
  public void close() {
    disconnect();
  }
}
