package io.ratesalgo.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.app.loop.DeskProfile;
import io.ratesalgo.app.pricing.PricingBindings;
import io.ratesalgo.common.security.KeytoolCertificates;
import io.ratesalgo.common.security.Secret;
import io.ratesalgo.ops.config.ProcessConfig;
import io.ratesalgo.sim.SessionSeeds;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The {@code desk} section of the process configuration: the v2 example starts the v2 profile as
 * deployed (FIX over TLS with credentials, sequence numbers resumed, on all four sessions), the
 * selection is written beside the journals, a restart with another selection is refused, a restart
 * with the same one recovers, and replay mode follows the file.
 */
class TradingProcessMainV2Test {

  private static final Path ROOT = root();
  private static final String SCHEMA = ROOT.resolve("deploy/config/schema/process-config.schema.json").toString();
  private static final String STORE_PASSWORD = "test-store-" + System.nanoTime();
  private static final String FIX_PASSWORD = "test-fix-" + System.nanoTime();
  private static final String SEED = Long.toString(SessionSeeds.seed(SessionSeeds.ACCEPTANCE, 91_007));

  @TempDir Path dir;

  private final ByteArrayOutputStream out = new ByteArrayOutputStream();
  private final ByteArrayOutputStream err = new ByteArrayOutputStream();

  private static Path root() {
    Path p = Path.of("").toAbsolutePath();
    while (p != null && !Files.isDirectory(p.resolve("deploy/config"))) {
      p = p.getParent();
    }
    return p;
  }

  private String config(final String name, final KeytoolCertificates.Identity id) throws Exception {
    final Path copy = dir.resolve(name);
    Files.writeString(copy, Files.readString(ROOT.resolve("deploy/config").resolve(name))
        .replace("/run/rates/certs/server-keystore.p12", id.keyStore().toString().replace(java.io.File.separatorChar, '/'))
        .replace("/run/rates/certs/truststore.p12", id.trustStore().toString().replace(java.io.File.separatorChar, '/')));
    return copy.toString();
  }

  private Map<String, String> env(final Path journals) {
    final Map<String, String> env = new HashMap<>();
    env.put("RATES_HTTP_KEYSTORE_PASSWORD", STORE_PASSWORD);
    env.put("RATES_FIX_TRUSTSTORE_PASSWORD", STORE_PASSWORD);
    env.put("RATES_FIX_ACCEPTOR_KEYSTORE_PASSWORD", STORE_PASSWORD);
    env.put("RATES_FIX_PASSWORD", FIX_PASSWORD);
    env.put("RATES_HTTP_PORT", "0");
    env.put("RATES_JOURNAL_DIR", journals.toString());
    env.put("RATES_CONTROL_TOKEN", "t0k3n");
    return env;
  }

  private TradingProcessMain.Started start(final String config, final Path journals) throws Exception {
    return TradingProcessMain.start(
        new String[] {"--config", config, "--schema", SCHEMA, "--seed", SEED, "--time-scale", "60"}, env(journals),
        new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
  }

  @Test
  void theV2ExampleRunsTheV2ProfileRecordsItRefusesAnotherOnRestartAndReplaysByIt() throws Exception {
    final KeytoolCertificates.Identity id =
        KeytoolCertificates.localhost(dir.resolve("certs"), "server", Secret.of(STORE_PASSWORD), 30);
    final String v2 = config("trading-v2.yaml", id);
    final String blue = config("trading-blue.yaml", id);
    final Path journals = dir.resolve("journal");
    final Path selection = journals.resolve(TradingProcessMain.SELECTION_FILE);

    try (TradingProcessMain.Started s = start(v2, journals)) {
      assertNotNull(s, err.toString(StandardCharsets.UTF_8));
      final TradingProcess p = s.process();
      assertEquals(DeskProfile.Market.SIM_V2, p.profile().market());
      assertEquals(PricingBindings.Version.V2, p.profile().pricing());
      assertEquals(DeskProfile.RouterModel.LADDER, p.profile().router());
      assertEquals(TradingProcessMain.V2_START, p.options().startNanos(), "a v2 market starts at 09:00 New York");
      assertEquals(4, p.sessionCount());
      // as deployed: TLS, credentials and resumed sequence numbers on every session's acceptor
      assertNotNull(p.options().fix().acceptorTls());
      assertFalse(p.options().fix().resetOnLogon());
      assertEquals("market=simV2 pricing=v2 strategy=v2 router=ladder\n", Files.readString(selection));
      assertTrue(out.toString(StandardCharsets.UTF_8).contains("desk selection: market=simV2"), out.toString(StandardCharsets.UTF_8));
      // wall-clock paced: wait on the process itself, with a ceiling that only stops a broken build
      final long deadline = System.nanoTime() + 180_000_000_000L;
      while (p.group(0).quotesSent() == 0) {
        assertTrue(System.nanoTime() < deadline, "no quote within 180 s: " + p.statusJson());
        assertNull(p.failure());
        Thread.onSpinWait();
      }
      assertTrue(p.statusJson().contains("\"profile\":{\"market\":\"SIM_V2\""), p.statusJson());
      final TradingProcess.KillResult kill = p.kill(9001);
      assertTrue(kill.confirmed(), String.valueOf(kill.reason()));
      assertNull(p.failure());
    }
    for (final String venue : new String[] {"d2c", "clob", "clob2", "fut"}) {
      assertTrue(Files.isDirectory(journals.resolve("venues").resolve(venue)), venue + " keeps a store under the journals");
      assertTrue(Files.exists(journals.resolve("fix").resolve(venue + ".seq")), venue + " session keeps its sequence numbers");
    }

    // the recorded desk cannot be started on journals the v2 desk wrote
    err.reset();
    assertNull(start(blue, journals));
    final String refused = err.toString(StandardCharsets.UTF_8);
    assertTrue(refused.contains("restart refused") && refused.contains("were written by the desk selection [market=simV2"),
        refused);
    assertTrue(Files.exists(selection), "a refused start leaves the record alone");

    // the same selection restarts on them: rebuilt by replay, the kill still pulled
    try (TradingProcessMain.Started s = start(v2, journals)) {
      assertNotNull(s, err.toString(StandardCharsets.UTF_8));
      assertTrue(s.process().recovered());
      assertTrue(s.process().statusJson().contains("\"killSwitch\":true"), s.process().statusJson());
      assertNull(s.process().failure());
    }

    // replay mode reads the selection and builds the v2 desk
    final ByteArrayOutputStream replayed = new ByteArrayOutputStream();
    assertEquals(0, TradingProcessMain.replay(journals, new PrintStream(replayed, true, StandardCharsets.UTF_8)));
    final String text = replayed.toString(StandardCharsets.UTF_8);
    assertTrue(text.contains("desk selection: market=simV2 pricing=v2 strategy=v2 router=ladder") && text.contains("match")
        && !text.contains("MISMATCH"), text);

    // a record that cannot be read refuses both the replay and the restart
    Files.writeString(selection, "market=moon\n");
    assertEquals(1, TradingProcessMain.replay(journals, new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8)));
    assertTrue(TradingProcessMain.selectionProblem(journals, ProcessConfig.DeskSelection.V2).contains("is not readable"));
  }

  @Test
  void theSelectionMapsToAProfileOneComponentAtATime() {
    assertTrue(TradingProcessMain.profile(ProcessConfig.DeskSelection.DEFAULT).isRecorded());
    final DeskProfile v2 = TradingProcessMain.profile(ProcessConfig.DeskSelection.V2);
    assertEquals(DeskProfile.v2().market(), v2.market());
    assertEquals(DeskProfile.v2().pricing(), v2.pricing());
    assertEquals(DeskProfile.v2().router(), v2.router());
    assertEquals(DeskProfile.v2().settings(), v2.settings());
    final DeskProfile pricingOnly =
        TradingProcessMain.profile(new ProcessConfig.DeskSelection("default", "standard", "v2", "v1", "touch"));
    assertEquals(PricingBindings.Version.V2, pricingOnly.pricing());
    assertEquals(DeskProfile.Market.STANDARD, pricingOnly.market());
    assertEquals(DeskProfile.RouterModel.TOUCH, pricingOnly.router());
    assertEquals(DeskProfile.defaults().settings(), pricingOnly.settings());
    final DeskProfile routerOnly =
        TradingProcessMain.profile(new ProcessConfig.DeskSelection("default", "standard", "v1", "v1", "ladder"));
    assertEquals(DeskProfile.RouterModel.LADDER, routerOnly.router());
    assertFalse(routerOnly.isRecorded());
  }

  @Test
  void journalsTakeOnlyTheSelectionThatWroteThem() throws Exception {
    final Path fresh = Files.createDirectories(dir.resolve("fresh"));
    final Path file = fresh.resolve(TradingProcessMain.SELECTION_FILE);
    // an empty directory takes any selection and records one that is not the recorded desk
    assertNull(TradingProcessMain.selectionProblem(fresh, ProcessConfig.DeskSelection.V2));
    assertEquals(ProcessConfig.DeskSelection.V2.components(), TradingProcessMain.recordedSelection(fresh).components());
    // still no journals: the recorded desk may take the directory over, and the record goes
    assertNull(TradingProcessMain.selectionProblem(fresh, ProcessConfig.DeskSelection.DEFAULT));
    assertFalse(Files.exists(file));
    assertTrue(TradingProcessMain.recordedSelection(fresh).isRecorded());

    // journals without a record were written by the recorded desk
    final Path old = Files.createDirectories(dir.resolve("old").resolve("usd")).getParent();
    Files.write(old.resolve("usd").resolve("in.000001.raj"), new byte[8]);
    assertNull(TradingProcessMain.selectionProblem(old, ProcessConfig.DeskSelection.DEFAULT));
    final String problem = TradingProcessMain.selectionProblem(old, ProcessConfig.DeskSelection.V2);
    assertTrue(problem.contains("[market=standard pricing=v1 strategy=v1 router=touch]")
        && problem.contains("[market=simV2 pricing=v2 strategy=v2 router=ladder]"), problem);
    assertFalse(Files.exists(old.resolve(TradingProcessMain.SELECTION_FILE)), "a refusal writes nothing");
    assertThrows(IllegalArgumentException.class, () -> ProcessConfig.DeskSelection.parse("market=simV2"));
    assertThrows(IllegalArgumentException.class, () -> ProcessConfig.DeskSelection.profile("v3"));
  }
}
