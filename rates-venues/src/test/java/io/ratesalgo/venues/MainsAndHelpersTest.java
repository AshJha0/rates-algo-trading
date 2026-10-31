package io.ratesalgo.venues;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.venues.sim.Fix;
import io.ratesalgo.venues.sim.Json;
import io.ratesalgo.venues.sim.Px;
import io.ratesalgo.venues.sim.VenueHarness;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MainsAndHelpersTest {

  @Test
  void standaloneProcessesStartAndStop() throws Exception {
    for (final String kind : new String[] {"d2c", "clob", "sef"}) {
      final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      try (PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
        VenueMain.run(new String[] {"--venue", kind, "--port", Integer.toString(VenueHarness.freePort()), "--duration-millis", "200"}, out);
      }
      final String text = bytes.toString(StandardCharsets.UTF_8);
      assertTrue(text.contains("listening") && text.contains("stopped"), text);
    }
    try (DatagramSocket socket = new DatagramSocket(0)) {
      VenueMain.run(new String[] {"--venue", "clob", "--port", Integer.toString(VenueHarness.freePort()),
          "--duration-millis", "100", "--feed-port", Integer.toString(socket.getLocalPort())}, new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
      final io.ratesalgo.venues.feed.UdpFeedChannel udp = new io.ratesalgo.venues.feed.UdpFeedChannel("127.0.0.1", socket.getLocalPort());
      udp.publish(new org.agrona.concurrent.UnsafeBuffer(new byte[] {1, 2, 3}), 0, 3);
      udp.close();
      socket.setSoTimeout(2000);
      final DatagramPacket p = new DatagramPacket(new byte[16], 16);
      socket.receive(p);
      assertEquals(3, p.getLength());
    }
    assertThrows(IllegalArgumentException.class, () -> VenueMain.run(new String[] {"venue", "d2c"}, System.out));
  }

  @Test
  void jsonAndDecimalHelpers() {
    final Object doc = Json.parse(" {\"a\": [1, 2.5, \"x\\n\\u0041\\\"\"], \"b\": {\"c\": true, \"d\": false, \"e\": null}, \"f\": \"7\"} ");
    assertEquals(3, Json.list(doc, "a").size());
    assertEquals("x\nA\"", Json.list(doc, "a").get(2));
    assertEquals(true, Json.obj(doc, "b").get("c"));
    assertEquals(7, Json.num(doc, "f").intValue());
    assertEquals("7", Json.str(doc, "f"));
    assertEquals(List.of(), Json.parse("[]"));
    assertEquals(Map.of(), Json.parse("{}"));
    for (final String bad : new String[] {"{", "[1,", "{\"a\" 1}", "{1:2}", "@", "[1] x", "\"\\u12\""}) {
      assertThrows(IllegalArgumentException.class, () -> Json.parse(bad), bad);
    }
    assertThrows(IllegalArgumentException.class, () -> Json.obj(doc, "a"));
    assertThrows(IllegalArgumentException.class, () -> Json.list(doc, "b"));
    assertThrows(IllegalArgumentException.class, () -> Json.num(doc, "b"));
    assertThrows(IllegalArgumentException.class, () -> Json.str(doc, "zz"));
    assertEquals(99_515_625_000L, Px.price("99.515625"));
    assertEquals("99.515625", Px.text(99_515_625_000L));
    assertEquals("100", Px.text(100_000_000_000L));
    assertEquals("-0.5", Px.text(-500_000_000L));
    assertThrows(ArithmeticException.class, () -> Px.price("1.0000000001"));
    final quickfix.Message m = Fix.message("D");
    assertEquals("D", Fix.type(m));
    assertThrows(IllegalArgumentException.class, () -> Fix.req(m, 11));
    assertEquals(null, Fix.group(m, 146, 55));
    assertEquals(null, Fix.bodyOrGroup(m, 48, 146, 55));
  }
}
