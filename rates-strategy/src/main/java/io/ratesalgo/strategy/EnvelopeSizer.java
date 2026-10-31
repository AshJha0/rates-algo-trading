package io.ratesalgo.strategy;

/**
 * Feature 3: quote size and size premium from the DV01 limit envelope and the size distribution.
 *
 * <pre>
 *   post = |net + tradeDv01|,  u = post / limit
 *   risk-reducing (post &lt;= |net|):  full size, no premium
 *   u &gt; hardUtil:                    decline (quantity 0)
 *   premium = riskPremiumBp * ((u - softUtil) / (hardUtil - softUtil))^2     when u &gt; softUtil
 *           + blockPremiumBp * ln(size / blockSizeMm)                        when size &gt; blockSizeMm
 * </pre>
 *
 * {@code blockSizeMm} is a high quantile of the declared size distribution (preset
 * {@code usd_rfq_flow}: lognormal sizes), so the block premium touches only the tail.
 */
public final class EnvelopeSizer implements QuoteSizer {

  private final double limitDv01;
  private final double softUtil;
  private final double hardUtil;
  private final double riskPremiumBp;
  private final double blockSizeMm;
  private final double blockPremiumBp;

  public EnvelopeSizer(
      final double limitDv01,
      final double softUtil,
      final double hardUtil,
      final double riskPremiumBp,
      final double blockSizeMm,
      final double blockPremiumBp) {
    if (!(limitDv01 > 0) || !(softUtil >= 0 && softUtil < hardUtil) || riskPremiumBp < 0 || !(blockSizeMm > 0)
        || blockPremiumBp < 0) {
      throw new IllegalArgumentException("invalid sizing parameters");
    }
    this.limitDv01 = limitDv01;
    this.softUtil = softUtil;
    this.hardUtil = hardUtil;
    this.riskPremiumBp = riskPremiumBp;
    this.blockSizeMm = blockSizeMm;
    this.blockPremiumBp = blockPremiumBp;
  }

  /** Size quantile of a lognormal size distribution: {@code exp(mu + z * sigma)}, millions. */
  public static double lognormalQuantileMm(final double mu, final double sigma, final double z) {
    return Math.exp(mu + z * sigma);
  }

  @Override
  public long maxQuantity(final RiskLadder ladder, final int index, final double tradeDv01, final long requested) {
    final double net = ladder.net();
    final double post = Math.abs(net + tradeDv01);
    if (post <= Math.abs(net)) {
      return requested;
    }
    return post / limitDv01 > hardUtil ? 0L : requested;
  }

  @Override
  public double premiumBp(final RiskLadder ladder, final int index, final double tradeDv01, final double sizeMillions) {
    final double net = ladder.net();
    final double post = Math.abs(net + tradeDv01);
    double p = 0.0;
    if (post > Math.abs(net)) {
      final double u = post / limitDv01;
      if (u > softUtil) {
        final double x = Math.min(1.0, (u - softUtil) / (hardUtil - softUtil));
        p += riskPremiumBp * x * x;
      }
    }
    if (sizeMillions > blockSizeMm) {
      p += blockPremiumBp * Math.log(sizeMillions / blockSizeMm);
    }
    return p;
  }
}
