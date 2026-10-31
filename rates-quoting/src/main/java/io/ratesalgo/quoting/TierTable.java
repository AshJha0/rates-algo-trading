package io.ratesalgo.quoting;

import java.util.List;
import java.util.Map;
import org.agrona.collections.Int2IntHashMap;

/**
 * A versioned, immutable client tier table. A new version is proposed and approved through {@link
 * TierTableControl}; the engine reads whichever version is active. Lookups do not allocate.
 *
 * <p>Blocking a tier is off in every default this class builds (P&L audit item 5): at the engine's
 * spreads, declining informed flow lost more than it saved (AUDIT-02 seq 9), so toxic tiers are
 * priced wider instead of refused. {@link #standard} widens tiers 2 and 3, and {@link #unblocked}
 * turns any blocked tier of a table into a widened one.
 */
public final class TierTable {

  /** Multiplier {@link #unblocked} is meant to apply on top of a blocked tier's own. */
  public static final double DEFAULT_BLOCK_WIDENING = 1.5;

  // spread multipliers of standard(): tier 1, 2 and 3
  private static final double[] STANDARD_MULTIPLIERS = {1.0, 1.15, 1.35};

  private final long version;
  private final ClientTier[] tiers;
  private final Int2IntHashMap clientToTier;
  private final int defaultTier;
  private final Map<Integer, Integer> clients;

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
    this.clients = Map.copyOf(clients);
  }

  /**
   * The default three-tier table: firm quotes, nothing blocked, tier 2 and 3 widened by {@link
   * #standardMultiplier}; every client gets tier 2 unless {@code clients} maps it (to position 0,
   * 1 or 2).
   */
  public static TierTable standard(
      final long version, final long maxQuantity, final Map<Integer, Integer> clients) {
    final List<ClientTier> tiers =
        List.of(
            new ClientTier(1, STANDARD_MULTIPLIERS[0], maxQuantity, true, false),
            new ClientTier(2, STANDARD_MULTIPLIERS[1], maxQuantity, true, false),
            new ClientTier(3, STANDARD_MULTIPLIERS[2], maxQuantity, true, false));
    return new TierTable(version, tiers, clients, 1);
  }

  /**
   * A copy at {@code version} in which every blocked tier quotes instead, with its spread
   * multiplier times {@code widening} (at least 1).
   */
  public TierTable unblocked(final long newVersion, final double widening) {
    if (!(widening >= 1.0) || Double.isInfinite(widening)) {
      throw new IllegalArgumentException("widening " + widening);
    }
    final ClientTier[] out = new ClientTier[tiers.length];
    for (int i = 0; i < tiers.length; i++) {
      final ClientTier t = tiers[i];
      out[i] =
          t.blocked()
              ? new ClientTier(t.tier(), t.spreadMultiplier() * widening, t.maxQuantity(), t.firm(), false)
              : t;
    }
    return new TierTable(newVersion, List.of(out), clients, defaultTier);
  }

  /** Spread multiplier of tier {@code tier} (1 to 3) in {@link #standard}. */
  public static double standardMultiplier(final int tier) {
    return STANDARD_MULTIPLIERS[tier - 1];
  }

  /** Number of tiers that refuse every quote. */
  public int blockedTiers() {
    int n = 0;
    for (final ClientTier t : tiers) {
      if (t.blocked()) {
        n++;
      }
    }
    return n;
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
