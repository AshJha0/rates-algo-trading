package io.ratesalgo.fix.session;

import org.agrona.DirectBuffer;

/** Where a session writes its bytes. Implementations must not retain the buffer. */
public interface FixTransport {

  /** Sends one complete message. */
  void send(DirectBuffer buffer, int offset, int length);

  /** Closes the connection; the session is told through {@link FixSession#onDisconnected()}. */
  void disconnect();
}
