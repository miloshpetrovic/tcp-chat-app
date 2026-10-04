package com.example.tcpchat.net;

import java.io.IOException;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Listens on a local port and serves ONE client at a time. To support several clients later,
 * keep the accept loop but track a collection of TcpConnection instead of {@code current}
 * and stop awaiting {@code closed()} inside the loop.
 */
public final class TcpServer {

    private final TcpEvents events;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "tcp-server-accept");
        t.setDaemon(true);
        return t;
    });

    // guarded by this
    private ServerSocket serverSocket;
    private TcpConnection current;

    public TcpServer(TcpEvents events) {
        this.events = events;
    }

    /** Binds (instant, never waits on the network) and accepts on a background thread. Port 0 = any free port. */
    public void start(int port) {
        ServerSocket ss;
        synchronized (this) {
            if (serverSocket != null) {
                events.statusChanged(ConnectionStatus.ERROR, "Server already running");
                return;
            }
            events.statusChanged(ConnectionStatus.STARTING, "Starting...");
            try {
                ss = new ServerSocket();
                try {
                    ss.bind(new InetSocketAddress(port));
                } catch (IOException e) {
                    ss.close();
                    throw e;
                }
            } catch (IOException e) {
                String msg = e instanceof BindException
                        ? "Port already in use (or not permitted): " + port
                        : "Unable to start server: " + e.getMessage();
                events.statusChanged(ConnectionStatus.ERROR, msg);
                return;
            }
            serverSocket = ss;
        }
        events.statusChanged(ConnectionStatus.LISTENING, "Listening on port " + ss.getLocalPort());
        executor.execute(() -> acceptLoop(ss));
    }

    public synchronized int localPort() {
        return serverSocket == null ? -1 : serverSocket.getLocalPort();
    }

    private void acceptLoop(ServerSocket ss) {
        while (isActive(ss)) {
            try {
                Socket socket = ss.accept();
                TcpConnection conn = new TcpConnection(socket, events::dataReceived);
                synchronized (this) {
                    if (serverSocket != ss) { // stopped while accepting
                        conn.close();
                        return;
                    }
                    current = conn;
                }
                events.statusChanged(ConnectionStatus.CLIENT_CONNECTED,
                        "Client connected: " + conn.remoteAddress());
                conn.start();
                String reason = conn.closed().get(); // one client at a time
                synchronized (this) {
                    if (current == conn) current = null;
                }
                if (isActive(ss)) {
                    events.statusChanged(ConnectionStatus.LISTENING,
                            "Client disconnected (" + reason + "). Listening on port " + ss.getLocalPort());
                }
            } catch (InterruptedException e) {
                return;
            } catch (IOException e) {
                if (isActive(ss)) {
                    stopInternal(ss);
                    events.statusChanged(ConnectionStatus.ERROR, "Server error: " + e.getMessage());
                }
                return;
            } catch (Exception e) {
                return;
            }
        }
    }

    private synchronized boolean isActive(ServerSocket ss) {
        return serverSocket == ss;
    }

    public void stop() {
        if (stopInternal(null)) {
            events.statusChanged(ConnectionStatus.STOPPED, "Server stopped");
        }
    }

    /** @param expected only stop if this is still the active socket (null = whatever is active) */
    private boolean stopInternal(ServerSocket expected) {
        ServerSocket ss;
        TcpConnection conn;
        synchronized (this) {
            if (serverSocket == null || (expected != null && serverSocket != expected)) return false;
            ss = serverSocket;
            conn = current;
            serverSocket = null;
            current = null;
        }
        try {
            ss.close();
        } catch (IOException ignored) {
            // nothing useful to do
        }
        if (conn != null) conn.close();
        return true;
    }

    public void send(byte[] data) throws IOException {
        TcpConnection conn;
        synchronized (this) {
            conn = current;
        }
        if (conn == null) {
            throw new IOException("No client connected");
        }
        conn.send(data);
    }

    /** Drops the connected client (if any) but keeps listening. */
    public void disconnectClient() {
        TcpConnection conn;
        synchronized (this) {
            conn = current;
        }
        if (conn != null) conn.close();
    }

    /** Releases the sockets and the executor; the instance is unusable afterwards. */
    public void shutdown() {
        stop();
        executor.shutdownNow();
    }
}
