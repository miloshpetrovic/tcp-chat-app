package com.example.tcpchat.script.groovy;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

import com.example.tcpchat.script.ByteMatcher;
import com.example.tcpchat.script.Bytes;
import com.example.tcpchat.script.Channel;
import com.example.tcpchat.script.ReceiveBuffer;
import com.example.tcpchat.script.ScriptChannel;
import com.example.tcpchat.script.ScriptFailure;
import groovy.lang.Closure;

/**
 * The {@code client} / {@code server} object seen by Groovy scripts.
 *
 * <p>Reading rules (the design decision for sharing a channel between workers): reads on a channel are
 * serialized by a fair lock, so two workers that both {@code expect} on the same channel take turns in FIFO
 * order and never steal half of each other's data. A channel that has {@code onMessage} handlers is read
 * by its dispatcher thread only; calling {@code expect} on it is an error.
 */
public final class GroovyChannel {

    private record Handler(ByteMatcher matcher, Pattern pattern, Closure<?> body) {
    }

    private static final long REGISTRATION_GRACE_MS = 100;

    private final Channel id;
    private final ScriptChannel io;
    private final ReceiveBuffer buffer;
    private final GroovyRunner run;
    private final ReentrantLock readLock = new ReentrantLock(true);
    private final List<Handler> handlers = new CopyOnWriteArrayList<>();
    private volatile Closure<?> unmatched;
    private Thread dispatcher; // guarded by this

    GroovyChannel(Channel id, ScriptChannel io, ReceiveBuffer buffer, GroovyRunner run) {
        this.id = id;
        this.io = io;
        this.buffer = buffer;
        this.run = run;
    }

    public String getName() {
        return id.toString();
    }

    // ------------------------------------------------------------------ sending

    /** Sends exactly these bytes: a String (encoded with the script encoding), byte[] (e.g. hex("02 41")) or a list of numbers. */
    public void send(Object data) throws ScriptFailure {
        byte[] bytes = run.toBytes(data);
        try {
            io.send(bytes);
        } catch (IOException e) {
            throw new ScriptFailure("Unable to send on " + id + ": " + e.getMessage());
        }
        run.trace(id + " send " + Bytes.escape(bytes, bytes.length));
    }

    public void close() {
        io.closeChannel();
        run.trace(id + " close");
    }

    // ------------------------------------------------------------------ receiving (blocking)

    /**
     * Waits for the expected data at the head of the receive buffer, consumes it and returns it.
     * {@code what}: a String or byte[] (exact prefix), a regex ({@code ~/.../}), {@code contains("x")} or {@code any()}.
     */
    public Msg expect(Object what) throws ScriptFailure, InterruptedException {
        return doExpect(what, run.defaultTimeoutMs());
    }

    /** {@code client.expect(timeout: 5.s, "B")} */
    public Msg expect(Map<String, Object> options, Object what) throws ScriptFailure, InterruptedException {
        Object t = options.get("timeout");
        return doExpect(what, t == null ? run.defaultTimeoutMs() : run.toMillis(t));
    }

    /** {@code client.expect("B", 5.s)} */
    public Msg expect(Object what, Object timeout) throws ScriptFailure, InterruptedException {
        return doExpect(what, run.toMillis(timeout));
    }

    /** Waits for any data and returns everything currently buffered. */
    public Msg receive() throws ScriptFailure, InterruptedException {
        return doExpect(ByteMatcher.any(), run.defaultTimeoutMs());
    }

    public Msg receive(Map<String, Object> options) throws ScriptFailure, InterruptedException {
        return expect(options, ByteMatcher.any());
    }

    private Msg doExpect(Object what, long timeoutMs) throws ScriptFailure, InterruptedException {
        ByteMatcher matcher = run.toMatcher(what);
        readLock.lockInterruptibly();
        try {
            if (!handlers.isEmpty()) {
                throw new ScriptFailure(id + " has onMessage handlers, which read this channel; "
                        + "use only handlers on it, or call " + id + ".clearHandlers() first");
            }
            byte[] got;
            try {
                got = buffer.await(matcher, timeoutMs);
            } catch (ScriptFailure f) {
                throw new ScriptFailure(id + " expect failed: " + f.getMessage());
            }
            Msg msg = Msg.of(got, run.charset(), what instanceof Pattern p ? p : null);
            run.trace(id + " expect " + matcher.describe() + " OK " + Bytes.escape(got, got.length));
            return msg;
        } finally {
            readLock.unlock();
        }
    }

    // ------------------------------------------------------------------ handlers (reactive)

    /** Runs {@code body(msg)} on the channel's dispatcher thread whenever data matching {@code what} arrives. */
    public void onMessage(Object what, Closure<?> body) {
        handlers.add(new Handler(run.toMatcher(what), what instanceof Pattern p ? p : null, body));
        startDispatcher();
    }

    /** Handler for any data not claimed by an earlier handler. */
    public void onMessage(Closure<?> body) {
        onMessage(ByteMatcher.any(), body);
    }

    /** Called with the unprocessed data when no handler can ever match it (default: log and discard). */
    public void onUnmatched(Closure<?> body) {
        this.unmatched = body;
    }

    /** Removes all handlers and stops the dispatcher; {@code expect} works on this channel again. */
    public void clearHandlers() {
        handlers.clear();
        Thread d;
        synchronized (this) {
            d = dispatcher;
            dispatcher = null;
        }
        if (d != null) d.interrupt();
    }

    private synchronized void startDispatcher() {
        if (dispatcher != null) return;
        Thread t = new Thread(this::dispatchLoop, "script-dispatch-" + id);
        t.setDaemon(true);
        dispatcher = t;
        run.register(t);
        t.start();
    }

    private void dispatchLoop() {
        try {
            // Grace period: lets the script finish registering its handlers (and onUnmatched) before the first
            // data is judged, so data that is already buffered is not discarded as "unhandled" too early.
            Thread.sleep(REGISTRATION_GRACE_MS);
            while (!Thread.currentThread().isInterrupted()) {
                List<Handler> hs = List.copyOf(handlers);
                if (hs.isEmpty()) return;
                long seen = buffer.version();
                byte[][] out = new byte[1][];
                int r = buffer.tryMatchAny(hs.stream().map(Handler::matcher).toList(), out);
                if (r >= 0) {
                    Handler h = hs.get(r);
                    run.trace(id + " handler " + h.matcher().describe() + " <- " + Bytes.escape(out[0], out[0].length));
                    h.body().call(Msg.of(out[0], run.charset(), h.pattern()));
                } else if (r == ReceiveBuffer.ALL_MISMATCH) {
                    byte[] junk = buffer.dropAll();
                    Closure<?> u = unmatched;
                    if (u != null) {
                        u.call(Msg.of(junk, run.charset(), null));
                    } else {
                        run.trace(id + " unhandled data discarded " + Bytes.escape(junk, junk.length));
                    }
                } else {
                    if (buffer.isClosed()) return;
                    buffer.awaitVersionChange(seen);
                }
            }
        } catch (Throwable t) {
            if (!(t instanceof InterruptedException) && !run.isCancelled()) {
                run.fail("handler " + id, t);
            }
        }
    }
}
