package io.ratesalgo.app.backtest;

import io.ratesalgo.app.loop.DeskSettings;
import io.ratesalgo.app.pricing.UsdUniverse;
import io.ratesalgo.refdata.RefData;
import io.ratesalgo.sim.MarketSpec;
import io.ratesalgo.venues.Venues;
import io.ratesalgo.venues.sim.Instrument;
import io.ratesalgo.venues.sim.SimV2Session;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * How a simulator v2 session's ids map onto the desk's (docs/research/SIM_V2_DESIGN.md section 14,
 * D3 and D12).
 *
 * <p>Instruments: the model's universe carries its own ids (101 to 107 swaps, 201 to 204
 * Treasuries, 301 TY); the desk knows the reference data's. The venue simulator maps one onto the
 * other with {@link SimV2Session#instruments}, and this binding uses the same mapping, so the
 * back-tested desk sees the instrument ids it would see on the simulated venues. Model
 * instruments the desk's universe does not hold (three swap tenors without a reference-data
 * template, and the future) map to 0: their messages are not shown to the desk.
 *
 * <p>Clients: the model draws a tier per RFQ and the back-test journal carries tier + 1 as the
 * client id. The desk recognises clients by id, so each tier is given one fixed desk client id.
 * The default map sends tiers A, B and C to 2001, 2003 and 2005, ids that {@link
 * DeskSettings#V2_CLIENT_TIERS} places in tiers A, B and C. It is configuration: {@link
 * #withClientIds} replaces it.
 */
public final class SimBinding {

  /** Default desk client id per tier (index 0 is tier A). */
  public static final List<Integer> DEFAULT_CLIENT_IDS = List.of(2001, 2003, 2005);

  private final Map<Integer, Integer> deskOfSim;
  private final Map<Integer, Integer> simOfDesk;
  private final List<Integer> clientIds;

  private SimBinding(
      final Map<Integer, Integer> deskOfSim, final Map<Integer, Integer> simOfDesk, final List<Integer> clientIds) {
    this.deskOfSim = Map.copyOf(deskOfSim);
    this.simOfDesk = Map.copyOf(simOfDesk);
    this.clientIds = List.copyOf(clientIds);
  }

  /** The binding of a market specification's universe to a desk universe. */
  public static SimBinding of(final MarketSpec spec, final UsdUniverse universe) {
    final List<Instrument> venue = SimV2Session.instruments(spec, RefData.loadDefault(), Venues.TRADE_DATE);
    final java.util.Set<Integer> deskIds = new java.util.HashSet<>();
    for (final UsdUniverse.SwapSpec s : universe.swaps()) {
      deskIds.add(s.instrumentId());
    }
    for (final UsdUniverse.BondSpec b : universe.bonds()) {
      deskIds.add(b.instrumentId());
    }
    final Map<Integer, Integer> deskOfSim = new HashMap<>();
    final Map<Integer, Integer> simOfDesk = new HashMap<>();
    for (int i = 0; i < spec.instruments(); i++) {
      final int desk = venue.get(i).instrumentId();
      if (deskIds.contains(desk)) {
        deskOfSim.put(spec.instrument(i).id(), desk);
        simOfDesk.put(desk, spec.instrument(i).id());
      }
    }
    return new SimBinding(deskOfSim, simOfDesk, DEFAULT_CLIENT_IDS);
  }

  /** The same binding with another desk client id per tier (A, B, C). */
  public SimBinding withClientIds(final List<Integer> perTier) {
    if (perTier.size() != DEFAULT_CLIENT_IDS.size()) {
      throw new IllegalArgumentException("one client id per tier is needed: " + perTier);
    }
    return new SimBinding(deskOfSim, simOfDesk, perTier);
  }

  /** The desk's id of a model instrument, 0 when the desk does not hold it. */
  public int deskId(final int simId) {
    return deskOfSim.getOrDefault(simId, 0);
  }

  /** The model's id of a desk instrument. */
  public int simId(final int deskId) {
    final Integer sim = simOfDesk.get(deskId);
    if (sim == null) {
      throw new IllegalArgumentException("desk instrument " + deskId + " is not in the simulator's universe");
    }
    return sim;
  }

  /** The desk client id of the back-test journal's client id (tier + 1). */
  public int clientIdOfTier(final int journalClientId) {
    if (journalClientId < 1 || journalClientId > clientIds.size()) {
      throw new IllegalArgumentException("journal client id " + journalClientId + " is not a tier (1 to 3)");
    }
    return clientIds.get(journalClientId - 1);
  }

  /** Model instruments the desk is shown. */
  public int boundInstruments() {
    return deskOfSim.size();
  }
}
