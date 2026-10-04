package com.example.tcpchat.net;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Connects to a remote TCP server. All blocking work runs off the caller's thread. */
public final class TcpClient {

    private static final int CONNECT_TIMEOUT_MS = 5000;

    private final TcpEvents events;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "tcp-client-connect");
        t.setDaemon(true);
        return t;
    });

    // guarded by this
    private Socket pending;          // socket currently being connected
    private TcpConnection connection;

    public TcpClient(TcpEvents events) {
        this.events = events;
    }

    public void connect(String host, int port) {
        Socket socket = new Socket();
        synchronized (this) {
            if (pending != null || connection != null) {
                events.statusChanged(ConnectionStatus.ERROR, "Already connected or connecting");
                return;
            }
            pending = socket;
        }
        events.statusChanged(ConnectionStatus.CONNECTING, "Connecting to " + host + ":" + port + "...");
        executor.execute(() -> doConnect(socket, host, port));
    }

    private void doConnect(Socket socket, String host, int port) {
        try {
            InetSocketAddress address = new InetSocketAddress(host, port);
            if (address.isUnresolved()) {
                throw new UnknownHostException(host);
            }
            socket.connect(address, CONNECT_TIMEOUT_MS);
            TcpConnection conn = new TcpConnection(socket, events::dataReceived);
            synchronized (this) {
                if (pending != socket) { // cancelled by disconnect()/shutdown() meanwhile
                    conn.close();
                    return;
                }
                pending = null;
                connection = conn;
            }
            conn.closed().thenAccept(reason -> onConnectionClosed(conn, reason));
            conn.start();
            events.statusChanged(ConnectionStatus.CONNECTED, "Connected to " + host + ":" + port);
        } catch (IOException e) {
            closeQuietly(socket);
            boolean cancelled;
            synchronized (this) {
                cancelled = pending != socket;
                if (!cancelled) pending = null;
            }
            if (!cancelled) {
                events.statusChanged(ConnectionStatus.ERROR, describe(e, host, port));
            }
        }
    }

    private void onConnectionClosed(TcpConnection conn, String reason) {
        synchronized (this) {
            if (connection != conn) return; // user-initiated disconnect already reported
            connection = null;
        }
        events.statusChanged(ConnectionStatus.DISCONNECTED, reason);
    }

    public void disconnect() {
        TcpConnection conn;
        boolean wasConnecting;
        synchronized (this) {
            conn = connection;
            connection = null;
            wasConnecting = pending != null;
            if (pending != null) {
                closeQuietly(pending);
                pending = null;
            }
        }
        if (conn != null) conn.close();
        if (conn != null || wasConnecting) {
            events.statusChanged(ConnectionStatus.DISCONNECTED, "Disconnected");
        }
    }

    public void send(byte[] data) throws IOException {
        TcpConnection conn;
        synchronized (this) {
            conn = connection;
        }
        if (conn == null) {
            throw new IOException("Not connected");
        }
        conn.send(data);
    }

    /** Releases the socket and the executor; the instance is unusable afterwards. */
    public void shutdown() {
        disconnect();
        executor.shutdownNow();
    }

    private static String describe(IOException e, String host, int port) {
        if (e instanceof UnknownHostException) return "Invalid host: " + host;
        if (e instanceof ConnectException) return "Connection refused (" + host + ":" + port + ")";
        if (e instanceof SocketTimeoutException) return "Unable to connect to host: timed out";
        if (e instanceof NoRouteToHostException) return "Unable to connect to host: no route";
        return "Unable to connect to host: " + e.getMessage();
    }

    private static void closeQuietly(Socket s) {
        try {
            s.close();
        } catch (IOException ignored) {
            // nothing useful to do
        }
    }
}
