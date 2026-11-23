package io.ratesalgo.app.pricing;

import io.ratesalgo.api.CurrencyCode;
import io.ratesalgo.api.Dv01Vector;
import io.ratesalgo.api.SpreadSensitivityProvider;

/**
 * The desk's net exposure to the Treasury-to-SOFR spread, by tenor: positions times the spread
 * ladders of a {@link SpreadSensitivityProvider}.
 *
 * <p>This is the additive hook for the second risk factor. The risk engine keeps its swap-rate
 * ladder as it is; this class keeps the spread ladder beside it, fed by the same fills, so a desk
 * that is flat in swap-rate DV01 because it hedged swaps with Treasuries can see, and later
 * limit, what it holds in swap spread. Nothing reads it for limits or quoting yet.
 *
 * <p>Wiring (one line each, in the desk loop): call {@link #onFill} where the position keeper is
 * told of a fill, and {@link #recompute} after a reprice. No allocation after construction.
 */
public final class SpreadRiskBook {

  private final SpreadSensitivityProvider provider;
  private final int[] instrumentIds;
  private final long[] positionMinor;
  private final Dv01Vector net = new Dv01Vector(CurrencyCode.USD);
  private final Dv01Vector scratch = new Dv01Vector(CurrencyCode.USD);

  /**
   * Creates an empty book.
   *
   * @param provider spread ladders per instrument
   * @param instrumentIds the instruments positions are kept for
   */
  public SpreadRiskBook(final SpreadSensitivityProvider provider, final int[] instrumentIds) {
    this.provider = provider;
    this.instrumentIds = instrumentIds.clone();
    this.positionMinor = new long[instrumentIds.length];
  }

  /**
   * Adds a signed fill (positive bought) to the position of an instrument and refreshes the net
   * ladder. A fill in an instrument the book does not keep is ignored and reported as false.
   */
  public boolean onFill(final int instrumentId, final long signedQuantityMinor) {
    for (int i = 0; i < instrumentIds.length; i++) {
      if (instrumentIds[i] == instrumentId) {
        positionMinor[i] += signedQuantityMinor;
        recompute();
        return true;
      }
    }
    return false;
  }

  /** Recomputes the net ladder from the positions and the provider's current ladders. */
  public void recompute() {
    net.clear();
    for (int i = 0; i < instrumentIds.length; i++) {
      if (positionMinor[i] != 0 && provider.spreadPv01(instrumentIds[i], positionMinor[i], scratch)) {
        net.addScaled(scratch, 1.0);
      }
    }
  }

  /** Position of an instrument in minor units, zero if it is not kept. */
  public long position(final int instrumentId) {
    for (int i = 0; i < instrumentIds.length; i++) {
      if (instrumentIds[i] == instrumentId) {
        return positionMinor[i];
      }
    }
    return 0L;
  }

  /** Net spread PV01 at tenor k of {@code KeyRateTenors}, currency units per basis point. */
  public double spreadPv01(final int k) {
    return net.get(k);
  }

  /** Net spread PV01 over all tenors. */
  public double parallel() {
    return net.parallel();
  }

  /** Sum of the absolute spread PV01 by tenor. */
  public double gross() {
    return net.gross();
  }
}
