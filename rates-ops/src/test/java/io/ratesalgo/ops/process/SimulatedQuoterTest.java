package io.ratesalgo.ops.process;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.common.time.ManualClock;
import io.ratesalgo.journal.JournalWriter;
import io.ratesalgo.journal.Replayer;
import io.ratesalgo.messages.sbe.KillReason;
import io.ratesalgo.messages.sbe.KillSwitchDecoder;
import io.ratesalgo.messages.sbe.MessageHeaderDecoder;
import io.ratesalgo.ops.config.ConfigValidatorMain;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The simulated quoter's kill, status and journal record, and the validator's report lines. */
class SimulatedQuoterTest {

  @TempDir Path dir;

  @Test
  void aKillPullsEveryQuoteOnceAndJournalsWhoAskedAndWhen() {
    final ManualClock clock = new ManualClock(1_800_000_000_000_000_000L);
    final Path j = dir.resolve("q.raj");
    final List<int[]> instruments = List.of(new int[] {2001, 1}, new int[] {2002, 1}, new int[] {2003, 1});
    try (JournalWriter w = JournalWriter.create(j, 1 << 16, clock)) {
      final SimulatedQuoter q = new SimulatedQuoter(w, clock, instruments, true);
      assertEquals("{\"quoting\":true,\"killSwitch\":false,\"liveQuotes\":3,\"instruments\":3}", q.statusJson());
      assertTrue(q.quoting());
      assertFalse(q.killed());
      clock.advance(5L);
      assertNull(q.kill(42), "confirmed: nothing left to report");
      assertNull(q.kill(43), "a second kill is a no-op");
      assertEquals("{\"quoting\":false,\"killSwitch\":true,\"liveQuotes\":0,\"instruments\":3}", q.statusJson());
      assertFalse(q.quoting());
      assertTrue(q.killed());
      assertEquals(0, q.liveQuotes());
      assertEquals(1L, q.frames());
    }
    final List<String> frames = new ArrayList<>();
    final MessageHeaderDecoder h = new MessageHeaderDecoder();
    final KillSwitchDecoder k = new KillSwitchDecoder();
    new Replayer(new ManualClock(0)).replay(j, (seq, at, t, v, b, o, l) -> {
      h.wrap(b, o);
      k.wrapAndApplyHeader(b, o, h);
      frames.add(t + " " + k.reason() + " " + k.requestedBy() + " " + (k.timestampNanos() - 1_800_000_000_000_000_000L));
    });
    assertEquals(List.of(KillSwitchDecoder.TEMPLATE_ID + " " + KillReason.Manual + " 42 5"), frames);

    try (JournalWriter w = JournalWriter.create(dir.resolve("off.raj"), 1 << 16, clock)) {
      final SimulatedQuoter off = new SimulatedQuoter(w, clock, instruments, false);
      assertEquals(0, off.liveQuotes());
      assertFalse(off.quoting());
    }
  }

  @Test
  void theValidatorPrintsOneLinePerFileAndEachErrorIndented() throws Exception {
    final Path schema = Path.of("../deploy/config/schema/process-config.schema.json");
    final Path blue = Path.of("../deploy/config/trading-blue.yaml");
    final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    final PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);
    final Path bad = dir.resolve("bad.yaml");
    Files.writeString(bad, "schemaVersion: 2\n");
    final Path missing = dir.resolve("missing.yaml");
    assertEquals(1, ConfigValidatorMain.run(new String[] {schema.toString(), blue.toString(), bad.toString(),
        missing.toString()}, out));
    final List<String> lines = bytes.toString(StandardCharsets.UTF_8).lines().toList();
    assertEquals("valid   " + blue, lines.get(0));
    assertEquals("INVALID " + bad, lines.get(1));
    assertTrue(lines.get(2).startsWith("        $"), lines.get(2));
    assertTrue(lines.get(lines.size() - 1).startsWith("INVALID " + missing + ": "), lines.get(lines.size() - 1));
    bytes.reset();
    assertEquals(2, ConfigValidatorMain.run(new String[] {schema.toString()}, out));
    assertEquals("usage: ConfigValidatorMain SCHEMA CONFIG...", bytes.toString(StandardCharsets.UTF_8).strip());
    bytes.reset();
    assertEquals(2, ConfigValidatorMain.run(new String[] {missing.toString(), blue.toString()}, out));
    assertTrue(bytes.toString(StandardCharsets.UTF_8).startsWith("schema " + missing + ": "));
    bytes.reset();
    assertEquals(0, ConfigValidatorMain.run(new String[] {schema.toString(), blue.toString()}, out));
  }
}
