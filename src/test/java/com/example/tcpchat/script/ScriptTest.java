package com.example.tcpchat.script;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.example.tcpchat.net.ConnectionStatus;
import com.example.tcpchat.net.TcpClient;
import com.example.tcpchat.net.TcpEvents;
import com.example.tcpchat.net.TcpServer;
import org.junit.jupiter.api.Test;

class ScriptTest {

    // ---- parser ----

    @Test
    void parsesAllStepKinds() throws Exception {
        Script s = ScriptParser.parse("""
                # comment
                encoding ASCII
                timeout 2s
                client send "A\\r\\n\\x01"   # trailing comment
                client expect "B"
                pause 500ms
                server expect contains "x"
                server expect /^C\\d+$/ timeout 1m
                server expect hex 41 0x42
                server expect any
                server send hex 02 03
                server close
                """);
        assertEquals(StandardCharsets.US_ASCII, s.charset());
        assertEquals(9, s.steps().size());
        Step.Send send = (Step.Send) s.steps().get(0);
        assertArrayEquals(new byte[] {'A', '\r', '\n', 1}, send.payload());
        assertEquals(java.time.Duration.ofSeconds(2), ((Step.Expect) s.steps().get(1)).timeout());
        assertEquals(java.time.Duration.ofMinutes(1), ((Step.Expect) s.steps().get(4)).timeout());
    }

    @Test
    void rawHexEscapeIsNotReEncoded() throws Exception {
        Script s = ScriptParser.parse("client send \"\\x80\\xFF\"");
        assertArrayEquals(new byte[] {(byte) 0x80, (byte) 0xFF}, ((Step.Send) s.steps().get(0)).payload());
    }

    @Test
    void reportsErrorsWithLineNumbers() {
        assertEquals(2, assertThrows(ScriptException.class, () -> ScriptParser.parse("\nfoo bar")).line());
        assertEquals(1, assertThrows(ScriptException.class, () -> ScriptParser.parse("client send \"open")).line());
        assertEquals(1, assertThrows(ScriptException.class, () -> ScriptParser.parse("pause 2")).line());
        assertEquals(1, assertThrows(ScriptException.class, () -> ScriptParser.parse("client expect /(/")).line());
        assertEquals(1, assertThrows(ScriptException.class, () -> ScriptParser.parse("client send hex 4G")).line());
    }

    // ---- matchers / buffer ----

    @Test
    void exactMatcherConsumesOnlyThePrefixAndToleratesSplits() throws Exception {
        ReceiveBuffer b = new ReceiveBuffer();
        b.append("BC".getBytes(StandardCharsets.UTF_8));
        assertArrayEquals("B".getBytes(StandardCharsets.UTF_8), b.await(ByteMatcher.exact("B".getBytes()), 100));
        assertArrayEquals("C".getBytes(StandardCharsets.UTF_8), b.await(ByteMatcher.exact("C".getBytes()), 100));

        b.append("HE".getBytes());
        CompletableFuture<byte[]> f = CompletableFuture.supplyAsync(() -> {
            try {
                return b.await(ByteMatcher.exact("HELLO".getBytes()), 2000);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        b.append("LLO".getBytes());
        assertArrayEquals("HELLO".getBytes(), f.get(3, TimeUnit.SECONDS));
    }

    @Test
    void regexConsumesOnlyTheMatchedMessage() throws Exception {
        ByteMatcher re = ByteMatcher.regex(java.util.regex.Pattern.compile("LVS1DD..\\?\\?OK\\d{3}001"), StandardCharsets.UTF_8);
        ReceiveBuffer b = new ReceiveBuffer();
        b.append("LVS1DD63??OK080001LVS1DD64??OK010001".getBytes(StandardCharsets.UTF_8));
        assertArrayEquals("LVS1DD63??OK080001".getBytes(), b.await(re, 100));
        assertArrayEquals("LVS1DD64??OK010001".getBytes(), b.await(re, 100));

        // partial data keeps waiting, a wrong start fails
        assertEquals(ByteMatcher.NEED_MORE, re.match("LVS1DD6".getBytes(), 7));
        assertEquals(ByteMatcher.MISMATCH, re.match("XVS1".getBytes(), 4));
    }

    @Test
    void mismatchAndTimeoutFail() {
        ReceiveBuffer b = new ReceiveBuffer();
        b.append("XYZ".getBytes());
        ScriptFailure mismatch = assertThrows(ScriptFailure.class, () -> b.await(ByteMatcher.exact("B".getBytes()), 100));
        assertTrue(mismatch.getMessage().contains("Mismatch"));
        ReceiveBuffer empty = new ReceiveBuffer();
        ScriptFailure timeout = assertThrows(ScriptFailure.class, () -> empty.await(ByteMatcher.exact("B".getBytes()), 50));
        assertTrue(timeout.getMessage().contains("Timeout"));
    }

    // ---- end to end over real sockets ----

    /** Plays "server 1" and "client 2" by hand against the app's client and server channels. */
    @Test
    void runsTheTwoChannelProtocolFromTheRequirements() throws Exception {
        AtomicReference<ScriptRunner> runnerRef = new AtomicReference<>();
        TcpEvents clientEvents = tap(Channel.CLIENT, runnerRef);
        TcpEvents serverEvents = tap(Channel.SERVER, runnerRef);
        TcpClient client = new TcpClient(clientEvents);
        TcpServer server = new TcpServer(serverEvents);
        CountDownLatch connected = new CountDownLatch(2);
        TcpEvents.class.getName(); // keep import used
        try (ServerSocket server1 = new ServerSocket(0)) {
            server.start(0);
            client.connect("127.0.0.1", server1.getLocalPort());
            try (Socket peerOfClient = server1.accept();
                 Socket client2 = new Socket("127.0.0.1", server.localPort())) {
                Thread.sleep(300); // let both sides register the connection

                Script script = ScriptParser.parse("""
                        timeout 5s
                        client send "A"
                        client expect "B"
                        pause 200ms
                        server expect "C"
                        server send "D"
                        """);
                List<String> events = new ArrayList<>();
                CompletableFuture<Boolean> done = new CompletableFuture<>();
                ScriptRunner runner = new ScriptRunner(script,
                        Map.of(Channel.CLIENT, adapter(client::send, client::disconnect),
                                Channel.SERVER, adapter(server::send, server::disconnectClient)),
                        new ScriptRunner.Listener() {
                            public void stepStarted(Step s, int i, int t) { }
                            public void stepPassed(Step s) { events.add("ok " + s.lineNo()); }
                            public void stepFailed(Step s, String m) { events.add("fail " + m); }
                            public void finished(boolean p, String sum) { done.complete(p); }
                        });
                runnerRef.set(runner);
                runner.start();

                // server 1 receives A, answers B
                assertArrayEquals("A".getBytes(), read(peerOfClient.getInputStream(), 1));
                peerOfClient.getOutputStream().write("B".getBytes());
                // client 2 sends C, receives D
                client2.getOutputStream().write("C".getBytes());
                assertArrayEquals("D".getBytes(), read(client2.getInputStream(), 1));

                assertTrue(done.get(5, TimeUnit.SECONDS), events.toString());
                assertEquals(5, events.size());
            }
        } finally {
            client.shutdown();
            server.shutdown();
        }
    }

    private interface Send {
        void send(byte[] d) throws IOException;
    }

    private static ScriptChannel adapter(Send send, Runnable close) {
        return new ScriptChannel() {
            public void send(byte[] d) throws IOException { send.send(d); }
            public void closeChannel() { close.run(); }
        };
    }

    private static TcpEvents tap(Channel ch, AtomicReference<ScriptRunner> ref) {
        return new TcpEvents() {
            public void statusChanged(ConnectionStatus s, String m) { }
            public void dataReceived(byte[] d) {
                ScriptRunner r = ref.get();
                if (r != null) r.onData(ch, d);
            }
        };
    }

    private static byte[] read(InputStream in, int n) throws IOException {
        return in.readNBytes(n);
    }
}
