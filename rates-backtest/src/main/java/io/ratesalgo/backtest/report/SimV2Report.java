package io.ratesalgo.backtest.report;

import io.ratesalgo.backtest.BacktestResult;
import io.ratesalgo.backtest.FillRecord;
import io.ratesalgo.backtest.Instrument;
import io.ratesalgo.backtest.InstrumentSet;
import io.ratesalgo.backtest.RfqRecord;
import io.ratesalgo.backtest.pnl.Attribution;
import io.ratesalgo.backtest.sim.SimDay;
import io.ratesalgo.backtest.util.Json;
import io.ratesalgo.backtest.util.Prices;
import io.ratesalgo.sim.MarketModel;
import io.ratesalgo.sim.MarketSpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The daily report of one simulator v2 session (docs/research/SIM_V2_DESIGN.md sections 4 and 12):
 * client markouts at 1 s, 5 s, 30 s, 60 s and 5 min by client tier and size bucket, spread
 * capture, hedge cost, inventory marking split into the part the three curve factors explain and
 * the rest, and the internalised share. Every figure is labelled as simulator output.
 *
 * <p>Definitions.
 *
 * <ul>
 *   <li>Markout of a client fill at horizon h: signed quantity times (latent mid at fill time + h
 *       minus fill price) times value per unit, USD; shown per tier and size bucket as the sum in
 *       USD and as bp of the bucket's client DV01. The latent mid includes the desk's own permanent
 *       impact up to that time, as the session's marks do.
 *   <li>Inventory marking on the 1 s grid: the position held over each step times the change of
 *       the latent mid over the step. Its factor part uses the change of L x F (the three curve
 *       factors through the instrument's loadings); own impact is the change of the level offset
 *       X1 the desk's orders caused; the residual is the rest (swap-spread factor, idiosyncratic
 *       node terms, futures basis). The back-tester's attribution line marks at every event rather
 *       than on the grid; the difference is shown as "timing", so the four parts sum to that line.
 *   <li>Internalised share: 1 - min(1, hedge DV01 traded / client DV01 traded). A share of 1 means
 *       the desk hedged nothing; it says nothing about whether holding the risk paid.
 * </ul>
 */
public final class SimV2Report {

  /** Markout horizons, nanoseconds. */
  static final long[] HORIZONS_NANOS = {1_000_000_000L, 5_000_000_000L, 30_000_000_000L, 60_000_000_000L, 300_000_000_000L};

  /** Labels of {@link #HORIZONS_NANOS}. */
  public static final List<String> HORIZON_LABELS = List.of("1s", "5s", "30s", "60s", "5min");

  /** Upper bounds of the client size buckets, millions of notional or face; the last is open. */
  static final double[] SIZE_BUCKETS_MM = {10.0, 50.0};

  /** The label every v2 report carries. */
  public static final String LABEL =
      "Simulator v2 output under data/calibration/sim_v2_params.json and the assumptions of"
          + " docs/research/SIM_V2_DESIGN.md (bounded and assumed parameters at their central values)."
          + " A statement about the simulator, not an estimate of desk P&L.";

  private final BacktestResult result;
  private final InstrumentSet instruments;
  private final SimDay day;

  public SimV2Report(final BacktestResult result, final InstrumentSet instruments, final SimDay day) {
    this.result = result;
    this.instruments = instruments;
    this.day = day;
  }

  static String bucket(final double sizeMm) {
    if (sizeMm < SIZE_BUCKETS_MM[0]) {
      return "<10mm";
    }
    return sizeMm < SIZE_BUCKETS_MM[1] ? "10-50mm" : ">=50mm";
  }

  /** The report as a map, for JSON. */
  public Map<String, Object> toMap() {
    final Map<String, Object> m = new LinkedHashMap<>();
    m.put("label", LABEL);
    m.put("seed", day.model().seed());
    m.put("day_type", day.model().dayType().name());
    m.put("strategy", result.strategy());
    double clientDv01 = 0.0;
    double hedgeDv01 = 0.0;
    for (final FillRecord f : result.fills()) {
      final double d = Math.abs(f.signedQuantity() * instruments.get(f.instrumentId()).dv01PerUnit());
      if (f.hedge()) {
        hedgeDv01 += d;
      } else {
        clientDv01 += d;
      }
    }
    long quoted = 0;
    long won = 0;
    for (final RfqRecord r : result.rfqs()) {
      quoted += r.quoted() ? 1 : 0;
      won += r.won() ? 1 : 0;
    }
    final Map<String, Object> flow = new LinkedHashMap<>();
    flow.put("rfqs", (long) result.rfqs().size());
    flow.put("quoted", quoted);
    flow.put("won", won);
    flow.put("client_dv01_usd", round(clientDv01, 2));
    flow.put("hedge_dv01_usd", round(hedgeDv01, 2));
    flow.put("internalised_share", clientDv01 > 0.0 ? round(1.0 - Math.min(1.0, hedgeDv01 / clientDv01), 6) : "undefined");
    m.put("flow", flow);
    final Attribution a = result.total();
    m.put("spread_capture", line(a.spreadCapture(), clientDv01));
    m.put("hedge_cost", line(a.hedgeCost(), clientDv01));
    m.put("fees", line(a.fees(), clientDv01));
    m.put("inventory_marking", inventory(a.inventoryMtm(), clientDv01));
    m.put("position_pnl", line(a.positionPnl(), clientDv01));
    m.put("markouts", markouts());
    return m;
  }

  private static Map<String, Object> line(final double usd, final double dv01) {
    final Map<String, Object> m = new LinkedHashMap<>();
    m.put("usd", Prices.cents(usd));
    m.put("bp_of_client_dv01", dv01 > 0.0 ? round(usd / dv01, 6) : "undefined");
    return m;
  }

  /** Markouts per tier and size bucket, and per tier over all sizes. */
  private List<Map<String, Object>> markouts() {
    final Map<String, double[]> sums = new LinkedHashMap<>();
    final int h = HORIZONS_NANOS.length;
    final List<FillRecord> clientFills = new ArrayList<>();
    for (final FillRecord f : result.fills()) {
      if (!f.hedge()) {
        clientFills.add(f);
      }
    }
    for (final int tier : new int[] {1, 2, 3}) {
      for (final String b : List.of("all", "<10mm", "10-50mm", ">=50mm")) {
        sums.put(tier + "|" + b, new double[h + 2]);
      }
    }
    for (final FillRecord f : clientFills) {
      final Instrument inst = instruments.get(f.instrumentId());
      // client RFQs are swaps and Treasuries, counted in minor units of notional or face
      final double sizeMm = Math.abs((double) f.signedQuantity()) / InstrumentSet.MILLION;
      final double dv01 = Math.abs(f.signedQuantity() * inst.dv01PerUnit());
      for (final String key : List.of(f.tier() + "|all", f.tier() + "|" + bucket(sizeMm))) {
        final double[] s = sums.computeIfAbsent(key, k -> new double[h + 2]);
        for (int k = 0; k < h; k++) {
          final double later = day.latentMid(f.instrumentId(), f.timeNanos() + HORIZONS_NANOS[k]);
          s[k] += f.signedQuantity() * (later - f.price()) * inst.valuePerUnit();
        }
        s[h] += dv01;
        s[h + 1] += 1.0;
      }
    }
    final List<Map<String, Object>> rows = new ArrayList<>();
    for (final Map.Entry<String, double[]> e : sums.entrySet()) {
      final double[] s = e.getValue();
      if (s[h + 1] == 0.0) {
        continue;
      }
      final int bar = e.getKey().indexOf('|');
      final int tier = Integer.parseInt(e.getKey().substring(0, bar));
      final Map<String, Object> row = new LinkedHashMap<>();
      row.put("tier", "ABC".substring(tier - 1, tier));
      row.put("size", e.getKey().substring(bar + 1));
      row.put("fills", (long) s[h + 1]);
      row.put("client_dv01_usd", round(s[h], 2));
      for (int k = 0; k < h; k++) {
        row.put("usd_" + HORIZON_LABELS.get(k), Prices.cents(s[k]));
        row.put("bp_" + HORIZON_LABELS.get(k), s[h] > 0.0 ? round(s[k] / s[h], 6) : "undefined");
      }
      rows.add(row);
    }
    return rows;
  }

  /** Grid marking split into factor, own impact and residual, with the timing difference. */
  private Map<String, Object> inventory(final double attributionLine, final double clientDv01) {
    final MarketModel model = day.model();
    final MarketSpec spec = model.spec();
    final List<FillRecord> fills = new ArrayList<>(result.fills());
    fills.sort((x, y) -> Long.compare(x.timeNanos(), y.timeNanos()));
    final List<Instrument> all = new ArrayList<>(instruments.all());
    final int n = all.size();
    final double[] pos = new double[n];
    final int[] index = new int[n];
    for (int i = 0; i < n; i++) {
      index[i] = day.index(all.get(i).id());
    }
    final Map<Integer, Integer> slot = new LinkedHashMap<>();
    for (int i = 0; i < n; i++) {
      slot.put(all.get(i).id(), i);
    }
    double factor = 0.0;
    double impact = 0.0;
    double grid = 0.0;
    int next = 0;
    final long open = day.openEpochNanos();
    final double[] lastMid = new double[n];
    final double[][] lastF = new double[n][];
    for (int i = 0; i < n; i++) {
      lastMid[i] = model.latentMid(index[i], 0L);
    }
    double lastX1 = model.levelOffset(0L);
    final double[] f0 = {model.factor(0, 0), model.factor(1, 0), model.factor(2, 0)};
    Arrays.fill(lastF, f0);
    for (int k = 1; k <= spec.steps(); k++) {
      final long t = (long) k * MarketSpec.STEP_NANOS;
      // the position held over the step is the one after every fill before its end
      while (next < fills.size() && fills.get(next).timeNanos() - open < t) {
        final FillRecord f = fills.get(next++);
        pos[slot.get(f.instrumentId())] += f.signedQuantity();
      }
      final int step = Math.min(k, spec.steps() - 1);
      final double[] fk = {model.factor(0, step), model.factor(1, step), model.factor(2, step)};
      final double x1 = model.levelOffset(t);
      for (int i = 0; i < n; i++) {
        final double mid = model.latentMid(index[i], t);
        if (pos[i] != 0.0) {
          final Instrument inst = all.get(i);
          final double pcpb = spec.instrument(index[i]).priceChangePerBp();
          double df = 0.0;
          for (int j = 0; j < 3; j++) {
            df += spec.loading(index[i], j) * (fk[j] - lastF[i][j]);
          }
          final double v = pos[i] * inst.valuePerUnit();
          grid += v * (mid - lastMid[i]);
          factor += v * pcpb * df;
          impact += v * pcpb * spec.loading(index[i], 0) * (x1 - lastX1);
        }
        lastMid[i] = mid;
        lastF[i] = fk;
      }
      lastX1 = x1;
    }
    final Map<String, Object> m = new LinkedHashMap<>();
    m.put("attribution_usd", Prices.cents(attributionLine));
    m.put("factor_usd", Prices.cents(factor));
    m.put("own_impact_usd", Prices.cents(impact));
    m.put("residual_usd", Prices.cents(grid - factor - impact));
    m.put("timing_usd", Prices.cents(attributionLine - grid));
    m.put("factor_bp_of_client_dv01", clientDv01 > 0.0 ? round(factor / clientDv01, 6) : "undefined");
    m.put("residual_bp_of_client_dv01", clientDv01 > 0.0 ? round((grid - factor - impact) / clientDv01, 6) : "undefined");
    return m;
  }

  public String toJson() {
    return Json.write(toMap());
  }

  /** The report as Markdown, label first. */
  @SuppressWarnings("unchecked")
  public String toMarkdown() {
    final Map<String, Object> m = toMap();
    final StringBuilder b = new StringBuilder(4096);
    b.append("# Simulator v2 session report\n\n").append(LABEL).append("\n\n");
    b.append("Seed ").append(m.get("seed")).append(", day type ").append(m.get("day_type")).append(", strategy ")
        .append(m.get("strategy")).append(".\n\n");
    b.append("| Line | USD | bp of client DV01 |\n|---|---:|---:|\n");
    for (final String k : List.of("spread_capture", "hedge_cost", "fees", "position_pnl")) {
      final Map<String, Object> l = (Map<String, Object>) m.get(k);
      b.append("| ").append(k).append(" | ").append(l.get("usd")).append(" | ").append(l.get("bp_of_client_dv01")).append(" |\n");
    }
    final Map<String, Object> inv = (Map<String, Object>) m.get("inventory_marking");
    b.append("\nInventory marking: ").append(inv.get("attribution_usd")).append(" USD = factor ").append(inv.get("factor_usd"))
        .append(" + own impact ").append(inv.get("own_impact_usd")).append(" + residual ").append(inv.get("residual_usd"))
        .append(" + timing ").append(inv.get("timing_usd")).append(".\n");
    final Map<String, Object> flow = (Map<String, Object>) m.get("flow");
    b.append("\nFlow: ").append(flow.get("rfqs")).append(" RFQs, ").append(flow.get("quoted")).append(" quoted, ")
        .append(flow.get("won")).append(" won; internalised share ").append(flow.get("internalised_share")).append(".\n");
    b.append("\n## Markouts (bp of client DV01)\n\n| Tier | Size | Fills |");
    for (final String h : HORIZON_LABELS) {
      b.append(' ').append(h).append(" |");
    }
    b.append("\n|---|---|---:|");
    b.append("---:|".repeat(HORIZON_LABELS.size()));
    b.append('\n');
    for (final Map<String, Object> row : (List<Map<String, Object>>) m.get("markouts")) {
      b.append("| ").append(row.get("tier")).append(" | ").append(row.get("size")).append(" | ").append(row.get("fills")).append(" |");
      for (final String h : HORIZON_LABELS) {
        b.append(' ').append(row.get("bp_" + h)).append(" |");
      }
      b.append('\n');
    }
    return b.toString();
  }

  private static double round(final double v, final int digits) {
    final double f = Math.pow(10, digits);
    return Math.round(v * f) / f;
  }

  @Override
  public String toString() {
    return String.format(Locale.ROOT, "SimV2Report[seed=%d, fills=%d]", day.model().seed(), result.fills().size());
  }
}
