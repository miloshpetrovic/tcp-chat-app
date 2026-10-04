package com.example.tcpchat.net;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * An established, full-duplex TCP connection, shared by the client and the server side.
 * Reading runs on its own daemon thread; writing happens on the caller's thread and is
 * independent of reading. Bytes are passed through untouched: no framing, no newlines.
 */
public final class TcpConnection implements Closeable {

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;
    private final Consumer<byte[]> onData;
    private final CompletableFuture<String> closed = new CompletableFuture<>();
    private final AtomicBoolean closing = new AtomicBoolean();
    private final Object writeLock = new Object();

    public TcpConnection(Socket socket, Consumer<byte[]> onData) throws IOException {
        this.socket = socket;
        this.in = socket.getInputStream();
        this.out = socket.getOutputStream();
        this.onData = onData;
    }

    public String remoteAddress() {
        return String.valueOf(socket.getRemoteSocketAddress());
    }

    public void start() {
        Thread reader = new Thread(this::readLoop, "tcp-reader-" + remoteAddress());
        reader.setDaemon(true);
        reader.start();
    }

    /** Sends exactly the given bytes. */
    public void send(byte[] data) throws IOException {
        if (closed.isDone()) {
            throw new IOException("Connection closed");
        }
        synchronized (writeLock) {
            out.write(data);
            out.flush();
        }
    }

    /** Completes (once) with the reason when the connection ends, whoever ended it. */
    public CompletableFuture<String> closed() {
        return closed;
    }

    @Override
    public void close() {
        finish("Closed locally");
    }

    private void readLoop() {
        byte[] buffer = new byte[8192];
        try {
            int n;
            while ((n = in.read(buffer)) != -1) {
                onData.accept(Arrays.copyOf(buffer, n));
            }
            finish("Connection closed by remote host");
        } catch (IOException e) {
            finish("Connection lost: " + e.getMessage());
        }
    }

    private void finish(String reason) {
        if (closing.compareAndSet(false, true)) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // nothing useful to do
            }
            closed.complete(reason);
        }
    }
}
