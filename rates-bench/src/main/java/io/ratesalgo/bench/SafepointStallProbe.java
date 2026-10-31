package io.ratesalgo.bench;

import io.ratesalgo.common.time.SystemClock;
import io.ratesalgo.journal.SegmentedJournal;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import org.agrona.IoUtil;

/**
 * Measures how long creating journal segment files holds up a thread that does nothing but spin,
 * as an event loop does (docs/BENCHMARKS.md, "Cause of the journaling-on tail"; DECISIONS.md
 * DR-R2J-01).
 *
 * <p>Three threads: a creator that creates and deletes {@code count} files of {@code sizeMiB} with
 * one method; a requester that calls {@code System.gc()} every 20 ms, which needs a safepoint; and a
 * victim that spins on {@code System.nanoTime()} and records the longest gap between two
 * iterations. The victim does no I/O and allocates nothing, so its longest gap is the longest time
 * the JVM held it at a safepoint (or the operating system descheduled it).
 *
 * <p>Methods:
 *
 * <ul>
 *   <li>{@code agrona}: {@code IoUtil.mapNewFile(file, size, true)}, which zero-fills by storing one
 *       byte per 4 KiB page through the new mapping (what {@code JournalWriter.create} used before
 *       R2);
 *   <li>{@code channel}: zeros written with {@code FileChannel.write}, then mapped;
 *   <li>{@code channel-touch}: as {@code channel}, then one store per page through the mapping in a
 *       plain loop;
 *   <li>{@code journal}: {@code SegmentedJournal.create} with preallocation, which zero-fills with
 *       channel writes and touches every page with a safepoint check every 16 pages (after R2).
 * </ul>
 *
 * <p>Usage: {@code java -XX:+UseParallelGC -cp ... io.ratesalgo.bench.SafepointStallProbe journal
 * target/probe 512 4}. Prints one line.
 */
public final class SafepointStallProbe {

  private static final long REQUEST_INTERVAL_MILLIS = 20;
  private static final int PAGE = 4096;

  private SafepointStallProbe() {}

  /**
   * What one probe run measured.
   *
   * @param method the creation method
   * @param createMaxNanos longest single file creation
   * @param requests safepoint requests made while files were created
   * @param requestMaxNanos longest {@code System.gc()} call, time to safepoint included
   * @param victimMaxGapNanos longest gap between two iterations of the spinning thread
   */
  public record Result(
      String method, long createMaxNanos, long requests, long requestMaxNanos, long victimMaxGapNanos) {

    /** One line for the console and for docs/BENCHMARKS.md. */
    public String line() {
      return String.format(
          Locale.ROOT,
          "%s: longest create %.1f ms; %d safepoint requests, longest %.1f ms; spinning thread's longest gap %.1f ms",
          method,
          createMaxNanos / 1e6,
          requests,
          requestMaxNanos / 1e6,
          victimMaxGapNanos / 1e6);
    }
  }

  public static void main(final String[] args) throws IOException, InterruptedException {
    if (args.length != 4) {
      throw new IllegalArgumentException("usage: SafepointStallProbe agrona|channel|channel-touch|journal dir sizeMiB count");
    }
    final Path dir = Path.of(args[1]);
    Files.createDirectories(dir);
    System.out.println(run(args[0], dir, Integer.parseInt(args[2]) << 20, Integer.parseInt(args[3])).line());
  }

  /** Creates and deletes {@code count} files of {@code size} bytes in {@code dir} with {@code method}. */
  public static Result run(final String method, final Path dir, final int size, final int count)
      throws InterruptedException, IOException {
    final AtomicBoolean on = new AtomicBoolean(true);
    final long[] victimMax = new long[1];
    final Thread victim =
        new Thread(
            () -> {
              long last = System.nanoTime();
              while (on.get()) {
                final long now = System.nanoTime();
                victimMax[0] = Math.max(victimMax[0], now - last);
                last = now;
              }
            },
            "probe-victim");
    final long[] requests = new long[2];
    final Thread requester =
        new Thread(
            () -> {
              while (on.get()) {
                final long t0 = System.nanoTime();
                System.gc();
                requests[1] = Math.max(requests[1], System.nanoTime() - t0);
                requests[0]++;
                try {
                  Thread.sleep(REQUEST_INTERVAL_MILLIS);
                } catch (final InterruptedException e) {
                  return;
                }
              }
            },
            "probe-requester");
    victim.start();
    requester.start();
    long createMax = 0;
    try {
      for (int i = 0; i < count; i++) {
        final long t0 = System.nanoTime();
        create(method, dir, "probe" + i, size);
        createMax = Math.max(createMax, System.nanoTime() - t0);
      }
    } finally {
      on.set(false);
      victim.join();
      requester.join();
    }
    return new Result(method, createMax, requests[0], requests[1], victimMax[0]);
  }

  private static void create(final String method, final Path dir, final String name, final int size)
      throws IOException {
    switch (method) {
      case "agrona" -> {
        final Path file = dir.resolve(name + ".raj");
        IoUtil.unmap(IoUtil.mapNewFile(file.toFile(), size, true));
        Files.delete(file);
      }
      case "channel", "channel-touch" -> {
        final Path file = dir.resolve(name + ".raj");
        final MappedByteBuffer mapped = channelZeroFill(file, size);
        if ("channel-touch".equals(method)) {
          for (int page = 0; page < size; page += PAGE) {
            mapped.put(page + PAGE - 1, (byte) 0);
          }
        }
        IoUtil.unmap(mapped);
        Files.delete(file);
      }
      case "journal" -> {
        SegmentedJournal.create(dir, name, size, SystemClock.INSTANCE, true).close();
        try (Stream<Path> files = Files.list(dir)) {
          for (final Path p : files.filter(p -> String.valueOf(p.getFileName()).startsWith(name + ".")).toList()) {
            Files.delete(p);
          }
        }
      }
      default -> throw new IllegalArgumentException("unknown method " + method);
    }
  }

  private static MappedByteBuffer channelZeroFill(final Path file, final int size) {
    try (FileChannel channel =
        FileChannel.open(
            file, StandardOpenOption.CREATE_NEW, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
      final ByteBuffer zeros = ByteBuffer.allocateDirect(Math.min(1 << 20, size));
      long written = 0;
      while (written < size) {
        zeros.clear().limit((int) Math.min(zeros.capacity(), size - written));
        while (zeros.hasRemaining()) {
          written += channel.write(zeros, written);
        }
      }
      return channel.map(FileChannel.MapMode.READ_WRITE, 0, size);
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
