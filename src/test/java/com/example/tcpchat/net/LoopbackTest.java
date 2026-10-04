package com.example.tcpchat.net;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

class LoopbackTest {

    /** Collects events so tests can wait for them. */
    static final class Recorder implements TcpEvents {
        final BlockingQueue<ConnectionStatus> statuses = new LinkedBlockingQueue<>();
        final ByteArrayOutputStream received = new ByteArrayOutputStream();

        @Override
        public void statusChanged(ConnectionStatus status, String message) {
            statuses.add(status);
        }

        @Override
        public synchronized void dataReceived(byte[] data) {
            received.writeBytes(data);
        }

        void await(ConnectionStatus expected) throws InterruptedException {
            while (true) {
                ConnectionStatus s = statuses.poll(5, TimeUnit.SECONDS);
                assertTrue(s != null, "timed out waiting for " + expected);
                if (s == expected) return;
            }
        }

        synchronized byte[] awaitBytes(int count) throws InterruptedException {
            long deadline = System.currentTimeMillis() + 5000;
            while (received.size() < count && System.currentTimeMillis() < deadline) {
                wait(20);
            }
            return received.toByteArray();
        }
    }

    @Test
    void clientAndServerExchangeExactBytesBothWays() throws Exception {
        Recorder serverEvents = new Recorder();
        Recorder clientEvents = new Recorder();
        TcpServer server = new TcpServer(serverEvents);
        TcpClient client = new TcpClient(clientEvents);
        try {
            server.start(0);
            serverEvents.await(ConnectionStatus.LISTENING);
            client.connect("localhost", server.localPort());
            clientEvents.await(ConnectionStatus.CONNECTED);
            serverEvents.await(ConnectionStatus.CLIENT_CONNECTED);

            byte[] hello = "  Hello from client".getBytes(StandardCharsets.UTF_8);
            client.send(hello);
            assertArrayEquals(hello, pollUntilLength(serverEvents, hello.length));

            byte[] reply = "Hello from server".getBytes(StandardCharsets.UTF_8);
            server.send(reply);
            assertArrayEquals(reply, pollUntilLength(clientEvents, reply.length));

            client.disconnect();
            serverEvents.await(ConnectionStatus.LISTENING); // server goes back to listening
        } finally {
            client.shutdown();
            server.shutdown();
        }
    }

    @Test
    void sendDoesNotAppendNewline() throws Exception {
        try (ServerSocket raw = new ServerSocket(0)) {
            TcpClient client = new TcpClient(new Recorder());
            try {
                client.connect("127.0.0.1", raw.getLocalPort());
                try (Socket peer = raw.accept()) {
                    client.send("Hello server".getBytes(StandardCharsets.UTF_8));
                    client.disconnect(); // peer then sees EOF, so we can read everything that was sent
                    assertEquals("Hello server", new String(peer.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
                }
            } finally {
                client.shutdown();
            }
        }
    }

    @Test
    void connectionRefusedIsReportedAsError() throws Exception {
        int freePort;
        try (ServerSocket s = new ServerSocket(0)) {
            freePort = s.getLocalPort();
        }
        Recorder events = new Recorder();
        TcpClient client = new TcpClient(events);
        try {
            client.connect("127.0.0.1", freePort);
            events.await(ConnectionStatus.ERROR);
            assertThrows(IOException.class, () -> client.send(new byte[] {1}));
        } finally {
            client.shutdown();
        }
    }

    @Test
    void portInUseIsReportedAsError() throws Exception {
        try (ServerSocket taken = new ServerSocket(0)) {
            Recorder events = new Recorder();
            TcpServer server = new TcpServer(events);
            server.start(taken.getLocalPort());
            events.await(ConnectionStatus.ERROR);
            server.shutdown();
        }
    }

    private static byte[] pollUntilLength(Recorder r, int n) throws InterruptedException {
        return r.awaitBytes(n);
    }
}
