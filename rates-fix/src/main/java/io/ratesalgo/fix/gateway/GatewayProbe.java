package io.ratesalgo.fix.gateway;

/**
 * Stage stamps on a session's thread for the wired-desk benchmark (docs/BENCHMARKS_V2.md): a
 * QuoteRequest translated and handed to the loop's ring, and the Quote that answers it taken from
 * the outbound ring, encoded and written to the socket. Off unless the JVM starts with {@code
 * -Drates.probes=true}; the flag is a constant, so with it off the JIT removes the calls.
 */
@FunctionalInterface
public interface GatewayProbe {

  /** True when the JVM was started with {@code -Drates.probes=true}. */
  boolean ENABLED = Boolean.getBoolean("rates.probes");

  /** A QuoteRequest was translated to SBE and offered to the loop. */
  int REQUEST_PUBLISHED = 0;

  /** A Quote was taken from the outbound ring. */
  int QUOTE_DEQUEUED = 1;

  /** The Quote's fields are encoded; what is left is finishing and writing it. */
  int QUOTE_ENCODED = 2;

  /** The Quote's bytes were written to the socket (or handed to TLS and written). */
  int QUOTE_WRITTEN = 3;

  /** Does nothing. */
  GatewayProbe NONE = (stage, rfqId) -> {};

  /** One stage for RFQ {@code rfqId}, on the session's thread. Must not allocate or block. */
  void onStage(int stage, long rfqId);

  /** Installs the probe every gateway calls; only takes effect when {@link #ENABLED}. */
  static void install(final GatewayProbe probe) {
    Installed.probe = probe;
  }

  /** The installed probe. */
  final class Installed {
    static volatile GatewayProbe probe = NONE;

    private Installed() {}
  }
}
