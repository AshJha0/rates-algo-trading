package io.ratesalgo.venues.clob;

import io.ratesalgo.venues.sim.Json;
import io.ratesalgo.venues.sim.Presets;
import io.ratesalgo.venues.sim.Px;
import java.util.Map;

/**
 * CLOB parameters.
 *
 * @param tickSize price increment, mantissa
 * @param minQty minimum order size and size increment, currency units
 * @param workupNanos workup duration after a trade; 0 disables workup
 * @param exclusiveNanos exclusive window at the start of a workup for the trade's participants
 * @param extendOnTrade whether each workup trade restarts the workup timer
 * @param selfMatch self-match prevention mode
 * @param depth levels published on market data
 */
public record ClobConfig(
    long tickSize,
    long minQty,
    long workupNanos,
    long exclusiveNanos,
    boolean extendOnTrade,
    SelfMatch selfMatch,
    int depth) {

  /** Validates. */
  public ClobConfig {
    if (tickSize <= 0 || minQty <= 0 || depth <= 0 || exclusiveNanos > workupNanos) {
      throw new IllegalArgumentException("bad CLOB configuration");
    }
  }

  /** Loads a preset. */
  public static ClobConfig preset(final String name) {
    final Map<String, Object> p = Presets.get("clob", name);
    return new ClobConfig(
        Px.price(Json.str(p, "tickSize")),
        Px.qty(Json.str(p, "minQty")),
        Json.num(p, "workupMillis").longValue() * 1_000_000L,
        Json.num(p, "exclusiveMillis").longValue() * 1_000_000L,
        Boolean.TRUE.equals(p.get("extendOnTrade")),
        SelfMatch.valueOf(Json.str(p, "selfMatch")),
        Json.num(p, "depth").intValue());
  }

  /** The default preset. */
  public static ClobConfig defaults() {
    return preset("default");
  }

  /** The same configuration with workup turned off. */
  public ClobConfig withoutWorkup() {
    return new ClobConfig(tickSize, minQty, 0, 0, false, selfMatch, depth);
  }
}
