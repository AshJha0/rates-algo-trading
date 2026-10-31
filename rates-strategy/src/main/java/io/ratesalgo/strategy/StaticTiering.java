package io.ratesalgo.strategy;

/**
 * The baseline tier rule of the back-test strategies: {@code tier = 1 + (clientId - 1) /
 * clientsPerTier}, the table the synthetic market was generated with.
 */
public final class StaticTiering implements ClientTiering {

  private final int clientsPerTier;

  public StaticTiering(final int clientsPerTier) {
    if (clientsPerTier <= 0) {
      throw new IllegalArgumentException("clientsPerTier must be positive: " + clientsPerTier);
    }
    this.clientsPerTier = clientsPerTier;
  }

  @Override
  public int tier(final int clientId) {
    return 1 + (clientId - 1) / clientsPerTier;
  }
}
