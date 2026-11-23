package io.ratesalgo.fix.session;

/**
 * Where a {@link FixSession} records its two sequence numbers so that a restarted process can
 * resume the session instead of resetting it. Called on the session's thread each time either
 * number has changed; an implementation must not allocate or block.
 */
@FunctionalInterface
public interface SequenceStore {

  /** Records nothing. */
  SequenceStore NONE = (o, i) -> {};

  /**
   * Records the current numbers.
   *
   * @param nextOutSeq sequence number our next outbound message will carry
   * @param expectedInSeq sequence number expected on the next inbound message
   */
  void record(long nextOutSeq, long expectedInSeq);
}
