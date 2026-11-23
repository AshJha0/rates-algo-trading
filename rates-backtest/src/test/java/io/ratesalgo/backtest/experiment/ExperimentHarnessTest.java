package io.ratesalgo.backtest.experiment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.backtest.BacktestConfig;
import io.ratesalgo.backtest.BacktestResult;
import io.ratesalgo.backtest.BacktestRunner;
import io.ratesalgo.backtest.RfqRecord;
import io.ratesalgo.backtest.Strategy;
import io.ratesalgo.backtest.TestSupport;
import io.ratesalgo.backtest.prereg.DataSets;
import io.ratesalgo.backtest.prereg.Ledger;
import io.ratesalgo.backtest.sim.PairedSessions;
import io.ratesalgo.backtest.sim.SimDay;
import io.ratesalgo.backtest.synth.SyntheticDay;
import io.ratesalgo.backtest.util.Yaml;
import io.ratesalgo.sim.MarketSpec;
import io.ratesalgo.sim.SessionSeeds;
import io.ratesalgo.sim.SimParams;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The long-run harness: common random numbers across arms, checkpoints and resume, the ledger
 * row written once and only when complete, refusals, status without peeking, and the pilot.
 * The arms are toys ({@link ToyArms}); no desk and no P&amp;L level is examined.
 */
class ExperimentHarnessTest {

  /** Toy arms that fail on one seed while {@link #FAIL_SEED} is set; for the resume test. */
  public static final class FlakyArms implements ArmFactory {
    static final AtomicLong FAIL_SEED = new AtomicLong(-1);

    @Override
    public String refusal(final String arm) {
      return new ToyArms().refusal(arm);
    }

    @Override
    public Strategy create(final String arm, final SimDay day) {
      if (day.model().seed() == FAIL_SEED.get()) {
        throw new IllegalStateException("simulated crash on seed " + day.model().seed());
      }
      return new ToyArms().create(arm, day);
    }
  }

  /** Arms that always fail, to drive the worker pool's failure path. */
  public static final class BrokenArms implements ArmFactory {
    @Override
    public String refusal(final String arm) {
      return null;
    }

    @Override
    public Strategy create(final String arm, final SimDay day) {
      throw new IllegalStateException("broken arm");
    }
  }

  private static Map<String, Object> withoutWallClock(final Map<String, Object> m) {
    final Map<String, Object> out = new LinkedHashMap<>();
    for (final Map.Entry<String, Object> e : m.entrySet()) {
      if ("wall_ms".equals(e.getKey())) {
        continue;
      }
      @SuppressWarnings("unchecked")
      final Object v = e.getValue() instanceof Map<?, ?> sub ? withoutWallClock((Map<String, Object>) sub) : e.getValue();
      out.put(e.getKey(), v);
    }
    return out;
  }

  @Test
  void armsShareTheSessionAndOnlyTheirOwnOrdersMoveTheMarket(@TempDir final Path dir) {
    final long seed = SessionSeeds.seed(SessionSeeds.ACCEPTANCE, 62_000);
    final SimParams params = SimParams.loadDefault();
    final SessionExecutor executor = new SessionExecutor(TestSupport.presets(), dir.resolve("work"));
    final List<BandParameters.Row> rows = List.of(new BandParameters.Row(BandParameters.CENTRAL, params));
    final List<String> arms = List.of("idle", "fixed", "fixed_hedged");
    final Map<String, Object> first = executor.run(seed, rows, arms, new ToyArms());
    final Map<String, Object> row = Yaml.asMap(Yaml.asMap(first.get("rows"), "rows").get("central"), "central");
    final Map<String, Object> out = Yaml.asMap(row.get("arms"), "arms");
    // one journal per row, replayed by every arm: their inputs are bit for bit the same
    for (final String arm : arms) {
      assertEquals(row.get("journal_chain"), Yaml.asMap(out.get(arm), arm).get("input_chain"), arm);
      assertEquals(row.get("rfqs"), Yaml.asMap(out.get(arm), arm).get("rfqs"), arm);
    }
    assertEquals(Long.toHexString(SessionSeeds.session(new MarketSpec(params), seed).replayHash()), row.get("market_hash"));
    assertEquals(0.0, ((Number) Yaml.asMap(out.get("idle"), "idle").get("position_pnl")).doubleValue(), 0.0);
    assertNotEquals(Yaml.asMap(out.get("fixed"), "f").get("output_chain"), Yaml.asMap(out.get("fixed_hedged"), "h").get("output_chain"));
    assertEquals(SessionSeeds.dayType(seed).name(), first.get("day_type"));
    // the same seed gives the same session record, timings aside
    assertEquals(withoutWallClock(first), withoutWallClock(executor.run(seed, rows, arms, new ToyArms())));
    assertThrows(IllegalArgumentException.class, () -> executor.run(8_020_549L, rows, arms, new ToyArms()));
  }

  /**
   * What is shared between arms at the level of the model: the exogenous path and every RFQ
   * attribute are identical; an arm's own orders change only the level offset X1 (and so the
   * latent mid it marks at and the mid competitors quote from) and its book's displacement.
   */
  @Test
  void theExogenousMarketIsIdenticalAcrossArms(@TempDir final Path dir) {
    final long seed = SessionSeeds.seed(SessionSeeds.ACCEPTANCE, 62_001);
    final MarketSpec spec = new MarketSpec(SimParams.loadDefault());
    final Path journal = dir.resolve("s.raj");
    SyntheticDay.generateSimV2(
        SimDay.of(spec, seed, PairedSessions.labelDate(seed)), SyntheticDay.Publication.fromPresets(TestSupport.presets()),
        journal, null, 3L * 3600L * 1_000_000_000L);
    final SimDay quiet = SimDay.of(spec, seed, PairedSessions.labelDate(seed));
    final SimDay hedging = SimDay.of(spec, seed, PairedSessions.labelDate(seed));
    final BacktestResult a =
        new BacktestRunner(BacktestConfig.forSimV2(TestSupport.presets(), quiet, List.of(journal), null)).run(new ToyArms().create("fixed", quiet));
    final BacktestResult b =
        new BacktestRunner(BacktestConfig.forSimV2(TestSupport.presets(), hedging, List.of(journal), null))
            .run(new ToyArms().create("fixed_hedged", hedging));
    assertTrue(b.hedgeFills() > 0);
    assertEquals(a.inputChainHash(), b.inputChainHash());
    assertEquals(a.rfqs().size(), b.rfqs().size());
    for (int n = 0; n < a.rfqs().size(); n++) {
      final RfqRecord x = a.rfqs().get(n);
      final RfqRecord y = b.rfqs().get(n);
      assertEquals(x.rfqId(), y.rfqId());
      assertEquals(x.receivedNanos(), y.receivedNanos());
      assertEquals(x.clientId(), y.clientId());
      assertEquals(x.instrumentId(), y.instrumentId());
      assertEquals(x.quantity(), y.quantity());
      assertEquals(x.dealers(), y.dealers());
      assertEquals(quiet.rfq(x.rfqId()), hedging.rfq(y.rfqId()));
    }
    final long end = 3L * 3600L * 1_000_000_000L;
    for (int i = 0; i < spec.instruments(); i++) {
      for (int k = 0; k < spec.steps(); k += 997) {
        assertEquals(quiet.model().exogenousMid(i, k), hedging.model().exogenousMid(i, k), 0.0);
      }
    }
    assertEquals(0.0, quiet.model().levelOffset(end), 0.0);
    assertNotEquals(0.0, hedging.model().levelOffset(end));
  }

  @Test
  void aKilledRunResumesWithoutRecomputingAndRecordsOnce(@TempDir final Path dir) throws IOException {
    final Path repo = HarnessFixtures.repo(dir);
    final String prereg = HarnessFixtures.description(repo, "T-RESUME");
    final Path out = dir.resolve("out");
    final String ledger = dir.resolve("ledger.jsonl").toString();
    final String[] args = {
      "run", "--prereg", prereg, "--repo", repo.toString(), "--out", out.toString(), "--factory", FlakyArms.class.getName(),
      "--ledger", ledger, "--in-process", "--commit", "abc123", "--dirty", "false"
    };
    final long first = SessionSeeds.seed(30_000_000L, 0);
    FlakyArms.FAIL_SEED.set(first + 1);
    assertThrows(IllegalStateException.class, () -> HarnessFixtures.run(args));
    final Checkpoints cp = new Checkpoints(out);
    assertTrue(cp.has(first));
    assertFalse(cp.has(first + 1));
    assertFalse(Files.exists(Path.of(ledger)), "nothing is recorded before every session is in");
    final Path kept = out.resolve("sessions").resolve(first + ".json");
    final byte[] before = Files.readAllBytes(kept);
    Files.setLastModifiedTime(kept, FileTime.fromMillis(1_000_000L));
    // a writer killed mid-file leaves only a temporary file, which the resume removes
    Files.writeString(out.resolve("sessions").resolve((first + 1) + ".json.tmp"), "{\"seed\": 1", StandardCharsets.UTF_8);

    FlakyArms.FAIL_SEED.set(-1);
    final HarnessFixtures.Run resumed = HarnessFixtures.run(args);
    assertEquals(ExperimentRunner.OK, resumed.code(), resumed.out());
    assertTrue(resumed.out().contains("1 of 2 sessions to run"), resumed.out());
    assertEquals(1_000_000L, Files.getLastModifiedTime(kept).toMillis(), "the finished session was not recomputed");
    assertEquals(new String(before, StandardCharsets.UTF_8), Files.readString(kept));
    assertFalse(Files.exists(out.resolve("sessions").resolve((first + 1) + ".json.tmp")));

    final List<Map<String, Object>> rows = new Ledger(Path.of(ledger)).entries();
    assertEquals(1, rows.size());
    final Map<String, Object> row = rows.get(0);
    assertEquals("T-RESUME", row.get("prereg_id"));
    assertEquals(DataSets.sessionsKey(30_000_000L, 0, 2), row.get("data_set"));
    assertEquals(1L, ((Number) row.get("data_set_look")).longValue());
    assertEquals("exploration", row.get("scope"));
    assertEquals("description", row.get("verdict"));
    assertEquals("abc123", row.get("code_commit"));
    assertEquals(false, row.get("code_dirty"));
    assertEquals(List.of(), row.get("prior_looks"));
    assertTrue(String.valueOf(row.get("journal_sha256")).matches("[0-9a-f]{64}"));
    assertTrue(String.valueOf(row.get("output_sha256")).matches("[0-9a-f]{64}"));
    final Map<String, Object> idle =
        Yaml.asMap(Yaml.asMap(Yaml.asMap(Yaml.asMap(Yaml.asMap(row.get("results"), "r").get("band"), "b").get("central"), "c").get("arms"), "a").get("idle"), "idle");
    assertTrue(idle.containsKey("attribution") && idle.containsKey("guards") && idle.containsKey("ci_low"));
    assertTrue(Files.isRegularFile(cp.summaryFile()));

    // the same pre-registration does not read the same data set twice
    final HarnessFixtures.Run again = HarnessFixtures.run(args);
    assertEquals(ExperimentRunner.REFUSED, again.code());
    assertTrue(again.out().contains("has already read data set"), again.out());

    // status reads names and timings only: a corrupted session file does not disturb it
    Files.writeString(kept, "not json", StandardCharsets.UTF_8);
    final HarnessFixtures.Run status = HarnessFixtures.run("status", "--out", out.toString());
    assertEquals(ExperimentRunner.OK, status.code(), status.out());
    assertTrue(status.out().contains("2 of 2 sessions done"), status.out());
    assertTrue(status.out().contains("complete: summary written"), status.out());
    assertTrue(status.out().contains("heartbeat:"), status.out());
  }

  @Test
  void aResumeOfAnotherRunIsRefused(@TempDir final Path dir) {
    final Path repo = HarnessFixtures.repo(dir);
    final Path out = dir.resolve("out");
    final Checkpoints cp = new Checkpoints(out);
    cp.writeOrCheckManifest(new LinkedHashMap<>(Map.of("prereg_id", "A", "created_utc", "x")));
    assertThrows(IllegalStateException.class, () -> cp.writeOrCheckManifest(new LinkedHashMap<>(Map.of("prereg_id", "B"))));
    // created_utc and workers may differ on a resume
    cp.writeOrCheckManifest(new LinkedHashMap<>(Map.of("prereg_id", "A", "created_utc", "y", "workers", 3L)));
    assertThrows(IllegalArgumentException.class, () -> cp.write(5L, Map.of("seed", 6L)));
    assertThrows(IllegalStateException.class, () -> new Checkpoints(repo).readManifest());
    assertThrows(IllegalStateException.class, () -> cp.loadComplete(List.of(1L)));
    cp.fail("x");
    assertTrue(cp.failed());
    cp.clearFailure();
    assertFalse(cp.failed());
    assertTrue(cp.timings().isEmpty());
  }

  @Test
  void runsThatMustNotStartAreRefusedBeforeAnySession(@TempDir final Path dir) throws IOException {
    final Path repo = HarnessFixtures.repo(dir);
    final String out = dir.resolve("out").toString();
    final String toy = ToyArms.class.getName();
    // a draft
    final String draft =
        HarnessFixtures.prereg(repo, "T-DRAFT", 30_000_000L, "idle", "[]", "{idle: toy}", "rule as text", "[]", "status: draft\n");
    HarnessFixtures.Run r = HarnessFixtures.run("run", "--prereg", draft, "--repo", repo.toString(), "--out", out, "--factory", toy);
    assertEquals(ExperimentRunner.REFUSED, r.code());
    assertTrue(r.out().contains("is a draft") && r.out().contains("not fixed"), r.out());
    // an arm the factory cannot build
    final String unknown = HarnessFixtures.prereg(repo, "T-ARM", 30_000_000L, "nobody", "[]", "{nobody: x}", "2", "[]", "");
    r = HarnessFixtures.run("run", "--prereg", unknown, "--repo", repo.toString(), "--out", out, "--factory", toy, "--dirty", "false");
    assertTrue(r.out().contains("arm nobody: unknown toy arm"), r.out());
    // a band row the simulator refuses (upper winner band at informed fraction 0.5, D14)
    final String band =
        HarnessFixtures.prereg(repo, "T-BAND", 30_000_000L, "idle", "[]", "{idle: toy}", "2", "[winner_half_spread]", "");
    r = HarnessFixtures.run("run", "--prereg", band, "--repo", repo.toString(), "--out", out, "--factory", toy);
    assertEquals(ExperimentRunner.REFUSED, r.code());
    assertTrue(r.out().contains("winner_half_spread:high is refused by the simulator's start-up checks"), r.out());
    // an output directory inside a git work tree, and a dirty checkout
    final String ok = HarnessFixtures.description(repo, "T-GIT");
    Files.createDirectories(dir.resolve("work/.git"));
    r = HarnessFixtures.run(
        "run", "--prereg", ok, "--repo", repo.toString(), "--out", dir.resolve("work/sub/out").toString(), "--factory", toy,
        "--dirty", "true");
    assertTrue(r.out().contains("inside the git work tree"), r.out());
    assertTrue(r.out().contains("uncommitted changes"), r.out());
    // an invalid file: spent base
    final String spent = HarnessFixtures.prereg(repo, "T-SPENT", 8_000_000L, "idle", "[]", "{idle: toy}", "2", "[]", "");
    r = HarnessFixtures.run("run", "--prereg", spent, "--repo", repo.toString(), "--out", out, "--factory", toy);
    assertEquals(ExperimentRunner.REFUSED, r.code());
    assertTrue(r.out().contains("seed base 8000000 is spent"), r.out());
    // a version 1 harness refuses a version 2 file
    assertEquals(ExperimentRunner.ERROR, HarnessFixtures.run().code());
    assertEquals(ExperimentRunner.ERROR, HarnessFixtures.run("nonsense").code());
    assertEquals(ExperimentRunner.REFUSED, HarnessFixtures.run("run").code());
    assertTrue(Files.notExists(dir.resolve("out")), "no refused run wrote anything");
    final HarnessFixtures.Run v = HarnessFixtures.run("validate", "--prereg", draft, "--repo", repo.toString());
    assertEquals(ExperimentRunner.OK, v.code());
    assertTrue(v.out().contains("valid (draft"), v.out());
    assertEquals(ExperimentRunner.REFUSED, HarnessFixtures.run("validate", "--prereg", spent, "--repo", repo.toString()).code());
  }

  @Test
  void workerProcessesRunSessionsAndAFailingSessionStopsTheRun(@TempDir final Path dir) {
    final Path repo = HarnessFixtures.repo(dir);
    final String prereg = HarnessFixtures.description(repo, "T-POOL");
    final Path out = dir.resolve("out");
    final HarnessFixtures.Run ok =
        HarnessFixtures.run(
            "run", "--prereg", prereg, "--repo", repo.toString(), "--out", out.toString(), "--factory", ToyArms.class.getName(),
            "--ledger", dir.resolve("ledger.jsonl").toString(), "--workers", "2", "--dirty", "false");
    assertEquals(ExperimentRunner.OK, ok.code(), ok.out());
    assertEquals(2, new Checkpoints(out).timings().size());

    final Path bad = dir.resolve("bad");
    final String broken = HarnessFixtures.description(repo, "T-BROKEN");
    final HarnessFixtures.Run failed =
        HarnessFixtures.run(
            "run", "--prereg", broken, "--repo", repo.toString(), "--out", bad.toString(), "--factory", BrokenArms.class.getName(),
            "--ledger", dir.resolve("ledger2.jsonl").toString(), "--workers", "1", "--dirty", "false");
    assertEquals(ExperimentRunner.INCOMPLETE, failed.code(), failed.out());
    assertTrue(failed.out().contains("retrying once") && failed.out().contains("failed twice"), failed.out());
    assertTrue(new Checkpoints(bad).failed());
    assertFalse(Files.exists(dir.resolve("ledger2.jsonl")));
    // a failed run is not restarted by accident
    final HarnessFixtures.Run again =
        HarnessFixtures.run(
            "run", "--prereg", broken, "--repo", repo.toString(), "--out", bad.toString(), "--factory", BrokenArms.class.getName(),
            "--ledger", dir.resolve("ledger2.jsonl").toString(), "--dirty", "false");
    assertEquals(ExperimentRunner.REFUSED, again.code());
    assertEquals(ExperimentRunner.ERROR, HarnessFixtures.run("status", "--out", bad.toString()).code());
  }

  @Test
  void thePilotUsesItsOwnBaseAndReportsVarianceNotTheMean(@TempDir final Path dir) throws IOException {
    final Path repo = HarnessFixtures.repo(dir);
    final String prereg =
        HarnessFixtures.prereg(
            repo, "T-PILOT", 32_000_000L, "fixed", "[fixed_hedged]", "{fixed: a, fixed_hedged: b}", "rule as text", "[]",
            "status: draft\n");
    final Path out = dir.resolve("pilot");
    final HarnessFixtures.Run r =
        HarnessFixtures.run(
            "pilot", "--prereg", prereg, "--repo", repo.toString(), "--out", out.toString(), "--factory", ToyArms.class.getName(),
            "--pilot", "3", "--arms", "fixed,fixed_hedged", "--in-process");
    assertEquals(ExperimentRunner.OK, r.code(), r.out());
    for (int n = 0; n < 3; n++) {
      assertTrue(Files.isRegularFile(out.resolve("sessions").resolve((SessionSeeds.PILOT + n) + ".json")));
    }
    final Map<String, Object> report = Yaml.loadMap(out.resolve("pilot.json"));
    assertTrue(report.containsKey("session_sd_usd") && report.containsKey("difference_sd_usd"));
    assertTrue(Yaml.asMap(report.get("implied_sessions"), "i").get("measured") instanceof Number);
    assertTrue(((Number) report.get("seconds_per_session_all_arms")).doubleValue() > 0.0);
    assertTrue(Yaml.asMap(report.get("projection"), "p").containsKey("hours_2000_sessions_per_arm_one_arm_central"));
    assertFalse(Files.readString(out.resolve("pilot.json")).contains("mean_pnl"));
    assertFalse(r.out().contains("mean_pnl"));
    // one arm: the implied sessions at the design table's correlations
    final HarnessFixtures.Run single =
        HarnessFixtures.run(
            "pilot", "--prereg", prereg, "--repo", repo.toString(), "--out", dir.resolve("pilot1").toString(),
            "--factory", ToyArms.class.getName(), "--pilot", "2", "--in-process");
    assertEquals(ExperimentRunner.OK, single.code(), single.out());
    assertTrue(single.out().contains("rho_0.9"), single.out());
    assertEquals(
        ExperimentRunner.REFUSED,
        HarnessFixtures.run("pilot", "--prereg", prereg, "--repo", repo.toString(), "--out", out.toString(), "--factory",
            ToyArms.class.getName(), "--pilot", "1").code());
  }

  @Test
  void defaultWorkersLeaveFourCoresFree() {
    assertEquals(Math.max(1, Runtime.getRuntime().availableProcessors() - 4), ExperimentRunner.defaultWorkers());
    assertEquals("true", ExperimentRunner.parse(new String[] {"--in-process", "--x", "1"}, 0).get("in-process"));
    assertThrows(IllegalArgumentException.class, () -> ExperimentRunner.parse(new String[] {"loose"}, 0));
  }
}
