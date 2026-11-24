package io.ratesalgo.core;

import org.agrona.DirectBuffer;

/**
 * Stage stamps on the loop thread for the wired-desk benchmark (docs/BENCHMARKS_V2.md): an inbound
 * message read from its ring, appended to the inbound journal, and handled (handler returned,
 * outbound published). Off unless the JVM starts with {@code -Drates.probes=true}; the flag is a
 * constant, so with it off the JIT removes the calls and the loop pays nothing.
 */
@FunctionalInterface
public interface LoopProbe {

  /** True when the JVM was started with {@code -Drates.probes=true}. */
  boolean ENABLED = Boolean.getBoolean("rates.probes");

  /** The message was read from its inbound ring and checked. */
  int READ = 0;

  /** The message was appended to the inbound journal (or its ring, in ring mode). */
  int JOURNALED = 1;

  /** The message's handler returned; whatever it published is in the outbound rings. */
  int HANDLED = 2;

  /** Does nothing. */
  LoopProbe NONE = (stage, templateId, buffer, offset) -> {};

  /**
   * One stage of one inbound message, on the loop thread. Must not allocate or block.
   *
   * @param buffer the message, SBE header first, at {@code offset}
   */
  void onStage(int stage, int templateId, DirectBuffer buffer, int offset);

  /** Installs the probe every loop calls; only takes effect when {@link #ENABLED}. */
  static void install(final LoopProbe probe) {
    Installed.probe = probe;
  }

  /** The installed probe. */
  final class Installed {
    static volatile LoopProbe probe = NONE;

    private Installed() {}
  }
}
