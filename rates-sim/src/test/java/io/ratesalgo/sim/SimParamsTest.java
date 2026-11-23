package io.ratesalgo.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.sim.SimParams.Scalar;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The parameter loader and the start-up checks (acceptance test A12): a configuration is refused
 * if an entry is missing or unknown, a unit is wrong, the loadings are not unit length, the
 * zero-profit identity fails by more than 0.001 bp, or informed flow would supply more than 60
 * percent of the level variance.
 */
class SimParamsTest {

  private static final String P = SimParams.defaultParamsText();
  private static final String S = SimParams.defaultStructureText();

  private static String refused(final String params, final String structure) {
    return assertThrows(IllegalArgumentException.class, () -> SimParams.parse(params, structure)).getMessage();
  }

  private static String swap(final String text, final String from, final String to) {
    assertTrue(text.contains(from), "fixture text not found: " + from);
    return text.replace(from, to);
  }

  @Test
  void committedFilesLoadWithTheCalibratedValues() {
    final SimParams p = SimParams.loadDefault();
    assertEquals(600.0, p.get(Scalar.SESSION_MINUTES));
    assertEquals(420.0, p.get(Scalar.SESSION_START_MINUTE_NY));
    assertEquals(7, p.nodes());
    assertEquals(10.0, p.tenorYears(4));
    assertEquals(0.3945, p.loading(4, 0));
    assertEquals(-0.1959, p.loading(4, 2));
    assertEquals(0.3178, p.factorSdPerSqrtMinute(0));
    assertEquals(13.27695, p.factorDailySdBp(0));
    assertEquals(0.0136, p.idiosyncraticSdPerSqrtMinute(4));
    assertEquals(0.3371, p.tenorMix(4));
    assertEquals(20, p.bins());
    assertEquals(0.07462, p.timeOfDayShare(3));
    assertEquals(2.5838, p.sizeLogMeanMm(4));
    assertEquals(1.223, p.sizeLogSd(4));
    assertEquals(470.0, p.sizeCapMm(4));
    assertEquals(0.1827, p.get(Scalar.MEDIAN_HALF_SPREAD_BP));
    assertEquals(3.0, p.get(Scalar.DEALERS_MIN));
    assertEquals(5.0, p.get(Scalar.DEALERS_MAX));
    assertEquals(0.5, p.tierWeight(0));
    assertEquals(0.3, p.tierWeight(1));
    assertEquals(0.2, p.tierWeight(2));
    assertEquals(0.975, p.tierInformedProbability(2), 1e-12);
    assertEquals(5, p.ladderSteps());
    assertEquals(50.0, p.ladderSizeMm(4));
    assertEquals(0.02, p.ladderWeight(4));
    assertEquals(5, p.bookLevels());
    assertEquals(1.5, p.depthMultiple(1));
    assertEquals(181.4, p.eventExtraVariance(DayType.RELEASE));
    assertEquals(30.2, p.eventExtraVariance(DayType.FOMC));
    assertEquals(16.3, p.eventExtraVariance(DayType.AUCTION));
    assertEquals(0.0, p.eventExtraVariance(DayType.NONE));
    assertEquals(510, p.eventStartMinuteNy(DayType.RELEASE));
    assertEquals(515, p.eventEndMinuteNy(DayType.RELEASE));
    assertEquals(840, p.eventStartMinuteNy(DayType.FOMC));
    assertEquals(870, p.eventEndMinuteNy(DayType.FOMC));
    assertEquals(-1, p.eventStartMinuteNy(DayType.NONE));
    assertEquals(-1, p.eventEndMinuteNy(DayType.NONE));
    assertEquals(12, p.instruments().size());
    assertEquals("UST-10Y", p.depthReferenceKey());
    assertEquals(2, p.treasuryVenues());
    assertEquals(0.6, p.treasuryVenueShare(0));
    assertEquals(0.4, p.treasuryVenueShare(1));
    assertEquals(0.7, p.get(Scalar.IMPACT_Y));
    assertEquals(800.0, p.get(Scalar.DEALER_DELAY_MEDIAN_MS));
  }

  @Test
  void missingUnknownOrDuplicateKeysAreRefused() {
    assertTrue(refused(swap(P, "\"taker_sign_skill\"", "\"taker_sign_skil\""), S).contains("missing key"));
    assertTrue(
        refused(
                swap(
                    P,
                    "\"taker_sign_skill\"",
                    "\"taker_patience\": {\"value\": 1.0, \"unit\": \"seconds\", \"status\": \"assumption\"}, \"taker_sign_skill\""),
                S)
            .contains("unknown key 'taker_patience'"));
    assertTrue(refused(swap(P, "\"swap_rfqs_per_day\"", "\"swap_rfqs\""), S).contains("missing key"));
    assertTrue(refused(swap(P, "\"schema\": \"sim_v2_params/1\",", "\"schema\": \"sim_v2_params/1\", \"extra\": 1,"), S)
        .contains("unknown key 'extra'"));
    assertTrue(refused(swap(P, "\"schema\": \"sim_v2_params/1\"", "\"schema\": \"sim_v2_params/2\""), S).contains("schema"));
    assertTrue(refused(swap(P, "\"status\": \"assumption\",\n      \"note\": \"no futures data in the repository; Ornstein-Uhlenbeck\"",
        "\"status\": \"assumption\",\n      \"comment\": \"x\""), S).contains("unknown key 'comment'"));
    assertTrue(refused(swap(P, "\"minutes\": 600.0", "\"minutes\": 600.0, \"minutes\": 600.0"), S).contains("duplicate key"));
    assertTrue(refused(swap(P, "\"30y\": 0.1577\n", "\"30y\": 0.1577, \"40y\": 0.0\n"), S).contains("unknown key '40y'"));
    assertTrue(refused(P, swap(S, "\"delay_log_sd\": 0.6,", "")).contains("missing key 'delay_log_sd'"));
    assertTrue(refused(P, swap(S, "\"step_seconds\": 1.0", "\"step_seconds\": 1.0, \"substeps\": 4.0")).contains("unknown key"));
    assertTrue(refused(P, swap(S, "\"sim_v2_structure/1\"", "\"sim_v2_structure/9\"")).contains("schema"));
    assertTrue(refused(P, swap(S, "\"key\": \"TY\"", "\"key\": \"UST-2Y\"")).contains("duplicate key UST-2Y"));
    assertTrue(refused(P, swap(S, "\"annuity\": 17.29}", "\"annuity\": 17.29, \"cusip\": \"x\"}")).contains("unknown key"));
  }

  @Test
  void wrongUnitsTypesAndStatusesAreRefused() {
    assertTrue(refused(swap(P, "\"unit\": \"seconds (exponential approach)\"", "\"unit\": \"milliseconds\""), S)
        .contains("does not start with 'seconds'"));
    assertTrue(refused(swap(P, "\"unit\": \"bp per sqrt-minute\",\n      \"interval\": [\n        0.0072", "\"unit\": \"bp per sqrt-hour\",\n      \"interval\": [\n        0.0072"), S)
        .contains("does not start with"));
    assertTrue(refused(swap(P, "\"value\": 0.0168", "\"value\": \"0.0168\""), S).contains("a number is required"));
    assertTrue(refused(swap(P, "\"value\": 0.0168,", ""), S).contains("missing key 'value'"));
    assertTrue(refused(swap(P, "\"status\": \"bounded\",\n      \"note\": \"daily_sd with", "\"status\": \"guessed\",\n      \"note\": \"daily_sd with"), S)
        .contains("unknown status"));
    assertTrue(refused(swap(P, "\"start_ny\": \"07:00\"", "\"start_ny\": \"7am\""), S).contains("HH:MM"));
    assertTrue(refused(swap(P, "\"start_ny\": \"07:00\"", "\"start_ny\": \"07:30\""), S).contains("minutes is not"));
    assertTrue(refused(swap(P, "\"session\": {", "\"session\": [], \"was_session\": {"), S).contains("an object is required"));
    assertTrue(refused(swap(P, "\"start_ny\": \"07:00\"", "\"start_ny\": 7.0"), S).contains("text is required"));
    assertTrue(refused(swap(P, "\"tenors_years\": [", "\"tenors_years\": 7.0, \"was\": ["), S).contains("a list is required"));
    assertTrue(refused(P, swap(S, "[0.25, 0.60, 0.975]", "[0.25, 0.60]")).contains("a list of 3 numbers"));
    assertTrue(refused(P, swap(S, "\"kind\": \"future\"", "\"kind\": \"swap\"")).contains("a swap rate rises"));
    assertTrue(refused(P, swap(S, "\"tick\": 0.015625", "\"tick\": 0.0")).contains("invalid instrument"));
    assertTrue(refused(P, swap(S, "\"key\": \"TY\"", "\"key\": \"\"")).contains("needs a key"));
  }

  @Test
  void brokenIdentitiesAreRefused() {
    // loadings not unit length within 0.001 (A12)
    assertTrue(refused(swap(P, "0.3383,", "0.3683,"), S).contains("length of loading column 0"));
    assertTrue(refused(swap(P, "\"10y\": 0.3371", "\"10y\": 0.3571"), S).contains("tenor_mix"));
    assertTrue(refused(swap(P, "0.0193,", "0.0393,"), S).contains("time_of_day_share"));
    assertTrue(refused(P, swap(S, "[0.25, 0.60, 0.975]", "[0.25, 0.60, 0.9]")).contains("tier-weighted informed probability"));
    assertTrue(refused(P, swap(S, "[50.0, 0.02]", "[50.0, 0.12]")).contains("ladder"));
    assertTrue(refused(P, swap(S, "[0.6, 0.4]", "[0.6, 0.5]")).contains("venue shares"));
    assertTrue(refused(P, swap(S, "\"bin_minutes\": 30.0", "\"bin_minutes\": 20.0")).contains("arrival bins"));
    assertTrue(refused(P, swap(S, "\"step_seconds\": 1.0", "\"step_seconds\": 0.5")).contains("grid step"));
    assertTrue(refused(P, swap(S, "\"depth_reference_instrument\": \"UST-10Y\"", "\"depth_reference_instrument\": \"TY\""))
        .contains("depth_reference_instrument"));
    assertTrue(refused(swap(P, "\"value\": 0.118", "\"value\": 0.2"), S).contains("move_per_informed_trade"));
    assertTrue(refused(swap(P, "\"tenors_years\": [\n    2,\n    3,", "\"tenors_years\": [\n    3,\n    2,"), S)
        .contains("must increase"));
    final SimParams p = SimParams.loadDefault();
    assertThrows(IllegalArgumentException.class, () -> p.with(Scalar.TIME_OF_DAY_MULTIPLIER, 1.2));
    assertThrows(IllegalArgumentException.class, () -> p.with(Scalar.SIZE_AND_TENOR_SCALING, 0.3));
    assertThrows(IllegalArgumentException.class, () -> p.with(Scalar.DEALERS_MIN, 1.0));
    assertThrows(IllegalArgumentException.class, () -> p.with(Scalar.IMPACT_Y, -0.1));
    assertThrows(IllegalArgumentException.class, () -> p.with(Scalar.IMPACT_Y, Double.NaN));
    assertThrows(IllegalArgumentException.class, () -> p.withInformedFraction(1.5));
  }

  @Test
  void strictJsonRefusesMalformedText() {
    for (final String bad :
        List.of("", "{", "{\"a\" 1}", "{\"a\": 1,}", "{\"a\": 1} x", "[1, 2", "[1 2]", "{a: 1}", "{\"a\": tru}",
            "{\"a\": \"x}", "{\"a\": \"\\q\"}", "{\"a\": \"x\\", "{\"a\": 1e999}", "{\"a\": 1.2.3}", "{\"a\": 1 \"b\": 2}")) {
      assertThrows(IllegalArgumentException.class, () -> StrictJson.parse(bad), bad);
    }
    assertEquals(List.of(1.0, true, false, "a\"\n\t\r/\\"), StrictJson.parse("[1, true, false, \"a\\\"\\n\\t\\r\\/\\\\\"]"));
    assertEquals(new ArrayList<>(), StrictJson.parse(" [ ] "));
    assertTrue(((java.util.Map<?, ?>) StrictJson.parse("{}")).isEmpty());
    assertTrue(((java.util.Map<?, ?>) StrictJson.parse("{\"a\": null}")).containsKey("a"));
  }

  @Test
  void sensitivityCopiesKeepTheIdentities() {
    final SimParams p = SimParams.loadDefault();
    // informed fraction at a fixed average move: the tiers keep their order and their mean is the fraction
    for (final double f : new double[] {0.0, 0.2, 0.5, 1.0}) {
      final SimParams q = p.withInformedFraction(f);
      double mean = 0.0;
      for (int t = 0; t < SimParams.TIERS; t++) {
        final double pr = q.tierInformedProbability(t);
        assertTrue(pr >= 0.0 && pr <= 1.0);
        mean += q.tierWeight(t) * pr;
      }
      assertEquals(f, mean, 1e-12);
      assertEquals(f == 0.0 ? 0.0 : 0.059 / f, q.get(Scalar.MOVE_PER_INFORMED_TRADE_BP), 1e-12);
    }
    assertEquals(0.1, p.withInformedFraction(0.2).tierInformedProbability(0), 1e-12);
    assertEquals(0.39, p.withInformedFraction(0.2).tierInformedProbability(2), 1e-12);

    // the winner half-spread band of section 12: the adverse move is solved again for zero profit
    final SimParams low = p.withZeroProfit(0.1228, 0.08);
    final double best = Competition.expectedBestPerMedian(p);
    assertEquals(0.1228 * best - 0.08, low.get(Scalar.EXPECTED_ADVERSE_MOVE_BP), 1e-12);
    assertEquals(low.get(Scalar.EXPECTED_ADVERSE_MOVE_BP) / 0.5, low.get(Scalar.MOVE_PER_INFORMED_TRADE_BP), 1e-12);
    assertThrows(IllegalArgumentException.class, () -> p.withZeroProfit(0.05, 0.08));
    assertEquals(0.0, p.withInformedFraction(0.0).withZeroProfit(0.2, 0.08).get(Scalar.MOVE_PER_INFORMED_TRADE_BP));

    final SimParams scaled = p.withFactorSdScale(1.29);
    assertEquals(0.3178 * 1.29, scaled.factorSdPerSqrtMinute(0), 1e-12);
    assertEquals(13.27695 * 1.29, scaled.factorDailySdBp(0), 1e-12);
    assertEquals(0.3178, p.factorSdPerSqrtMinute(0));
    assertEquals(0.0136, scaled.idiosyncraticSdPerSqrtMinute(4));

    assertEquals(60.0, p.with(Scalar.DRIFT_TIME_CONSTANT_S, 60.0).get(Scalar.DRIFT_TIME_CONSTANT_S));
    assertEquals(10.0, p.get(Scalar.DRIFT_TIME_CONSTANT_S));
    final List<SimInstrument> fewer = p.instruments().subList(0, 11);
    assertEquals(11, p.withInstruments(fewer).instruments().size());
  }

  @Test
  void instrumentConversions() {
    final SimInstrument note = SimParams.loadDefault().instruments().get(9);
    assertEquals("UST-10Y", note.key());
    assertTrue(note.hasBook());
    assertEquals(2.0, note.toBp(0.163), 1e-12);
    assertEquals(100.0078125, note.roundToTick(100.0040), 0.0);
    assertEquals(100.0, note.roundToTick(100.0039), 0.0);
    assertEquals(-0.0078125, note.roundToTick(-0.00390625), 0.0);
    assertEquals(0.015625, note.withTick(0.015625).tick());
    assertTrue(!SimParams.loadDefault().instruments().get(0).hasBook());
  }

  @Test
  void seedBasesAndDayTypes() {
    assertEquals(28_000_005L, SessionSeeds.seed(SessionSeeds.ACCEPTANCE, 5));
    assertThrows(IllegalArgumentException.class, () -> SessionSeeds.seed(5_000_000L, 0));
    assertThrows(IllegalArgumentException.class, () -> SessionSeeds.seed(8_000_000L, 0));
    assertThrows(IllegalArgumentException.class, () -> SessionSeeds.seed(28_500_000L, 0));
    assertThrows(IllegalArgumentException.class, () -> SessionSeeds.seed(SessionSeeds.PILOT, -1));
    assertThrows(IllegalArgumentException.class, () -> SessionSeeds.seed(SessionSeeds.PILOT, SessionSeeds.SPAN));
    assertTrue(SessionSeeds.RESERVED > SessionSeeds.CONFIRMATION && SessionSeeds.CONFIRMATION > SessionSeeds.EXPLORATION);
    // 4 release, 1 FOMC, 6 auction, 10 none in every 21 sessions
    final java.util.EnumMap<DayType, Integer> count = new java.util.EnumMap<>(DayType.class);
    for (int n = 0; n < 2100; n++) {
      count.merge(SessionSeeds.dayType(SessionSeeds.seed(SessionSeeds.EXPLORATION, n)), 1, Integer::sum);
    }
    assertEquals(1000, count.get(DayType.NONE));
    assertEquals(400, count.get(DayType.RELEASE));
    assertEquals(100, count.get(DayType.FOMC));
    assertEquals(600, count.get(DayType.AUCTION));
    assertEquals(DayType.RELEASE, DayType.ofSession(0));
    assertEquals(DayType.FOMC, DayType.ofSession(4));
    assertEquals(DayType.AUCTION, DayType.ofSession(10));
    assertEquals(DayType.NONE, DayType.ofSession(11));
    assertEquals(DayType.CYCLE, 21);
  }
}
