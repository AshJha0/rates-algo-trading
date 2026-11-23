package io.ratesalgo.fix.net;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.SocketChannel;

/**
 * The one piece of a {@link TcpInitiator} another thread may touch: the current socket, published
 * so that it can be closed from outside when the owning thread is stuck or dead. Everything else in
 * the initiator stays single-threaded.
 */
final class SharedChannel {

  private volatile SocketChannel channel;

  /** Owner thread: publishes the channel of a new connection. */
  void set(final SocketChannel current) {
    channel = current;
  }

  /** Any thread: closes the published channel if it is open. */
  void close() {
    final SocketChannel c = channel;
    if (c != null && c.isOpen()) {
      try {
        c.close();
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
  }
}
