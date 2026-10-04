package com.example.tcpchat.script;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/**
 * Unconsumed bytes received on one channel. TCP chunk boundaries are irrelevant: an expectation
 * consumes only the bytes it matched and leaves the rest for the next one.
 */
final class ReceiveBuffer {
    private byte[] data = new byte[256];
    private int len;
    private boolean closed;
    private String closeReason = "";

    synchronized void append(byte[] chunk) {
        if (len + chunk.length > data.length) {
            data = Arrays.copyOf(data, Math.max(data.length * 2, len + chunk.length));
        }
        System.arraycopy(chunk, 0, data, len, chunk.length);
        len += chunk.length;
        notifyAll();
    }

    synchronized void close(String reason) {
        closed = true;
        closeReason = reason;
        notifyAll();
    }

    synchronized byte[] await(ByteMatcher matcher, long timeoutMs) throws ScriptFailure, InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (true) {
            int r = matcher.match(data, len);
            if (r >= 0) {
                byte[] consumed = Arrays.copyOf(data, r);
                System.arraycopy(data, r, data, 0, len - r);
                len -= r;
                return consumed;
            }
            if (r == ByteMatcher.MISMATCH) {
                throw new ScriptFailure("Mismatch: expected " + matcher.describe() + ", received " + Bytes.escape(data, len));
            }
            if (closed) {
                throw new ScriptFailure("Channel closed (" + closeReason + ") while waiting for " + matcher.describe()
                        + "; received so far " + Bytes.escape(data, len));
            }
            long remainingMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
            if (remainingMs <= 0) {
                throw new ScriptFailure("Timeout after " + timeoutMs + " ms waiting for " + matcher.describe()
                        + "; received so far " + Bytes.escape(data, len));
            }
            wait(remainingMs);
        }
    }
}
