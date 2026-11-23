package io.ratesalgo.core;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.Arrays;
import java.util.Locale;

/**
 * Pins the calling thread to one CPU on Linux with {@code sched_setaffinity(2)}, called through
 * the JDK's foreign function API (DECISIONS.md DR-R6-L01): no native library of our own and no
 * dependency. On other operating systems, or where the call is refused, pinning is skipped and
 * reported as not done; the thread runs unpinned rather than failing to start.
 *
 * <p>The call is a restricted method. Without {@code --enable-native-access=ALL-UNNAMED} the JDK
 * prints one warning on first use and goes on; the trading image passes the flag. A JVM started
 * with {@code --illegal-native-access=deny} refuses the call, which reads here as "not pinned".
 *
 * <p>Pinning chooses the core; it does not keep other work off it. A pinned busy-spinning thread
 * pays off on a core isolated from the scheduler ({@code isolcpus}, {@code nohz_full}) or at least
 * reserved by the deployment (a cpuset), which is the host's business, not this class's.
 */
public final class CoreAffinity {

  /** Bytes of a {@code cpu_set_t}: 1,024 CPUs, the glibc size. */
  static final int CPU_SET_BYTES = 128;

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
    if (cpu < 0 || cpu >= CPU_SET_BYTES * 8 || !isLinux(osName)) {
      return false;
    }
    final MethodHandle set = Linux.SET;
    if (set == null) {
      return false;
    }
    try (Arena arena = Arena.ofConfined()) {
      final MemorySegment mask = arena.allocate(CPU_SET_BYTES, Long.BYTES);
      mask.set(ValueLayout.JAVA_LONG, (cpu / 64) * (long) Long.BYTES, 1L << (cpu % 64));
      // pid 0: the calling thread
      return (int) set.invokeExact(0, (long) CPU_SET_BYTES, mask) == 0;
    } catch (final Throwable e) {
      // a CPU outside the process's set, or a refused call: run unpinned
      return false;
    }
  }

  /**
   * The CPUs the calling thread may run on, ascending, from {@code sched_getaffinity(2)}; an empty
   * array off Linux or if the call is not available. For tests and run reports.
   */
  public static int[] allowedCpus() {
    return allowedCpus(System.getProperty("os.name", ""));
  }

  static int[] allowedCpus(final String osName) {
    final MethodHandle get = Linux.GET;
    if (!isLinux(osName) || get == null) {
      return new int[0];
    }
    try (Arena arena = Arena.ofConfined()) {
      final MemorySegment mask = arena.allocate(CPU_SET_BYTES, Long.BYTES);
      if ((int) get.invokeExact(0, (long) CPU_SET_BYTES, mask) != 0) {
        return new int[0];
      }
      int[] cpus = new int[CPU_SET_BYTES * 8];
      int n = 0;
      for (int word = 0; word < CPU_SET_BYTES / Long.BYTES; word++) {
        final long bits = mask.get(ValueLayout.JAVA_LONG, word * (long) Long.BYTES);
        for (int bit = 0; bit < 64; bit++) {
          if ((bits & (1L << bit)) != 0) {
            cpus[n++] = word * 64 + bit;
          }
        }
      }
      return Arrays.copyOf(cpus, n);
    } catch (final Throwable e) {
      return new int[0];
    }
  }

  private static boolean isLinux(final String osName) {
    return osName.toLowerCase(Locale.ROOT).startsWith("linux");
  }

  /** Looked up once, on first use on Linux. */
  private static final class Linux {
    static final MethodHandle SET = find("sched_setaffinity");
    static final MethodHandle GET = find("sched_getaffinity");

    // the two calls are what the class is for; each is bound to its C signature
    // int sched_[sg]etaffinity(pid_t pid, size_t cpusetsize, cpu_set_t *mask)
    @SuppressWarnings("restricted")
    private static MethodHandle find(final String symbol) {
      try {
        final Linker linker = Linker.nativeLinker();
        return linker
            .defaultLookup()
            .find(symbol)
            .map(
                address ->
                    linker.downcallHandle(
                        address,
                        FunctionDescriptor.of(
                            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS)))
            .orElse(null);
      } catch (final RuntimeException | LinkageError e) {
        return null;
      }
    }
  }
}
