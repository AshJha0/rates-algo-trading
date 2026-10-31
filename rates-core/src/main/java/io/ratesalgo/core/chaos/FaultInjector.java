package io.ratesalgo.core.chaos;

import io.ratesalgo.core.ControlMessages;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;

/**
 * Sits between a message source and a loop's inbound ring and injects faults: drop, duplicate,
 * delay by a number of later messages, and reorder with the next message. It also publishes gateway
 * connect and disconnect events. For tests and simulations only; it copies every message and
 * allocates freely.
 *
 * <p>Decisions come from a {@link SplittableRandom} seeded by the {@link FaultPlan}, so one plan and
 * one input sequence always give the same delivered sequence. Every message that reaches the
 * downstream is passed to the observer in delivery order, which is what a test compares with what
 * the loop processed. Delay counts messages, not nanoseconds: a delayed message is released after
 * the given number of later offers, or by {@link #flush()}.
 */
public final class FaultInjector {

  /** Where messages go: usually {@code InboundPublisher::offer}. */
  @FunctionalInterface
  public interface Delivery {
    boolean offer(DirectBuffer buffer, int offset, int length);
  }

  /** Told about each message the downstream accepted, in order. */
  @FunctionalInterface
  public interface DeliveryObserver {
    void delivered(DirectBuffer buffer, int offset, int length);
  }

  /**
   * Fault probabilities per offered message; they are tried in this order and must sum to at most
   * 1.
   *
   * @param dropRate probability the message is never delivered
   * @param duplicateRate probability it is delivered twice in a row
   * @param delayRate probability it is held for 1 to {@code maxDelay} later messages
   * @param reorderRate probability it is swapped with the next message
   * @param maxDelay the longest delay, in messages
   * @param seed the random seed
   */
  public record FaultPlan(
      double dropRate,
      double duplicateRate,
      double delayRate,
      double reorderRate,
      int maxDelay,
      long seed) {

    public FaultPlan {
      for (final double r : new double[] {dropRate, duplicateRate, delayRate, reorderRate}) {
        if (!(r >= 0.0 && r <= 1.0)) {
          throw new IllegalArgumentException("fault rate outside [0, 1]: " + r);
        }
      }
      if (dropRate + duplicateRate + delayRate + reorderRate > 1.0 + 1e-12) {
        throw new IllegalArgumentException("fault rates sum to more than 1");
      }
      if (maxDelay < 1) {
        throw new IllegalArgumentException("maxDelay must be at least 1: " + maxDelay);
      }
    }

    /** No faults. */
    public static FaultPlan none() {
      return new FaultPlan(0, 0, 0, 0, 1, 0L);
    }
  }

  private record Held(byte[] bytes, long releaseAfter) {}

  private final FaultPlan plan;
  private final Delivery downstream;
  private final SplittableRandom random;
  private final List<Held> held = new ArrayList<>();
  private final UnsafeBuffer view = new UnsafeBuffer(new byte[0]);
  private final UnsafeBuffer control = new UnsafeBuffer(new byte[64]);
  private DeliveryObserver observer = (b, o, l) -> {};

  private long offered;
  private long dropped;
  private long duplicated;
  private long delayed;
  private long reordered;
  private long delivered;
  private long refused;

  public FaultInjector(final FaultPlan plan, final Delivery downstream) {
    this.plan = plan;
    this.downstream = downstream;
    this.random = new SplittableRandom(plan.seed());
  }

  /** Sets the observer of delivered messages. */
  public FaultInjector observer(final DeliveryObserver deliveryObserver) {
    this.observer = deliveryObserver;
    return this;
  }

  /** Offers one message; it may be dropped, duplicated, held or delivered now. */
  public void offer(final DirectBuffer buffer, final int offset, final int length) {
    offered++;
    final byte[] copy = new byte[length];
    buffer.getBytes(offset, copy);
    final double roll = random.nextDouble();
    double edge = plan.dropRate();
    if (roll < edge) {
      dropped++;
    } else if (roll < (edge += plan.duplicateRate())) {
      duplicated++;
      deliver(copy);
      deliver(copy);
    } else if (roll < (edge += plan.delayRate())) {
      delayed++;
      held.add(new Held(copy, offered + 1 + random.nextInt(plan.maxDelay())));
    } else if (roll < edge + plan.reorderRate()) {
      reordered++;
      held.add(new Held(copy, offered + 1));
    } else {
      deliver(copy);
    }
    releaseDue();
  }

  /** Delivers every held message, in the order they were held. */
  public void flush() {
    final List<Held> all = new ArrayList<>(held);
    held.clear();
    for (final Held h : all) {
      deliver(h.bytes());
    }
  }

  /** Publishes a GatewayStatus message for {@code gatewayId}; not subject to faults. */
  public boolean gatewayStatus(final int gatewayId, final boolean connected, final long timestampNanos) {
    final int length =
        ControlMessages.encodeGatewayStatus(
            control,
            0,
            timestampNanos,
            gatewayId,
            connected ? ControlMessages.STATUS_CONNECTED : ControlMessages.STATUS_DISCONNECTED);
    final byte[] copy = new byte[length];
    control.getBytes(0, copy);
    return deliver(copy);
  }

  private void releaseDue() {
    for (int i = 0; i < held.size(); ) {
      if (held.get(i).releaseAfter() <= offered) {
        deliver(held.remove(i).bytes());
      } else {
        i++;
      }
    }
  }

  private boolean deliver(final byte[] bytes) {
    view.wrap(bytes);
    if (downstream.offer(view, 0, bytes.length)) {
      delivered++;
      observer.delivered(view, 0, bytes.length);
      return true;
    }
    refused++;
    return false;
  }

  public long offeredCount() {
    return offered;
  }

  public long droppedCount() {
    return dropped;
  }

  public long duplicatedCount() {
    return duplicated;
  }

  public long delayedCount() {
    return delayed;
  }

  public long reorderedCount() {
    return reordered;
  }

  /** Messages the downstream accepted. */
  public long deliveredCount() {
    return delivered;
  }

  /** Messages the downstream refused (ring full). */
  public long refusedCount() {
    return refused;
  }

  /** Messages held for later delivery. */
  public int heldCount() {
    return held.size();
  }
}
