package io.ratesalgo.quoting;

import java.util.List;
import java.util.Map;
import org.agrona.collections.Int2IntHashMap;

/**
 * A versioned, immutable client tier table. A new version is proposed and approved through {@link
 * TierTableControl}; the engine reads whichever version is active. Lookups do not allocate.
 */
public final class TierTable {

  private final long version;
  private final ClientTier[] tiers;
  private final Int2IntHashMap clientToTier;
  private final int defaultTier;

  /**
   * A table. {@code tiers} are indexed by position; {@code clients} maps client id to a position;
   * clients not listed get {@code defaultTierIndex}.
   */
  public TierTable(
      final long version,
      final List<ClientTier> tiers,
      final Map<Integer, Integer> clients,
      final int defaultTierIndex) {
    if (tiers.isEmpty() || defaultTierIndex < 0 || defaultTierIndex >= tiers.size()) {
      throw new IllegalArgumentException("tier table needs tiers and a valid default");
    }
    this.version = version;
    this.tiers = tiers.toArray(new ClientTier[0]);
    this.clientToTier = new Int2IntHashMap(Math.max(16, clients.size() * 2), 0.5f, -1);
    for (final Map.Entry<Integer, Integer> e : clients.entrySet()) {
      final int t = e.getValue();
      if (t < 0 || t >= this.tiers.length) {
        throw new IllegalArgumentException("client " + e.getKey() + " mapped to tier " + t);
      }
      clientToTier.put(e.getKey().intValue(), t);
    }
    this.defaultTier = defaultTierIndex;
  }

  /** A single-tier table: every client gets {@code tier}. */
  public static TierTable single(final long version, final ClientTier tier) {
    return new TierTable(version, List.of(tier), Map.of(), 0);
  }

  /** The tier of a client. */
  public ClientTier tierOf(final int clientId) {
    final int t = clientToTier.get(clientId);
    return tiers[t < 0 ? defaultTier : t];
  }

  public long version() {
    return version;
  }
}
