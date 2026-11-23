package io.ratesalgo.backtest.sim;

import io.ratesalgo.backtest.BacktestConfig;
import io.ratesalgo.backtest.BacktestResult;
import io.ratesalgo.backtest.BacktestRunner;
import io.ratesalgo.backtest.FillRecord;
import io.ratesalgo.backtest.InstrumentSet;
import io.ratesalgo.backtest.Strategy;
import io.ratesalgo.backtest.model.Presets;
import io.ratesalgo.backtest.pnl.Attribution;
import io.ratesalgo.backtest.prereg.DataSets;
import io.ratesalgo.backtest.stats.Stats;
import io.ratesalgo.backtest.synth.SyntheticDay;
import io.ratesalgo.sim.DayType;
import io.ratesalgo.sim.MarketSpec;
import io.ratesalgo.sim.SessionSeeds;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * The experiment harness of docs/research/SIM_V2_DESIGN.md section 12: full-day sessions by seed
 * base, arms run on common random numbers, the paired test that a pre-registration names as its
 * primary method, and the pilot that sizes a confirmation set.
 *
 * <p>Plain idea. Every arm of an experiment runs the same seeds. The session seed fixes the rate
 * path, the RFQs, every competitor's quote and every client's reserve, so two arms differ only in
 * what the desk does and in what its own orders cause. The noise the arms share cancels in the
 * per-session difference, which is what the test is run on.
 *
 * <pre>
 *   d_n = PnL_treatment(n) - PnL_baseline(n)                       one value per session
 *   primary test: two-sided paired t on d; interval: moving-block bootstrap of mean(d),
 *                 blocks of 21 sessions (one cycle of day types), 2000 resamples, 95 percent
 *   sessions needed (alpha 0.005, power 0.9): N = max(2000, ceil(16.7 sd(d)^2 / delta^2))
 * </pre>
 *
 * <p>This class runs what it is given and reports. It keeps no ledger: a run that is to support a
 * claim goes through the pre-registration and ledger rules ({@link DataSets}), with the data-set
 * key from {@link #dataSetKey}.
 */
public final class PairedSessions {

  /** Sessions the design asks for at least in a confirmation set. */
  public static final int MINIMUM_CONFIRMATION_SESSIONS = 2000;

  /**
   * (z_{0.0025} + z_{0.10})^2 = (2.807 + 1.282)^2: sessions per unit of variance of the paired
   * difference over squared effect, for a two-sided test at 0.005 with power 0.9.
   */
  public static final double SESSIONS_PER_VARIANCE_RATIO = 16.7;

  /** Resamples of the block bootstrap. */
  public static final int BOOTSTRAP_RESAMPLES = 2000;

  private static final LocalDate LABEL_EPOCH = LocalDate.of(2030, 1, 1);

  /** One arm: a name and a fresh strategy for each session. */
  public record Arm(String name, Supplier<Strategy> strategy) {}

  /**
   * One arm's result on one session.
   *
   * @param seed the session's seed, which is its label
   * @param dayType the session's day type
   * @param attribution P&amp;L lines of the session, USD
   * @param clientDv01Usd sum over client trades of |quantity| times the instrument's DV01, USD per bp
   * @param rfqs RFQs received
   * @param clientFills RFQs won
   * @param hedgeFills hedge executions
   */
  public record Session(
      long seed, DayType dayType, Attribution attribution, double clientDv01Usd, long rfqs, long clientFills, long hedgeFills) {

    /** The session's P&amp;L: cash plus the final position at the final latent mid, USD. */
    public double pnlUsd() {
      return attribution.positionPnl();
    }
  }

  /** One arm's sessions, in seed order. */
  public record ArmResult(String name, List<Session> sessions) {
    public ArmResult {
      sessions = List.copyOf(sessions);
    }

    /** P&amp;L per session, USD, in seed order. */
    public double[] pnlUsd() {
      return sessions.stream().mapToDouble(Session::pnlUsd).toArray();
    }

    /** Mean client DV01 per session, USD per bp. */
    public double meanClientDv01Usd() {
      return sessions.stream().mapToDouble(Session::clientDv01Usd).average().orElse(0.0);
    }
  }

  /**
   * The registered primary comparison of a treatment with a baseline on the same sessions.
   *
   * @param paired the two-sided paired t-test on the per-session differences
   * @param bootstrap 95 percent moving-block bootstrap interval of the mean difference, USD per session
   * @param meanDifferenceBpOfClientDv01 the mean difference over the baseline's mean client DV01, bp
   */
  public record Comparison(
      String treatment, String baseline, Stats.Paired paired, Stats.Interval bootstrap, double meanDifferenceBpOfClientDv01) {}

  /**
   * What a pilot measured.
   *
   * @param sessionSd standard deviation of the baseline arm's session P&amp;L, USD
   * @param differenceSd standard deviation of the paired difference, USD
   * @param correlation correlation of the two arms across sessions
   * @param smallestEffectUsd the effect the pre-registration cares about, stated before the pilot is read
   * @param impliedSessions sessions a confirmation set needs
   */
  public record Pilot(
      int sessions, double sessionSd, double differenceSd, double correlation, double smallestEffectUsd, long impliedSessions) {}

  private final MarketSpec spec;
  private final Presets presets;
  private final Path workDir;

  /**
   * @param spec the market specification every session is built from
   * @param presets desk-side and transport settings (latencies, fee, publication interval)
   * @param workDir directory for the session journals, each deleted after its arms have run
   */
  public PairedSessions(final MarketSpec spec, final Presets presets, final Path workDir) {
    this.spec = spec;
    this.presets = presets;
    this.workDir = workDir;
  }

  /** The label date of a session: one calendar day per seed, so RFQ ids are unique across a set. */
  public static LocalDate labelDate(final long seed) {
    return LABEL_EPOCH.plusDays(seed % SessionSeeds.SPAN);
  }

  /**
   * The ledger's data-set key of sessions {@code first .. first + sessions - 1} of a base. Rows
   * with the same key share one look counter ({@link DataSets}).
   */
  public static String dataSetKey(final long base, final int first, final int sessions) {
    final List<LocalDate> days = new ArrayList<>();
    for (int n = first; n < first + sessions; n++) {
      days.add(labelDate(SessionSeeds.seed(base, n)));
    }
    return DataSets.key(days, d -> base + (d.toEpochDay() - LABEL_EPOCH.toEpochDay()));
  }

  /**
   * Runs every arm on the same sessions. Each arm gets its own model of the session's seed, so
   * one arm's orders cannot reach another's market; the journal is written once per session.
   */
  public List<ArmResult> run(final long base, final int first, final int sessions, final List<Arm> arms) {
    final List<List<Session>> out = new ArrayList<>();
    for (int a = 0; a < arms.size(); a++) {
      out.add(new ArrayList<>());
    }
    final SyntheticDay.Publication publication = SyntheticDay.Publication.fromPresets(presets);
    final InstrumentSet instruments = InstrumentSet.simV2(spec.params());
    for (int n = first; n < first + sessions; n++) {
      final long seed = SessionSeeds.seed(base, n);
      final LocalDate date = labelDate(seed);
      final Path journal = workDir.resolve("session-" + seed + ".raj");
      SyntheticDay.generateSimV2(SimDay.of(spec, seed, date), publication, journal, null);
      for (int a = 0; a < arms.size(); a++) {
        final SimDay day = SimDay.of(spec, seed, date);
        final BacktestResult r =
            new BacktestRunner(BacktestConfig.forSimV2(presets, day, List.of(journal), null)).run(arms.get(a).strategy().get());
        double dv01 = 0.0;
        for (final FillRecord f : r.fills()) {
          if (!f.hedge()) {
            dv01 += Math.abs(f.signedQuantity() * instruments.get(f.instrumentId()).dv01PerUnit());
          }
        }
        out.get(a)
            .add(
                new Session(
                    seed, day.model().dayType(), r.total(), dv01, r.rfqs().size(), r.clientFills(), r.hedgeFills()));
      }
      try {
        Files.deleteIfExists(journal);
      } catch (final IOException e) {
        throw new UncheckedIOException(e);
      }
    }
    final List<ArmResult> results = new ArrayList<>();
    for (int a = 0; a < arms.size(); a++) {
      results.add(new ArmResult(arms.get(a).name(), out.get(a)));
    }
    return results;
  }

  private static void requireSameSessions(final ArmResult a, final ArmResult b) {
    if (a.sessions().size() != b.sessions().size()) {
      throw new IllegalArgumentException("the arms were not run on the same sessions");
    }
    for (int n = 0; n < a.sessions().size(); n++) {
      if (a.sessions().get(n).seed() != b.sessions().get(n).seed()) {
        throw new IllegalArgumentException("the arms were not run on the same sessions");
      }
    }
  }

  /**
   * The primary comparison: paired t-test and block bootstrap of the per-session difference. The
   * unpaired Welch test is not offered here; on a paired design it throws the pairing away.
   *
   * @param bootstrapSeed seed of the bootstrap's resampling, stated in the pre-registration
   */
  public static Comparison compare(final ArmResult treatment, final ArmResult baseline, final long bootstrapSeed) {
    requireSameSessions(treatment, baseline);
    final double[] t = treatment.pnlUsd();
    final double[] b = baseline.pnlUsd();
    final Stats.Paired paired = Stats.pairedT(t, b);
    final Stats.Interval interval =
        Stats.pairedBlockBootstrapCi(t, b, Math.min(DayType.CYCLE, t.length), BOOTSTRAP_RESAMPLES, 0.95, bootstrapSeed);
    final double dv01 = baseline.meanClientDv01Usd();
    return new Comparison(
        treatment.name(), baseline.name(), paired, interval, dv01 > 0.0 ? paired.meanDifference() / dv01 : Double.NaN);
  }

  /** Sessions a confirmation set needs for a paired difference with this sd and this smallest effect. */
  public static long impliedSessions(final double differenceSd, final double smallestEffectUsd) {
    if (!(smallestEffectUsd > 0.0) || !(differenceSd >= 0.0)) {
      throw new IllegalArgumentException("the smallest effect must be positive and the sd non-negative");
    }
    final double ratio = differenceSd / smallestEffectUsd;
    return Math.max(MINIMUM_CONFIRMATION_SESSIONS, (long) Math.ceil(SESSIONS_PER_VARIANCE_RATIO * ratio * ratio));
  }

  /**
   * Reads a pilot: the session sd of the baseline, the sd of the paired difference with a
   * perturbed arm, their correlation, and the sessions that implies. A pilot is discarded for
   * inference after this.
   */
  public static Pilot pilot(final ArmResult baseline, final ArmResult perturbed, final double smallestEffectUsd) {
    requireSameSessions(baseline, perturbed);
    final double[] b = baseline.pnlUsd();
    final double[] q = perturbed.pnlUsd();
    final double sdB = Math.sqrt(Stats.variance(b));
    final double sdQ = Math.sqrt(Stats.variance(q));
    final double sdD = Math.sqrt(Stats.variance(Stats.pairedDifferences(q, b)));
    // var(d) = var(q) + var(b) - 2 cov
    final double covariance = (sdQ * sdQ + sdB * sdB - sdD * sdD) / 2.0;
    final double correlation = sdB > 0.0 && sdQ > 0.0 ? covariance / (sdB * sdQ) : Double.NaN;
    return new Pilot(b.length, sdB, sdD, correlation, smallestEffectUsd, impliedSessions(sdD, smallestEffectUsd));
  }
}
