package io.ratesalgo.app.loop;

import io.ratesalgo.venues.clob.ClobSimulator;

/**
 * The identifiers the process assembly fixes (API_SPEC.md Section 18.2): venue ids on the wire,
 * outbound gateway ids in every loop, inbound GatewayStatus ids, heartbeat sources and the client
 * order id ranges that tell the router's children from other orders.
 */
public final class AppIds {

  /** Venue id the D2C RFQ gateway stamps on its SBE messages. */
  public static final int D2C_VENUE = 101;

  /** Venue id of the CLOB: its FIX gateway and its SBE book feed use the same id. */
  public static final int CLOB_VENUE = ClobSimulator.VENUE_ID;

  /** Outbound gateway id of the D2C session in every loop (the first ring added). */
  public static final int D2C_GATEWAY = 0;

  /** Outbound gateway id of the CLOB session. */
  public static final int CLOB_GATEWAY = 1;

  /** Outbound gateway id of the control ring (KillSwitch, RiskLimitBreach). */
  public static final int CONTROL_GATEWAY = 2;

  /** Number of outbound rings per loop. */
  public static final int OUTBOUND_RINGS = 3;

  /** GatewayStatus id for the CLOB book feed: CONNECTED after a snapshot rebuild clears a gap. */
  public static final int CLOB_FEED_STATUS = 3;

  /**
   * Venue id of the second Treasury CLOB of the v2 desk profile (simulator v2 shows shares of one
   * book on two venues, SIM_V2_DESIGN.md D10).
   */
  public static final int CLOB2_VENUE = CLOB_VENUE + 1;

  /** Venue id of the futures order book of the v2 desk profile. */
  public static final int FUTURES_VENUE = CLOB_VENUE + 2;

  /**
   * Outbound gateway id of the second CLOB session. Gateway ids and feed status ids share the
   * GatewayStatus id space, and 3 is the first CLOB's feed, so the v2 sessions start at 4; the
   * v2 loop's outbound ring 3 exists and is never written.
   */
  public static final int CLOB2_GATEWAY = 4;

  /** Outbound gateway id of the futures session. */
  public static final int FUTURES_GATEWAY = 5;

  /** Number of outbound rings per loop in the v2 desk profile. */
  public static final int V2_OUTBOUND_RINGS = 6;

  /** GatewayStatus id for the second CLOB's book feed. */
  public static final int CLOB2_FEED_STATUS = 6;

  /** GatewayStatus id for the futures book feed. */
  public static final int FUTURES_FEED_STATUS = 7;

  /** Instrument id of the 1-year SOFR swap of the v2 universe: priced and risk-managed, listed on no venue. */
  public static final int SWAP_1Y = io.ratesalgo.app.pricing.UsdUniverse.SWAP_1Y_ID;

  /** Heartbeat source id of the curve feed; input health requires it. */
  public static final int CURVE_HEARTBEAT_SOURCE = 900;

  /** CurveInput curve id of the USD SOFR OIS curve. */
  public static final int SOFR_CURVE = 1;

  /** First router child order id; router {@code j} owns ids from {@code ROUTER_BASE + (j << 40)}. */
  public static final long ROUTER_BASE = 1L << 52;

  /** Set on the ClOrdID of a cancel request; the child's id is the rest of the bits. */
  public static final long CANCEL_FLAG = 1L << 61;

  /** Engine CompID of this desk on every venue. */
  public static final String COMP_ID = "RALGO";

  private AppIds() {}

  /** Outbound gateway id for a venue id, or -1. */
  public static int gatewayFor(final int venueId) {
    if (venueId == D2C_VENUE) {
      return D2C_GATEWAY;
    }
    if (venueId == CLOB_VENUE) {
      return CLOB_GATEWAY;
    }
    if (venueId == CLOB2_VENUE) {
      return CLOB2_GATEWAY;
    }
    if (venueId == FUTURES_VENUE) {
      return FUTURES_GATEWAY;
    }
    return -1;
  }

  /** True for a router child order id or a cancel request for one. */
  public static boolean isRouterOrder(final long clOrdId) {
    return (clOrdId & ~CANCEL_FLAG) >= ROUTER_BASE;
  }

  /** Index of the router that owns a child order id. */
  public static int routerOf(final long clOrdId) {
    return (int) (((clOrdId & ~CANCEL_FLAG) - ROUTER_BASE) >>> 40);
  }
}
