package io.ratesalgo.venues.d2c;

import java.util.List;

/**
 * A scripted RFQ.
 *
 * @param securityId instrument
 * @param buy true if the client buys (pays fixed for swaps)
 * @param quantity size in currency units
 * @param dealerCount dealers in competition including the engine dealers
 * @param competitorPrices prices on the client's side from simulated dealers, in arrival order
 * @param clientId client party id
 * @param decision what the client does
 * @param informed whether the client is informed
 */
public record RfqSpec(
    String securityId,
    boolean buy,
    long quantity,
    int dealerCount,
    List<Long> competitorPrices,
    int clientId,
    ClientDecision decision,
    boolean informed) {

  /** Copies the list. */
  public RfqSpec {
    competitorPrices = List.copyOf(competitorPrices);
  }
}
