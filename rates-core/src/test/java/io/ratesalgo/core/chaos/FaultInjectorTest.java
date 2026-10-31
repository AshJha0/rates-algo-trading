package io.ratesalgo.core.chaos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.core.ControlMessages;
import io.ratesalgo.core.chaos.FaultInjector.FaultPlan;
import java.util.ArrayList;
import java.util.List;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;

class FaultInjectorTest {

  private final UnsafeBuffer msg = new UnsafeBuffer(new byte[16]);

  private List<Integer> run(final FaultPlan plan, final int n) {
    final List<Integer> out = new ArrayList<>();
    final FaultInjector f = new FaultInjector(plan, (b, o, l) -> out.add(b.getInt(o)));
    for (int i = 1; i <= n; i++) {
      msg.putInt(0, i);
      f.offer(msg, 0, 16);
    }
    f.flush();
    assertEquals(0, f.heldCount());
    return out;
  }

  @Test
  void noFaultsDeliversInOrder() {
    assertEquals(List.of(1, 2, 3), run(FaultPlan.none(), 3));
  }

  @Test
  void eachFaultKindDoesWhatItSays() {
    assertEquals(List.of(), run(new FaultPlan(1, 0, 0, 0, 1, 1), 3));
    assertEquals(List.of(1, 1, 2, 2), run(new FaultPlan(0, 1, 0, 0, 1, 1), 2));
    // reorder with probability 1: each message waits for the next, the last waits for flush
    assertEquals(List.of(1, 2, 3), run(new FaultPlan(0, 0, 0, 1, 1, 1), 3));
    final List<Integer> delayed = run(new FaultPlan(0, 0, 1, 0, 3, 7), 20);
    assertEquals(20, delayed.size());
    assertEquals(210, delayed.stream().mapToInt(Integer::intValue).sum());
  }

  @Test
  void reorderSwapsWithTheNextMessage() {
    final List<Integer> out = new ArrayList<>();
    // seed chosen so the first message is reordered and the second is not
    for (long seed = 0; seed < 1000; seed++) {
      out.clear();
      final FaultInjector f = new FaultInjector(new FaultPlan(0, 0, 0, 0.5, 1, seed), (b, o, l) -> out.add(b.getInt(o)));
      msg.putInt(0, 1);
      f.offer(msg, 0, 16);
      if (f.reorderedCount() == 1) {
        msg.putInt(0, 2);
        f.offer(msg, 0, 16);
        if (f.reorderedCount() == 1) {
          assertEquals(List.of(2, 1), out);
          return;
        }
      }
    }
    throw new AssertionError("no seed reordered exactly the first message");
  }

  @Test
  void sameSeedSameDelivery() {
    final FaultPlan plan = new FaultPlan(0.1, 0.1, 0.1, 0.1, 4, 42);
    assertEquals(run(plan, 200), run(plan, 200));
  }

  @Test
  void countsAndRefusalsAndGatewayStatus() {
    final List<Integer> templates = new ArrayList<>();
    final boolean[] accept = {true};
    final FaultInjector f =
        new FaultInjector(
                new FaultPlan(0.2, 0.2, 0.2, 0.2, 2, 3),
                (b, o, l) -> accept[0])
            .observer((b, o, l) -> templates.add(l));
    for (int i = 0; i < 100; i++) {
      f.offer(msg, 0, 16);
    }
    accept[0] = false;
    for (int i = 0; i < 10; i++) {
      f.offer(msg, 0, 16);
    }
    f.flush();
    assertEquals(110, f.offeredCount());
    assertTrue(f.droppedCount() > 0 && f.duplicatedCount() > 0);
    assertTrue(f.delayedCount() > 0 && f.reorderedCount() > 0);
    assertTrue(f.refusedCount() > 0);
    assertEquals(f.deliveredCount(), templates.size());
    assertFalse(f.gatewayStatus(1, false, 5L));
    accept[0] = true;
    assertTrue(f.gatewayStatus(1, true, 5L));
    assertEquals(
        ControlMessages.HEADER_LENGTH + ControlMessages.GATEWAY_STATUS_BLOCK_LENGTH,
        templates.get(templates.size() - 1));
  }

  @Test
  void planValidation() {
    assertThrows(IllegalArgumentException.class, () -> new FaultPlan(-0.1, 0, 0, 0, 1, 0));
    assertThrows(IllegalArgumentException.class, () -> new FaultPlan(Double.NaN, 0, 0, 0, 1, 0));
    assertThrows(IllegalArgumentException.class, () -> new FaultPlan(0.5, 0.5, 0.5, 0, 1, 0));
    assertThrows(IllegalArgumentException.class, () -> new FaultPlan(0, 0, 0, 0, 0, 0));
  }
}
