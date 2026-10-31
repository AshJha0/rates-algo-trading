package io.ratesalgo.core;

import org.agrona.DirectBuffer;

/**
 * Handles one inbound message of a registered template id, on the loop thread.
 *
 * <p>The buffer is a view of the ring buffer (live) or the journal (replay) and is valid only for
 * the call: copy what must outlive it. {@code offset} is the start of the SBE message header and
 * {@code length} counts header and body. A handler must not allocate after warm-up, must read time
 * only from {@link EventLoop#clock()}, and must send output only through {@link
 * EventLoop#publish}; then live and replay produce the same outbound bytes.
 */
@FunctionalInterface
public interface MessageHandler {

  /**
   * Called once per inbound message, in ring order.
   *
   * @param templateId the SBE template id from the message header
   * @param buffer the buffer holding the message
   * @param offset offset of the SBE message header in {@code buffer}
   * @param length bytes of header plus body
   */
  void onMessage(int templateId, DirectBuffer buffer, int offset, int length);
}
