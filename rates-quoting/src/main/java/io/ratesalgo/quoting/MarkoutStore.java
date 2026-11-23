package io.ratesalgo.quoting;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.List;
import org.agrona.collections.Int2IntHashMap;

/**
 * Client markouts at several horizons, per client and side, kept across sessions (review
 * 2026-11-23, Q6; design change 4).
 *
 * <p>Plain idea. After each of our fills the engine measures how far the mid moved in the
 * client's favour after 1 s, 5 s, 30 s, 60 s and 5 minutes. A client whose trades are followed by
 * moves in its favour is informed, and the desk charges it that move as adverse cost. A new client
 * has no history, so its estimate starts at its tier's prior and moves to its own mean as fills
 * accumulate:
 *
 * <pre>
 *   adverse(c, side, h) = (n m + n0 prior(tier, h)) / (n + n0)          bp
 * </pre>
 *
 * with m the client's exponentially weighted mean markout (weight 1/n up to {@code window}
 * fills, then 1/window), n its number of fills at that horizon and n0 the shrinkage weight. With
 * n0 = 20 a client needs 20 fills before its own mean carries half the weight.
 *
 * <p>Persistence. {@link #save} writes one line per (client, side, horizon) in client, side and
 * horizon order, numbers in Java's shortest round-trip form, to a temporary file that is then
 * moved over the target, so the file is deterministic and never half written. It is meant to be
 * called at the end of a session on the loop thread, or from a copy, never per RFQ. {@link
 * #load} reads it back. Record and read are allocation-free while the client table has room; a
 * client beyond {@code capacity} is not learned (counted in {@link #clientsDropped}).
 */
public final class MarkoutStore {

  /** Markout horizons, nanoseconds: 1 s, 5 s, 30 s, 60 s and 5 minutes. */
  private static final long[] HORIZONS_NANOS = {
    1_000_000_000L, 5_000_000_000L, 30_000_000_000L, 60_000_000_000L, 300_000_000_000L
  };

  /** Number of horizons. */
  public static final int H = HORIZONS_NANOS.length;

  private static final String HEADER = "# markout store v1: client side(B=client buys, S=client sells) horizon_ms count mean_bp";

  private final int capacity;
  private final double window;
  private final double shrinkage;
  private final TierPriors priors;
  private final Int2IntHashMap slots;
  private final int[] clientOf;
  private final long[] count;
  private final double[] mean;
  private long clientsDropped;

  /** A store for {@code capacity} clients. */
  public MarkoutStore(final int capacity, final double window, final double shrinkage, final TierPriors priors) {
    if (capacity <= 0 || !(window >= 1.0) || !(shrinkage >= 0.0) || Double.isInfinite(shrinkage)) {
      throw new IllegalArgumentException("capacity " + capacity + ", window " + window + ", shrinkage " + shrinkage);
    }
    this.capacity = capacity;
    this.window = window;
    this.shrinkage = shrinkage;
    this.priors = priors;
    slots = new Int2IntHashMap(capacity * 2, 0.5f, -1);
    clientOf = new int[capacity];
    count = new long[capacity * 2 * H];
    mean = new double[capacity * 2 * H];
  }

  /**
   * One markout: {@code adverseBp} is the move after our fill at horizon {@code h}, in bp, positive
   * when it went the client's way (against us). Non-finite values are ignored.
   */
  public void record(final int clientId, final boolean clientBuys, final int h, final double adverseBp) {
    if (!Double.isFinite(adverseBp)) {
      return;
    }
    int s = slots.get(clientId);
    if (s < 0) {
      s = slots.size();
      if (s == capacity) {
        clientsDropped++;
        return;
      }
      slots.put(clientId, s);
      clientOf[s] = clientId;
    }
    final int i = index(s, clientBuys, h);
    final long n = ++count[i];
    mean[i] += (adverseBp - mean[i]) / Math.min((double) n, window);
  }

  /** The shrunk adverse estimate of a client and side at horizon {@code h}, bp. */
  public double adverseBp(final int clientId, final boolean clientBuys, final int tierId, final int h) {
    final double prior = priors.adverseBp(tierId, HORIZONS_NANOS[h]);
    final int s = slots.get(clientId);
    if (s < 0) {
      return prior;
    }
    final int i = index(s, clientBuys, h);
    final double n = count[i];
    return n + shrinkage == 0.0 ? prior : (n * mean[i] + shrinkage * prior) / (n + shrinkage);
  }

  /** Fills recorded for a client and side at horizon {@code h}. */
  public long count(final int clientId, final boolean clientBuys, final int h) {
    final int s = slots.get(clientId);
    return s < 0 ? 0L : count[index(s, clientBuys, h)];
  }

  /** The client's own mean markout, bp; 0 without fills. */
  public double mean(final int clientId, final boolean clientBuys, final int h) {
    final int s = slots.get(clientId);
    return s < 0 ? 0.0 : mean[index(s, clientBuys, h)];
  }

  /** Clients held. */
  public int clients() {
    return slots.size();
  }

  /** Markouts of clients that found the table full. */
  public long clientsDropped() {
    return clientsDropped;
  }

  /** The priors the estimates shrink toward. */
  public TierPriors priors() {
    return priors;
  }

  /** Writes the store to {@code file}; deterministic, atomic. Not for the hot path. */
  public void save(final Path file) throws IOException {
    final int n = slots.size();
    final int[] order = Arrays.copyOf(clientOf, n);
    Arrays.sort(order);
    final Path parent = file.toAbsolutePath().getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
    final Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
    try (BufferedWriter w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
      w.write(HEADER);
      w.write('\n');
      for (final int client : order) {
        final int s = slots.get(client);
        for (int side = 0; side < 2; side++) {
          for (int h = 0; h < H; h++) {
            final int i = index(s, side == 0, h);
            if (count[i] == 0) {
              continue;
            }
            w.write(client + " " + (side == 0 ? 'B' : 'S') + " " + HORIZONS_NANOS[h] / 1_000_000L + " " + count[i] + " "
                + Double.toString(mean[i]));
            w.write('\n');
          }
        }
      }
    }
    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
  }

  /**
   * Reads a file written by {@link #save} into this store, replacing what it holds for the clients
   * the file names. A missing file is an empty store. A line that does not parse is an error.
   */
  public MarkoutStore load(final Path file) throws IOException {
    if (!Files.isRegularFile(file)) {
      return this;
    }
    final List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
    for (int ln = 0; ln < lines.size(); ln++) {
      final String line = lines.get(ln).strip();
      if (line.isEmpty() || line.startsWith("#")) {
        continue;
      }
      final String[] f = line.split(" ", -1);
      try {
        if (f.length != 5 || !("B".equals(f[1]) || "S".equals(f[1]))) {
          throw new IllegalArgumentException("five fields with side B or S");
        }
        final int client = Integer.parseInt(f[0]);
        final int h = horizonIndex(Long.parseLong(f[2]) * 1_000_000L);
        final long c = Long.parseLong(f[3]);
        final double m = Double.parseDouble(f[4]);
        if (c <= 0 || !Double.isFinite(m)) {
          throw new IllegalArgumentException("count " + c + ", mean " + m);
        }
        int s = slots.get(client);
        if (s < 0) {
          s = slots.size();
          if (s == capacity) {
            throw new IllegalArgumentException("more clients than the capacity " + capacity);
          }
          slots.put(client, s);
          clientOf[s] = client;
        }
        final int i = index(s, "B".equals(f[1]), h);
        count[i] = c;
        mean[i] = m;
      } catch (final IllegalArgumentException e) {
        throw new IOException(file + " line " + (ln + 1) + ": " + e.getMessage(), e);
      }
    }
    return this;
  }

  /** Horizon {@code h} in nanoseconds: 1 s, 5 s, 30 s, 60 s, 5 minutes for h = 0 to 4. */
  public static long horizonNanos(final int h) {
    return HORIZONS_NANOS[h];
  }

  /** Index of a horizon, the inverse of {@link #horizonNanos}. */
  public static int horizonIndex(final long nanos) {
    for (int h = 0; h < H; h++) {
      if (HORIZONS_NANOS[h] == nanos) {
        return h;
      }
    }
    throw new IllegalArgumentException("no markout horizon of " + nanos + " ns");
  }

  private static int index(final int slot, final boolean clientBuys, final int h) {
    return (slot * 2 + (clientBuys ? 0 : 1)) * H + h;
  }
}
