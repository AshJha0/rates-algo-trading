# Cookbook

## How recipes work

Each recipe below is a program in `rates-cookbook/src/main/java/io/ratesalgo/cookbook/`. `RecipesTest` runs it in CI and compares its output with the block under "Expected output"; `tools/check_cookbook.py` checks that this page quotes both verbatim. The outputs were recorded on 2026-10-31 with JDK 25.0.5 on Windows 11 and reproduced on JDK 27; they do not depend on the machine.

## The planned recipes

1. How do I use the ManualClock and TimerWheel in a test? (P0, written below)
2. How do I write frames to a journal and replay them? (P0, written below)
3. How do I encode and decode a QuoteRequest with the SBE codecs? (P0, written below)
4. How do I bootstrap a USD SOFR discount curve from OIS quotes? (P1)
5. How do I price a swap and get its par rate and DV01? (P1)
6. How do I price a Treasury from yield and get accrued interest? (P1)
7. How do I compute a gilt's accrued interest inside the ex-dividend period? (P1)
8. How do I find the CTD and net basis for a Treasury futures contract? (P1)
9. How do I compare a result to QuantLib within a stated tolerance? (P1)
10. How do I start a venue simulator and connect a FIX 4.4 session? (P2)
11. How do I subscribe to the SBE book feed and rebuild a book? (P2)
12. How do I run the event loop against a journal and check determinism? (P2)
13. How do I check that a hot-path package allocates nothing? (P2)
14. How do I configure a quoting parameter and see its effect on skew? (P3)
15. How do I read the DV01 ladder and ask the hedger what it would do? (P3)
16. How do I set a risk limit and trigger the kill switch in a test? (P3)
17. How do I run the back-tester on one synthetic day? (P5, written below)
18. How do I pre-register an experiment and record it in the ledger? (P5, written below)
19. How do I reconcile attribution to position P&L? (P5, written below; a Python recipe)
20. How do I verify a Java journal's hash chain from Python? (P5)
21. How do I route a hedge order and read the markout report? (P4; was item 17 before P5 took 17 to 19)
22. How do I run the latency job and read the histogram? (P5 latency harness; was item 19)

## 1. How do I use the ManualClock and TimerWheel in a test?

`ManualClock` is a `Clock` whose time only moves when the test calls `advance`; both readings move together. `TimerWheel(tickNanos, wheelSize, initialCapacity, startNanos)` is a hashed wheel: `schedule(deadlineNanos, handler)` returns an id, `cancel(id)` removes a timer, and `poll(nowNanos)` fires every timer whose deadline is at or before `nowNanos`, in deadline order and then id order. There is no scheduler thread; the owner calls `poll` from its own loop. The handler receives its timer id and the `nowNanos` of the poll that fired it, so a late poll shows how late it is.

```java
package io.ratesalgo.cookbook;

import io.ratesalgo.common.time.ManualClock;
import io.ratesalgo.common.time.TimerWheel;
import java.util.ArrayList;
import java.util.List;

/** COOKBOOK.md recipe 1: a ManualClock drives a TimerWheel in a test. */
public final class Recipe01TimerWheel {

  private Recipe01TimerWheel() {}

  public static void main(final String[] args) {
    final ManualClock clock = new ManualClock(1_700_000_000_000_000_000L);
    final long t0 = clock.nanoTime();
    // 1 ms ticks, 1024 slots, room for 16 timers before the wheel grows, tick zero at t0
    final TimerWheel wheel = new TimerWheel(1_000_000L, 1024, 16, t0);
    final List<String> fired = new ArrayList<>();

    final long a = wheel.schedule(t0 + 5_000_000L, (id, now) -> fired.add("a@" + (now - t0)));
    final long b = wheel.schedule(t0 + 2_000_000L, (id, now) -> fired.add("b@" + (now - t0)));
    final long c = wheel.schedule(t0 + 5_000_000L, (id, now) -> fired.add("c@" + (now - t0)));
    wheel.cancel(c);

    clock.advance(1_000_000L);
    final int n1 = wheel.poll(clock.nanoTime());
    System.out.println("after 1 ms: fired=" + n1 + " " + fired);

    clock.advance(4_000_000L);
    final int n2 = wheel.poll(clock.nanoTime());
    System.out.println("after 5 ms: fired=" + n2 + " " + fired);
    System.out.println("ids distinct: " + (a != b && b != c) + ", live: " + wheel.liveCount());
  }
}
```

Expected output:

```
after 1 ms: fired=0 []
after 5 ms: fired=2 [b@5000000, a@5000000]
ids distinct: true, live: 0
```

Timer `b` was due at 2 ms but the loop only polled at 5 ms, so its handler sees `now - t0 = 5000000`; the firing order is still by deadline, `b` before `a`. Timer `c` was cancelled and never fires, and nothing is live afterwards.

## 2. How do I write frames to a journal and replay them?

`JournalWriter.create(path, capacityBytes, clock)` maps a new file and appends frames with `append(templateId, schemaVersion, buffer, offset, length)`; the timestamp comes from the writer's clock. Each frame carries sequence, timestamp, template id, schema version and a chain hash (API_SPEC.md Section 1). `Replayer` reads them back in order, drives its `ManualClock` to each frame's stamp, recomputes the chain and throws `JournalCorruptionException` naming the sequence of the first bad frame.

```java
package io.ratesalgo.cookbook;

import io.ratesalgo.common.time.ManualClock;
import io.ratesalgo.journal.JournalCorruptionException;
import io.ratesalgo.journal.JournalWriter;
import io.ratesalgo.journal.Replayer;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import org.agrona.concurrent.UnsafeBuffer;

/** COOKBOOK.md recipe 2: write frames to a journal, replay them, and see the chain check fire. */
public final class Recipe02Journal {

  private Recipe02Journal() {}

  public static void main(final String[] args) throws IOException {
    final Path dir = Files.createTempDirectory("recipe02");
    final Path file = dir.resolve("recipe.raj");
    final ManualClock clock = new ManualClock(0L);

    final UnsafeBuffer payload = new UnsafeBuffer(ByteBuffer.allocateDirect(64));
    try (JournalWriter writer = JournalWriter.create(file, 1 << 16, clock)) {
      for (int i = 1; i <= 3; i++) {
        clock.advance(1_000L);
        payload.putLong(0, 100L * i);
        writer.append(/* templateId */ 1, /* schemaVersion */ 0, payload, 0, 8);
      }
    }

    final ManualClock replayClock = new ManualClock(0L);
    final long frames =
        new Replayer(replayClock)
            .replay(
                file,
                (sequence, timestampNanos, templateId, schemaVersion, buffer, offset, length) ->
                    System.out.println(
                        "seq="
                            + sequence
                            + " ts="
                            + timestampNanos
                            + " template="
                            + templateId
                            + " value="
                            + buffer.getLong(offset)));
    System.out.println(
        "frames=" + frames + " finalHash=" + Long.toHexString(Replayer.finalHash(file)));

    // flip one payload byte of the second frame: header 32 + first frame 40 + frame header 32
    final byte[] bytes = Files.readAllBytes(file);
    bytes[32 + 40 + 32] ^= 0x01;
    Files.write(file, bytes);
    try {
      Replayer.finalHash(file);
    } catch (final JournalCorruptionException e) {
      System.out.println("corruption detected at sequence " + e.sequence());
    }
    Files.delete(file);
    Files.delete(dir);
  }
}
```

Expected output:

```
seq=1 ts=1000 template=1 value=100
seq=2 ts=2000 template=1 value=200
seq=3 ts=3000 template=1 value=300
frames=3 finalHash=c6d0336109f10ed2
corruption detected at sequence 2
```

The final hash is reproduced by `research/ratesresearch/fnv.py` from the three eight-byte payloads alone, since only payload bytes and the previous hash are hashed (API_SPEC.md Section 1.3). The flipped byte sits in the payload of frame 2, so frame 1 replays and frame 2 is reported.

## 3. How do I encode and decode a QuoteRequest with the SBE codecs?

The codecs are generated by `sbe-tool` from `rates-messages/src/main/resources/sbe/rates-schema-v1.xml` into `io.ratesalgo.messages.sbe`. Encoders and decoders are flyweights: wrap them over a buffer, set or read fields, and reuse the instance. `wrapAndApplyHeader` writes or reads the standard SBE message header (block length, template id, schema id, version) ahead of the body, and `encoder.limit()` is the total encoded length.

```java
package io.ratesalgo.cookbook;

import io.ratesalgo.messages.sbe.MessageHeaderDecoder;
import io.ratesalgo.messages.sbe.MessageHeaderEncoder;
import io.ratesalgo.messages.sbe.QuoteRequestDecoder;
import io.ratesalgo.messages.sbe.QuoteRequestEncoder;
import io.ratesalgo.messages.sbe.Side;
import java.nio.ByteBuffer;
import org.agrona.concurrent.UnsafeBuffer;

/** COOKBOOK.md recipe 3: encode and decode a QuoteRequest with the generated SBE codecs. */
public final class Recipe03QuoteRequest {

  private Recipe03QuoteRequest() {}

  public static void main(final String[] args) {
    final UnsafeBuffer buffer = new UnsafeBuffer(ByteBuffer.allocateDirect(256));

    final MessageHeaderEncoder headerEncoder = new MessageHeaderEncoder();
    final QuoteRequestEncoder encoder = new QuoteRequestEncoder();
    encoder
        .wrapAndApplyHeader(buffer, 0, headerEncoder)
        .venueId(7)
        .rfqId(42L)
        .clientId(7)
        .instrumentId(1001)
        .side(Side.Buy)
        .quantity(10_000_000_000L) // 100m notional in minor units (API_SPEC.md Section 0)
        .currency("USD")
        .dealerCount((short) 4)
        .receivedNanos(1_700_000_000_000_000_000L)
        .expiryNanos(1_700_000_000_000_000_000L + 30_000_000_000L);
    final int encodedLength = encoder.limit();

    final MessageHeaderDecoder headerDecoder = new MessageHeaderDecoder();
    final QuoteRequestDecoder decoder = new QuoteRequestDecoder();
    decoder.wrapAndApplyHeader(buffer, 0, headerDecoder);

    System.out.println(
        "templateId="
            + headerDecoder.templateId()
            + " schemaId="
            + headerDecoder.schemaId()
            + " version="
            + headerDecoder.version()
            + " blockLength="
            + headerDecoder.blockLength());
    System.out.println(
        "rfqId="
            + decoder.rfqId()
            + " clientId="
            + decoder.clientId()
            + " instrumentId="
            + decoder.instrumentId()
            + " side="
            + decoder.side()
            + " quantity="
            + decoder.quantity()
            + " currency="
            + decoder.currency()
            + " dealers="
            + decoder.dealerCount()
            + " expiryNanos="
            + decoder.expiryNanos());
    System.out.println("encodedLength=" + encodedLength);
  }
}
```

Expected output:

```
templateId=10 schemaId=1 version=0 blockLength=49
rfqId=42 clientId=7 instrumentId=1001 side=Buy quantity=10000000000 currency=USD dealers=4 expiryNanos=1700000030000000000
encodedLength=57
```

The block length of 49 is the sum of the fixed fields in the XML (4 + 8 + 4 + 4 + 1 + 8 + 3 + 1 + 8 + 8) and the encoded length adds the 8-byte header. Field names come from the XML, which is the source of truth (API_SPEC.md Section 2).

## 17. How do I run the back-tester on one synthetic day?

`SyntheticDay` writes one session of QuoteRequests, BookUpdates and CurveInputs to a journal from a date and a seed; the presets in `data/presets.yaml` fix the market, the clients and the flow profile fitted from DTCC (API_SPEC.md Section 16.5). `BacktestConfig.fromPresets` builds the outcome models at one point of the declared win-probability band, and `BacktestRunner.run` replays the journal through a `Strategy` and returns fills, RFQ outcomes and the P&L attribution (Section 16.1). Running the same strategy at the three band points is how every result in this project is reported (DR-008).

```java
package io.ratesalgo.cookbook;

import io.ratesalgo.backtest.BacktestConfig;
import io.ratesalgo.backtest.BacktestResult;
import io.ratesalgo.backtest.BacktestRunner;
import io.ratesalgo.backtest.RfqRecord;
import io.ratesalgo.backtest.model.BandPoint;
import io.ratesalgo.backtest.model.Presets;
import io.ratesalgo.backtest.pnl.Attribution;
import io.ratesalgo.backtest.strategy.FixedSpreadStrategy;
import io.ratesalgo.backtest.synth.SyntheticDay;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

/** COOKBOOK.md recipe 17: run the back-tester on one synthetic day at every band point. */
public final class Recipe17Backtest {

  private Recipe17Backtest() {}

  public static void main(final String[] args) throws IOException {
    final Presets presets = Presets.load(RepoRoot.find().resolve("data/presets.yaml"));
    final Path dir = Files.createTempDirectory("recipe17");
    final Path journal = dir.resolve("2026-02-02.raj");
    final LocalDate date = LocalDate.of(2026, 2, 2);
    final SyntheticDay.Summary day =
        new SyntheticDay(SyntheticDay.Params.fromPresets(presets))
            .generate(date, 5_000_000L + date.toEpochDay(), journal, null);
    System.out.println(
        "journal: " + day.frames() + " frames, " + day.rfqs() + " rfqs, chain hash " + Long.toHexString(day.chainHash()));

    for (final BandPoint point : BandPoint.values()) {
      final BacktestConfig config = BacktestConfig.fromPresets(presets, List.of(journal), 7L, point, null);
      final BacktestResult r =
          new BacktestRunner(config).run(new FixedSpreadStrategy(0.30, 0.15, 100_000, 4));
      final long won = r.rfqs().stream().filter(RfqRecord::won).count();
      final Attribution a = r.total();
      System.out.println(
          String.format(
              Locale.ROOT,
              "%-7s won %d of %d, hedges %d, spread %.2f, inventory %.2f, hedge %.2f, fees %.2f,"
                  + " funding %.2f, P&L %.2f, residual below a cent: %b",
              point.label(), won, r.rfqs().size(), r.hedgeFills(), a.spreadCapture(), a.inventoryMtm(),
              a.hedgeCost(), a.fees(), a.funding(), a.positionPnl(), Math.abs(a.residual()) < 0.01));
    }
    Files.delete(journal);
    Files.delete(dir);
  }
}
```

Expected output:

```
journal: 94571 frames, 398 rfqs, chain hash f136c9dbcf88d7b1
low     won 180 of 398, hedges 11, spread 1601139.62, inventory 2204880.69, hedge -850804.69, fees -54451.50, funding -600.02, P&L 2900164.11, residual below a cent: true
central won 163 of 398, hedges 14, spread 1639687.50, inventory 1230784.67, hedge -963445.31, fees -61660.50, funding 1292.74, P&L 1846659.10, residual below a cent: true
high    won 129 of 398, hedges 15, spread 1748176.93, inventory 2386981.21, hedge -1116281.25, fees -71442.00, funding 2740.75, P&L 2950175.63, residual below a cent: true
```

The journal line is the same on every machine because the generator is seeded and the hash is the RAJ1 chain hash. The three band points see the same RFQs, competitors and uniform draws (common random numbers keyed by RFQ id), so the only difference between the rows is the slope of the win probability: the high point, where price matters most, wins the fewest RFQs. Inventory dominates the day's P&L, which is why one session says little and experiments use many (recipe 18).

## 18. How do I pre-register an experiment and record it in the ledger?

A pre-registration (API_SPEC.md Section 16.8) is a YAML file that names the hypothesis, metric, baseline, treatments, evaluation period, fitting period, holdout dates, success threshold, assumption band, author, date and the SHA-256 of the experiment config, written before the experiment runs. `ExperimentCli` refuses to run without one, refuses a holdout that overlaps the fitting or evaluation period, refuses a config whose hash has changed, and refuses to open a holdout twice. A run that passes is recorded in the ledger whatever its verdict, with the hashes of the pre-registration, config and journals and a hash chain over the ledger lines.

```java
package io.ratesalgo.cookbook;

import io.ratesalgo.backtest.prereg.ExperimentCli;
import io.ratesalgo.backtest.prereg.Ledger;
import io.ratesalgo.backtest.util.Hashing;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** COOKBOOK.md recipe 18: pre-register an experiment, see the refusals, record a run. */
public final class Recipe18Ledger {

  private Recipe18Ledger() {}

  public static void main(final String[] args) throws IOException {
    // a scratch repository with the real presets and a copy of the P5-001 experiment config
    final Path repo = Files.createTempDirectory("recipe18");
    Files.createDirectories(repo.resolve("data/experiments"));
    Files.createDirectories(repo.resolve("data/preregs"));
    Files.copy(RepoRoot.find().resolve("data/presets.yaml"), repo.resolve("data/presets.yaml"));
    Files.copy(
        RepoRoot.find().resolve("data/experiments/P5-001.config.yaml"),
        repo.resolve("data/experiments/demo.config.yaml"));
    final String configSha = Hashing.sha256TextFile(repo.resolve("data/experiments/demo.config.yaml"));

    final String prereg =
        """
        prereg_version: 1
        id: DEMO-001
        title: cookbook demonstration
        hypothesis: the model spread earns more than the fixed spread
        metric: total position P&L per session, USD
        baseline: fixed_spread
        treatments: [model_spread]
        period: {start: 2026-03-02, end: 2026-03-03}
        fitting_period: {start: 2026-01-05, end: 2026-01-16}
        holdout: [HOLDOUT]
        success_threshold: Holm-adjusted Welch p below 0.05 and bootstrap interval above zero at every band point
        assumption_band: {preset: usd_rfq_hit_rate, parameter: spread_to_cover_bp, points: [low, central, high]}
        author: cookbook
        date: 2026-10-31
        config: data/experiments/demo.config.yaml
        config_sha256: SHA
        """
            .replace("SHA", configSha);
    final Path file = repo.resolve("data/preregs/demo.yaml");

    System.out.println("1. no pre-registration:");
    run(repo, "--prereg", "data/preregs/demo.yaml");

    System.out.println("2. holdout overlapping the fitting period:");
    Files.writeString(file, prereg.replace("HOLDOUT", "2026-01-09, 2026-03-10"), StandardCharsets.UTF_8);
    run(repo, "--prereg", "data/preregs/demo.yaml");

    System.out.println("3. valid pre-registration:");
    Files.writeString(file, prereg.replace("HOLDOUT", "2026-03-09, 2026-03-10"), StandardCharsets.UTF_8);
    run(repo, "--prereg", "data/preregs/demo.yaml", "--commit", "recipe18");

    final Ledger ledger = new Ledger(repo.resolve("data/ledger/ledger.jsonl"));
    final Map<String, Object> entry = ledger.entries().get(0);
    System.out.println(
        "ledger seq "
            + entry.get("seq")
            + ", prereg_sha256 matches file: "
            + Hashing.sha256TextFile(file).equals(entry.get("prereg_sha256"))
            + ", journals "
            + ((java.util.List<?>) entry.get("journals")).size()
            + ", verdict "
            + entry.get("verdict"));

    System.out.println("4. holdout opened twice:");
    run(repo, "--prereg", "data/preregs/demo.yaml", "--scope", "holdout", "--commit", "recipe18");
    run(repo, "--prereg", "data/preregs/demo.yaml", "--scope", "holdout");
    System.out.println("ledger lines: " + ledger.entries().size());
  }

  private static void run(final Path repo, final String... args) throws IOException {
    final Path work = Files.createDirectories(repo.resolve("work"));
    final String[] all = new String[args.length + 4];
    System.arraycopy(args, 0, all, 0, args.length);
    all[args.length] = "--repo";
    all[args.length + 1] = repo.toString();
    all[args.length + 2] = "--work";
    all[args.length + 3] = work.toString();
    final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    final int code;
    try (PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
      code = ExperimentCli.run(all, out);
    }
    final String text =
        bytes.toString(StandardCharsets.UTF_8).replace(repo.toString(), "<repo>").replace('\\', '/');
    for (final String line : text.strip().split("\\R", -1)) {
      System.out.println("   " + line.strip());
    }
    System.out.println("   exit code " + code);
  }
}
```

Expected output:

```
1. no pre-registration:
   refused:
   pre-registration not found: <repo>/data/preregs/demo.yaml
   exit code 2
2. holdout overlapping the fitting period:
   refused:
   holdout date 2026-01-09 overlaps the fitting period 2026-01-05..2026-01-16
   exit code 2
3. valid pre-registration:
   DEMO-001 evaluation look 1: not supported (2 days)
   recorded in data/ledger/ledger.jsonl
   exit code 0
ledger seq 1, prereg_sha256 matches file: true, journals 2, verdict not supported
4. holdout opened twice:
   DEMO-001 holdout look 1: not supported (2 days)
   recorded in data/ledger/ledger.jsonl
   exit code 0
   refused: the holdout of DEMO-001 has already been opened once
   exit code 2
ledger lines: 2
```

Refused runs read no data and leave the ledger untouched; the two recorded runs (evaluation, then the holdout once) are the two ledger lines. The real experiment of this phase, P5-001, went through the same command on 2026-10-31 and its record is the first line of `data/ledger/ledger.jsonl` (LEARN.md Section 10).

## 19. How do I reconcile attribution to position P&L?

The runner books every trade twice: as cash and position (position-based P&L is cash plus the final position at the final mid) and as attribution lines (spread capture, inventory mark-to-market, hedge cost, carry and roll, funding, fees). Because valuation is linear in price the two agree identically, and the residual must stay below one cent on every run (API_SPEC.md Section 16.4). This recipe is in Python: `ratesresearch.backtest_analysis.reconcile` checks a run's tables independently of the Java code, rebuilding spread capture, hedge cost and fees from the fills. The input is a fitting-period run committed as a fixture under `data/fixtures/backtest/fitting/`.

```python
"""COOKBOOK.md recipe 19: reconcile a run's attribution to its position-based P&L."""

from pathlib import Path

from ratesresearch import backtest_analysis as ba

root = Path(__file__).resolve().parents[2]
run = ba.load_run(root / "data/fixtures/backtest/fitting/2026-01-05")
total = run.pnl[run.pnl["instrument"] == "TOTAL"].iloc[0]

explained = 0.0
for line in ba.LINES:
    explained += float(total[line])
    print(f"{line:<15} {float(total[line]):>16,.2f}")
print(f"{'explained':<15} {explained:>16,.2f}")
print(f"{'position_pnl':<15} {float(total['position_pnl']):>16,.2f}")
print(f"{'residual':<15} {float(total['residual']):>16,.6f}")

client = run.fills[run.fills["kind"] == "client"]
hedge = run.fills[run.fills["kind"] == "hedge"]
print(f"client fills {len(client)}, sum of edges {client['edge'].sum():,.2f}")
print(f"hedge fills {len(hedge)}, sum of edges {hedge['edge'].sum():,.2f}")
failures = ba.reconcile(run)
print("reconciles to the cent:", not failures)
```

Expected output:

```
spread_capture      1,986,264.94
inventory_mtm      -1,547,896.30
hedge_cost         -1,633,546.88
carry_roll                  0.00
funding                -1,940.61
fees                 -104,547.00
explained          -1,301,665.84
position_pnl       -1,301,665.84
residual                0.000000
client fills 144, sum of edges 1,986,264.94
hedge fills 13, sum of edges -1,633,546.88
reconciles to the cent: True
```

The explained lines sum to the position P&L exactly at two decimals, and the sums of fill edges equal the spread-capture and hedge-cost lines. Inventory is the line the fills alone cannot rebuild, since it needs the mid path; it is the difference that the identity pins down. `tools/check_cookbook.py` runs this script in CI and compares its output with the block above.
