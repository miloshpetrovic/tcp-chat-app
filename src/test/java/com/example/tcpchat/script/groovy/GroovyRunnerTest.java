package com.example.tcpchat.script.groovy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

import com.example.tcpchat.script.Channel;
import com.example.tcpchat.script.ScriptChannel;
import com.example.tcpchat.script.ScriptException;
import org.junit.jupiter.api.Test;

class GroovyRunnerTest {

    /** Fake channels: records what the script sends and lets the test play the remote peers. */
    static final class Harness {
        final List<String> clientSent = new CopyOnWriteArrayList<>();
        final List<String> serverSent = new CopyOnWriteArrayList<>();
        final List<String> logs = new CopyOnWriteArrayList<>();
        final CompletableFuture<Boolean> passed = new CompletableFuture<>();
        volatile String summary;
        final AtomicReference<GroovyRunner> runner = new AtomicReference<>();
        /** called after every send: (channel, text) */
        volatile BiConsumer<Channel, String> peer = (c, s) -> { };

        GroovyRunner create(String source) throws ScriptException {
            GroovyRunner r = GroovyRunner.create(source, "test.groovy",
                    Map.of(Channel.CLIENT, channel(Channel.CLIENT, clientSent),
                            Channel.SERVER, channel(Channel.SERVER, serverSent)),
                    new GroovyRunner.Listener() {
                        public void log(String line) { logs.add(line); }

                        public void finished(boolean p, String s) {
                            summary = s;
                            passed.complete(p);
                        }
                    });
            runner.set(r);
            return r;
        }

        private ScriptChannel channel(Channel id, List<String> sent) {
            return new ScriptChannel() {
                public void send(byte[] data) {
                    String s = new String(data, StandardCharsets.UTF_8);
                    sent.add(s);
                    peer.accept(id, s);
                }

                public void closeChannel() {
                    sent.add("<close>");
                }
            };
        }

        void feed(Channel c, String text) {
            runner.get().onData(c, text.getBytes(StandardCharsets.UTF_8));
        }

        boolean run(String source) throws Exception {
            create(source).start();
            return passed.get(10, TimeUnit.SECONDS);
        }

        String logText() {
            return String.join("\n", logs);
        }
    }

    @Test
    void sendExpectPauseAndMsg() throws Exception {
        Harness h = new Harness();
        h.peer = (c, s) -> { if (c == Channel.CLIENT && s.equals("A")) h.feed(Channel.CLIENT, "B"); };
        assertTrue(h.run("""
                client.send "A"
                def m = client.expect "B"
                assert m.text == "B"
                pause 10.ms
                log "done"
                """), h.summary + h.logText());
        assertEquals(List.of("A"), h.clientSent);
    }

    @Test
    void durationSyntaxWorksOnEveryThread() throws Exception {
        Harness h = new Harness();
        assertTrue(h.run("""
                assert 2.s.toMillis() == 2000
                assert 500.ms.toMillis() == 500
                assert 1.m.toMillis() == 60000
                spawn { assert 1.5.s.toMillis() == 1500 }
                """), h.summary + h.logText());
    }

    @Test
    void regexGroupsHexAndContains() throws Exception {
        Harness h = new Harness();
        h.create("""
                def m = client.expect(~/ID(\\d+)/)
                assert m[0] == "ID42" && m[1] == "42"
                client.expect hex("01 02")
                def c = client.expect contains("OK")
                assert c.text == "xxOK"
                client.send hex("02 41 03")
                client.send([65, 66])
                """).start();
        h.feed(Channel.CLIENT, "ID42");
        h.runner.get().onData(Channel.CLIENT, new byte[] {1, 2});
        h.feed(Channel.CLIENT, "xxOK");
        assertTrue(h.passed.get(10, TimeUnit.SECONDS), h.summary + h.logText());
        assertEquals(2, h.clientSent.size());
        assertEquals("\u0002A\u0003", h.clientSent.get(0));
        assertEquals("AB", h.clientSent.get(1));
    }

    @Test
    void workersRunInParallel() throws Exception {
        Harness h = new Harness();
        h.peer = (c, s) -> { if (c == Channel.CLIENT && s.equals("A")) h.feed(Channel.CLIENT, "B"); };
        h.create("""
                def c = worker("c") { client.send "A"; client.expect "B" }
                def s = worker("s") { server.expect "C"; server.send "D" }
                parallel(c, s)
                """).start();
        Thread.sleep(150);
        h.feed(Channel.SERVER, "C");
        assertTrue(h.passed.get(10, TimeUnit.SECONDS), h.summary + h.logText());
        assertEquals(List.of("A"), h.clientSent);
        assertEquals(List.of("D"), h.serverSent);
    }

    @Test
    void workersExchangeValuesThroughMailboxes() throws Exception {
        Harness h = new Harness();
        assertTrue(h.run("""
                def box = mailbox("m")
                def a = spawn("a") { box.put("hello") }
                def b = spawn("b") { assert box.take() == "hello" }
                parallel(a, b)
                """), h.summary + h.logText());
    }

    @Test
    void workersSharingAChannelTakeTurns() throws Exception {
        Harness h = new Harness();
        h.create("""
                def got = Collections.synchronizedList([])
                def w1 = spawn("w1") { got << client.expect("A").text }
                def w2 = spawn("w2") { got << client.expect("A").text }
                parallel(w1, w2)
                assert got == ["A", "A"]
                """).start();
        Thread.sleep(100);
        h.feed(Channel.CLIENT, "AA"); // one read each, nothing is split or lost
        assertTrue(h.passed.get(10, TimeUnit.SECONDS), h.summary + h.logText());
    }

    @Test
    void onMessageHandlersReact() throws Exception {
        Harness h = new Harness();
        GroovyRunner r = h.create("""
                server.onMessage("PING") { m -> server.send "PONG" }
                client.onMessage(~/GET (\\w+);/) { m -> client.send "RES ${m[1]}" }
                runFor 600.ms
                """);
        h.feed(Channel.SERVER, "PINGPING");
        h.feed(Channel.CLIENT, "GET abc;");
        r.start();
        assertTrue(h.passed.get(10, TimeUnit.SECONDS), h.summary + h.logText());
        assertEquals(List.of("PONG", "PONG"), h.serverSent);
        assertEquals(List.of("RES abc"), h.clientSent);
    }

    @Test
    void handlersSeeDataThatArrivesLater() throws Exception {
        Harness h = new Harness();
        h.create("""
                server.onMessage("HELLO") { server.send "WORLD" }
                keepRunning()
                """).start();
        Thread.sleep(100);
        h.feed(Channel.SERVER, "HEL");
        Thread.sleep(100);
        h.feed(Channel.SERVER, "LO");
        long end = System.currentTimeMillis() + 5000;
        while (h.serverSent.isEmpty() && System.currentTimeMillis() < end) Thread.sleep(20);
        assertEquals(List.of("WORLD"), h.serverSent);
        h.runner.get().stop();
        assertTrue(h.passed.get(10, TimeUnit.SECONDS), "Stop during keepRunning is a normal end: " + h.summary);
    }

    @Test
    void unmatchedDataGoesToOnUnmatched() throws Exception {
        Harness h = new Harness();
        GroovyRunner r = h.create("""
                def seen = []
                server.onMessage("OK") { }
                server.onUnmatched { m -> seen << m.text }
                runFor 300.ms
                assert seen == ["garbage"]
                """);
        h.feed(Channel.SERVER, "garbage");
        r.start();
        assertTrue(h.passed.get(10, TimeUnit.SECONDS), h.summary + h.logText());
    }

    @Test
    void expectOnChannelWithHandlersFails() throws Exception {
        Harness h = new Harness();
        assertFalse(h.run("""
                server.onMessage("X") { }
                server.expect "Y"
                """));
        assertTrue(h.summary.contains("handlers"), h.summary);
    }

    @Test
    void timeoutFailureReportsLine() throws Exception {
        Harness h = new Harness();
        assertFalse(h.run("""
                timeout 100.ms
                client.send "A"
                client.expect "NEVER"
                """));
        assertTrue(h.summary.contains("line 3") && h.summary.contains("Timeout"), h.summary);
    }

    @Test
    void failedAssertInWorkerCancelsTheOthers() throws Exception {
        Harness h = new Harness();
        long start = System.currentTimeMillis();
        assertFalse(h.run("""
                def bad = worker("bad") { pause 50.ms; assert 1 == 2 }
                def slow = worker("slow") { pause 30.s }
                parallel(bad, slow)
                """));
        assertTrue(System.currentTimeMillis() - start < 5000, "the slow worker must be interrupted");
        assertTrue(h.summary.contains("bad") && h.summary.contains("1 == 2"), h.summary);
    }

    @Test
    void stopInterruptsABlockedExpect() throws Exception {
        Harness h = new Harness();
        GroovyRunner r = h.create("client.expect(\"X\", 30.s)");
        r.start();
        Thread.sleep(150);
        r.stop();
        assertFalse(h.passed.get(10, TimeUnit.SECONDS));
        assertEquals("Stopped by user", h.summary);
    }

    @Test
    void printlnAndLogGoToTheListener() throws Exception {
        Harness h = new Harness();
        assertTrue(h.run("println \"hello\"\nspawn('w') { log 'from worker' }"), h.summary + h.logText());
        assertTrue(h.logText().contains("hello"), h.logText());
        assertTrue(h.logText().contains("[w] from worker"), h.logText());
    }

    @Test
    void syntaxErrorsAreReportedWithTheLine() {
        ScriptException e = assertThrows(ScriptException.class, () -> GroovyRunner.check("def x = 1\ndef y = (\n", "t.groovy"));
        assertTrue(e.line() >= 2, e.getMessage());
    }
}
