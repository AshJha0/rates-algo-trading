package io.ratesalgo.backtest.experiment;

import io.ratesalgo.backtest.stats.Stats;
import io.ratesalgo.backtest.util.Hashing;
import io.ratesalgo.backtest.util.Prices;
import io.ratesalgo.backtest.util.Yaml;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * The statistics of a finished run, computed once from every registered session (design section
 * 12): per band row and arm the mean P&amp;L per session with its moving-block bootstrap interval,
 * the attribution in USD per session and in bp of client DV01, and the guard metrics; per
 * treatment the paired comparison with the baseline (mean difference, paired t, block-bootstrap
 * interval with the registered block length, the difference in bp of the baseline's client DV01,
 * and the guard ratios).
 *
 * <p>Benjamini-Hochberg is not applied here: the family of a confirmation set grows as tests are
 * added, so the adjusted values are computed by the ledger command over the family's rows
 * ({@code LedgerCli --bh}), and a row's verdict stays {@code pending_family_adjustment} until then.
 * A description (no treatments) gets the verdict {@code description} and no test.
 */
public final class ExperimentSummary {

  /** The attribution lines, in report order. */
  static final List<String> LINES =
      List.of("spread_capture", "inventory_mtm", "hedge_cost", "carry_roll", "funding", "fees", "residual", "position_pnl");

  private ExperimentSummary() {}

  /**
   * The results of a complete run.
   *
   * @param sessions every registered session's record, in seed order
   */
  public static Map<String, Object> compute(
      final SessionDesign design, final List<String> rowLabels, final Map<Long, Map<String, Object>> sessions) {
    final Map<String, Object> byRow = new LinkedHashMap<>();
    final Map<String, Object> primary = new LinkedHashMap<>();
    for (final String row : rowLabels) {
      final Map<String, Object> armsOut = new LinkedHashMap<>();
      final Map<String, Arm> arms = new LinkedHashMap<>();
      for (final String arm : design.arms()) {
        final Arm a = Arm.collect(sessions, row, arm);
        arms.put(arm, a);
        armsOut.put(arm, a.summary(design));
      }
      final Map<String, Object> rowOut = new LinkedHashMap<>();
      rowOut.put("arms", armsOut);
      final Map<String, Object> comparisons = new LinkedHashMap<>();
      for (final String t : design.treatments()) {
        final Map<String, Object> c = compare(arms.get(t), arms.get(design.baseline()), design);
        comparisons.put(t + "_vs_" + design.baseline(), c);
        if (BandParameters.CENTRAL.equals(row)) {
          primary.put(t, c);
        }
      }
      if (!comparisons.isEmpty()) {
        rowOut.put("comparisons", comparisons);
      }
      byRow.put(row, rowOut);
    }
    final Map<String, Object> out = new LinkedHashMap<>();
    out.put("unit", "USD per full-day simulator v2 session; bp of client DV01 = USD over the summed client DV01 traded");
    out.put(
        "label",
        "simulator output under data/calibration/sim_v2_params.json and the assumptions of docs/research/SIM_V2_DESIGN.md;"
            + " not an estimate of desk P&L");
    out.put("sessions", (long) sessions.size());
    out.put("band", byRow);
    if (!primary.isEmpty()) {
      out.put("primary", primary);
    }
    out.put("signs_by_band", signs(design, byRow));
    return out;
  }

  /**
   * The results of a complete run of a registered family, computed once: per data-set row each
   * arm's summary over the sessions that row has; per hypothesis the paired comparison on the
   * central row with its guards and its minimum detectable difference; for each sensitivity row,
   * the comparisons whose two arms it runs, as description (sign and interval, no p-value); and
   * the default arm's description.
   *
   * @param sessions every registered session's record, in seed order
   */
  public static Map<String, Object> computeFamily(final SessionDesign design, final Map<Long, Map<String, Object>> sessions) {
    final SessionDesign.Plan plan = design.plan();
    final Map<String, Object> rows = new LinkedHashMap<>();
    final Map<String, Map<String, Arm>> collected = new LinkedHashMap<>();
    for (final SessionDesign.RowPlan row : plan.rows()) {
      final Map<Long, Map<String, Object>> subset = new LinkedHashMap<>();
      for (final Map.Entry<Long, Map<String, Object>> s : sessions.entrySet()) {
        if (Yaml.asMap(s.getValue().get("rows"), "rows").containsKey(row.label())) {
          subset.put(s.getKey(), s.getValue());
        }
      }
      final Map<String, Arm> arms = new LinkedHashMap<>();
      final Map<String, Object> armsOut = new LinkedHashMap<>();
      for (final String arm : row.arms()) {
        final Arm a = Arm.collect(subset, row.label(), arm);
        arms.put(arm, a);
        armsOut.put(arm, a.summary(design));
      }
      collected.put(row.label(), arms);
      final Map<String, Object> rowOut = new LinkedHashMap<>();
      rowOut.put("sessions", (long) subset.size());
      rowOut.put("arms", armsOut);
      rows.put(row.label(), rowOut);
    }
    final Map<String, Arm> central = collected.get(BandParameters.CENTRAL);
    final Map<String, Object> hypotheses = new LinkedHashMap<>();
    final Map<String, Object> secondary = new LinkedHashMap<>();
    for (final SessionDesign.Hypothesis h : plan.hypotheses()) {
      final Map<String, Object> c = compare(central.get(h.treatment()), central.get(h.baseline()), design);
      familyGuards(c, h, central, plan);
      c.put("minimum_detectable_difference_80", minimumDetectable(c, plan.hypotheses().size()));
      hypotheses.put(h.id(), c);
      final Map<String, Object> byRow = new LinkedHashMap<>();
      for (final Map.Entry<String, Map<String, Arm>> r : collected.entrySet()) {
        if (BandParameters.CENTRAL.equals(r.getKey()) || !r.getValue().containsKey(h.treatment())
            || !r.getValue().containsKey(h.baseline())) {
          continue;
        }
        final Map<String, Object> d = compare(r.getValue().get(h.treatment()), r.getValue().get(h.baseline()), design);
        // description only: the sensitivity rows carry no test
        for (final String k : List.of("paired_t", "paired_df", "paired_p", "paired_se", "guards")) {
          d.remove(k);
        }
        d.put("sign", (long) Math.signum((Double) d.get("mean_difference")));
        byRow.put(r.getKey(), d);
      }
      if (!byRow.isEmpty()) {
        secondary.put(h.id(), byRow);
      }
    }
    final Map<String, Object> out = new LinkedHashMap<>();
    out.put("unit", "USD per full-day simulator v2 session; bp of client DV01 = USD over the summed client DV01 traded");
    out.put(
        "label",
        "simulator output under data/calibration/sim_v2_params.json and the assumptions of docs/research/SIM_V2_DESIGN.md;"
            + " not an estimate of desk P&L");
    out.put("family_id", plan.familyId());
    out.put("sessions", (long) sessions.size());
    out.put("rows", rows);
    out.put("hypotheses", hypotheses);
    out.put("band_secondary", secondary);
    out.put(
        "band_secondary_note",
        "description only: sign and interval at each sensitivity row; not part of the family's error control");
    if (central.containsKey("default")) {
      out.put("descriptive_baseline", Yaml.asMap(Yaml.asMap(rows.get(BandParameters.CENTRAL), "row").get("arms"), "arms").get("default"));
    }
    return out;
  }

  /**
   * The family's guards beside the hypothesis' own: the treatment's client DV01 over the default
   * arm's, and whether it declines no more RFQs for a structural reason than its baseline (1 when
   * it does not, 0 when it does, so that every guard is a ratio checked against a minimum).
   */
  private static void familyGuards(
      final Map<String, Object> c, final SessionDesign.Hypothesis h, final Map<String, Arm> central,
      final SessionDesign.Plan plan) {
    @SuppressWarnings("unchecked")
    final Map<String, Object> guards = (Map<String, Object>) c.get("guards");
    final Arm t = central.get(h.treatment());
    final Arm b = central.get(h.baseline());
    if (!plan.guardsVsDefault().isEmpty() && central.containsKey("default")) {
      guards.put("client_dv01_vs_default_ratio", ratio(t.sumClientDv01(), central.get("default").sumClientDv01()));
    }
    if (plan.structuralGuard()) {
      guards.put("structural_declines_not_above_baseline_ratio", t.structuralDeclines <= b.structuralDeclines ? 1.0 : 0.0);
    }
    guards.put("treatment_structural_declines", t.structuralDeclines);
    guards.put("baseline_structural_declines", b.structuralDeclines);
  }

  /** The guard minimums a family's hypothesis row is judged by. */
  public static Map<String, Object> guardMinimums(final SessionDesign.Hypothesis h, final SessionDesign.Plan plan) {
    final Map<String, Object> min = new LinkedHashMap<>(h.guards());
    for (final Map.Entry<String, Double> e : plan.guardsVsDefault().entrySet()) {
      min.put(e.getKey() + "_vs_default", e.getValue());
    }
    if (plan.structuralGuard()) {
      min.put("structural_declines_not_above_baseline", 1.0);
    }
    return min;
  }

  /**
   * The paired difference detectable with 80 percent power by a two-sided test: (z_(1-a/2) +
   * z_0.8) sd_d / sqrt(n), at a = 0.05 (Benjamini-Hochberg when every test of the family is
   * significant) and at a = 0.05 / m (when this one alone is).
   */
  static Map<String, Object> minimumDetectable(final Map<String, Object> c, final int familySize) {
    final double sd = ((Number) c.get("sd_difference")).doubleValue();
    final double n = ((Number) c.get("n")).doubleValue();
    final double power = io.ratesalgo.sim.Gaussian.inverse(0.8);
    final Map<String, Object> m = new LinkedHashMap<>();
    m.put("formula", "(z_(1-a/2) + z_0.8) x sd_difference / sqrt(n)");
    m.put("at_a_0_05", round((io.ratesalgo.sim.Gaussian.inverse(1.0 - 0.05 / 2.0) + power) * sd / Math.sqrt(n), 2));
    m.put(
        "at_a_0_05_over_m",
        round((io.ratesalgo.sim.Gaussian.inverse(1.0 - 0.05 / familySize / 2.0) + power) * sd / Math.sqrt(n), 2));
    return m;
  }

  /** For each treatment, the sign of the mean paired difference at every band row. */
  private static Map<String, Object> signs(final SessionDesign design, final Map<String, Object> byRow) {
    final Map<String, Object> out = new LinkedHashMap<>();
    for (final String t : design.treatments()) {
      final Map<String, Object> s = new LinkedHashMap<>();
      for (final Map.Entry<String, Object> e : byRow.entrySet()) {
        final Map<String, Object> c =
            Yaml.asMap(Yaml.asMap(Yaml.asMap(e.getValue(), "row").get("comparisons"), "comparisons").get(t + "_vs_" + design.baseline()), "c");
        s.put(e.getKey(), (long) Math.signum((Double) c.get("mean_difference")));
      }
      out.put(t, s);
    }
    return out;
  }

  private static Map<String, Object> compare(final Arm t, final Arm b, final SessionDesign design) {
    final Map<String, Object> c = new LinkedHashMap<>();
    final double[] d = Stats.pairedDifferences(t.pnl, b.pnl);
    c.put("n", (long) d.length);
    c.put("mean_difference", Prices.cents(Stats.mean(d)));
    c.put("sd_difference", round(Math.sqrt(Stats.variance(d)), 2));
    if (Stats.variance(d) > 0.0) {
      final Stats.Paired p = Stats.pairedT(t.pnl, b.pnl);
      c.put("paired_t", round(p.t(), 6));
      c.put("paired_df", round(p.df(), 1));
      c.put("paired_p", p.p());
      c.put("paired_se", round(p.standardError(), 4));
    } else {
      // identical arms: the test is undefined and reported as such, never as p = 1
      c.put("paired_t", "undefined");
      c.put("paired_p", "undefined");
    }
    final Stats.Interval ci =
        Stats.pairedBlockBootstrapCi(
            t.pnl, b.pnl, Math.min(design.blockSessions(), d.length), design.resamples(), 0.95, design.bootstrapSeed());
    c.put("ci_low", Prices.cents(ci.lower()));
    c.put("ci_high", Prices.cents(ci.upper()));
    c.put("ci_level", 0.95);
    c.put("block_sessions", (long) Math.min(design.blockSessions(), d.length));
    c.put("resamples", (long) design.resamples());
    c.put("bootstrap_seed", design.bootstrapSeed());
    final double dv01 = b.meanClientDv01();
    c.put("mean_difference_bp_of_baseline_client_dv01", dv01 > 0.0 ? round(Stats.mean(d) / dv01, 6) : "undefined");
    final Map<String, Object> guards = new LinkedHashMap<>();
    guards.put("quoted_share_ratio", ratio(t.quotedShare(), b.quotedShare()));
    guards.put("hit_rate_ratio", ratio(t.hitRate(), b.hitRate()));
    guards.put("client_dv01_ratio", ratio(t.sumClientDv01(), b.sumClientDv01()));
    c.put("guards", guards);
    return c;
  }

  private static Object ratio(final double a, final double b) {
    return b > 0.0 ? round(a / b, 6) : "undefined";
  }

  static double round(final double v, final int digits) {
    final double f = Math.pow(10, digits);
    return Math.round(v * f) / f;
  }

  /** SHA-256 of the sorted list of the central row's session journal hashes: the data set read. */
  public static String journalDigest(final Map<Long, Map<String, Object>> sessions) {
    final TreeSet<String> hashes = new TreeSet<>();
    for (final Map<String, Object> s : sessions.values()) {
      hashes.add(
          String.valueOf(Yaml.asMap(Yaml.asMap(s.get("rows"), "rows").get(BandParameters.CENTRAL), "row").get("journal_sha256")));
    }
    return Hashing.sha256Text(String.join("\n", hashes) + "\n");
  }

  /** SHA-256 of the sorted list of every arm's output chain hash on every row: what the run produced. */
  public static String outputDigest(final Map<Long, Map<String, Object>> sessions) {
    final TreeSet<String> lines = new TreeSet<>();
    for (final Map.Entry<Long, Map<String, Object>> s : sessions.entrySet()) {
      final Map<String, Object> rows = Yaml.asMap(s.getValue().get("rows"), "rows");
      for (final Map.Entry<String, Object> r : rows.entrySet()) {
        final Map<String, Object> arms = Yaml.asMap(Yaml.asMap(r.getValue(), "row").get("arms"), "arms");
        for (final Map.Entry<String, Object> a : arms.entrySet()) {
          lines.add(s.getKey() + "|" + r.getKey() + "|" + a.getKey() + "|" + Yaml.asMap(a.getValue(), "arm").get("output_chain"));
        }
      }
    }
    return Hashing.sha256Text(String.join("\n", lines) + "\n");
  }

  /** One arm's per-session numbers on one row, in seed order. */
  static final class Arm {
    final double[] pnl;
    final double[] clientDv01;
    final double[][] lines;
    long rfqs;
    long quoted;
    long won;
    long withdrawn;
    long hedgeFills;
    double hedgeDv01;
    long wallMs;
    long structuralDeclines;

    private Arm(final int n) {
      pnl = new double[n];
      clientDv01 = new double[n];
      lines = new double[LINES.size()][n];
    }

    static Arm collect(final Map<Long, Map<String, Object>> sessions, final String row, final String arm) {
      final Arm a = new Arm(sessions.size());
      int i = 0;
      for (final Map<String, Object> s : sessions.values()) {
        final Map<String, Object> r = Yaml.asMap(Yaml.asMap(s.get("rows"), "rows").get(row), "row " + row);
        final Map<String, Object> m = Yaml.asMap(Yaml.asMap(r.get("arms"), "arms").get(arm), "arm " + arm);
        for (int k = 0; k < LINES.size(); k++) {
          a.lines[k][i] = ((Number) m.get(LINES.get(k))).doubleValue();
        }
        a.pnl[i] = Prices.cents(((Number) m.get("position_pnl")).doubleValue());
        a.clientDv01[i] = ((Number) m.get("client_dv01_usd")).doubleValue();
        a.rfqs += ((Number) m.get("rfqs")).longValue();
        a.quoted += ((Number) m.get("quoted")).longValue();
        a.won += ((Number) m.get("won")).longValue();
        a.withdrawn += ((Number) m.get("withdrawn")).longValue();
        a.hedgeFills += ((Number) m.get("hedge_fills")).longValue();
        a.hedgeDv01 += ((Number) m.get("hedge_dv01_usd")).doubleValue();
        a.wallMs += ((Number) m.get("wall_ms")).longValue();
        // counted by strategies that keep it (the desk); absent means none
        a.structuralDeclines += m.get("structural_declines") instanceof Number n ? n.longValue() : 0L;
        i++;
      }
      return a;
    }

    double sumClientDv01() {
      double s = 0.0;
      for (final double v : clientDv01) {
        s += v;
      }
      return s;
    }

    double meanClientDv01() {
      return pnl.length == 0 ? 0.0 : sumClientDv01() / pnl.length;
    }

    double quotedShare() {
      return rfqs == 0 ? 0.0 : (double) quoted / rfqs;
    }

    double hitRate() {
      return quoted == 0 ? 0.0 : (double) won / quoted;
    }

    Map<String, Object> summary(final SessionDesign design) {
      final Map<String, Object> m = new LinkedHashMap<>();
      final double dv01 = sumClientDv01();
      m.put("mean_pnl_usd", Prices.cents(Stats.mean(pnl)));
      m.put("sd_pnl_usd", pnl.length > 1 ? round(Math.sqrt(Stats.variance(pnl)), 2) : "undefined");
      final Stats.Interval ci =
          Stats.blockBootstrapMeanCi(
              pnl, Math.min(design.blockSessions(), pnl.length), design.resamples(), 0.95, design.bootstrapSeed());
      m.put("ci_low", Prices.cents(ci.lower()));
      m.put("ci_high", Prices.cents(ci.upper()));
      m.put("mean_client_dv01_usd", round(meanClientDv01(), 2));
      final Map<String, Object> attribution = new LinkedHashMap<>();
      for (int k = 0; k < LINES.size(); k++) {
        final double sum = sum(lines[k]);
        final Map<String, Object> line = new LinkedHashMap<>();
        line.put("usd_per_session", Prices.cents(sum / pnl.length));
        line.put("bp_of_client_dv01", dv01 > 0.0 ? round(sum / dv01, 6) : "undefined");
        attribution.put(LINES.get(k), line);
      }
      m.put("attribution", attribution);
      final Map<String, Object> guards = new LinkedHashMap<>();
      guards.put("rfqs", rfqs);
      guards.put("quoted", quoted);
      guards.put("withdrawn", withdrawn);
      guards.put("won", won);
      guards.put("quoted_share", round(quotedShare(), 6));
      guards.put("hit_rate", round(hitRate(), 6));
      guards.put("client_dv01_usd", round(dv01, 2));
      guards.put("hedge_fills", hedgeFills);
      guards.put("hedge_dv01_usd", round(hedgeDv01, 2));
      guards.put("structural_declines", structuralDeclines);
      m.put("guards", guards);
      m.put("wall_seconds", round(wallMs / 1000.0, 3));
      return m;
    }

    private static double sum(final double[] x) {
      double s = 0.0;
      for (final double v : x) {
        s += v;
      }
      return s;
    }
  }
}
