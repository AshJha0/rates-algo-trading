package io.ratesalgo.core;

import java.util.Locale;

/**
 * Pins the calling thread to one CPU on Linux through {@code net.openhft:affinity}, an optional
 * dependency. On other operating systems, or when the library is not on the class path, pinning is
 * skipped and reported as not done; the loop runs unpinned rather than failing to start.
 */
public final class CoreAffinity {

  private CoreAffinity() {}

  /**
   * Pins the current thread to {@code cpu}.
   *
   * @param cpu the CPU index, or a negative value for no pinning
   * @return true if the thread was pinned
   */
  public static boolean pin(final int cpu) {
    return pin(cpu, System.getProperty("os.name", ""));
  }

  static boolean pin(final int cpu, final String osName) {
    if (cpu < 0 || !osName.toLowerCase(Locale.ROOT).startsWith("linux")) {
      return false;
    }
    try {
      net.openhft.affinity.Affinity.setAffinity(cpu);
      return true;
    } catch (final LinkageError | RuntimeException e) {
      // library absent or native call refused: run unpinned
      return false;
    }
  }
}
