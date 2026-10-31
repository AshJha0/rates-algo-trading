package io.ratesalgo.strategy.report;

import io.ratesalgo.backtest.BacktestResult;
import io.ratesalgo.backtest.FillRecord;
import io.ratesalgo.backtest.Instrument;
import io.ratesalgo.backtest.InstrumentSet;
import io.ratesalgo.backtest.RfqRecord;
import io.ratesalgo.backtest.pnl.Attribution;
import io.ratesalgo.strategy.RiskLadder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The desk view of one back-test session (feature 7; API_SPEC.md Section 19.10): P&amp;L per DV01
 * traded, the capture-versus-cost waterfall, hit rate by tier and tenor, inventory half-life,
 * hedge ratio and the metrics the desk-feature experiments test.
 *
 * <p>Definitions (all in major currency unless stated):
 *
 * <pre>
 *   clientDv01      = sum over client fills of |q * dv01PerUnit|
 *   adverse         = sum over client fills of q * (mid(t + h) - mid(t)) * v,  h the markout horizon
 *   waterfall       = spread capture + adverse + other inventory + hedge cost + carry + funding + fees
 *                   = position P&amp;L - residual,  other inventory = inventory MTM - adverse
 *   hedgeSlippage   = hedge cost + hedge fees
 *   riskCharge      = gamma / 2 * sigma^2 * integral of netDv01(t)^2 dt          (sigma in bp per sqrt(s))
 *   riskAdjHedge    = hedgeSlippage - riskCharge
 *   bsCharge        = integral of (bondBs * |bond face| + swapBs * |swap notional|) dt / 360 days
 *   fundingNet      = funding + carry - bsCharge
 *   halfLife        = median over client fills of the time until |netDv01| first falls to half its
 *                     level just after the fill (censored at the session end), seconds
 * </pre>
 *
 * The net DV01 path is rebuilt from the fills: valuation is linear, so net DV01 only changes when
 * a fill books.
 */
public final class DeskMetrics {

  /** Settings the metrics need. */
  public record Settings(
      double limitDv01,
      double positionLimitMm,
      int horizonIndex,
      double gamma,
      double sigmaBpPerSqrtSecond,
      double bondBsPerYear,
      double swapBsPerYear) {}

  private static final double SECONDS_PER_YEAR_360 = 360.0 * 86_400.0;

  private final Map<String, Double> values = new LinkedHashMap<>();
  private final Map<String, Map<String, double[]>> hitRates = new LinkedHashMap<>();
  private final boolean limitBreached;

  private DeskMetrics(final boolean limitBreached) {
    this.limitBreached = limitBreached;
  }

  /** Computes the metrics of one session. */
  public static DeskMetrics of(final BacktestResult r, final InstrumentSet instruments, final Settings s) {
    final List<FillRecord> fills = r.fills();
    final Attribution a = r.total();
    double clientDv01 = 0;
    double hedgeDv01 = 0;
    double adverse = 0;
    double hedgeFees = 0;
    long hedges = 0;
    final int n = fills.size();
    final long[] times = new long[n];
    final double[] netAfter = new double[n];
    final boolean[] client = new boolean[n];
    final Map<Integer, Long> pos = new TreeMap<>();
    double net = 0;
    // integrals over time
    double net2 = 0;
    double absNet = 0;
    double bsCost = 0;
    long last = r.startNanos();
    double bsRate = 0; // currency per year at the current inventory
    for (int i = 0; i < n; i++) {
      final FillRecord f = fills.get(i);
      final Instrument inst = instruments.get(f.instrumentId());
      final double dt = (f.timeNanos() - last) / 1e9;
      net2 += net * net * dt;
      absNet += Math.abs(net) * dt;
      bsCost += bsRate * dt / SECONDS_PER_YEAR_360;
      last = f.timeNanos();
      final double d = f.signedQuantity() * inst.dv01PerUnit();
      net += d;
      final long p = pos.merge(f.instrumentId(), f.signedQuantity(), Long::sum);
      bsRate = balanceSheetRate(pos, instruments, s);
      if (f.hedge()) {
        hedgeDv01 += Math.abs(d);
        hedgeFees += f.fee();
        hedges++;
      } else {
        clientDv01 += Math.abs(d);
        adverse += f.markout(s.horizonIndex()) - f.edge(inst);
      }
      times[i] = f.timeNanos();
      netAfter[i] = net;
      client[i] = !f.hedge();
      if (p == 0) {
        pos.remove(f.instrumentId());
      }
    }
    final double tail = (r.endNanos() - last) / 1e9;
    net2 += net * net * tail;
    absNet += Math.abs(net) * tail;
    bsCost += bsRate * tail / SECONDS_PER_YEAR_360;
    final double session = Math.max(1e-9, (r.endNanos() - r.startNanos()) / 1e9);

    boolean breach = r.maxAbsNetDv01() > s.limitDv01();
    for (final long q : r.maxAbsPosition().values()) {
      breach |= q / (double) InstrumentSet.MILLION > s.positionLimitMm();
    }
    final DeskMetrics m = new DeskMetrics(breach);
    final double hedgeSlippage = a.hedgeCost() - hedgeFees;
    final double riskCharge = 0.5 * s.gamma() * s.sigmaBpPerSqrtSecond() * s.sigmaBpPerSqrtSecond() * net2;
    m.put("pnl", a.positionPnl());
    m.put("client_dv01_traded", clientDv01);
    m.put("hedge_dv01_traded", hedgeDv01);
    m.put("pnl_per_dv01", clientDv01 > 0 ? a.positionPnl() / clientDv01 : 0.0);
    m.put("capture_per_dv01", clientDv01 > 0 ? a.spreadCapture() / clientDv01 : 0.0);
    m.put("hedge_ratio", clientDv01 > 0 ? hedgeDv01 / clientDv01 : 0.0);
    m.put("spread_capture", a.spreadCapture());
    m.put("adverse_selection", adverse);
    m.put("other_inventory", a.inventoryMtm() - adverse);
    m.put("hedge_cost", a.hedgeCost());
    m.put("carry", a.carryRoll());
    m.put("funding", a.funding());
    m.put("fees", a.fees());
    m.put("residual", a.residual());
    m.put("hedges", (double) hedges);
    m.put("hedge_slippage", hedgeSlippage);
    m.put("hedge_slippage_per_dv01", hedgeDv01 > 0 ? hedgeSlippage / hedgeDv01 : 0.0);
    m.put("risk_charge", riskCharge);
    m.put("risk_adjusted_hedge_cost", hedgeSlippage - riskCharge);
    m.put("mean_abs_net_dv01", absNet / session);
    m.put("max_abs_net_dv01", r.maxAbsNetDv01());
    m.put("limit_utilisation", r.maxAbsNetDv01() / s.limitDv01());
    m.put("balance_sheet_charge", -bsCost);
    m.put("funding_net", a.funding() + a.carryRoll() - bsCost);
    m.put("inventory_half_life_s", halfLife(times, netAfter, client, r.endNanos()));
    m.put("limit_breach", breach ? 1.0 : 0.0);
    m.hitRates.putAll(hitRates(r, instruments));
    m.put("adverse_tier_1", adverseByTier(r, instruments, s.horizonIndex(), 1));
    m.put("adverse_tier_2", adverseByTier(r, instruments, s.horizonIndex(), 2));
    m.put("adverse_tier_3", adverseByTier(r, instruments, s.horizonIndex(), 3));
    return m;
  }

  private static double balanceSheetRate(
      final Map<Integer, Long> pos, final InstrumentSet instruments, final Settings s) {
    double rate = 0;
    for (final Map.Entry<Integer, Long> e : pos.entrySet()) {
      final Instrument inst = instruments.get(e.getKey());
      final double face = Math.abs(e.getValue()) / 100.0;
      if (inst.kind() == Instrument.Kind.BOND) {
        rate += s.bondBsPerYear() * face;
      } else if (inst.kind() == Instrument.Kind.SWAP) {
        rate += s.swapBsPerYear() * face;
      }
    }
    return rate;
  }

  private static double adverseByTier(
      final BacktestResult r, final InstrumentSet instruments, final int horizonIndex, final int tier) {
    double sum = 0;
    for (final FillRecord f : r.fills()) {
      if (!f.hedge() && f.tier() == tier) {
        sum += f.markout(horizonIndex) - f.edge(instruments.get(f.instrumentId()));
      }
    }
    return sum;
  }

  /** Median time for |net DV01| to halve after each client fill, seconds; 0 if there are none. */
  static double halfLife(final long[] times, final double[] netAfter, final boolean[] client, final long end) {
    final List<Double> lives = new ArrayList<>();
    for (int i = 0; i < times.length; i++) {
      if (!client[i] || !(Math.abs(netAfter[i]) > 0)) {
        continue;
      }
      final double half = Math.abs(netAfter[i]) / 2;
      long until = end;
      for (int j = i + 1; j < times.length; j++) {
        if (Math.abs(netAfter[j]) <= half) {
          until = times[j];
          break;
        }
      }
      lives.add((until - times[i]) / 1e9);
    }
    if (lives.isEmpty()) {
      return 0.0;
    }
    final double[] x = lives.stream().mapToDouble(Double::doubleValue).toArray();
    Arrays.sort(x);
    final int k = x.length / 2;
    return x.length % 2 == 1 ? x[k] : 0.5 * (x[k - 1] + x[k]);
  }

  private static Map<String, Map<String, double[]>> hitRates(
      final BacktestResult r, final InstrumentSet instruments) {
    final Map<String, Map<String, double[]>> out = new TreeMap<>();
    for (final RfqRecord q : r.rfqs()) {
      if (!q.quoted()) {
        continue;
      }
      final Instrument inst = instruments.get(q.instrumentId());
      final String tenor = RiskLadder.label(RiskLadder.bucketOf(inst.tenorYears()));
      final String product = inst.kind() == Instrument.Kind.SWAP ? "swap" : "bond";
      final double[] c =
          out.computeIfAbsent("tier " + q.tier(), k -> new TreeMap<>())
              .computeIfAbsent(product + " " + tenor, k -> new double[2]);
      c[0] += 1;
      if (q.won()) {
        c[1] += 1;
      }
    }
    return out;
  }

  private void put(final String key, final double v) {
    values.put(key, v);
  }

  /** A metric by name; throws if unknown. */
  public double get(final String key) {
    final Double v = values.get(key);
    if (v == null) {
      throw new IllegalArgumentException("unknown desk metric " + key);
    }
    return v;
  }

  public boolean limitBreached() {
    return limitBreached;
  }

  /** Every scalar metric in definition order. */
  public Map<String, Double> values() {
    return java.util.Collections.unmodifiableMap(values);
  }

  /** Quoted and won counts by tier, then by product and tenor bucket: {quoted, won}. */
  public Map<String, Map<String, double[]>> hitRateCounts() {
    final Map<String, Map<String, double[]>> copy = new TreeMap<>();
    for (final Map.Entry<String, Map<String, double[]>> e : hitRates.entrySet()) {
      final Map<String, double[]> inner = new TreeMap<>();
      for (final Map.Entry<String, double[]> c : e.getValue().entrySet()) {
        inner.put(c.getKey(), c.getValue().clone());
      }
      copy.put(e.getKey(), inner);
    }
    return copy;
  }
}
