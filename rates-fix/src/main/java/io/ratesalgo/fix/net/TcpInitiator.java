package io.ratesalgo.fix.net;

import io.ratesalgo.fix.codec.FixFramer;
import io.ratesalgo.fix.session.FixSession;
import io.ratesalgo.fix.session.FixTransport;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.concurrent.locks.LockSupport;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;

/**
 * TCP connection for one engine-side session. {@link #connect()} waits for the connection up to a
 * deadline, then the owner calls {@link #poll()} from its loop: it reads, frames complete messages,
 * hands each to the session and runs the session's timers. The channel is non-blocking throughout.
 *
 * <p>Nothing here waits without a bound, because one gateway thread carries every session and the
 * kill path: a connect that is not established by its deadline fails, and a write the venue does
 * not take within the spin budget closes the connection and is counted ({@link #writeTimeouts()}).
 * A venue that has stopped reading is treated as down; its cancel-on-disconnect then applies.
 *
 * <p>One thread owns an initiator, with one exception: {@link #abort()} may be called from any
 * thread to close the socket when the owner is stuck or dead.
 */
public final class TcpInitiator implements FixTransport, AutoCloseable {

  private final String host;
  private final int port;
  private final ByteBuffer inBytes;
  private final UnsafeBuffer in;
  private final ByteBuffer outBytes;
  private final long connectTimeoutNanos;
  private final int maxWriteSpins;
  private final int maxBodyLength;
  private FixSession session;
  /** The owner's channel; the same channel is published to {@link #shared} for {@link #abort()}. */
  private SocketChannel channel;
  private final SharedChannel shared = new SharedChannel();
  private InboundFilter filter = InboundFilter.PASS;
  private long bytesRead;
  private long writeTimeouts;
  private long connectTimeouts;

  /** Default connect deadline: two seconds. */
  public static final long DEFAULT_CONNECT_TIMEOUT_NANOS = 2_000_000_000L;

  /**
   * Default number of write attempts that may make no progress before the connection is closed.
   * Each attempt is a system call followed by a spin hint, so the budget is some tens of
   * milliseconds: far longer than a healthy venue leaves its receive window shut.
   */
  public static final int DEFAULT_MAX_WRITE_SPINS = 20_000;

  /** Creates an unconnected initiator with buffers of the given size and the default bounds. */
  public TcpInitiator(final String host, final int port, final int bufferBytes) {
    this(host, port, bufferBytes, DEFAULT_CONNECT_TIMEOUT_NANOS, DEFAULT_MAX_WRITE_SPINS);
  }

  /**
   * Creates an unconnected initiator.
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
    if (connectTimeoutNanos <= 0 || maxWriteSpins < 1) {
      throw new IllegalArgumentException("connect timeout and write spins must be positive");
    }
    this.host = host;
    this.port = port;
    this.inBytes = ByteBuffer.allocateDirect(bufferBytes);
    this.in = new UnsafeBuffer(inBytes);
    this.outBytes = ByteBuffer.allocateDirect(bufferBytes);
    this.connectTimeoutNanos = connectTimeoutNanos;
    this.maxWriteSpins = maxWriteSpins;
    // a frame must fit the read buffer, or the stream could never be framed again
    this.maxBodyLength = Math.min(FixFramer.MAX_BODY_LENGTH, Math.max(0, bufferBytes - 64));
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

  /** Writes abandoned because the venue did not take the bytes within the spin budget. */
  public long writeTimeouts() {
    return writeTimeouts;
  }

  /** Connects abandoned at the deadline. */
  public long connectTimeouts() {
    return connectTimeouts;
  }

  /** Total bytes read since construction. */
  public long bytesRead() {
    return bytesRead;
  }

  /**
   * Connects and sends the session's Logon.
   *
   * @throws UncheckedIOException if the connection is refused or not established by the deadline
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
    channel = c;
    shared.set(c);
    inBytes.clear();
    session.onConnected();
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
      if (session != null) {
        session.onDisconnected();
      }
    }
    if (isConnected()) {
      int n;
      try {
        n = channel.read(inBytes);
      } catch (IOException e) {
        n = -1;
      }
      if (n < 0) {
        disconnect();
        return 0;
      }
      bytesRead += n;
      messages = drain();
    }
    if (session != null) {
      session.poll();
    }
    return messages;
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
    outBytes.clear();
    buffer.getBytes(offset, outBytes, length);
    outBytes.flip();
    final SocketChannel c = channel;
    try {
      int spins = 0;
      while (outBytes.hasRemaining()) {
        if (c.write(outBytes) > 0) {
          spins = 0;
        } else if (++spins > maxWriteSpins) {
          // The venue is not reading. Part of a message may be on the wire, so the stream is no
          // longer usable: close it and let the venue cancel on disconnect.
          writeTimeouts++;
          disconnect();
          return;
        } else {
          Thread.onSpinWait();
        }
      }
    } catch (IOException e) {
      disconnect();
    }
  }

  @Override
  public void disconnect() {
    abort();
    if (session != null) {
      session.onDisconnected();
    }
  }

  /**
   * Closes the socket and nothing else. Safe from any thread: the supervisor uses it when the
   * owning thread is stuck or dead, so that the venue sees a disconnect and cancels what we have
   * working there. The owner finds the closed channel on its next poll and tells the session.
   */
  public void abort() {
    shared.close();
  }

  @Override
  public void close() {
    disconnect();
  }
}
