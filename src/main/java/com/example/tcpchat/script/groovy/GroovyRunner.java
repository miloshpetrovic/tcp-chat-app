package com.example.tcpchat.script.groovy;

import java.io.PrintWriter;
import java.io.Writer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.example.tcpchat.script.ByteMatcher;
import com.example.tcpchat.script.Channel;
import com.example.tcpchat.script.ProtocolRun;
import com.example.tcpchat.script.ReceiveBuffer;
import com.example.tcpchat.script.ScriptChannel;
import com.example.tcpchat.script.ScriptException;
import groovy.lang.Binding;
import groovy.lang.ExpandoMetaClass;
import groovy.lang.GroovyShell;
import groovy.lang.Script;
import org.codehaus.groovy.control.CompilationFailedException;
import org.codehaus.groovy.control.CompilerConfiguration;

/**
 * Runs a Groovy protocol script. The script is the "manager": it runs on the script-main thread and may
 * spawn workers and register message handlers. Everything here is independent of the GUI.
 *
 * <p>Failure model: the first failure (failed assert, exception, timeout, mismatch) in any thread is
 * recorded, every other script thread is interrupted, and the run ends as FAILED with that failure.
 */
public final class GroovyRunner implements ProtocolRun {

    /** Called on arbitrary script threads. */
    public interface Listener {
        void log(String line);

        void finished(boolean passed, String summary);
    }

    /** Thrown inside the script when Stop is pressed during runFor()/keepRunning(): a normal end of a simulation. */
    static final class StopSimulation extends RuntimeException {
        StopSimulation() {
            super("simulation stopped", null, false, false);
        }
    }

    private record Failure(String summary) {
    }

    private static final Pattern DURATION = Pattern.compile("(\\d+)\\s*(ms|s|m)");
    private static final Pattern LINE_IN_MESSAGE = Pattern.compile("@ line (\\d+)");
    private static boolean metaReady;

    private final Map<Channel, ReceiveBuffer> buffers = new EnumMap<>(Channel.class);
    private final Map<Channel, GroovyChannel> channels = new EnumMap<>(Channel.class);
    private final Listener listener;
    private final String scriptName;
    private final AtomicReference<Failure> failure = new AtomicReference<>();
    private final List<Thread> threads = new CopyOnWriteArrayList<>();
    private final List<Worker> workers = new CopyOnWriteArrayList<>();
    private final Map<String, BlockingQueue<Object>> mailboxes = new ConcurrentHashMap<>();
    private volatile Charset charset = StandardCharsets.UTF_8;
    private volatile long defaultTimeoutMs = 5000;
    private volatile boolean stopRequested;
    private volatile boolean cancelled;
    private Thread mainThread;
    private ScriptBase script;

    private GroovyRunner(String scriptName, Map<Channel, ScriptChannel> io, Listener listener) {
        this.scriptName = scriptName;
        this.listener = listener;
        for (Channel c : Channel.values()) {
            ReceiveBuffer b = new ReceiveBuffer();
            buffers.put(c, b);
            channels.put(c, new GroovyChannel(c, io.get(c), b, this));
        }
    }

    // ------------------------------------------------------------------ compile / create

    /** Compiles only; throws with the line number on a syntax error. */
    public static void check(String source, String scriptName) throws ScriptException {
        try {
            newShell(new Binding()).parse(source, scriptName);
        } catch (CompilationFailedException e) {
            throw compileError(e);
        }
    }

    public static GroovyRunner create(String source, String scriptName, Map<Channel, ScriptChannel> io,
                                      Listener listener) throws ScriptException {
        GroovyRunner runner = new GroovyRunner(scriptName, io, listener);
        Binding binding = new Binding();
        binding.setVariable("client", runner.channels.get(Channel.CLIENT));
        binding.setVariable("server", runner.channels.get(Channel.SERVER));
        // println in the script goes to the log
        binding.setVariable("out", new PrintWriter(new LogWriter(listener), true));
        try {
            Script s = newShell(binding).parse(source, scriptName);
            runner.script = (ScriptBase) s;
        } catch (CompilationFailedException e) {
            throw compileError(e);
        }
        runner.script.attach(runner);
        return runner;
    }

    private static GroovyShell newShell(Binding binding) {
        CompilerConfiguration cfg = new CompilerConfiguration();
        cfg.setScriptBaseClass(ScriptBase.class.getName());
        return new GroovyShell(GroovyRunner.class.getClassLoader(), binding, cfg);
    }

    private static ScriptException compileError(CompilationFailedException e) {
        String msg = String.valueOf(e.getMessage()).strip();
        int line = 1;
        Matcher m = LINE_IN_MESSAGE.matcher(msg);
        if (m.find()) line = Integer.parseInt(m.group(1));
        // keep the first diagnostic only, without the "N error(s)" tail
        String first = msg.lines().filter(l -> !l.isBlank()).skip(1).findFirst().orElse(msg).strip();
        return new ScriptException(line, first);
    }

    /** `2.s`, `500.ms`, `1.m` on numbers. Registered once per JVM; works on every thread. */
    private static synchronized void ensureMeta() {
        if (metaReady) return;
        ExpandoMetaClass.enableGlobally();
        new GroovyShell(GroovyRunner.class.getClassLoader()).evaluate("""
                Number.metaClass.getMs = { -> java.time.Duration.ofMillis(delegate.longValue()) }
                Number.metaClass.getS  = { -> java.time.Duration.ofMillis((long) (delegate * 1000)) }
                Number.metaClass.getM  = { -> java.time.Duration.ofMillis((long) (delegate * 60000)) }
                """);
        metaReady = true;
    }

    // ------------------------------------------------------------------ ProtocolRun

    @Override
    public void onData(Channel channel, byte[] data) {
        buffers.get(channel).append(data);
    }

    @Override
    public void onClosed(Channel channel, String reason) {
        buffers.get(channel).close(reason);
    }

    @Override
    public synchronized void start() {
        if (mainThread != null) throw new IllegalStateException("already started");
        mainThread = new Thread(this::runMain, "script-main");
        mainThread.setDaemon(true);
        mainThread.start();
    }

    @Override
    public synchronized void stop() {
        stopRequested = true;
        if (mainThread != null) mainThread.interrupt();
    }

    private void runMain() {
        Throwable thrown = null;
        try {
            ensureMeta();
            script.run();
            if (!stopRequested) joinWorkers();
        } catch (Throwable t) {
            thrown = t;
        }
        cancelled = true;
        cancelAll();
        for (Thread t : threads) {
            try {
                t.join(1000);
            } catch (InterruptedException ignored) {
                // finishing anyway
            }
        }
        Failure f = failure.get();
        if (f == null && thrown != null && !(thrown instanceof StopSimulation) && !(thrown instanceof InterruptedException)) {
            fail("script", thrown);
            f = failure.get();
        }
        if (f != null) {
            listener.finished(false, f.summary());
        } else if (thrown instanceof StopSimulation) {
            listener.finished(true, "Stopped: simulation ended");
        } else if (thrown instanceof InterruptedException || stopRequested) {
            listener.finished(false, "Stopped by user");
        } else {
            listener.finished(true, "PASSED");
        }
    }

    private void joinWorkers() throws InterruptedException {
        for (Worker w : workers) w.join();
    }

    // ------------------------------------------------------------------ failure / cancellation

    boolean isCancelled() {
        return cancelled;
    }

    void register(Thread t) {
        threads.add(t);
    }

    void registerWorker(Worker w) {
        workers.add(w);
    }

    /** Records the first failure, logs it and interrupts every other script thread. */
    void fail(String who, Throwable t) {
        String message = describe(t);
        int line = lineOf(t);
        String where = line > 0 ? " at line " + line : "";
        String prefix = who.equals("script") ? "" : "[" + who + "] ";
        Failure f = new Failure("FAILED" + where + ": " + prefix + firstLine(message));
        if (failure.compareAndSet(null, f)) {
            listener.log("FAIL  " + prefix + message.strip() + (where.isEmpty() ? "" : "   (" + scriptName + where + ")"));
            cancelAll();
        }
    }

    private void cancelAll() {
        Thread me = Thread.currentThread();
        if (mainThread != null && mainThread != me) mainThread.interrupt();
        for (Thread t : threads) if (t != me) t.interrupt();
    }

    private String describe(Throwable t) {
        String m = t.getMessage();
        return (m == null || m.isBlank()) ? t.getClass().getSimpleName() : m;
    }

    private static String firstLine(String s) {
        // Groovy's power assert starts with a header line that says nothing; show the asserted expression instead
        return s.strip().lines().map(String::strip)
                .filter(l -> !l.isEmpty() && !l.equals("Assertion failed:"))
                .findFirst().orElse(s);
    }

    private int lineOf(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            for (StackTraceElement e : c.getStackTrace()) {
                if (scriptName.equals(e.getFileName()) && e.getLineNumber() > 0) return e.getLineNumber();
            }
            if (c.getCause() == c) break;
        }
        return -1;
    }

    // ------------------------------------------------------------------ services for the script API

    GroovyChannel channel(Channel c) {
        return channels.get(c);
    }

    Charset charset() {
        return charset;
    }

    void setCharset(Charset c) {
        this.charset = c;
    }

    long defaultTimeoutMs() {
        return defaultTimeoutMs;
    }

    void setDefaultTimeoutMs(long ms) {
        this.defaultTimeoutMs = ms;
    }

    boolean isStopRequested() {
        return stopRequested;
    }

    boolean onMainThread() {
        return Thread.currentThread() == mainThread;
    }

    BlockingQueue<Object> mailbox(String name) {
        return mailboxes.computeIfAbsent(name, n -> new LinkedBlockingQueue<>());
    }

    void log(String text) {
        listener.log(threadPrefix() + text);
    }

    void trace(String text) {
        log(text);
    }

    private static String threadPrefix() {
        String n = Thread.currentThread().getName();
        if (n.startsWith("script-worker-")) return "[" + n.substring("script-worker-".length()) + "] ";
        if (n.startsWith("script-dispatch-")) return "[handler] ";
        return "";
    }

    /** Duration, Number (milliseconds) or text such as "500ms", "2s", "1m". */
    public long toMillis(Object d) {
        if (d instanceof Duration dur) return dur.toMillis();
        if (d instanceof Number n) return n.longValue();
        if (d != null) {
            Matcher m = DURATION.matcher(d.toString().strip());
            if (m.matches()) {
                long v = Long.parseLong(m.group(1));
                return switch (m.group(2)) {
                    case "ms" -> v;
                    case "s" -> v * 1000;
                    default -> v * 60_000;
                };
            }
        }
        throw new IllegalArgumentException("not a duration: " + d + " (use 2.s, 500.ms, 1.m, a number of milliseconds or \"2s\")");
    }

    byte[] toBytes(Object o) {
        if (o instanceof byte[] b) return b;
        if (o instanceof Msg m) return m.getBytes();
        if (o instanceof CharSequence cs) return cs.toString().getBytes(charset);
        if (o instanceof Collection<?> c) {
            byte[] out = new byte[c.size()];
            int i = 0;
            for (Object x : c) {
                if (!(x instanceof Number n)) throw new IllegalArgumentException("byte list may only contain numbers: " + x);
                out[i++] = n.byteValue();
            }
            return out;
        }
        throw new IllegalArgumentException("cannot send " + (o == null ? "null" : o.getClass().getSimpleName())
                + "; use a String, hex(\"..\") or a list of byte values");
    }

    ByteMatcher toMatcher(Object what) {
        if (what instanceof ByteMatcher m) return m;
        if (what instanceof Pattern p) return ByteMatcher.regex(p, charset);
        return ByteMatcher.exact(toBytes(what));
    }

    /** Forwards complete lines written with println to the log. */
    private static final class LogWriter extends Writer {
        private final Listener listener;
        private final StringBuilder pending = new StringBuilder();

        LogWriter(Listener listener) {
            this.listener = listener;
        }

        @Override
        public synchronized void write(char[] buf, int off, int len) {
            pending.append(buf, off, len);
            emit(false);
        }

        @Override
        public synchronized void flush() {
            emit(false);
        }

        @Override
        public synchronized void close() {
            emit(true);
        }

        private void emit(boolean all) {
            int nl;
            while ((nl = pending.indexOf("\n")) >= 0) {
                listener.log(pending.substring(0, nl).stripTrailing());
                pending.delete(0, nl + 1);
            }
            if (all && !pending.isEmpty()) {
                listener.log(pending.toString());
                pending.setLength(0);
            }
        }
    }
}
