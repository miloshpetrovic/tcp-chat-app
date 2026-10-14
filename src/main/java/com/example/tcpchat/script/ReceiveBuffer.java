package com.example.tcpchat.script;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Unconsumed bytes received on one channel. TCP chunk boundaries are irrelevant: an expectation
 * consumes only the bytes it matched and leaves the rest for the next one.
 */
public final class ReceiveBuffer {
    /** Results of {@link #tryMatchAny} besides a matcher index (>= 0). */
    public static final int NEED_MORE = -1;
    public static final int ALL_MISMATCH = -2;
    public static final int EMPTY = -3;

    private byte[] data = new byte[256];
    private int len;
    private boolean closed;
    private String closeReason = "";
    private long version; // bumped on every append/close so waiters can detect changes

    public synchronized void append(byte[] chunk) {
        if (len + chunk.length > data.length) {
            data = Arrays.copyOf(data, Math.max(data.length * 2, len + chunk.length));
        }
        System.arraycopy(chunk, 0, data, len, chunk.length);
        len += chunk.length;
        version++;
        notifyAll();
    }

    public synchronized void close(String reason) {
        closed = true;
        closeReason = reason;
        version++;
        notifyAll();
    }

    public synchronized boolean isClosed() {
        return closed;
    }

    public synchronized long version() {
        return version;
    }

    /** Blocks until the buffer changed (data appended or closed) since {@code seenVersion} was read. */
    public synchronized void awaitVersionChange(long seenVersion) throws InterruptedException {
        while (version == seenVersion) {
            wait();
        }
    }

    /**
     * Tries the matchers in order against the head of the buffer. On a match, consumes the bytes, stores them in
     * {@code consumedOut[0]} and returns the matcher index. Otherwise returns NEED_MORE (some matcher could still
     * match), ALL_MISMATCH (data present, nothing can match) or EMPTY.
     */
    public synchronized int tryMatchAny(List<ByteMatcher> matchers, byte[][] consumedOut) {
        if (len == 0) return EMPTY;
        boolean needMore = false;
        for (int i = 0; i < matchers.size(); i++) {
            int r = matchers.get(i).match(data, len);
            if (r >= 0) {
                consumedOut[0] = consume(r);
                return i;
            }
            if (r == ByteMatcher.NEED_MORE) needMore = true;
        }
        return needMore ? NEED_MORE : ALL_MISMATCH;
    }

    /** Removes and returns everything buffered. */
    public synchronized byte[] dropAll() {
        return consume(len);
    }

    private byte[] consume(int n) {
        byte[] consumed = Arrays.copyOf(data, n);
        System.arraycopy(data, n, data, 0, len - n);
        len -= n;
        return consumed;
    }

    public synchronized byte[] await(ByteMatcher matcher, long timeoutMs) throws ScriptFailure, InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (true) {
            int r = matcher.match(data, len);
            if (r >= 0) {
                return consume(r);
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
