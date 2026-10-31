package io.ratesalgo.core;

import java.util.concurrent.TimeUnit;
import org.agrona.concurrent.IdleStrategy;

/**
 * Runs an {@link EventLoop} on its own named thread: optional core pinning, start-of-day hook,
 * duty cycles with an idle strategy until closed, end-of-day hook.
 *
 * <p>If a duty cycle or a hook throws, the runner journals a LoopError through {@link
 * EventLoop#recordError(Throwable)} on the loop thread, skips the end-of-day hook and stops. The
 * failure is kept for {@link #failure()}; nothing is retried, because a loop that has thrown may
 * hold half-applied state (API_SPEC.md Section 4).
 */
public final class LoopRunner implements AutoCloseable {

  private final EventLoop loop;
  private final IdleStrategy idle;
  private final int cpu;
  private final LoopHook startOfDay;
  private final LoopHook endOfDay;
  private final Thread thread;

  private volatile boolean running = true;
  private volatile boolean pinned;
  private volatile Throwable failure;

  private LoopRunner(final Builder b) {
    this.loop = b.loop;
    this.idle = b.idle;
    this.cpu = b.cpu;
    this.startOfDay = b.startOfDay;
    this.endOfDay = b.endOfDay;
    this.thread = new Thread(this::run, b.name);
    this.thread.setDaemon(b.daemon);
  }

  /** A runner for {@code loop}; set a name and start it. */
  public static Builder builder(final EventLoop loop) {
    return new Builder(loop);
  }

  /** Starts the loop thread. */
  public LoopRunner start() {
    thread.start();
    return this;
  }

  private void run() {
    try {
      pinned = CoreAffinity.pin(cpu);
      startOfDay.run(loop);
      while (running) {
        idle.idle(loop.doWork());
      }
      endOfDay.run(loop);
    } catch (final Throwable t) {
      failure = t;
      try {
        loop.recordError(t);
      } catch (final Throwable journalFailure) {
        t.addSuppressed(journalFailure);
      }
    } finally {
      running = false;
    }
  }

  /** The loop thread's id, for per-thread measurements such as allocated bytes. */
  public long threadId() {
    return thread.threadId();
  }

  /** The loop thread's name. */
  public String threadName() {
    return thread.getName();
  }

  /** True while the loop thread is cycling. */
  public boolean isRunning() {
    return running && thread.isAlive();
  }

  /** True if the thread was pinned to its CPU. */
  public boolean isPinned() {
    return pinned;
  }

  /** What stopped the loop, or null if it was closed normally or is still running. */
  public Throwable failure() {
    return failure;
  }

  /**
   * Waits for the loop thread to end by itself (after a failure).
   *
   * @return true if it ended within the timeout
   */
  public boolean awaitTermination(final long timeout, final TimeUnit unit) throws InterruptedException {
    thread.join(Math.max(1L, unit.toMillis(timeout)));
    return !thread.isAlive();
  }

  /** Asks the loop to finish its cycle, run the end-of-day hook and stop; waits for it. */
  @Override
  public void close() {
    running = false;
    try {
      thread.join();
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /** Runner settings. */
  public static final class Builder {

    private final EventLoop loop;
    private String name = "rates-core-loop";
    private IdleStrategy idle = IdleStrategies.of("backoff");
    private int cpu = -1;
    private boolean daemon;
    private LoopHook startOfDay = LoopHook.NONE;
    private LoopHook endOfDay = LoopHook.NONE;

    Builder(final EventLoop loop) {
      this.loop = loop;
    }

    /** Thread name; name it after the instrument group so thread dumps and profiles say whose it is. */
    public Builder name(final String threadName) {
      this.name = threadName;
      return this;
    }

    public Builder idleStrategy(final IdleStrategy strategy) {
      this.idle = strategy;
      return this;
    }

    /** CPU to pin to on Linux; negative for none (the default). */
    public Builder cpu(final int cpuIndex) {
      this.cpu = cpuIndex;
      return this;
    }

    /** Whether the thread is a daemon; tests and benchmarks set this so a failure cannot hang the JVM. */
    public Builder daemon(final boolean isDaemon) {
      this.daemon = isDaemon;
      return this;
    }

    public Builder startOfDay(final LoopHook hook) {
      this.startOfDay = hook;
      return this;
    }

    public Builder endOfDay(final LoopHook hook) {
      this.endOfDay = hook;
      return this;
    }

    public LoopRunner build() {
      return new LoopRunner(this);
    }
  }
}
