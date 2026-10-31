package io.ratesalgo.venues.feed;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import org.agrona.DirectBuffer;

/** Publishes each frame as one UDP datagram, for the standalone CLOB process. */
public final class UdpFeedChannel implements FeedChannel, AutoCloseable {

  private final DatagramChannel channel;
  private final InetSocketAddress target;
  private final ByteBuffer out = ByteBuffer.allocateDirect(2048);

  /** Opens a channel sending to {@code host:port}. */
  public UdpFeedChannel(final String host, final int port) {
    try {
      this.channel = DatagramChannel.open();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    this.target = new InetSocketAddress(host, port);
  }

  @Override
  public void publish(final DirectBuffer buffer, final int offset, final int length) {
    out.clear();
    buffer.getBytes(offset, out, length);
    out.flip();
    try {
      channel.send(out, target);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Override
  public void close() {
    try {
      channel.close();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
