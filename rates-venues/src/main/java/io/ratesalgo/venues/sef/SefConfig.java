package io.ratesalgo.venues.sef;

import io.ratesalgo.venues.clob.ClobConfig;
import io.ratesalgo.venues.clob.SelfMatch;
import io.ratesalgo.venues.sim.Json;
import io.ratesalgo.venues.sim.Presets;
import io.ratesalgo.venues.sim.Px;
import java.util.HashMap;
import java.util.Map;

/**
 * SEF parameters.
 *
 * @param requiredMinDealers least dealers on an RFQ for a Required Transaction below block size
 * @param responseWindowNanos RFQ response window
 * @param book order book parameters (no workup)
 * @param fcmLimits FCM to notional limit in currency units
 * @param partyFcm party (CompID or client id) to FCM
 */
public record SefConfig(
    int requiredMinDealers,
    long responseWindowNanos,
    ClobConfig book,
    Map<String, Long> fcmLimits,
    Map<String, String> partyFcm) {

  /** Copies the maps. */
  public SefConfig {
    fcmLimits = Map.copyOf(fcmLimits);
    partyFcm = Map.copyOf(partyFcm);
  }

  /** Loads a preset. */
  public static SefConfig preset(final String name) {
    final Map<String, Object> p = Presets.get("sef", name);
    final Map<String, Long> limits = new HashMap<>();
    for (final Map.Entry<String, Object> e : Json.obj(p, "fcmLimits").entrySet()) {
      limits.put(e.getKey(), Px.qty(e.getValue().toString()));
    }
    final Map<String, String> parties = new HashMap<>();
    for (final Map.Entry<String, Object> e : Json.obj(p, "partyFcm").entrySet()) {
      parties.put(e.getKey(), e.getValue().toString());
    }
    return new SefConfig(
        Json.num(p, "requiredMinDealers").intValue(),
        Json.num(p, "responseWindowMillis").longValue() * 1_000_000L,
        new ClobConfig(Px.price(Json.str(p, "tickSize")), Px.qty(Json.str(p, "minQty")), 0, 0, false,
            SelfMatch.CANCEL_RESTING, 5),
        limits,
        parties);
  }

  /** The default preset. */
  public static SefConfig defaults() {
    return preset("default");
  }

  /** The same configuration with one FCM limit changed. */
  public SefConfig withLimit(final String fcm, final long limit) {
    final Map<String, Long> m = new HashMap<>(fcmLimits);
    m.put(fcm, limit);
    return new SefConfig(requiredMinDealers, responseWindowNanos, book, m, partyFcm);
  }
}
