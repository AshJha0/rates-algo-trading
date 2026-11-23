package io.ratesalgo.hedge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.api.KeyRateTenors;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Review 2026-11-23, Q2: the fitted three-factor covariance in place of exp(-0.06 dt). */
class FactorModelTest {

  private static final int Y1 = 0;
  private static final int Y2 = 1;
  private static final int Y10 = 5;
  private static final int Y30 = 7;

  @Test
  void theShippedCalibrationIsTheRoundThreeFit() {
    final FactorModel m = FactorModel.calibrated();
    assertEquals(0.3945, m.loading(Y10, FactorModel.LEVEL), 1e-12);
    assertEquals(0.5125, m.loading(Y30, FactorModel.SLOPE), 1e-12);
    assertEquals(13.27695, m.factorSdBp(FactorModel.LEVEL), 1e-12);
    // below the 2-year node the simulator uses the 2-year node, so the 1Y pillar copies it
    for (int f = 0; f < FactorModel.FACTORS; f++) {
      assertEquals(m.loading(Y2, f), m.loading(Y1, f), 1e-15);
    }
    assertEquals(1.0, m.covariance().correlation(Y1, Y2), 1e-12, "same node, same idiosyncratic term");
    // the idiosyncratic sd is scaled from per-sqrt-minute like the level factor: 13.27695 / 0.3178
    assertEquals(0.0136 * 13.27695 / 0.3178, m.idioSdBp(Y10), 1e-12);
    assertEquals(0.7, m.spreadSdBp(), 1e-15);
    assertEquals(0.15 * Math.sqrt(2.0), m.basisSdBp(), 1e-15);
    assertTrue(m.source().contains("hedge-sim-v2-factors.json"));
  }

  @Test
  void tenYearVolatilityMatchesTheDesignDocument() {
    // SIM_V2_DESIGN.md Section 8: daily yield sd at the 10-year node is 5.3 bp
    final FactorModel m = FactorModel.calibrated();
    assertEquals(5.35, m.covariance().volBp(Y10), 0.05);
  }

  @Test
  void tensThirtiesCorrelationIsNoLongerThirtyPercent() {
    final double fitted = FactorModel.calibrated().covariance().correlation(Y10, Y30);
    final double assumed = KeyRateCovariance.assumedDefault().correlation(Y10, Y30);
    assertEquals(Math.exp(-0.06 * 20.0), assumed, 1e-12);
    assertTrue(assumed < 0.31, "the old assumption: " + assumed);
    assertTrue(fitted > 0.9 && fitted < 0.95, "fitted 10s30s correlation " + fitted);
  }

  @Test
  void aLevelHedgeOfTheThirtyYearWithTheTenYearRemovesMostOfItsVariance() {
    final KeyRateCovariance fitted = FactorModel.calibrated().covariance();
    final KeyRateCovariance assumed = KeyRateCovariance.assumedDefault();
    assertTrue(leftAfterMinVarianceHedge(fitted) < 0.15, "fitted: " + leftAfterMinVarianceHedge(fitted));
    assertTrue(leftAfterMinVarianceHedge(assumed) > 0.9, "assumed: " + leftAfterMinVarianceHedge(assumed));
  }

  private static double leftAfterMinVarianceHedge(final KeyRateCovariance c) {
    final double rho = c.correlation(Y10, Y30);
    return 1.0 - rho * rho;
  }

  @Test
  void exposuresAndIdiosyncraticVarianceAddUpToTheCovariance() {
    final FactorModel m = FactorModel.calibrated();
    final double[] ladder = new double[KeyRateTenors.COUNT];
    ladder[Y10] = 1_000.0;
    ladder[Y30] = -400.0;
    ladder[Y1] = 250.0;
    final double[] e = new double[FactorModel.FACTORS];
    m.exposures(ladder, e);
    double v = m.idioVariance(ladder);
    for (int f = 0; f < FactorModel.FACTORS; f++) {
      v += e[f] * e[f] * m.factorSdBp(f) * m.factorSdBp(f);
    }
    assertEquals(m.covariance().quadratic(ladder, ladder), v, 1e-6 * v);
    assertEquals("curvature", FactorModel.factorName(FactorModel.CURVATURE));
  }

  @Test
  void loadsTheFullCalibrationFileShape(@TempDir final Path dir) throws IOException {
    final String shipped = resource();
    // the real file wraps more blocks around the same keys; the reader ignores the rest
    final String full = "{\"rfq\": {\"x\": [1, 2.5e-1, true, false, null]}," + shipped.substring(shipped.indexOf('{') + 1);
    final Path p = dir.resolve("sim_v2_params.json");
    Files.writeString(p, full, StandardCharsets.UTF_8);
    final FactorModel m = FactorModel.load(p);
    assertEquals(FactorModel.calibrated().covariance().get(Y10, Y30), m.covariance().get(Y10, Y30), 0.0);
    assertEquals(p.toString(), m.source());
  }

  @Test
  void refusesADocumentWithoutTheFactorBlock() {
    assertThrows(IllegalArgumentException.class, () -> FactorModel.parse("{\"schema\": \"other/1\"}", "x"));
    assertThrows(IllegalArgumentException.class, () -> FactorModel.parse("{\"schema\": \"sim_v2_params/1\"}", "x"));
    final String shipped = resource();
    assertThrows(
        IllegalArgumentException.class,
        () -> FactorModel.parse(shipped.replace("[13.27695, 4.46964, 1.51051]", "[13.27695, 4.46964]"), "x"));
    assertThrows(
        IllegalArgumentException.class,
        () -> FactorModel.parse(shipped.replace("[0.3178, 0.107, 0.0362]", "[0.0, 0.107, 0.0362]"), "x"));
    assertThrows(IllegalArgumentException.class, () -> FactorModel.parse(shipped.replace("\"30y\": 0.0118", "\"30y\": \"a\""), "x"));
    assertThrows(IllegalArgumentException.class, () -> FactorModel.parse(shipped.replace("\"swap_spread_factor\"", "\"s\""), "x"));
  }

  @Test
  void explicitModelsAreValidated() {
    final double[][] nodes = new double[7][3];
    final double[] idio = new double[7];
    assertThrows(IllegalArgumentException.class, () -> FactorModel.of(new double[6][3], new double[3], idio, 0.0, 0.0));
    assertThrows(IllegalArgumentException.class, () -> FactorModel.of(nodes, new double[] {-1.0, 0.0, 0.0}, idio, 0.0, 0.0));
    assertThrows(IllegalArgumentException.class, () -> FactorModel.of(nodes, new double[3], idio, -1.0, 0.0));
    assertThrows(IllegalArgumentException.class, () -> FactorModel.of(new double[7][2], new double[3], idio, 0.0, 0.0));
    final FactorModel zero = FactorModel.of(nodes, new double[3], idio, 0.0, 0.0);
    assertEquals(0.0, zero.covariance().correlation(Y10, Y30));
    assertEquals("explicit", zero.source());
  }

  @Test
  void aFullCovarianceMatrixIsCheckedForShapeAndSymmetry() {
    final double[][] ok = new double[KeyRateTenors.COUNT][KeyRateTenors.COUNT];
    ok[0][0] = 4.0;
    assertEquals(2.0, KeyRateCovariance.ofMatrix(ok).volBp(0), 0.0);
    assertThrows(IllegalArgumentException.class, () -> KeyRateCovariance.ofMatrix(new double[3][3]));
    final double[][] asym = new double[KeyRateTenors.COUNT][KeyRateTenors.COUNT];
    asym[0][1] = 1.0;
    assertThrows(IllegalArgumentException.class, () -> KeyRateCovariance.ofMatrix(asym));
    final double[][] negative = new double[KeyRateTenors.COUNT][KeyRateTenors.COUNT];
    negative[2][2] = -1.0;
    assertThrows(IllegalArgumentException.class, () -> KeyRateCovariance.ofMatrix(negative));
    final double[][] ragged = new double[KeyRateTenors.COUNT][];
    for (int i = 0; i < ragged.length; i++) {
      ragged[i] = new double[i == 3 ? 2 : KeyRateTenors.COUNT];
    }
    assertThrows(IllegalArgumentException.class, () -> KeyRateCovariance.ofMatrix(ragged));
  }

  @Test
  void theJsonReaderReadsWhatTheCalibrationWritesAndNothingElse() {
    @SuppressWarnings("unchecked")
    final Map<String, Object> m =
        (Map<String, Object>) MiniJson.parse(" {\"a\": [], \"b\": {}, \"c\": [-1.5E2, \"\\t\\r\\b\\f\\/\\n\\u0041\"]} ");
    assertEquals(List.of(), m.get("a"));
    assertEquals(Map.of(), m.get("b"));
    assertEquals(List.of(-150.0, "\t\r\b\f/\nA"), m.get("c"));
    for (final String bad : new String[] {"", "{", "[1,", "{\"a\" 1}", "[1] x", "\"abc", "\"\\", "\"\\u12\"", "tru", "[-]", "@"}) {
      assertThrows(IllegalArgumentException.class, () -> MiniJson.parse(bad), bad);
    }
  }

  private static String resource() {
    try (InputStream in = FactorModel.class.getResourceAsStream("/hedge-sim-v2-factors.json")) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (final IOException e) {
      throw new IllegalStateException(e);
    }
  }
}
