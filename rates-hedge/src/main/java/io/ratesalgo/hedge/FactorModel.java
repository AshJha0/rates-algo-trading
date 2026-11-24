package io.ratesalgo.hedge;

import io.ratesalgo.api.KeyRateTenors;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * The three-factor model of daily par-yield changes fitted in Round 3 (review 2026-11-23, Q2;
 * docs/research/SIM_V2_CALIBRATION.md and SIM_V2_DESIGN.md Section 3), in place of the assumed
 * {@code exp(-0.06 |t_i - t_j|)} correlation.
 *
 * <pre>
 *   dy_T = L[T,level] F_level + L[T,slope] F_slope + L[T,curvature] F_curvature + e_T      (bp)
 *   Sigma = L diag(sd_f^2) L' + W diag(e_n^2) W'                                       (bp^2 per day)
 * </pre>
 *
 * L holds the loadings at the calibration's nodes (2, 3, 5, 7, 10, 20 and 30 years), carried to
 * the key-rate pillars by linear interpolation in maturity, the 2-year node below 2 years and the
 * 30-year node above 30, as the simulator does; W is the same interpolation applied to the
 * independent node terms e_n, so the 1-year and 2-year pillars share the 2-year node's term. The
 * daily idiosyncratic sd is the calibration's per-sqrt-minute value scaled like the level factor
 * (daily sd over per-sqrt-minute sd), which is how the calibration derived it.
 *
 * <p>Also carries the swap-spread factor (loading 1 on every swap rate) and the futures-basis sd,
 * which no Treasury hedge can remove; the planner charges the basis as variance of a futures
 * hedge. Immutable after construction; every read is allocation-free.
 */
public final class FactorModel {

  /** Number of curve factors. */
  public static final int FACTORS = 3;

  public static final int LEVEL = 0;
  public static final int SLOPE = 1;
  public static final int CURVATURE = 2;

  private static final int K = KeyRateTenors.COUNT;
  private static final String[] FACTOR_NAMES = {"level", "slope", "curvature"};

  /** The calibration's node tenors in years. */
  static final double[] NODE_YEARS = {2.0, 3.0, 5.0, 7.0, 10.0, 20.0, 30.0};

  private static final String[] NODE_KEYS = {"2y", "3y", "5y", "7y", "10y", "20y", "30y"};
  private static final String SHIPPED = "/hedge-sim-v2-factors.json";

  private final double[][] loading = new double[K][FACTORS];
  private final double[] factorSdBp = new double[FACTORS];
  private final double[][] nodeWeight = new double[K][NODE_YEARS.length];
  private final double[] nodeIdioSdBp = new double[NODE_YEARS.length];
  private final double spreadSdBp;
  private final double basisSdBp;
  private final KeyRateCovariance covariance;
  private final String source;

  private FactorModel(
      final double[][] nodeLoadings,
      final double[] factorSd,
      final double[] nodeIdioSd,
      final double spreadSd,
      final double basisSd,
      final String source) {
    if (nodeLoadings.length != NODE_YEARS.length || nodeIdioSd.length != NODE_YEARS.length || factorSd.length != FACTORS) {
      throw new IllegalArgumentException("need " + NODE_YEARS.length + " nodes and " + FACTORS + " factors");
    }
    for (int f = 0; f < FACTORS; f++) {
      if (!(factorSd[f] >= 0.0) || Double.isInfinite(factorSd[f])) {
        throw new IllegalArgumentException("factor sd " + factorSd[f]);
      }
      factorSdBp[f] = factorSd[f];
    }
    for (int n = 0; n < NODE_YEARS.length; n++) {
      if (nodeLoadings[n].length != FACTORS || !(nodeIdioSd[n] >= 0.0)) {
        throw new IllegalArgumentException("node " + NODE_KEYS[n]);
      }
      nodeIdioSdBp[n] = nodeIdioSd[n];
    }
    if (!(spreadSd >= 0.0) || !(basisSd >= 0.0)) {
      throw new IllegalArgumentException("spread sd " + spreadSd + ", basis sd " + basisSd);
    }
    spreadSdBp = spreadSd;
    basisSdBp = basisSd;
    this.source = source;
    for (int k = 0; k < K; k++) {
      interpolate(KeyRateTenors.years(k), nodeWeight[k]);
      for (int n = 0; n < NODE_YEARS.length; n++) {
        for (int f = 0; f < FACTORS; f++) {
          loading[k][f] += nodeWeight[k][n] * nodeLoadings[n][f];
        }
      }
    }
    final double[][] sigma = new double[K][K];
    for (int i = 0; i < K; i++) {
      for (int j = 0; j < K; j++) {
        double v = 0.0;
        for (int f = 0; f < FACTORS; f++) {
          v += loading[i][f] * loading[j][f] * factorSdBp[f] * factorSdBp[f];
        }
        for (int n = 0; n < NODE_YEARS.length; n++) {
          v += nodeWeight[i][n] * nodeWeight[j][n] * nodeIdioSdBp[n] * nodeIdioSdBp[n];
        }
        sigma[i][j] = v;
      }
    }
    covariance = KeyRateCovariance.ofMatrix(sigma);
  }

  /**
   * A model from node values: {@code nodeLoadings[n][f]} bp of yield per bp of factor at node n
   * (2, 3, 5, 7, 10, 20, 30 years), daily factor sds, daily idiosyncratic sd per node, and the
   * daily swap-spread and futures-basis sds, all in bp.
   */
  public static FactorModel of(
      final double[][] nodeLoadings,
      final double[] factorSdBp,
      final double[] nodeIdioSdBp,
      final double spreadSdBp,
      final double basisSdBp) {
    return new FactorModel(nodeLoadings, factorSdBp, nodeIdioSdBp, spreadSdBp, basisSdBp, "explicit");
  }

  /** The Round 3 calibration as shipped in this module (a copy of the factor block of the file). */
  public static FactorModel calibrated() {
    try (InputStream in = FactorModel.class.getResourceAsStream(SHIPPED)) {
      if (in == null) {
        throw new IllegalStateException("missing resource " + SHIPPED);
      }
      return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8), SHIPPED);
    } catch (final IOException e) {
      throw new IllegalStateException("reading " + SHIPPED, e);
    }
  }

  /** Reads {@code data/calibration/sim_v2_params.json} (or any file with the same factor keys). */
  public static FactorModel load(final Path simV2Params) throws IOException {
    return parse(Files.readString(simV2Params, StandardCharsets.UTF_8), simV2Params.toString());
  }

  /**
   * Parses the factor block of a {@code sim_v2_params/1} document: {@code
   * curve_factors.loadings}, {@code daily_sd}, {@code sd_per_sqrt_minute}, {@code
   * idiosyncratic_sd_per_sqrt_minute}, {@code swap_spread_factor.daily_sd} and {@code
   * futures_basis.stationary_sd}. The basis is an Ornstein-Uhlenbeck process with a half-life of
   * minutes, so over a day its change has variance twice the stationary one.
   */
  static FactorModel parse(final String json, final String source) {
    final Map<String, Object> root = map(MiniJson.parse(json), "document");
    if (!"sim_v2_params/1".equals(root.get("schema"))) {
      throw new IllegalArgumentException(source + ": schema " + root.get("schema"));
    }
    final Map<String, Object> curve = map(root.get("curve_factors"), "curve_factors");
    final Map<String, Object> loadings = map(value(curve, "loadings"), "loadings");
    final double[][] nodes = new double[NODE_YEARS.length][];
    for (int n = 0; n < NODE_YEARS.length; n++) {
      nodes[n] = doubles(loadings.get(NODE_KEYS[n]), "loadings." + NODE_KEYS[n], FACTORS);
    }
    final double[] daily = doubles(value(curve, "daily_sd"), "daily_sd", FACTORS);
    final double[] perMinute = doubles(value(curve, "sd_per_sqrt_minute"), "sd_per_sqrt_minute", FACTORS);
    if (!(perMinute[LEVEL] > 0.0)) {
      throw new IllegalArgumentException(source + ": level sd per sqrt-minute " + perMinute[LEVEL]);
    }
    final double scale = daily[LEVEL] / perMinute[LEVEL];
    final Map<String, Object> idio = map(value(curve, "idiosyncratic_sd_per_sqrt_minute"), "idiosyncratic");
    final double[] idioDaily = new double[NODE_YEARS.length];
    for (int n = 0; n < NODE_YEARS.length; n++) {
      idioDaily[n] = number(idio.get(NODE_KEYS[n]), "idiosyncratic." + NODE_KEYS[n]) * scale;
    }
    final Map<String, Object> spread = map(root.get("swap_spread_factor"), "swap_spread_factor");
    final Map<String, Object> basis = map(root.get("futures_basis"), "futures_basis");
    final double spreadSd = number(value(spread, "daily_sd"), "swap_spread_factor.daily_sd");
    final double basisSd = Math.sqrt(2.0) * number(value(basis, "stationary_sd"), "futures_basis.stationary_sd");
    return new FactorModel(nodes, daily, idioDaily, spreadSd, basisSd, source);
  }

  /** Loading of pillar {@code k} on factor {@code f}, bp of yield per bp of factor. */
  public double loading(final int k, final int f) {
    return loading[k][f];
  }

  /** Daily sd of factor {@code f}, bp. */
  public double factorSdBp(final int f) {
    return factorSdBp[f];
  }

  /** Daily idiosyncratic sd of pillar {@code k}, bp. */
  public double idioSdBp(final int k) {
    double v = 0.0;
    for (int n = 0; n < NODE_YEARS.length; n++) {
      v += nodeWeight[k][n] * nodeWeight[k][n] * nodeIdioSdBp[n] * nodeIdioSdBp[n];
    }
    return Math.sqrt(v);
  }

  /** Daily sd of the swap-spread factor, bp. */
  public double spreadSdBp() {
    return spreadSdBp;
  }

  /** Daily sd of a futures contract's basis change, bp of yield. */
  public double basisSdBp() {
    return basisSdBp;
  }

  /** The key-rate covariance this model implies, bp squared per day. */
  public KeyRateCovariance covariance() {
    return covariance;
  }

  /** Where the parameters came from (a path or the shipped resource). */
  public String source() {
    return source;
  }

  /** Factor name, for reports. */
  public static String factorName(final int f) {
    return FACTOR_NAMES[f];
  }

  /**
   * Exposure of a DV01 ladder (currency per bp at each pillar) to each factor, currency per bp of
   * factor: {@code out[f] = sum_k ladder[k] L[k,f]}. Allocation-free.
   */
  public void exposures(final double[] ladder, final double[] out) {
    for (int f = 0; f < FACTORS; f++) {
      double e = 0.0;
      for (int k = 0; k < K; k++) {
        e += ladder[k] * loading[k][f];
      }
      out[f] = e;
    }
  }

  /** Daily variance, currency squared, of a ladder's idiosyncratic part alone. */
  public double idioVariance(final double[] ladder) {
    double v = 0.0;
    for (int n = 0; n < NODE_YEARS.length; n++) {
      double w = 0.0;
      for (int k = 0; k < K; k++) {
        w += ladder[k] * nodeWeight[k][n];
      }
      v += w * w * nodeIdioSdBp[n] * nodeIdioSdBp[n];
    }
    return v;
  }

  private static void interpolate(final double years, final double[] weights) {
    final int last = NODE_YEARS.length - 1;
    if (years <= NODE_YEARS[0]) {
      weights[0] = 1.0;
      return;
    }
    if (years >= NODE_YEARS[last]) {
      weights[last] = 1.0;
      return;
    }
    for (int n = 0; n < last; n++) {
      if (years <= NODE_YEARS[n + 1]) {
        final double w = (years - NODE_YEARS[n]) / (NODE_YEARS[n + 1] - NODE_YEARS[n]);
        weights[n] = 1.0 - w;
        weights[n + 1] = w;
        return;
      }
    }
  }

  private static Object value(final Map<String, Object> m, final String key) {
    final Object entry = m.get(key);
    // calibration entries are {"value": ..., "unit": ..., ...}; a bare value is accepted too
    return entry instanceof Map<?, ?> e && e.containsKey("value") ? e.get("value") : entry;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(final Object o, final String what) {
    if (!(o instanceof Map<?, ?>)) {
      throw new IllegalArgumentException("missing object " + what);
    }
    return (Map<String, Object>) o;
  }

  private static double[] doubles(final Object o, final String what, final int n) {
    if (!(o instanceof List<?> list) || list.size() != n) {
      throw new IllegalArgumentException(what + " must be a list of " + n + " numbers");
    }
    final double[] a = new double[n];
    for (int i = 0; i < n; i++) {
      a[i] = number(list.get(i), what);
    }
    return a;
  }

  private static double number(final Object o, final String what) {
    if (!(o instanceof Double d) || !Double.isFinite(d)) {
      throw new IllegalArgumentException(what + " must be a finite number, found " + o);
    }
    return d;
  }
}
