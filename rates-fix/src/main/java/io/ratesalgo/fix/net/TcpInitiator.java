package io.ratesalgo.fix.net;

import io.ratesalgo.fix.codec.FixFramer;
import io.ratesalgo.fix.session.FixSession;
import io.ratesalgo.fix.session.FixTransport;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;

/**
 * TCP connection for one engine-side session. {@link #connect()} blocks until connected, then the
 * channel is non-blocking and the owner calls {@link #poll()} from its loop: it reads, frames
 * complete messages, hands each to the session and runs the session's timers.
 */
public final class TcpInitiator implements FixTransport, AutoCloseable {

  private final String host;
  private final int port;
  private final ByteBuffer inBytes;
  private final UnsafeBuffer in;
  private final ByteBuffer outBytes;
  private FixSession session;
  private SocketChannel channel;
  private InboundFilter filter = InboundFilter.PASS;
  private long bytesRead;

  /** Creates an unconnected initiator with buffers of the given size. */
  public TcpInitiator(final String host, final int port, final int bufferBytes) {
    this.host = host;
    this.port = port;
    this.inBytes = ByteBuffer.allocateDirect(bufferBytes);
    this.in = new UnsafeBuffer(inBytes);
    this.outBytes = ByteBuffer.allocateDirect(bufferBytes);
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
    return channel != null && channel.isOpen();
  }

  /** Total bytes read since construction. */
  public long bytesRead() {
    return bytesRead;
  }

  /** Connects and sends the session's Logon. */
  public void connect() {
    if (session == null) {
      throw new IllegalStateException("attach a session before connecting");
    }
    try {
      channel = SocketChannel.open(new InetSocketAddress(host, port));
      channel.socket().setTcpNoDelay(true);
      channel.configureBlocking(false);
    } catch (IOException e) {
      throw new UncheckedIOException("cannot connect to " + host + ":" + port, e);
    }
    inBytes.clear();
    session.onConnected();
  }

  /** Reads and dispatches what is available, then runs the session timers. Returns messages. */
  public int poll() {
    int messages = 0;
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
      final int length = FixFramer.frame(in, start, limit - start);
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
    try {
      while (outBytes.hasRemaining()) {
        channel.write(outBytes);
      }
    } catch (IOException e) {
      disconnect();
    }
  }

  @Override
  public void disconnect() {
    if (channel != null && channel.isOpen()) {
      try {
        channel.close();
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
    if (session != null) {
      session.onDisconnected();
    }
  }

  @Override
  public void close() {
    disconnect();
  }
}
