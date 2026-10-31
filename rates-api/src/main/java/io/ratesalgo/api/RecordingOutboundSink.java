package io.ratesalgo.api;

import io.ratesalgo.messages.sbe.MessageHeaderDecoder;
import java.util.ArrayList;
import java.util.List;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;

/**
 * Test stub sink that keeps a copy of every message. It allocates per message and is not for
 * allocation tests. An optional listener sees each message as it arrives, which is how the chaos
 * and million-RFQ tests audit what reaches the gateway.
 */
public final class RecordingOutboundSink implements OutboundSink {

  /** Sees each message as it is sent. */
  @FunctionalInterface
  public interface Listener {
    /** Called with the copied message. */
    void onMessage(int templateId, DirectBuffer buffer, int offset, int length);
  }

  private final MessageHeaderDecoder header = new MessageHeaderDecoder();
  private final List<UnsafeBuffer> messages = new ArrayList<>();
  private final List<Integer> templateIds = new ArrayList<>();
  private Listener listener;
  private boolean keep = true;

  /** Installs a listener (null removes it). */
  public void listener(final Listener newListener) {
    this.listener = newListener;
  }

  /** Whether to keep copies; the million-RFQ test only listens. */
  public void keep(final boolean value) {
    keep = value;
  }

  @Override
  public void send(final DirectBuffer buffer, final int offset, final int length) {
    header.wrap(buffer, offset);
    final int templateId = header.templateId();
    if (keep) {
      final byte[] copy = new byte[length];
      buffer.getBytes(offset, copy);
      messages.add(new UnsafeBuffer(copy));
      templateIds.add(templateId);
    }
    if (listener != null) {
      listener.onMessage(templateId, buffer, offset, length);
    }
  }

  /** Number of messages kept. */
  public int size() {
    return messages.size();
  }

  /** The {@code i}th kept message, header included, starting at offset 0. */
  public DirectBuffer message(final int i) {
    return messages.get(i);
  }

  /** Template id of the {@code i}th kept message. */
  public int templateId(final int i) {
    return templateIds.get(i);
  }

  /** Number of kept messages with this template id. */
  public int count(final int templateId) {
    int n = 0;
    for (final int t : templateIds) {
      if (t == templateId) {
        n++;
      }
    }
    return n;
  }

  /** Forgets every kept message. */
  public void clear() {
    messages.clear();
    templateIds.clear();
  }
}
