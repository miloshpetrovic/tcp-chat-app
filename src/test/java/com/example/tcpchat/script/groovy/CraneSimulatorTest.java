package com.example.tcpchat.script.groovy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import com.example.tcpchat.script.Channel;
import com.example.tcpchat.script.groovy.GroovyRunnerTest.Harness;
import org.junit.jupiter.api.Test;

/** Runs examples/cran-simulator.groovy against a scripted "remote system". */
class CraneSimulatorTest {

    private static final String FILLER = "630000010116"; // seq 63, 00, 00010116 (copied from the DD)

    private static String dd(String crane, String palette) {
        return "LVS1UST1DD" + FILLER.substring(0, 2) + "00" + FILLER.substring(4) + "3005????" + crane + "18OK" + palette + "001";
    }

    private static String expected(String id, String who, String status, String palette) {
        return "UST1LVS1" + id + FILLER + "3005????" + who + "18" + status + palette + "001";
    }

    /** Harness whose remote system confirms every 16/17/18 with AF/AG/AH, like the real one. */
    private static Harness harness(boolean confirmDt) throws Exception {
        Harness h = new Harness();
        h.peer = (channel, sent) -> {
            if (channel != Channel.CLIENT || !sent.startsWith("UST1LVS1")) return;
            String id = sent.substring(8, 10);
            boolean dt = sent.contains("18DT");
            String type = switch (id) {
                case "16" -> "AF";
                case "17" -> "AG";
                case "18" -> "AH";
                default -> null;
            };
            if (type == null || (dt && !confirmDt)) return;
            h.feed(Channel.CLIENT, "LVS1UST1" + type + sent.substring(10));
        };
        String src = Files.readString(Path.of("examples", "cran-simulator.groovy"));
        src = src.replaceAll("def minPause\\s*=\\s*2\\.s", "def minPause = 20.ms")
                .replaceAll("def maxPause\\s*=\\s*5\\.s", "def maxPause = 60.ms")
                .replaceAll("def confirmTimeout\\s*=\\s*30\\.s", "def confirmTimeout = 3.s");
        h.create(src).start();
        Thread.sleep(150); // handlers settle
        return h;
    }

    private static void await(BooleanSupplier condition, String what) throws Exception {
        long end = System.currentTimeMillis() + 8000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > end) throw new AssertionError("timed out waiting for " + what);
            Thread.sleep(10);
        }
    }

    private static void stopAndExpectPassed(Harness h) throws Exception {
        h.runner.get().stop();
        assertTrue(h.passed.get(10, TimeUnit.SECONDS), h.summary + "\n" + h.logText());
    }

    @Test
    void twoCranesWorkInterleavedAndRefuseWhileBusy() throws Exception {
        Harness h = harness(true);
        // R001 gets 0100201, R002 gets 0800201, and a third DD for busy R001 arrives immediately
        h.feed(Channel.SERVER, dd("R001", "0100201") + dd("R002", "0800201") + dd("R001", "0100101"));

        await(() -> h.clientSent.contains(expected("18", "3005", "OK", "0100201"))
                && h.clientSent.contains(expected("18", "3005", "OK", "0800201")), "both palettes to finish");

        assertEquals(List.of(expected("44", "R001", "OK", "0100201"), expected("44", "R002", "OK", "0800201")),
                h.serverSent);
        // the refusal goes out on the client channel, before any 16 (those wait for the random pause)
        assertEquals(expected("17", "R001", "DT", "0100101"), h.clientSent.get(0));
        for (String[] p : new String[][] {{"R001", "5005", "0100201"}, {"R002", "7005", "0800201"}}) {
            int i16 = h.clientSent.indexOf(expected("16", p[1], "OK", p[2]));
            int i17 = h.clientSent.indexOf(expected("17", "3005", "OK", p[2]));
            int i18 = h.clientSent.indexOf(expected("18", "3005", "OK", p[2]));
            assertTrue(0 < i16 && i16 < i17 && i17 < i18, p[0] + " order " + h.clientSent);
        }
        assertFalse(h.logText().contains("WARNING"), "the DT confirmation must be consumed quietly:\n" + h.logText());
        stopAndExpectPassed(h);
    }

    @Test
    void craneAcceptsNextDdAsSoonAsItSent16WhileThePreviousPalettFinishes() throws Exception {
        Harness h = harness(true);
        h.feed(Channel.SERVER, dd("R001", "0100201"));
        await(() -> h.clientSent.contains(expected("16", "5005", "OK", "0100201")), "16 of the first palette");

        h.feed(Channel.SERVER, dd("R001", "0100101")); // crane is free again: accepted, not refused
        await(() -> h.clientSent.contains(expected("18", "3005", "OK", "0100101"))
                && h.clientSent.contains(expected("18", "3005", "OK", "0100201")), "both palettes to finish");

        assertEquals(List.of(expected("44", "R001", "OK", "0100201"), expected("44", "R001", "OK", "0100101")),
                h.serverSent);
        assertFalse(h.clientSent.stream().anyMatch(s -> s.contains("18DT")), h.clientSent.toString());
        stopAndExpectPassed(h);
    }

    @Test
    void repeatedPaletteCodeInProgressIsRefusedEvenForAFreeCrane() throws Exception {
        Harness h = harness(true);
        h.feed(Channel.SERVER, dd("R001", "0100201") + dd("R002", "0100201"));
        await(() -> h.clientSent.contains(expected("17", "R002", "DT", "0100201")), "the refusal");
        assertEquals(List.of(expected("44", "R001", "OK", "0100201")), h.serverSent);
        stopAndExpectPassed(h);
    }

    @Test
    void unknownCraneFailsTheRun() throws Exception {
        Harness h = harness(true);
        h.feed(Channel.SERVER, dd("R009", "0100201"));
        assertFalse(h.passed.get(10, TimeUnit.SECONDS));
        assertTrue(h.summary.contains("unknown crane 'R009'"), h.summary);
    }

    @Test
    void unexpectedInputIsOnlyAWarning() throws Exception {
        Harness h = harness(true);
        h.feed(Channel.CLIENT, "LVS1UST1AF" + FILLER + "3005????5005" + "18OK9999999001"); // nobody waits for it
        h.feed(Channel.SERVER, "garbage that is no message at all");
        await(() -> h.logText().contains("unexpected data on the server channel")
                && h.logText().contains("unexpected AF for palette 9999999"), "the warnings");
        assertTrue(h.logText().contains("unexpected AF for palette 9999999"), h.summary + " / " + h.logText());
        // and the simulator still works
        h.feed(Channel.SERVER, dd("R002", "0800101"));
        await(() -> h.clientSent.contains(expected("18", "3005", "OK", "0800101")), "a normal palette");
        stopAndExpectPassed(h);
    }

    @Test
    void missingConfirmationFailsTheRun() throws Exception {
        Harness h = new Harness(); // a remote system that never confirms anything
        String src = Files.readString(Path.of("examples", "cran-simulator.groovy"))
                .replaceAll("def minPause\\s*=\\s*2\\.s", "def minPause = 10.ms")
                .replaceAll("def maxPause\\s*=\\s*5\\.s", "def maxPause = 20.ms")
                .replaceAll("def confirmTimeout\\s*=\\s*30\\.s", "def confirmTimeout = 300.ms");
        h.create(src).start();
        Thread.sleep(150);
        h.feed(Channel.SERVER, dd("R001", "0100201"));
        assertFalse(h.passed.get(10, TimeUnit.SECONDS));
        assertTrue(h.summary.contains("R001/0100201") && h.summary.contains("AF"), h.summary);
    }
}
