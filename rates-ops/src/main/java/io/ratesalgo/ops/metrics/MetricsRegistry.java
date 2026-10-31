package io.ratesalgo.ops.metrics;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.function.DoubleSupplier;
import java.util.regex.Pattern;

/**
 * A minimal Prometheus text-format (version 0.0.4) exporter: counters, gauges and fixed-bucket
 * histograms, with labels, and no client library (API_SPEC.md Section 17.12).
 *
 * <p>Updates are lock-free (atomics) so that the process thread can record while the HTTP thread
 * renders. Registration takes a lock and happens at start-up only. Nothing here runs on the
 * trading hot path: the event loop publishes its counters to the operations thread, which owns
 * this registry.
 */
public final class MetricsRegistry {

  private static final Pattern NAME = Pattern.compile("[a-zA-Z_:][a-zA-Z0-9_:]*");
  private static final Pattern LABEL = Pattern.compile("[a-zA-Z_][a-zA-Z0-9_]*");

  /** A monotonically increasing count. */
  public static final class Counter {
    private final AtomicLong value = new AtomicLong();

    /** Adds one. */
    public void inc() {
      value.incrementAndGet();
    }

    /** Adds {@code n}, which must not be negative. */
    public void add(final long n) {
      if (n < 0) {
        throw new IllegalArgumentException("counters only go up: " + n);
      }
      value.addAndGet(n);
    }

    /** The current count. */
    public long get() {
      return value.get();
    }
  }

  /** A value that goes up and down, set directly or read from a supplier at render time. */
  public static final class Gauge {
    private final AtomicLong bits = new AtomicLong(Double.doubleToLongBits(0.0));
    private volatile DoubleSupplier supplier;

    /** Sets the value. */
    public void set(final double v) {
      bits.set(Double.doubleToLongBits(v));
    }

    /** Reads the value from {@code s} at each render instead. */
    public void bind(final DoubleSupplier s) {
      supplier = s;
    }

    /** The current value. */
    public double get() {
      final DoubleSupplier s = supplier;
      return s != null ? s.getAsDouble() : Double.longBitsToDouble(bits.get());
    }
  }

  /** Observations counted into fixed upper-bound buckets. */
  public static final class Histogram {
    private final double[] bounds;
    private final AtomicLongArray counts;
    private final DoubleAdder sum = new DoubleAdder();
    private final AtomicLong count = new AtomicLong();

    Histogram(final double[] bounds) {
      this.bounds = bounds.clone();
      this.counts = new AtomicLongArray(bounds.length);
    }

    /** Records one observation. */
    public void observe(final double v) {
      for (int i = 0; i < bounds.length; i++) {
        if (v <= bounds[i]) {
          counts.incrementAndGet(i);
          break;
        }
      }
      sum.add(v);
      count.incrementAndGet();
    }

    /** Observations so far. */
    public long count() {
      return count.get();
    }
  }

  private record Series(String labels, Object metric) {}

  private static final class Family {
    private final String type;
    private final String help;
    private final List<Series> series = new ArrayList<>();

    Family(final String type, final String help) {
      this.type = type;
      this.help = help;
    }
  }

  private final Map<String, Family> families = new LinkedHashMap<>();

  /** Registers (or returns) a counter series. Labels are name, value pairs. */
  public Counter counter(final String name, final String help, final String... labels) {
    return (Counter) series(name, "counter", help, labels, () -> new Counter());
  }

  /** Registers (or returns) a gauge series. */
  public Gauge gauge(final String name, final String help, final String... labels) {
    return (Gauge) series(name, "gauge", help, labels, () -> new Gauge());
  }

  /** Registers (or returns) a histogram series with ascending finite bucket bounds. */
  public Histogram histogram(final String name, final String help, final double[] bounds, final String... labels) {
    for (int i = 1; i < bounds.length; i++) {
      if (!(bounds[i] > bounds[i - 1])) {
        throw new IllegalArgumentException(name + ": bucket bounds must ascend");
      }
    }
    return (Histogram) series(name, "histogram", help, labels, () -> new Histogram(bounds));
  }

  private synchronized Object series(final String name, final String type, final String help,
      final String[] labels, final java.util.function.Supplier<Object> make) {
    if (!NAME.matcher(name).matches()) {
      throw new IllegalArgumentException("bad metric name " + name);
    }
    if (labels.length % 2 != 0) {
      throw new IllegalArgumentException(name + ": labels come in name, value pairs");
    }
    final StringBuilder sb = new StringBuilder();
    for (int i = 0; i < labels.length; i += 2) {
      if (!LABEL.matcher(labels[i]).matches() || "le".equals(labels[i])) {
        throw new IllegalArgumentException(name + ": bad label name " + labels[i]);
      }
      sb.append(i == 0 ? "" : ",").append(labels[i]).append("=\"").append(escape(labels[i + 1])).append('"');
    }
    final String labelText = sb.toString();
    final Family f = families.computeIfAbsent(name, n -> new Family(type, help));
    if (!f.type.equals(type)) {
      throw new IllegalArgumentException(name + " is a " + f.type + ", not a " + type);
    }
    for (final Series s : f.series) {
      if (s.labels().equals(labelText)) {
        return s.metric();
      }
    }
    final Object m = make.get();
    f.series.add(new Series(labelText, m));
    return m;
  }

  /** The exposition text, families in registration order. */
  public synchronized String render() {
    final StringBuilder sb = new StringBuilder(4096);
    for (final Map.Entry<String, Family> e : families.entrySet()) {
      final String name = e.getKey();
      final Family f = e.getValue();
      sb.append("# HELP ").append(name).append(' ').append(f.help.replace("\\", "\\\\").replace("\n", "\\n"))
          .append('\n');
      sb.append("# TYPE ").append(name).append(' ').append(f.type).append('\n');
      for (final Series s : f.series) {
        if (s.metric() instanceof Counter c) {
          line(sb, name, s.labels(), Long.toString(c.get()));
        } else if (s.metric() instanceof Gauge g) {
          line(sb, name, s.labels(), number(g.get()));
        } else if (s.metric() instanceof Histogram h) {
          long cumulative = 0;
          for (int i = 0; i < h.bounds.length; i++) {
            cumulative += h.counts.get(i);
            line(sb, name + "_bucket", join(s.labels(), "le=\"" + number(h.bounds[i]) + "\""), Long.toString(cumulative));
          }
          line(sb, name + "_bucket", join(s.labels(), "le=\"+Inf\""), Long.toString(h.count.get()));
          line(sb, name + "_sum", s.labels(), number(h.sum.sum()));
          line(sb, name + "_count", s.labels(), Long.toString(h.count.get()));
        }
      }
    }
    return sb.toString();
  }

  private static String join(final String a, final String b) {
    return a.isEmpty() ? b : a + "," + b;
  }

  private static void line(final StringBuilder sb, final String name, final String labels, final String value) {
    sb.append(name);
    if (!labels.isEmpty()) {
      sb.append('{').append(labels).append('}');
    }
    sb.append(' ').append(value).append('\n');
  }

  private static String number(final double v) {
    if (Double.isNaN(v)) {
      return "NaN";
    }
    if (Double.isInfinite(v)) {
      return v > 0 ? "+Inf" : "-Inf";
    }
    if (Double.compare(v, Math.rint(v)) == 0 && Math.abs(v) < 1e15) {
      return Long.toString((long) v);
    }
    return String.format(Locale.ROOT, "%s", v);
  }

  private static String escape(final String v) {
    return v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
  }
}
