package io.ratesalgo.venues.sef;

import java.util.HashMap;
import java.util.Map;

/**
 * A simulated credit hub: each party clears through one FCM, each FCM grants a notional limit, and
 * a trade or an order is accepted only if the party's FCM has room (CFTC Rule 1.73 pre-execution
 * screening, as a SEF credit hub presents it). Usage counts reservations for live orders and
 * executed notional.
 */
public final class FcmLimitHub {

  private final Map<String, Long> limits;
  private final Map<String, String> partyFcm;
  private final Map<String, Long> used = new HashMap<>();
  private long rejections;

  /** Creates a hub from FCM limits and the party-to-FCM mapping. */
  public FcmLimitHub(final Map<String, Long> limits, final Map<String, String> partyFcm) {
    this.limits = Map.copyOf(limits);
    this.partyFcm = Map.copyOf(partyFcm);
  }

  /**
   * Reserves notional for a party. Returns null if accepted, otherwise the reason (no FCM, or
   * not enough limit), and counts the rejection.
   */
  public String reserve(final String party, final long notional) {
    final String fcm = partyFcm.get(party);
    if (fcm == null || !limits.containsKey(fcm)) {
      rejections++;
      return "no clearing certainty: party " + party + " has no FCM";
    }
    final long u = used.getOrDefault(fcm, 0L);
    if (u + notional > limits.get(fcm)) {
      rejections++;
      return "no clearing certainty: " + fcm + " limit " + limits.get(fcm) + " used " + u + " requested " + notional;
    }
    used.put(fcm, u + notional);
    return null;
  }

  /** Releases a reservation. */
  public void release(final String party, final long notional) {
    final String fcm = partyFcm.get(party);
    if (fcm != null && notional > 0) {
      used.merge(fcm, -notional, Long::sum);
    }
  }

  /** Notional in use at an FCM. */
  public long used(final String fcm) {
    return used.getOrDefault(fcm, 0L);
  }

  /** Checks refused so far. */
  public long rejections() {
    return rejections;
  }
}
