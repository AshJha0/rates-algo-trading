package io.ratesalgo.journal;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.MappedByteBuffer;
import java.util.Locale;

/**
 * Locks a mapped segment in memory with {@code mlock(2)} on Linux, through the JDK's foreign
 * function API (no native library of our own, no new dependency). A locked page is never evicted,
 * so the appending thread cannot take a major fault on it under memory pressure. It does not stop
 * the minor, write-protect fault after writeback (review finding L9); {@link
 * SegmentedJournal.Options#refaultWindowBytes} deals with that.
 *
 * <p>Locking needs {@code RLIMIT_MEMLOCK} (or {@code CAP_IPC_LOCK}) to cover the segments; a
 * container's default limit is often 64 KiB. A refused lock is reported as false and the journal
 * goes on unlocked. On other operating systems nothing is done. Unmapping a segment releases its
 * lock.
 */
public final class PageLock {

  private PageLock() {}

  /**
   * Locks every page of {@code mapped}.
   *
   * @return true if the pages are locked; false on another operating system, without the
   *     permission or the limit, or if the call is not available
   */
  public static boolean lock(final MappedByteBuffer mapped) {
    return lock(mapped, System.getProperty("os.name", ""));
  }

  static boolean lock(final MappedByteBuffer mapped, final String osName) {
    if (!osName.toLowerCase(Locale.ROOT).startsWith("linux")) {
      return false;
    }
    final MethodHandle mlock = Linux.MLOCK;
    if (mlock == null) {
      return false;
    }
    try {
      final MemorySegment segment = MemorySegment.ofBuffer(mapped);
      return (int) mlock.invokeExact(segment, segment.byteSize()) == 0;
    } catch (final Throwable e) {
      // a refused or failed native call leaves the segment unlocked, which is safe
      return false;
    }
  }

  /** Looked up once, on first use, and only on Linux. */
  private static final class Linux {
    static final MethodHandle MLOCK = find();

    // mlock(2) is the point of the class; the downcall is checked against its C signature
    @SuppressWarnings("restricted")
    private static MethodHandle find() {
      try {
        final Linker linker = Linker.nativeLinker();
        return linker
            .defaultLookup()
            .find("mlock")
            .map(
                address ->
                    linker.downcallHandle(
                        address, FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG)))
            .orElse(null);
      } catch (final RuntimeException | LinkageError e) {
        // native access refused (--illegal-native-access=deny) or no linker: run unlocked
        return null;
      }
    }
  }
}
