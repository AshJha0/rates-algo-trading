package io.ratesalgo.fix.gateway;

/** Producer of SBE-encoded messages, polled by its owner (for example a ring-buffer reader). */
@FunctionalInterface
public interface MessageSource {

  /** Delivers up to {@code limit} messages to the sink; returns how many were delivered. */
  int poll(MessageSink sink, int limit);
}
