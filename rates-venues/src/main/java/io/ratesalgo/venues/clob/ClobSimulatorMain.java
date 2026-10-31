package io.ratesalgo.venues.clob;

import io.ratesalgo.venues.VenueMain;

/** Standalone CLOB simulator process: {@code VenueMain --venue clob} with the given options. */
public final class ClobSimulatorMain {

  private ClobSimulatorMain() {}

  /** Entry point; options as for {@link VenueMain}. */
  public static void main(final String[] args) {
    final String[] all = new String[args.length + 2];
    all[0] = "--venue";
    all[1] = "clob";
    System.arraycopy(args, 0, all, 2, args.length);
    VenueMain.main(all);
  }
}
