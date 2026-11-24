package io.ratesalgo.core;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.agrona.concurrent.IdleStrategy;

/**
 * Runs one {@link Agent} on its own named thread: optional core pinning, then duty cycles under an
 * idle strategy until closed. The trading process runs each FIX session, the book feed and the
 * simulated venues on one of these (DECISIONS.md DR-R6-L02); {@link LoopRunner} does the same for
 * an event loop, with the loop's own failure journaling.
 *
 * <p>Every duty cycle first moves a beat counter that other threads read ({@link #beats()}), so a
 * supervisor can tell a thread that cycles from one that is stuck inside a cycle. The counter is
 * written with an ordered store, a plain store on x86.
 *
 * <p>If the agent throws, the failure is kept for {@link #failure()} and handed to the failure
 * hook on this same thread before it ends: a thread that owns a FIX session uses the hook to
 * withdraw from its venue on the way down, since no other thread may act on the session. Nothing
 * is retried.
 */
public final class DutyCycleRunner implements AutoCloseable {

  private final Agent agent;
  private final IdleStrategy idle;
  private final int cpu;
  private final Consumer<Throwable> onFailure;
  private final Thread thread;
  private final long closeTimeoutMillis;
  private final AtomicLong beats = new AtomicLong();

  private volatile boolean running = true;
  private volatile boolean pinned;
  private volatile Throwable failure;

  private DutyCycleRunner(final Builder b) {
    this.agent = b.agent;
    this.idle = b.idle;
    this.cpu = b.cpu;
    this.onFailure = b.onFailure;
    this.closeTimeoutMillis = b.closeTimeoutMillis;
    this.thread = new Thread(this::run, b.name);
    this.thread.setDaemon(b.daemon);
  }

  /** A runner for {@code agent} on a thread called {@code name}. */
  public static Builder builder(final String name, final Agent agent) {
    return new Builder(name, agent);
  }

  /** Starts the thread. */
  public DutyCycleRunner start() {
    thread.start();
    return this;
  }

  private void run() {
    try {
      pinned = CoreAffinity.pin(cpu);
      while (running) {
        beats.lazySet(beats.get() + 1);
        idle.idle(agent.doWork());
      }
    } catch (final Throwable t) {
      failure = t;
      try {
        onFailure.accept(t);
      } catch (final Throwable again) {
        t.addSuppressed(again);
      }
    } finally {
      running = false;
    }
  }

  /** Duty cycles started so far. Any thread. */
  public long beats() {
    return beats.get();
  }

  /** True while the thread is cycling. */
  public boolean isRunning() {
    return running && thread.isAlive();
  }

  /** True while the thread exists, cycling, stuck or withdrawing after a failure. */
  public boolean isAlive() {
    return thread.isAlive();
  }

  /** True if the thread was pinned to its CPU. */
  public boolean isPinned() {
    return pinned;
  }

  /** What stopped the agent, or null if it was closed normally or is still running. */
  public Throwable failure() {
    return failure;
  }

  /** The thread's name. */
  public String name() {
    return thread.getName();
  }

  /** The thread's id, for per-thread measurements. */
  public long threadId() {
    return thread.threadId();
  }

  /** True when called on this runner's own thread. */
  public boolean isCurrentThread() {
    return Thread.currentThread() == thread;
  }

  /**
   * Asks the thread to finish its cycle and stop, and waits up to the close timeout.
   *
   * @return true if the thread has ended
   */
  public boolean stop() {
    running = false;
    try {
      thread.join(closeTimeoutMillis);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    return !thread.isAlive();
  }

  /** {@link #stop()}; a thread still alive after the timeout is left behind. */
  @Override
  public void close() {
    stop();
  }

  /** Runner settings. */
  public static final class Builder {

    private final String name;
    private final Agent agent;
    private IdleStrategy idle = IdleStrategies.of("park");
    private int cpu = -1;
    private boolean daemon = true;
    private long closeTimeoutMillis = 5_000L;
    private Consumer<Throwable> onFailure = t -> {};

    Builder(final String name, final Agent agent) {
      this.name = name;
      this.agent = agent;
    }

    /** What the thread does after an empty cycle; {@code park} by default. */
    public Builder idleStrategy(final IdleStrategy strategy) {
      this.idle = strategy;
      return this;
    }

    /** CPU to pin to on Linux; negative for none (the default). */
    public Builder cpu(final int cpuIndex) {
      this.cpu = cpuIndex;
      return this;
    }

    /** Whether the thread is a daemon; true by default, so a stuck thread cannot hold the JVM. */
    public Builder daemon(final boolean isDaemon) {
      this.daemon = isDaemon;
      return this;
    }

    /** How long {@link DutyCycleRunner#stop()} waits; five seconds by default. */
    public Builder closeTimeoutMillis(final long millis) {
      if (millis < 1) {
        throw new IllegalArgumentException("close timeout must be positive: " + millis);
      }
      this.closeTimeoutMillis = millis;
      return this;
    }

    /** Called on the runner's thread with the agent's failure, before the thread ends. */
    public Builder onFailure(final Consumer<Throwable> hook) {
      this.onFailure = hook;
      return this;
    }

    public DutyCycleRunner build() {
      return new DutyCycleRunner(this);
    }
  }
}
