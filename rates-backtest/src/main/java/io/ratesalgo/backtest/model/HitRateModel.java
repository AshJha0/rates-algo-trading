package io.ratesalgo.backtest.model;

/**
 * Logistic probability that our quote wins an RFQ.
 *
 * <p>Plain idea: the further our price is behind the best competing price (the cover), the less
 * likely the client picks us; larger trades, longer tenors, busy periods around fixings and more
 * dealers in competition shift the curve.
 *
 * <p>Formal statement (API_SPEC.md Section 16.3):
 *
 * <pre>
 *   z = b0 + b1 * s + b2 * ln(size / 1mm) + b3 * tenorYears + b4 * fixingWindow + b5 * dealers
 *   P(win) = 1 / (1 + exp(-z))
 * </pre>
 *
 * where {@code s} is spread-to-cover in basis points: our distance from mid on the side the client
 * trades minus the best competitor's distance. Coefficients come from preset {@value #PRESET}
 * ({@code ratesresearch.hitrate}); the slope {@code b1} is replaced by the declared band value for
 * the run's {@link BandPoint}, because the public tape shows no losing quotes and the slope cannot
 * be fitted from it (DR-008).
 */
public final class HitRateModel {

  public static final String PRESET = "usd_rfq_hit_rate";
  public static final String SLOPE = "spread_to_cover_bp";

  private final double intercept;
  private final double slope;
  private final double logSize;
  private final double tenor;
  private final double fixingWindow;
  private final double dealerCount;
  private final BandPoint bandPoint;

  public HitRateModel(
      final double intercept,
      final double slope,
      final double logSize,
      final double tenor,
      final double fixingWindow,
      final double dealerCount,
      final BandPoint bandPoint) {
    if (slope >= 0) {
      throw new IllegalArgumentException("spread-to-cover slope must be negative: " + slope);
    }
    this.intercept = intercept;
    this.slope = slope;
    this.logSize = logSize;
    this.tenor = tenor;
    this.fixingWindow = fixingWindow;
    this.dealerCount = dealerCount;
    this.bandPoint = bandPoint;
  }

  /** Builds the model from the preset with the spread-to-cover slope taken at {@code point}. */
  public static HitRateModel fromPreset(final Preset preset, final BandPoint point) {
    return new HitRateModel(
        preset.value("intercept"),
        preset.band(SLOPE).at(point),
        preset.value("log_size_mm"),
        preset.value("tenor_years"),
        preset.value("fixing_window"),
        preset.value("dealer_count"),
        point);
  }

  public double winProbability(
      final double spreadToCoverBp,
      final double sizeMillions,
      final double tenorYears,
      final boolean inFixingWindow,
      final int dealers) {
    if (!(sizeMillions > 0)) {
      throw new IllegalArgumentException("size must be positive: " + sizeMillions);
    }
    final double z =
        intercept
            + slope * spreadToCoverBp
            + logSize * Math.log(sizeMillions)
            + tenor * tenorYears
            + (inFixingWindow ? fixingWindow : 0.0)
            + dealerCount * dealers;
    return 1.0 / (1.0 + Math.exp(-z));
  }

  public double slope() {
    return slope;
  }

  public BandPoint bandPoint() {
    return bandPoint;
  }
}
