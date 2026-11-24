package io.ratesalgo.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.common.random.SplitMix64;
import io.ratesalgo.sim.SimParams.Scalar;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Acceptance tests of docs/research/SIM_V2_DESIGN.md section 11 that the market model alone makes
 * testable: A1 replay, A2 polling invariance, A3 zero-profit competitor, A4 factor moments, A5
 * hedge ratio, A6 no tick dependence, A8 information is about the market. Seeds: base 28,000,000.
 *
 * <p>Sample size. The design asks for 400 sessions and tolerances that a correct implementation
 * passes with probability above 99 percent. For second moments 400 sessions do not give that: the
 * standard error of a standard deviation is 1 / sqrt(2 N) = 3.5 percent at N = 400, so the
 * design's 8 percent is 2.3 standard errors. The moment tests therefore keep the design's
 * tolerances and use N = 1000 sessions, where 8 percent is 3.6 standard errors and 15 percent on a
 * variance (standard error sqrt(2 / N) = 4.5 percent) is 3.4. Each test states its own standard
 * error. Expected values are computed here from the parameter file, not read from the model.
 */
class MarketModelAcceptanceTest {

  private static final int N = 1000;
  private static final long FIVE_MINUTES = 300L * MarketSpec.STEP_NANOS;

  private static SimParams p;
  private static MarketSpec spec;
  private static int steps;

  // session-end changes, bp: [session][node], and the three hedge legs
  private static double[][] nodeChange;
  private static double[] swap10;
  private static double[] note10;
  private static double[] ty;

  // RFQ statistics over the same sessions, desk never quotes
  private static final Mean SWAP_WINNER_MINUS_ADVERSE = new Mean();
  private static final Mean SWAP_WINNER = new Mean();
  private static final Mean TREASURY_WINNER = new Mean();
  private static final Mean ADVERSE = new Mean();
  private static final Mean INFORMED_TRADED = new Mean();
  private static final Mean INFORMED_NOT_TRADED = new Mean();
  private static final Mean UNINFORMED = new Mean();
  private static final Mean CROSS_TENOR_ERROR = new Mean();
  private static final Mean TICK_ROUNDING_TICKS = new Mean();
  private static int quotesInsideUnrounded;

  private static final class Mean {
    private double sum;
    private double squares;
    private long n;

    void add(final double v) {
      sum += v;
      squares += v * v;
      n++;
    }

    double mean() {
      return sum / n;
    }

    double standardError() {
      final double m = mean();
      return Math.sqrt((squares / n - m * m) / n);
    }
  }

  @BeforeAll
  static void runSessions() {
    p = SimParams.loadDefault();
    spec = new MarketSpec(p);
    steps = spec.steps();
    nodeChange = new double[N][p.nodes()];
    swap10 = new double[N];
    note10 = new double[N];
    ty = new double[N];
    final int iSwap = spec.indexOf("SOFR-10Y");
    final int iNote = spec.indexOf("UST-10Y");
    final int iTy = spec.indexOf("TY");
    for (int s = 0; s < N; s++) {
      final MarketModel m = new MarketModel(spec, SessionSeeds.seed(SessionSeeds.ACCEPTANCE, s), DayType.NONE);
      for (int n = 0; n < p.nodes(); n++) {
        nodeChange[s][n] = m.yieldChangeBp(spec.swapOfNode(n), steps);
      }
      swap10[s] = bpChange(m, iSwap);
      note10[s] = bpChange(m, iNote);
      ty[s] = bpChange(m, iTy);
      collectRfqs(m);
    }
  }

  /** Session-end change of an instrument's latent mid in bp of its own yield or rate. */
  private static double bpChange(final MarketModel m, final int i) {
    final SimInstrument inst = spec.instrument(i);
    return (m.exogenousMid(i, steps) - inst.initialMid()) / inst.priceChangePerBp();
  }

  private static void collectRfqs(final MarketModel m) {
    for (final SimRfq r : m.rfqs()) {
      // the markout needs five minutes of path after the arrival
      if (r.arrivalNanos() + FIVE_MINUTES > spec.sessionNanos()) {
        continue;
      }
      final SimInstrument inst = spec.instrument(r.instrument());
      final boolean swap = inst.kind() == SimInstrument.Kind.SWAP;
      final RfqDecision d = m.decide(r, Double.NaN, 0L);
      final double a = m.adverseMoveBp(r, FIVE_MINUTES);
      if (d.traded()) {
        ADVERSE.add(a);
        (swap ? SWAP_WINNER : TREASURY_WINNER).add(d.winnerHalfSpreadBp());
        if (swap) {
          SWAP_WINNER_MINUS_ADVERSE.add(d.winnerHalfSpreadBp() - a);
        }
        final double rounded = inst.toBp(Math.abs(d.price() - m.midAtArrival(r)));
        final double extraTicks = (rounded - d.winnerHalfSpreadBp()) * Math.abs(inst.priceChangePerBp()) / inst.tick();
        TICK_ROUNDING_TICKS.add(extraTicks);
        if (extraTicks < -1e-9 || extraTicks > 1.0 + 1e-9) {
          quotesInsideUnrounded++;
        }
      }
      if (r.informed()) {
        (d.traded() ? INFORMED_TRADED : INFORMED_NOT_TRADED).add(a);
        // the move at every other swap tenor against A x L[T] / L[T_r]
        for (int n = 0; n < p.nodes(); n++) {
          final int other = spec.swapOfNode(n);
          final double expected =
              p.get(Scalar.MOVE_PER_INFORMED_TRADE_BP) * spec.loading(other, 0) / spec.loading(r.instrument(), 0);
          CROSS_TENOR_ERROR.add(m.adverseMoveBp(r, other, FIVE_MINUTES) - expected);
        }
      } else {
        UNINFORMED.add(a);
      }
    }
  }

  // ------------------------------------------------------------------ A1

  @Test
  void a1_replayIsBitIdentical() {
    final long seed = SessionSeeds.seed(SessionSeeds.ACCEPTANCE, 3);
    final MarketModel a = new MarketModel(spec, seed, DayType.RELEASE);
    final MarketModel b = new MarketModel(spec, seed, DayType.RELEASE);
    for (int k = 0; k <= steps; k++) {
      for (int j = 0; j < SimParams.FACTORS; j++) {
        assertEquals(Double.doubleToRawLongBits(a.factor(j, k)), Double.doubleToRawLongBits(b.factor(j, k)));
      }
      assertEquals(Double.doubleToRawLongBits(a.spreadFactor(k)), Double.doubleToRawLongBits(b.spreadFactor(k)));
      assertEquals(Double.doubleToRawLongBits(a.basis(0, k)), Double.doubleToRawLongBits(b.basis(0, k)));
      assertEquals(Double.doubleToRawLongBits(a.pendingDrift(k)), Double.doubleToRawLongBits(b.pendingDrift(k)));
      for (int n = 0; n < p.nodes(); n++) {
        assertEquals(Double.doubleToRawLongBits(a.idiosyncratic(n, k)), Double.doubleToRawLongBits(b.idiosyncratic(n, k)));
      }
    }
    assertEquals(a.rfqs().size(), b.rfqs().size());
    for (int r = 0; r < a.rfqs().size(); r++) {
      final SimRfq x = a.rfqs().get(r);
      final SimRfq y = b.rfqs().get(r);
      assertEquals(x.hash(1L), y.hash(1L));
      for (int d = 0; d < x.competitors(); d++) {
        assertEquals(Double.doubleToRawLongBits(a.competitorQuote(x, d)), Double.doubleToRawLongBits(b.competitorQuote(y, d)));
      }
      assertEquals(a.decide(x, Double.NaN, 0L), b.decide(y, Double.NaN, 0L));
    }
    final BookModel ba = a.book(spec.indexOf("UST-10Y"));
    final BookModel bb = b.book(spec.indexOf("UST-10Y"));
    assertEquals(ba.takerStart(steps), bb.takerStart(steps));
    for (int n = 0; n < ba.takerStart(steps); n++) {
      assertEquals(ba.takerBuys(n), bb.takerBuys(n));
      assertEquals(ba.takerUnits(n), bb.takerUnits(n), 0.0);
    }
    assertEquals(a.replayHash(), b.replayHash());

    // another seed or another day type is another session
    assertNotEquals(a.replayHash(), new MarketModel(spec, seed + 1, DayType.RELEASE).replayHash());
    assertNotEquals(a.replayHash(), new MarketModel(spec, seed, DayType.NONE).replayHash());
    assertEquals(seed, a.seed());
    assertEquals(DayType.RELEASE, a.dayType());
    assertEquals(spec, a.spec());
  }

  // ------------------------------------------------------------------ A2

  /**
   * One seed, four ways of reading: (a) every step, (b) every 10 ms, (c) at 500 random times, (d)
   * once at the end. A read returns the state of the step that contains its time, so every read
   * must equal the per-step value bit for bit. (a), (c) and (d) cover all twelve instruments; the
   * 10 ms poll (3.6 million reads per instrument) covers one swap, one Treasury and the future.
   */
  @Test
  void a2_latentMidDoesNotDependOnPolling() {
    final MarketModel m = new MarketModel(spec, SessionSeeds.seed(SessionSeeds.ACCEPTANCE, 7), DayType.FOMC);
    final int count = spec.instruments();
    final double[][] perStep = new double[count][steps + 1];
    for (int i = 0; i < count; i++) {
      for (int k = 0; k <= steps; k++) {
        perStep[i][k] = m.latentMid(i, k * MarketSpec.STEP_NANOS);
        assertEquals(m.exogenousMid(i, k), perStep[i][k], 0.0);
      }
    }
    for (final String key : List.of("SOFR-10Y", "UST-2Y", "TY")) {
      final int i = spec.indexOf(key);
      for (long t = 0; t <= spec.sessionNanos(); t += 10_000_000L) {
        if (m.latentMid(i, t) != perStep[i][(int) (t / MarketSpec.STEP_NANOS)]) {
          throw new AssertionError(key + " differs at " + t);
        }
      }
    }
    final SplitMix64 times = new SplitMix64(99L);
    for (int n = 0; n < 500; n++) {
      final long t = (long) (times.nextDouble() * spec.sessionNanos());
      for (int i = 0; i < count; i++) {
        assertEquals(perStep[i][(int) (t / MarketSpec.STEP_NANOS)], m.latentMid(i, t), 0.0);
      }
    }
    for (int i = 0; i < count; i++) {
      // before the open the initial state, after the close the last state
      assertEquals(spec.instrument(i).initialMid(), m.latentMid(i, -5L), 0.0);
      assertEquals(perStep[i][steps], m.latentMid(i, spec.sessionNanos()), 0.0);
      assertEquals(perStep[i][steps], m.latentMid(i, spec.sessionNanos() + 3_600_000_000_000L), 0.0);
      assertEquals(perStep[i][steps - 1], m.latentMid(i, spec.sessionNanos() - 1), 0.0);
    }
  }

  // ------------------------------------------------------------------ A3

  /**
   * Zero-profit competitor, desk absent. Over the swap trades of the N sessions the mean of
   * (winning half-spread - 5-minute adverse move) must be all_in_cost within 0.01 bp, and over all
   * trades the mean adverse move must be expected_adverse_move within 0.01 bp.
   *
   * <p>Standard errors, measured in the test: 0.0009 bp for the first mean (108,000 swap trades)
   * and 0.0007 bp for the second (201,000 trades), so 0.01 bp is more than 10 standard errors. The
   * tolerance is the design's and also covers one known difference: the client's reserve removes
   * a few wide lone quotes, which the identity's E[min] does not, so the realised winning
   * half-spread is about 0.003 bp under median x E[min].
   *
   * <p>The winning half-spread is the unrounded one and the identity is checked on swaps, whose
   * tick is 0.01 bp. In Treasuries competitors quote the 0.5 multiple of the swap median, so their
   * winning half-spread is near half the swap value and the identity cannot hold there at the
   * same all-in cost. It is a little above half (0.076 against 0.070 bp) because a Treasury tick
   * is as wide as the half-spread itself: several quotes round to one price, the earliest of them
   * wins, and that is not always the tightest unrounded one. The last assertion pins both
   * properties (design document, "Deviations and clarifications").
   */
  @Test
  void a3_bestPriceCompetitorEarnsItsAllInCostAndNothingMore() {
    assertTrue(SWAP_WINNER_MINUS_ADVERSE.n > 90_000, "swap trades: " + SWAP_WINNER_MINUS_ADVERSE.n);
    assertTrue(SWAP_WINNER_MINUS_ADVERSE.standardError() < 0.002);
    assertTrue(ADVERSE.standardError() < 0.002);
    assertEquals(p.get(Scalar.ALL_IN_COST_BP), SWAP_WINNER_MINUS_ADVERSE.mean(), 0.01);
    assertEquals(p.get(Scalar.EXPECTED_ADVERSE_MOVE_BP), ADVERSE.mean(), 0.01);
    // the identity itself: median x E[min] = adverse + cost within the start-up tolerance; the
    // realised winner is below it (the reserve only removes wide quotes) and within the design's 0.01
    final double winner = p.get(Scalar.MEDIAN_HALF_SPREAD_BP) * Competition.expectedBestPerMedian(p);
    assertEquals(p.get(Scalar.EXPECTED_ADVERSE_MOVE_BP) + p.get(Scalar.ALL_IN_COST_BP), winner, 0.001);
    assertTrue(SWAP_WINNER.mean() < winner && SWAP_WINNER.mean() > winner - 0.01, "swap winner " + SWAP_WINNER.mean());
    final double treasury = winner * p.get(Scalar.TREASURY_HALF_SPREAD_MULTIPLE);
    assertTrue(
        TREASURY_WINNER.mean() > treasury && TREASURY_WINNER.mean() < treasury + 0.015,
        "Treasury winner half-spread " + TREASURY_WINNER.mean());
  }

  /** The same with informed_fraction 0: no drift, so the mean adverse move is 0 within 0.01 bp (200 sessions, s.e. 0.0015). */
  @Test
  void a3_withoutInformedFlowThereIsNoAdverseMove() {
    final MarketSpec quiet = new MarketSpec(p.withInformedFraction(0.0));
    assertEquals(0.0, quiet.informedVarianceBp2(), 0.0);
    assertEquals(p.factorSdPerSqrtMinute(0), quiet.levelDiffusiveSdPerSqrtMinute(), 1e-15);
    final Mean a = new Mean();
    for (int s = 0; s < 200; s++) {
      final MarketModel m = new MarketModel(quiet, SessionSeeds.seed(SessionSeeds.ACCEPTANCE, 2000 + s), DayType.NONE);
      for (final SimRfq r : m.rfqs()) {
        assertTrue(!r.informed());
        assertEquals(0.0, m.pendingDrift(steps), 0.0);
        if (r.arrivalNanos() + FIVE_MINUTES <= quiet.sessionNanos() && m.decide(r, Double.NaN, 0L).traded()) {
          a.add(m.adverseMoveBp(r, FIVE_MINUTES));
        }
      }
    }
    assertTrue(a.standardError() < 0.002);
    assertEquals(0.0, a.mean(), 0.01);
  }

  // ------------------------------------------------------------------ A4

  /** Model variance of a node's session-end yield change, bp^2, from the parameter file alone. */
  private static double nodeCovariance(final int a, final int b) {
    double c = 0.0;
    for (int j = 0; j < SimParams.FACTORS; j++) {
      c += p.loading(a, j) * p.loading(b, j) * p.factorSdPerSqrtMinute(j) * p.factorSdPerSqrtMinute(j);
    }
    if (a == b) {
      c += p.idiosyncraticSdPerSqrtMinute(a) * p.idiosyncraticSdPerSqrtMinute(a);
    }
    return c * p.get(Scalar.SESSION_MINUTES);
  }

  private static double covariance(final double[] x, final double[] y) {
    double mx = 0;
    double my = 0;
    for (int i = 0; i < x.length; i++) {
      mx += x[i];
      my += y[i];
    }
    mx /= x.length;
    my /= x.length;
    double c = 0;
    for (int i = 0; i < x.length; i++) {
      c += (x[i] - mx) * (y[i] - my);
    }
    return c / (x.length - 1);
  }

  private static double[] column(final int n) {
    final double[] out = new double[N];
    for (int s = 0; s < N; s++) {
      out[s] = nodeChange[s][n];
    }
    return out;
  }

  /**
   * Session-end changes of the seven node yields on days with no event. Each standard deviation
   * within 8 percent of sqrt(600 x (sum_j (L[T,j] sd_j)^2 + idio^2)) with the total level value
   * 0.3178 (the information drift supplies part of it): standard error 1 / sqrt(2 N) = 2.2
   * percent, so 8 percent is 3.6 standard errors.
   *
   * <p>Each pairwise correlation within the design's 0.03 of the model value where that is at
   * least 3.5 standard errors, (1 - rho^2) / sqrt(N - 1); for the weakly correlated pairs (2-year
   * against 20- and 30-year, rho near 0.6, standard error 0.02) the tolerance is 3.5 standard
   * errors, because 0.03 there would need more than 5,000 sessions.
   *
   * <p>First three principal components of the sample covariance against the loading columns:
   * cosine above 0.98.
   */
  @Test
  void a4_factorMomentsMatchTheCalibration() {
    final int nodes = p.nodes();
    final double[][] sample = new double[nodes][nodes];
    for (int a = 0; a < nodes; a++) {
      for (int b = a; b < nodes; b++) {
        sample[a][b] = covariance(column(a), column(b));
        sample[b][a] = sample[a][b];
      }
    }
    for (int a = 0; a < nodes; a++) {
      final double modelSd = Math.sqrt(nodeCovariance(a, a));
      assertEquals(1.0, Math.sqrt(sample[a][a]) / modelSd, 0.08, "sd of the " + p.tenorYears(a) + "-year node");
      for (int b = a + 1; b < nodes; b++) {
        final double rho = nodeCovariance(a, b) / Math.sqrt(nodeCovariance(a, a) * nodeCovariance(b, b));
        final double standardError = (1.0 - rho * rho) / Math.sqrt(N - 1.0);
        final double r = sample[a][b] / Math.sqrt(sample[a][a] * sample[b][b]);
        assertEquals(rho, r, Math.max(0.03, 3.5 * standardError), "correlation " + a + "," + b);
      }
    }
    final double[][] vectors = eigenvectors(sample);
    for (int j = 0; j < SimParams.FACTORS; j++) {
      double dot = 0.0;
      for (int n = 0; n < nodes; n++) {
        dot += vectors[j][n] * p.loading(n, j);
      }
      assertTrue(Math.abs(dot) > 0.98, "principal component " + j + " cosine " + dot);
    }
  }

  /** Eigenvectors of a symmetric matrix by Jacobi rotations, rows sorted by decreasing eigenvalue. */
  private static double[][] eigenvectors(final double[][] symmetric) {
    final int n = symmetric.length;
    final double[][] a = new double[n][];
    final double[][] v = new double[n][n];
    for (int i = 0; i < n; i++) {
      a[i] = symmetric[i].clone();
      v[i][i] = 1.0;
    }
    for (int sweep = 0; sweep < 100; sweep++) {
      double off = 0.0;
      for (int i = 0; i < n; i++) {
        for (int j = i + 1; j < n; j++) {
          off += a[i][j] * a[i][j];
        }
      }
      if (off < 1e-22) {
        break;
      }
      for (int i = 0; i < n; i++) {
        for (int j = i + 1; j < n; j++) {
          if (Math.abs(a[i][j]) < 1e-300) {
            continue;
          }
          final double theta = (a[j][j] - a[i][i]) / (2.0 * a[i][j]);
          final double t = Math.copySign(1.0, theta) / (Math.abs(theta) + Math.sqrt(theta * theta + 1.0));
          final double c = 1.0 / Math.sqrt(t * t + 1.0);
          final double s = t * c;
          for (int k = 0; k < n; k++) {
            final double aki = a[k][i];
            final double akj = a[k][j];
            a[k][i] = c * aki - s * akj;
            a[k][j] = s * aki + c * akj;
          }
          for (int k = 0; k < n; k++) {
            final double aik = a[i][k];
            final double ajk = a[j][k];
            a[i][k] = c * aik - s * ajk;
            a[j][k] = s * aik + c * ajk;
            final double vki = v[k][i];
            final double vkj = v[k][j];
            v[k][i] = c * vki - s * vkj;
            v[k][j] = s * vki + c * vkj;
          }
        }
      }
    }
    final List<double[]> pairs = new ArrayList<>();
    for (int j = 0; j < n; j++) {
      final double[] row = new double[n + 1];
      row[0] = a[j][j];
      for (int k = 0; k < n; k++) {
        row[k + 1] = v[k][j];
      }
      pairs.add(row);
    }
    pairs.sort((x, y) -> Double.compare(y[0], x[0]));
    final double[][] out = new double[n][n];
    for (int j = 0; j < n; j++) {
      System.arraycopy(pairs.get(j), 1, out[j], 0, n);
    }
    return out;
  }

  /**
   * Release days. Inside 08:30 to 08:35 every curve factor's diffusive variance per minute is
   * 1 + 181.4 times the normal one, so a session's variance is larger by the window's extra part.
   * The design states the ratio as (600 + 5 x 181.4) / 600 = 2.51; that shorthand scales the whole
   * variance, while the recursion of section 3 scales only the diffusive part (not the information
   * drift, 19 percent of the level variance, nor the idiosyncratic terms), which gives 2.22 at the
   * 10-year node. The test checks the recursion.
   *
   * <p>It measures the variance of the change across the window itself on N release-day sessions
   * against the closed form, within the design's 15 percent. The standard error of a variance from
   * N = 1000 is 4.5 percent, so 15 percent is 3.4 standard errors. (The session-end ratio has
   * standard error 6.3 percent at this N and would pass only 98 percent of the time.) Outside the
   * window a release day follows the same law as the days of {@link #a4_factorMomentsMatchTheCalibration}.
   */
  @Test
  void a4_releaseWindowCarriesTheCalibratedBurst() {
    final double perMinute = 60.0;
    final int from = (int) ((p.eventStartMinuteNy(DayType.RELEASE) - p.get(Scalar.SESSION_START_MINUTE_NY)) * perMinute);
    final int to = (int) ((p.eventEndMinuteNy(DayType.RELEASE) - p.get(Scalar.SESSION_START_MINUTE_NY)) * perMinute);
    final double minutes = (to - from) / perMinute;
    final int[] nodes = {0, 4, 6};
    final double[][] window = new double[nodes.length][N];
    final double[] before = new double[N];
    for (int s = 0; s < N; s++) {
      final MarketModel m = new MarketModel(spec, SessionSeeds.seed(SessionSeeds.ACCEPTANCE, 10_000 + s), DayType.RELEASE);
      for (int x = 0; x < nodes.length; x++) {
        final int i = spec.swapOfNode(nodes[x]);
        window[x][s] = m.yieldChangeBp(i, to) - m.yieldChangeBp(i, from);
      }
      before[s] = m.yieldChangeBp(spec.swapOfNode(4), from) - m.yieldChangeBp(spec.swapOfNode(4), from - 300);
    }
    final double levelDiffusive = spec.levelDiffusiveSdPerSqrtMinute() * spec.levelDiffusiveSdPerSqrtMinute();
    // information released inside the window: V_inf times the window's share of the day's arrivals
    final int bin = (int) (from / (p.get(Scalar.RFQ_BIN_MINUTES) * perMinute));
    final double informedShare = p.timeOfDayShare(bin) * minutes / p.get(Scalar.RFQ_BIN_MINUTES);
    for (int x = 0; x < nodes.length; x++) {
      final int n = nodes[x];
      final double diffusive =
          p.loading(n, 0) * p.loading(n, 0) * levelDiffusive
              + Math.pow(p.loading(n, 1) * p.factorSdPerSqrtMinute(1), 2)
              + Math.pow(p.loading(n, 2) * p.factorSdPerSqrtMinute(2), 2);
      final double model =
          minutes * (1.0 + p.eventExtraVariance(DayType.RELEASE)) * diffusive
              + minutes * Math.pow(p.idiosyncraticSdPerSqrtMinute(n), 2)
              + p.loading(n, 0) * p.loading(n, 0) * spec.informedVarianceBp2() * informedShare;
      assertEquals(1.0, covariance(window[x], window[x]) / model, 0.15, "window variance at node " + n);
    }
    // the five minutes before the window are ordinary: variance ratio to the window near 1 / 182
    final double ordinary = covariance(before, before);
    assertTrue(covariance(window[1], window[1]) / ordinary > 100.0);
  }

  // ------------------------------------------------------------------ A5

  /**
   * Hedging works by the model's ratios and not otherwise. Variances are over N = 1000 session-end
   * changes; the standard error of each variance is 4.5 percent, so the design's 15 percent is 3.4
   * standard errors.
   */
  @Test
  void a5_hedgeAtTheModelRatioRemovesTheTheoreticalVariance() {
    final double minutes = p.get(Scalar.SESSION_MINUTES);
    final double unhedged = covariance(swap10, swap10);
    final double spreadVariance = minutes * Math.pow(p.get(Scalar.SPREAD_SD_PER_SQRT_MINUTE), 2);
    assertEquals(1.0, unhedged / (nodeCovariance(4, 4) + spreadVariance), 0.15, "unhedged 10-year swap");

    // 10-year swap against the 10-year note at ratio 1: only the spread factor is left
    final double[] versusNote = new double[N];
    final double[] halfHedged = new double[N];
    final double[] versusTy = new double[N];
    final double ratio = p.loading(4, 0) / p.loading(3, 0);
    for (int s = 0; s < N; s++) {
      versusNote[s] = swap10[s] - note10[s];
      halfHedged[s] = swap10[s] - 0.5 * note10[s];
      versusTy[s] = swap10[s] - ratio * ty[s];
    }
    final double left = covariance(versusNote, versusNote);
    assertEquals(1.0, left / spreadVariance, 0.15, "residual variance against the note");
    assertEquals(0.169, spreadVariance, 0.001);
    assertTrue(1.0 - left / unhedged >= 0.97, "variance reduction " + (1.0 - left / unhedged));

    // against TY (7-year node) at L[10y, level] / L[7y, level]: the level factor cancels; slope,
    // curvature, the two idiosyncratic terms, the spread factor and the basis are left
    final double halfLife = p.get(Scalar.BASIS_HALF_LIFE_MINUTES);
    final double basisVariance =
        Math.pow(p.get(Scalar.BASIS_STATIONARY_SD_BP), 2) * (1.0 - Math.exp(-2.0 * Math.log(2.0) / halfLife * minutes));
    final double closedForm =
        minutes
                * (Math.pow((p.loading(4, 1) - ratio * p.loading(3, 1)) * p.factorSdPerSqrtMinute(1), 2)
                    + Math.pow((p.loading(4, 2) - ratio * p.loading(3, 2)) * p.factorSdPerSqrtMinute(2), 2)
                    + Math.pow(p.idiosyncraticSdPerSqrtMinute(4), 2)
                    + Math.pow(ratio * p.idiosyncraticSdPerSqrtMinute(3), 2))
            + spreadVariance
            + ratio * ratio * basisVariance;
    assertEquals(1.0, covariance(versusTy, versusTy) / closedForm, 0.15, "residual variance against TY");
    assertTrue(closedForm < 0.1 * unhedged);

    // half the correct ratio leaves at least a quarter of the variance (model value 26.3 percent;
    // the two variances share the same sessions, so the ratio's standard error is 0.2 percent)
    assertTrue(covariance(halfHedged, halfHedged) / unhedged >= 0.25);
  }

  // ------------------------------------------------------------------ A6

  /**
   * Doubling every tick changes no latent value and changes competitor quotes only by rounding:
   * each quote is within one new tick of its unrounded price and never inside it.
   */
  @Test
  void a6_ticksEnterOnlyWhereAPriceLeavesTheModel() {
    final List<SimInstrument> doubled = new ArrayList<>();
    for (final SimInstrument i : p.instruments()) {
      doubled.add(i.withTick(2.0 * i.tick()));
    }
    final MarketSpec coarse = new MarketSpec(p.withInstruments(doubled));
    final long seed = SessionSeeds.seed(SessionSeeds.ACCEPTANCE, 11);
    final MarketModel fine = new MarketModel(spec, seed, DayType.AUCTION);
    final MarketModel wide = new MarketModel(coarse, seed, DayType.AUCTION);
    for (int i = 0; i < spec.instruments(); i++) {
      for (int k = 0; k <= steps; k += 7) {
        assertEquals(Double.doubleToRawLongBits(fine.exogenousMid(i, k)), Double.doubleToRawLongBits(wide.exogenousMid(i, k)));
      }
      assertEquals(Double.doubleToRawLongBits(fine.exogenousMid(i, steps)), Double.doubleToRawLongBits(wide.exogenousMid(i, steps)));
    }
    assertEquals(fine.rfqs().size(), wide.rfqs().size());
    int quotes = 0;
    for (int r = 0; r < fine.rfqs().size(); r++) {
      final SimRfq x = fine.rfqs().get(r);
      final SimRfq y = wide.rfqs().get(r);
      assertEquals(x.hash(1L), y.hash(1L));
      final SimInstrument inst = coarse.instrument(y.instrument());
      final double sign = y.clientBuys() ? 1.0 : -1.0;
      final double mid = wide.midAtArrival(y);
      assertEquals(fine.midAtArrival(x), mid, 0.0);
      for (int d = 0; d < y.competitors(); d++) {
        final double unrounded = mid + sign * y.halfSpreadBp(d) * Math.abs(inst.priceChangePerBp());
        final double outside = sign * (wide.competitorQuote(y, d) - unrounded);
        assertTrue(outside >= -1e-12 && outside <= inst.tick() + 1e-12, "quote " + outside);
        final double onGrid = wide.competitorQuote(y, d) / inst.tick();
        assertEquals(Math.rint(onGrid), onGrid, 1e-6);
        // the fine quote is at least as good for the client and at most one coarse tick better
        final double gain = sign * (wide.competitorQuote(y, d) - fine.competitorQuote(x, d));
        assertTrue(gain >= -1e-12 && gain <= inst.tick() + 1e-12);
        quotes++;
      }
    }
    assertTrue(quotes > 400);
    // the published reference price is derived on each read and is on the grid; the latent mid is not
    final int note = spec.indexOf("UST-10Y");
    final long t = 5_000L * MarketSpec.STEP_NANOS;
    final double reference = wide.referencePrice(note, t);
    assertEquals(Math.rint(reference / coarse.instrument(note).tick()), reference / coarse.instrument(note).tick(), 0.0);
    assertTrue(Math.abs(reference - wide.latentMid(note, t)) <= coarse.instrument(note).tick() / 2.0);
    assertNotEquals(reference, wide.latentMid(note, t));
  }

  /** Over the N sessions every winning competitor quote sat between 0 and 1 tick outside its unrounded price. */
  @Test
  void a6_roundingIsAwayFromTheMidAndUnderOneTick() {
    assertEquals(0, quotesInsideUnrounded);
    assertTrue(TICK_ROUNDING_TICKS.mean() > 0.3 && TICK_ROUNDING_TICKS.mean() < 0.7, "mean " + TICK_ROUNDING_TICKS.mean());
  }

  // ------------------------------------------------------------------ A8

  /**
   * Information is about the market, not about the trade. With a desk that never quotes, the mean
   * signed 5-minute move after informed RFQs that did not trade equals the mean after those that
   * traded within 0.02 bp, both equal A, and the move at every other swap tenor is
   * A x L[T] / L[T_r] within 0.02 bp. Uninformed RFQs are followed by no move.
   *
   * <p>Standard errors, checked below: 0.0023 bp for the informed RFQs that did not trade (15,500
   * of them: passed, done away or unanswered), under 0.001 bp for the others; 0.02 bp is at least
   * 8 standard errors.
   */
  @Test
  void a8_informationMovesTheMarketWhoeverWins() {
    final double move = p.get(Scalar.MOVE_PER_INFORMED_TRADE_BP);
    assertTrue(INFORMED_NOT_TRADED.n > 5_000, "informed, not traded: " + INFORMED_NOT_TRADED.n);
    assertTrue(INFORMED_NOT_TRADED.standardError() < 0.005);
    assertEquals(INFORMED_TRADED.mean(), INFORMED_NOT_TRADED.mean(), 0.02);
    assertEquals(move, INFORMED_TRADED.mean(), 0.02);
    assertEquals(move, INFORMED_NOT_TRADED.mean(), 0.02);
    assertEquals(0.0, CROSS_TENOR_ERROR.mean(), 0.02);
    assertTrue(CROSS_TENOR_ERROR.standardError() < 0.001);
    assertEquals(0.0, UNINFORMED.mean(), 0.02);
  }

  /** The drift is exponential with the declared time constant, starts at the arrival and is complete in the level factor. */
  @Test
  void a8_driftFollowsTheDeclaredTimeConstant() {
    final MarketModel m = new MarketModel(spec, SessionSeeds.seed(SessionSeeds.ACCEPTANCE, 21), DayType.NONE);
    final double release = 1.0 - Math.exp(-1.0 / p.get(Scalar.DRIFT_TIME_CONSTANT_S));
    final double[] arrivals = new double[steps + 1];
    for (final SimRfq r : m.rfqs()) {
      if (r.informed()) {
        arrivals[(int) (r.arrivalNanos() / MarketSpec.STEP_NANOS)] +=
            m.yieldDirection(r) * p.get(Scalar.MOVE_PER_INFORMED_TRADE_BP) / spec.loading(r.instrument(), 0);
      }
    }
    double pending = 0.0;
    double released = 0.0;
    for (int k = 0; k < steps; k++) {
      assertEquals(pending, m.pendingDrift(k), 1e-12);
      released += pending * release;
      pending = pending - pending * release + arrivals[k];
    }
    // what arrived is either released into the level factor or still pending
    double total = 0.0;
    for (final double a : arrivals) {
      total += a;
    }
    assertEquals(total, released + m.pendingDrift(steps), 1e-9);
    assertTrue(Math.abs(released) > 0.0);
  }

  // ------------------------------------------------------------------ decision rule

  @Test
  void deskQuoteCompetesOnPriceThenTime() {
    final MarketModel m = new MarketModel(spec, SessionSeeds.seed(SessionSeeds.ACCEPTANCE, 5), DayType.NONE);
    int deskWins = 0;
    int covered = 0;
    final java.util.EnumSet<RfqDecision.Outcome> outcomes = java.util.EnumSet.noneOf(RfqDecision.Outcome.class);
    for (final SimRfq r : m.rfqs()) {
      final SimInstrument inst = spec.instrument(r.instrument());
      final double sign = r.clientBuys() ? 1.0 : -1.0;
      final double mid = m.midAtArrival(r);
      final RfqDecision alone = m.decide(r, Double.NaN, 0L);
      outcomes.add(alone.outcome());
      // a desk quote at the mid is the best price; it wins unless the client trades elsewhere
      final RfqDecision atMid = m.decide(r, mid, 1_000_000L);
      if (r.doneAway()) {
        assertEquals(RfqDecision.Outcome.DONE_AWAY, atMid.outcome());
        assertTrue(!atMid.traded() && !atMid.deskWon());
        continue;
      }
      assertTrue(atMid.deskWon());
      assertEquals(0.0, atMid.winnerHalfSpreadBp(), 1e-9);
      deskWins++;
      if (!Double.isNaN(atMid.coverPrice())) {
        // the cover is the best competitor: what would have won without the desk, reserve aside
        assertTrue(sign * (atMid.coverPrice() - mid) > 0.0);
        covered++;
      }
      // a late desk quote does not count; a quote far outside the reserve does not trade
      assertEquals(alone, m.decide(r, mid, 6_000_000_000L));
      final RfqDecision far = m.decide(r, mid + sign * 50.0 * Math.abs(inst.priceChangePerBp()), 1_000_000L);
      assertTrue(!far.deskWon());
      if (alone.traded()) {
        assertEquals(alone.winner(), far.winner());
        // a tie on price goes to the earlier quote
        final long theirs = r.delayNanos(alone.winner());
        assertTrue(m.decide(r, alone.price(), theirs - 1).deskWon());
        assertEquals(alone.winner(), m.decide(r, alone.price(), theirs + 1).winner());
        assertEquals(alone.price(), m.decide(r, alone.price(), theirs - 1).coverPrice(), 0.0);
        assertTrue(inst.toBp(sign * (alone.price() - mid)) <= r.reserveBp());
        assertTrue(alone.decisionDelayNanos() == 5_000_000_000L);
      }
      // with the desk in and every competitor answering, the client decides when the last answer is in
      boolean all = true;
      long last = 2_000_000L;
      for (int d = 0; d < r.competitors(); d++) {
        all &= r.answers(d);
        last = Math.max(last, r.delayNanos(d));
      }
      assertEquals(all ? last : 5_000_000_000L, m.decide(r, mid, 2_000_000L).decisionDelayNanos());
    }
    assertTrue(deskWins > 150 && covered > 150);
    // traded, passed and done away all occur in a session's 230 RFQs
    assertTrue(
        outcomes.containsAll(
            List.of(RfqDecision.Outcome.DONE, RfqDecision.Outcome.PASSED, RfqDecision.Outcome.DONE_AWAY)));
  }

  @Test
  void rfqAttributesAreInsideTheirRanges() {
    final MarketModel m = new MarketModel(spec, SessionSeeds.seed(SessionSeeds.ACCEPTANCE, 6), DayType.NONE);
    long previous = 0;
    for (int n = 0; n < m.rfqs().size(); n++) {
      final SimRfq r = m.rfqs().get(n);
      assertEquals(n, r.number());
      assertTrue(r.arrivalNanos() >= previous && r.arrivalNanos() < spec.sessionNanos());
      assertEquals(0, r.arrivalNanos() % 1_000_000L);
      previous = r.arrivalNanos();
      assertTrue(r.dealersAsked() >= 3 && r.dealersAsked() <= 5);
      assertEquals(r.dealersAsked() - 1, r.competitors());
      assertTrue(!r.answers(r.competitors()));
      assertTrue(r.tier() >= 0 && r.tier() < SimParams.TIERS);
      assertTrue(r.quantityUnits() >= 1e6 && r.quantityUnits() % 1e6 == 0.0);
      assertTrue(r.sizeDrawMm() > 0.0 && r.reserveBp() > 0.0);
      final SimInstrument inst = spec.instrument(r.instrument());
      assertTrue(inst.kind() != SimInstrument.Kind.FUTURE);
      if (inst.kind() == SimInstrument.Kind.TREASURY) {
        assertTrue(List.of(1.0, 5.0, 10.0, 25.0, 50.0).contains(r.sizeDrawMm()));
        assertEquals(m.yieldDirection(r), r.clientBuys() ? -1 : 1);
      } else {
        assertTrue(r.sizeDrawMm() <= p.sizeCapMm(spec.nodeLow(r.instrument())));
        assertEquals(m.yieldDirection(r), r.clientBuys() ? 1 : -1);
      }
      for (int d = 0; d < r.competitors(); d++) {
        assertTrue(r.halfSpreadBp(d) > 0.0 && r.delayNanos(d) > 0);
        assertTrue(!r.answers(d) || r.delayNanos(d) < 5_000_000_000L);
      }
    }
  }

  @Test
  void specExposesTheUniverse() {
    assertEquals(36_000, steps);
    assertEquals(12, spec.instruments());
    assertEquals(4, spec.treasuryCount());
    assertEquals(5, spec.bookCount());
    assertEquals(1, spec.futureCount());
    assertEquals(0, spec.futureOf(spec.indexOf("TY")));
    assertEquals(-1, spec.futureOf(spec.indexOf("UST-2Y")));
    assertEquals(-1, spec.bookOf(spec.indexOf("SOFR-2Y")));
    assertEquals(spec.indexOf("UST-2Y"), spec.treasury(0));
    assertEquals(spec.indexOf("TY"), spec.bookInstrument(4));
    assertEquals(3, spec.nodeLow(spec.indexOf("TY")));
    assertEquals(0.0, spec.nodeWeightHigh(spec.indexOf("TY")), 0.0);
    assertEquals(p, spec.params());
    assertEquals(0, spec.stepAt(-1L));
    assertEquals(steps, spec.stepAt(Long.MAX_VALUE));
    assertThrows(IllegalArgumentException.class, () -> spec.indexOf("UST-7Y"));
    final MarketModel m = new MarketModel(spec, 1L, DayType.NONE);
    assertThrows(IllegalArgumentException.class, () -> m.book(spec.indexOf("SOFR-10Y")));
    assertEquals(0.0, m.levelOffset(0L), 0.0);
  }
}
