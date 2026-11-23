package io.ratesalgo.app.backtest;

import io.ratesalgo.app.loop.DeskSettings;
import io.ratesalgo.app.pricing.UsdUniverse;
import io.ratesalgo.refdata.RefData;
import io.ratesalgo.sim.MarketSpec;
import io.ratesalgo.sim.SimInstrument;
import io.ratesalgo.venues.Venues;
import io.ratesalgo.venues.sim.Instrument;
import io.ratesalgo.venues.sim.SimV2Session;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * How a simulator v2 session's ids map onto the desk's (docs/research/SIM_V2_DESIGN.md section 14,
 * D3 and D12).
 *
 * <p>Instruments: the model's universe carries its own ids (101 to 107 swaps, 201 to 204
 * Treasuries, 301 TY); the desk knows the reference data's. The venue simulator maps one onto the
 * other with {@link SimV2Session#instruments}, and this binding uses the same mapping, so the
 * back-tested desk sees the instrument ids it would see on the simulated venues. Model
 * instruments the desk's universe does not hold map to 0 and their messages are not shown to it:
 * with {@link UsdUniverse#standard()} the 3, 7 and 20-year swaps and TY; with {@link
 * UsdUniverse#simV2()} none. An RFQ on such an instrument is a structural decline, which the
 * desk strategy counts.
 *
 * <p>Clients: the model draws a tier per RFQ and the back-test journal carries tier + 1 as the
 * client id. The desk recognises clients by id, so each tier is given the id the simulator's D2C
 * venue sends for it on the wire, {@link DeskSettings#v2ClientId}: one source for the back-test,
 * the wired desk and the desk's tier table.
 */
public final class SimBinding {

  /** Desk client id per tier (index 0 is tier A): the ids the simulator sends on the wire. */
  public static final List<Integer> DEFAULT_CLIENT_IDS = clientIds();

  private final MarketSpec spec;
  private final Map<Integer, Integer> deskOfSim;
  private final Map<Integer, Integer> simOfDesk;
  private final List<Integer> clientIds;

  private SimBinding(
      final MarketSpec spec, final Map<Integer, Integer> deskOfSim, final Map<Integer, Integer> simOfDesk,
      final List<Integer> clientIds) {
    this.spec = spec;
    this.deskOfSim = Map.copyOf(deskOfSim);
    this.simOfDesk = Map.copyOf(simOfDesk);
    this.clientIds = List.copyOf(clientIds);
  }

  private static List<Integer> clientIds() {
    final List<Integer> out = new ArrayList<>();
    for (int t = 0; t < DeskSettings.V2_TIERS.size(); t++) {
      out.add(DeskSettings.v2ClientId(t));
    }
    return List.copyOf(out);
  }

  /** The binding of a market specification's universe to a desk universe. */
  public static SimBinding of(final MarketSpec spec, final UsdUniverse universe) {
    final List<Instrument> venue = SimV2Session.instruments(spec, RefData.loadDefault(), Venues.TRADE_DATE);
    final Set<Integer> deskIds = new HashSet<>();
    for (final int id : universe.instrumentIds()) {
      deskIds.add(id);
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
    return new SimBinding(spec, deskOfSim, simOfDesk, DEFAULT_CLIENT_IDS);
  }

  /** The same binding with another desk client id per tier (A, B, C). */
  public SimBinding withClientIds(final List<Integer> perTier) {
    if (perTier.size() != DEFAULT_CLIENT_IDS.size()) {
      throw new IllegalArgumentException("one client id per tier is needed: " + perTier);
    }
    return new SimBinding(spec, deskOfSim, simOfDesk, perTier);
  }

  /** The market specification the binding was made for. */
  public MarketSpec spec() {
    return spec;
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

  /** The model instrument with this model id. */
  public SimInstrument simInstrument(final int simId) {
    for (int i = 0; i < spec.instruments(); i++) {
      if (spec.instrument(i).id() == simId) {
        return spec.instrument(i);
      }
    }
    throw new IllegalArgumentException("no model instrument " + simId);
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
