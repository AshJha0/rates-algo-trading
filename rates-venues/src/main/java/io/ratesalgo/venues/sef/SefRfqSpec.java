package io.ratesalgo.venues.sef;

import java.util.List;

/**
 * A scripted SEF RFQ from a simulated client.
 *
 * @param securityId swap template code
 * @param buy true if the client pays fixed
 * @param quantity notional in currency units
 * @param dealerCount dealers the client asks, engine dealers included
 * @param clientId client party id
 * @param competitorRates simulated dealers' rates on the client's side
 * @param accept true if the client accepts the best rate, false to pass
 */
public record SefRfqSpec(
    String securityId,
    boolean buy,
    long quantity,
    int dealerCount,
    int clientId,
    List<Long> competitorRates,
    boolean accept) {

  /** Copies the list. */
  public SefRfqSpec {
    competitorRates = List.copyOf(competitorRates);
  }
}
