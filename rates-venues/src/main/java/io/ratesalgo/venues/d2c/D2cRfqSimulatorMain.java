package io.ratesalgo.venues.d2c;

import io.ratesalgo.venues.VenueMain;

/** Standalone D2C RFQ simulator process: {@code VenueMain --venue d2c} with the given options. */
public final class D2cRfqSimulatorMain {

  private D2cRfqSimulatorMain() {}

  /** Entry point; options as for {@link VenueMain}. */
  public static void main(final String[] args) {
    VenueMain.main(prepend("d2c", args));
  }

  static String[] prepend(final String kind, final String[] args) {
    final String[] all = new String[args.length + 2];
    all[0] = "--venue";
    all[1] = kind;
    System.arraycopy(args, 0, all, 2, args.length);
    return all;
  }
}
