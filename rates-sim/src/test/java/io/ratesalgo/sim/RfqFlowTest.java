package io.ratesalgo.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.sim.SimParams.Scalar;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Acceptance test A11 (arrivals and sizes) and the stream rules of the RFQ generator.
 *
 * <p>Sample size. The design asks for 400 sessions. Counts and shares are comfortable there, but
 * the median size of the thinnest tenor is not: the 20-year tenor gets 4.9 RFQs a session, a
 * sample median of n lognormal draws has relative standard error 1.2533 x log_sd / sqrt(n), which
 * is 3.7 percent at 400 sessions against the design's 5 percent. The RFQ list alone is cheap to
 * generate (no rate path), so this test uses 6,000 sessions; the standard errors are stated at
 * each assertion.
 */
class RfqFlowTest {

  private static final int SESSIONS = 6000;

  private static SimParams p;
  private static MarketSpec spec;
  private static long rfqs;
  private static long swaps;
  private static long[] byNode;
  private static long[] byBin;
  private static long[] byTier;
  private static long[] informedByTier;
  private static long[] byDealers;
  private static long[] byTreasury;
  private static long answered;
  private static long asked;
  private static long doneAway;
  private static long buys;
  private static long capped;
  private static List<List<Double>> sizes;
  private static double reserveLogSum;
  private static double halfSpreadLogSum;
  private static long halfSpreads;

  @BeforeAll
  static void generate() {
    p = SimParams.loadDefault();
    spec = new MarketSpec(p);
    byNode = new long[p.nodes()];
    byBin = new long[p.bins()];
    byTier = new long[SimParams.TIERS];
    informedByTier = new long[SimParams.TIERS];
    byDealers = new long[8];
    byTreasury = new long[spec.instruments()];
    sizes = new ArrayList<>();
    for (int n = 0; n < p.nodes(); n++) {
      sizes.add(new ArrayList<>());
    }
    final long binNanos = (long) (p.get(Scalar.RFQ_BIN_MINUTES) * 60e9);
    for (int s = 0; s < SESSIONS; s++) {
      for (final SimRfq r : RfqFlow.generate(spec, SessionSeeds.seed(SessionSeeds.ACCEPTANCE, 20_000 + s))) {
        rfqs++;
        byBin[(int) (r.arrivalNanos() / binNanos)]++;
        byTier[r.tier()]++;
        byDealers[r.dealersAsked()]++;
        buys += r.clientBuys() ? 1 : 0;
        doneAway += r.doneAway() ? 1 : 0;
        if (r.informed()) {
          informedByTier[r.tier()]++;
        }
        final SimInstrument inst = spec.instrument(r.instrument());
        final double median = spec.medianHalfSpreadBp(r.instrument());
        reserveLogSum += Math.log(r.reserveBp() / median);
        for (int d = 0; d < r.competitors(); d++) {
          asked++;
          answered += r.answers(d) ? 1 : 0;
          halfSpreadLogSum += Math.log(r.halfSpreadBp(d) / median);
          halfSpreads++;
        }
        if (inst.kind() == SimInstrument.Kind.SWAP) {
          swaps++;
          final int node = spec.nodeLow(r.instrument());
          byNode[node]++;
          sizes.get(node).add(r.sizeDrawMm());
          capped += r.sizeDrawMm() == p.sizeCapMm(node) ? 1 : 0;
        } else {
          byTreasury[r.instrument()]++;
        }
      }
    }
  }

  /**
   * RFQs per session within 2 percent of N_swap + N_tsy = 232.8. The count is Poisson, so the mean
   * over 6,000 sessions has relative standard error sqrt(232.8 / 6000) / 232.8 = 0.08 percent.
   */
  @Test
  void a11_rfqsPerSession() {
    final double expected = p.get(Scalar.SWAP_RFQS_PER_DAY) * (1.0 + p.get(Scalar.TREASURY_RFQS_PER_SWAP_RFQ));
    assertEquals(232.8, expected, 1e-9);
    assertEquals(1.0, (double) rfqs / SESSIONS / expected, 0.02);
    // half are swaps: standard error 0.5 / sqrt(1.4 million) = 0.0004
    assertEquals(0.5, (double) swaps / rfqs, 0.003);
  }

  /** Tenor mix and 30-minute shares within 0.01 of P: standard errors are at most 0.0006 and 0.0003. */
  @Test
  void a11_tenorMixAndTimeOfDay() {
    for (int n = 0; n < p.nodes(); n++) {
      assertEquals(p.tenorMix(n), (double) byNode[n] / swaps, 0.01, "tenor " + p.tenorYears(n));
    }
    for (int b = 0; b < p.bins(); b++) {
      assertEquals(p.timeOfDayShare(b), (double) byBin[b] / rfqs, 0.01, "bin " + b);
    }
    // Treasuries are uniform over the on-the-runs
    for (int t = 0; t < spec.treasuryCount(); t++) {
      assertEquals(1.0 / spec.treasuryCount(), (double) byTreasury[spec.treasury(t)] / (rfqs - swaps), 0.005);
    }
  }

  /**
   * Median swap notional per tenor within 5 percent of exp(log_mean_mm), measured on the size as
   * drawn (the traded size is rounded to whole millions, which alone moves the 20-year median from
   * 7.56 to 8, more than 5 percent).
   *
   * <p>Two things use the tolerance. The redraw above the cap removes the top 0.3 to 1.1 percent of
   * the distribution, which lowers the median by at most 1.9 percent (3-year and 7-year). The
   * sampling error at 6,000 sessions is at most 0.96 percent (20-year, 29,000 draws) and 0.88
   * percent (7-year). The margin left is at least 3.5 standard errors.
   */
  @Test
  void a11_medianSwapSizeByTenor() {
    for (int n = 0; n < p.nodes(); n++) {
      final List<Double> draws = sizes.get(n);
      Collections.sort(draws);
      final double median = draws.get(draws.size() / 2);
      assertEquals(1.0, median / Math.exp(p.sizeLogMeanMm(n)), 0.05, "median size at " + p.tenorYears(n) + " years");
      assertTrue(draws.get(draws.size() - 1) <= p.sizeCapMm(n));
    }
    // twenty redraws all above the cap have probability below 0.012^20: the cap itself is never drawn
    assertEquals(0, capped);
  }

  /**
   * The remaining attributes against their declared probabilities. Standard errors with 1.4 million
   * RFQs are below 0.001 for every share here; the tolerances are 0.005.
   */
  @Test
  void clientAndCompetitorAttributesHaveTheirDeclaredFrequencies() {
    double informed = 0;
    for (int t = 0; t < SimParams.TIERS; t++) {
      assertEquals(p.tierWeight(t), (double) byTier[t] / rfqs, 0.005);
      assertEquals(p.tierInformedProbability(t), (double) informedByTier[t] / byTier[t], 0.005);
      informed += informedByTier[t];
    }
    assertEquals(p.get(Scalar.INFORMED_FRACTION), informed / rfqs, 0.005);
    assertEquals(0.5, (double) buys / rfqs, 0.005);
    assertEquals(p.get(Scalar.DONE_AWAY_PROBABILITY), (double) doneAway / rfqs, 0.005);
    for (int k = 3; k <= 5; k++) {
      assertEquals(1.0 / 3.0, (double) byDealers[k] / rfqs, 0.005);
    }
    assertEquals(Competition.answerProbability(p), (double) answered / asked, 0.005);
    // medians: the mean log of reserve / median and of half-spread / median are log(2.5) and 0
    assertEquals(Math.log(p.get(Scalar.RESERVE_MULTIPLE_MEDIAN)), reserveLogSum / rfqs, 0.005);
    assertEquals(0.0, halfSpreadLogSum / halfSpreads, 0.005);
  }

  /** The closed forms the start-up check uses, against known values. */
  @Test
  void competitionClosedForms() {
    // one quote: the lognormal mean exp(s^2 / 2); two: 2 exp(s^2 / 2) Phi(-s / sqrt 2)
    assertEquals(Math.exp(0.125), Competition.expectedMinOf(1, 0.5), 1e-9);
    assertEquals(2.0 * Math.exp(0.125) * Gaussian.cdf(-0.5 / Math.sqrt(2.0)), Competition.expectedMinOf(2, 0.5), 1e-9);
    assertEquals(1.0, Competition.expectedMinOf(3, 0.0), 1e-9);
    assertTrue(Competition.expectedMinOf(4, 0.5) < Competition.expectedMinOf(3, 0.5));
    // the design's 0.761 at the committed parameters
    assertEquals(0.761, Competition.expectedBestPerMedian(p), 0.0005);
    assertEquals(0.761, spec.expectedBestPerMedian(), 0.0005);
    assertEquals(0.9 * Gaussian.cdf(Math.log(6.25) / 0.6), Competition.answerProbability(p), 1e-12);
  }

  /**
   * Draws that are not used are still made: changing who is asked, the cap or the response
   * probability leaves every other attribute of every RFQ where it was.
   */
  @Test
  void streamPositionDoesNotDependOnOutcomes() {
    final long seed = SessionSeeds.seed(SessionSeeds.ACCEPTANCE, 31);
    final List<SimRfq> base = RfqFlow.generate(spec, seed);
    final List<SimRfq> fewer = RfqFlow.generate(new MarketSpec(p.with(Scalar.RESPONSE_PROBABILITY, 0.8)
        .withZeroProfit(p.get(Scalar.MEDIAN_HALF_SPREAD_BP), p.get(Scalar.ALL_IN_COST_BP))), seed);
    assertEquals(base.size(), fewer.size());
    int changed = 0;
    for (int r = 0; r < base.size(); r++) {
      final SimRfq a = base.get(r);
      final SimRfq b = fewer.get(r);
      assertEquals(a.arrivalNanos(), b.arrivalNanos());
      assertEquals(a.instrument(), b.instrument());
      assertEquals(a.clientBuys(), b.clientBuys());
      assertEquals(a.quantityUnits(), b.quantityUnits(), 0.0);
      assertEquals(a.tier(), b.tier());
      assertEquals(a.informed(), b.informed());
      assertEquals(a.dealersAsked(), b.dealersAsked());
      assertEquals(a.reserveBp(), b.reserveBp(), 0.0);
      assertEquals(a.doneAway(), b.doneAway());
      for (int d = 0; d < a.competitors(); d++) {
        assertEquals(a.halfSpreadBp(d), b.halfSpreadBp(d), 0.0);
        assertEquals(a.delayNanos(d), b.delayNanos(d));
        assertTrue(a.answers(d) || !b.answers(d));
        changed += a.answers(d) != b.answers(d) ? 1 : 0;
      }
    }
    assertTrue(changed > 10);
  }

  /** A12: a configuration that breaks a start-up check cannot produce a session. */
  @Test
  void a12_startUpChecksRefuseInconsistentConfigurations() {
    // zero-profit identity off by more than 0.001 bp
    assertTrue(
        assertThrows(IllegalArgumentException.class, () -> new MarketSpec(p.with(Scalar.ALL_IN_COST_BP, 0.082)))
            .getMessage()
            .contains("zero-profit identity"));
    assertThrows(IllegalArgumentException.class, () -> new MarketSpec(p.with(Scalar.MEDIAN_HALF_SPREAD_BP, 0.19)));
    assertThrows(IllegalArgumentException.class, () -> new MarketSpec(p.with(Scalar.COMPETITOR_LOG_SD, 0.6)));
    // and inside the tolerance it starts
    assertEquals(36_000, new MarketSpec(p.with(Scalar.ALL_IN_COST_BP, 0.0805)).steps());

    // informed flow above 60 percent of the level variance: V_inf is 11.7 bp^2 of 60.6 at the
    // committed values and grows as 1 / fraction at a fixed average move
    assertEquals(0.3178 * 0.3178 * 600.0, 60.6, 0.01);
    assertEquals(11.71, spec.informedVarianceBp2(), 0.01);
    assertEquals(Math.sqrt((60.598 - spec.informedVarianceBp2()) / 600.0), spec.levelDiffusiveSdPerSqrtMinute(), 1e-4);
    assertEquals(29.3, new MarketSpec(p.withInformedFraction(0.2)).informedVarianceBp2(), 0.05);
    assertTrue(
        assertThrows(IllegalArgumentException.class, () -> new MarketSpec(p.withInformedFraction(0.15)))
            .getMessage()
            .contains("above the allowed share"));
    // the design's upper winner band (median 0.2719 with the adverse move solved again) is refused
    // at informed fraction 0.5: it needs 52 bp^2 of the level factor's 60.6
    final SimParams upper = p.withZeroProfit(0.2719, 0.08);
    assertThrows(IllegalArgumentException.class, () -> new MarketSpec(upper));
    assertEquals(36_000, new MarketSpec(p.withInformedFraction(1.0).withZeroProfit(0.2719, 0.08)).steps());

    // the universe must carry what the flow needs
    final List<SimInstrument> noSevenYearSwap = new ArrayList<>(p.instruments());
    noSevenYearSwap.removeIf(i -> i.key().equals("SOFR-7Y"));
    assertTrue(
        assertThrows(IllegalArgumentException.class, () -> new MarketSpec(p.withInstruments(reindex(noSevenYearSwap))))
            .getMessage()
            .contains("no swap instrument"));
    final List<SimInstrument> oneTreasury = new ArrayList<>(p.instruments());
    oneTreasury.removeIf(i -> i.kind() == SimInstrument.Kind.TREASURY && !i.key().equals("UST-10Y"));
    assertEquals(1, new MarketSpec(p.withInstruments(reindex(oneTreasury))).treasuryCount());
    // arrival bins beyond the Poisson inversion's range are refused rather than silently wrong
    final MarketSpec busy = new MarketSpec(p.withInformedFraction(0.0).with(Scalar.SWAP_RFQS_PER_DAY, 4000.0));
    assertThrows(IllegalArgumentException.class, () -> RfqFlow.generate(busy, 1L));
  }

  private static List<SimInstrument> reindex(final List<SimInstrument> list) {
    final List<SimInstrument> out = new ArrayList<>();
    for (final SimInstrument i : list) {
      out.add(
          new SimInstrument(
              out.size(), i.key(), i.id(), i.kind(), i.nodeYears(), i.initialMid(), i.priceChangePerBp(), i.tick(),
              i.unitFaceUsd(), i.minQuantity(), i.annuity()));
    }
    return out;
  }
}
