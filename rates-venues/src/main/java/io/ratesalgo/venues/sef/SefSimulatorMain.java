package io.ratesalgo.venues.sef;

import io.ratesalgo.venues.VenueMain;

/** Standalone SEF simulator process: {@code VenueMain --venue sef} with the given options. */
public final class SefSimulatorMain {

  private SefSimulatorMain() {}

  /** Entry point; options as for {@link VenueMain}. */
  public static void main(final String[] args) {
    final String[] all = new String[args.length + 2];
    all[0] = "--venue";
    all[1] = "sef";
    System.arraycopy(args, 0, all, 2, args.length);
    VenueMain.main(all);
  }
}
