package com.example.tcpchat.script.groovy;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.regex.Pattern;

import com.example.tcpchat.script.ByteMatcher;
import com.example.tcpchat.script.Bytes;
import com.example.tcpchat.script.ScriptFailure;
import groovy.lang.Binding;
import groovy.lang.Closure;
import groovy.lang.Script;
import org.codehaus.groovy.runtime.DefaultGroovyMethods;

/**
 * Base class of every Groovy protocol script: its public methods are the script's vocabulary
 * (together with the {@code client} and {@code server} variables and {@code 2.s} style durations).
 */
public abstract class ScriptBase extends Script {

    private GroovyRunner run;

    protected ScriptBase() {
        super();
    }

    protected ScriptBase(Binding binding) {
        super(binding);
    }

    void attach(GroovyRunner run) {
        this.run = run;
    }

    // ------------------------------------------------------------------ basics

    public void log(Object message) {
        run.log(String.valueOf(message));
    }

    public void pause(Object duration) throws InterruptedException {
        Thread.sleep(run.toMillis(duration));
    }

    public void fail(Object message) throws ScriptFailure {
        throw new ScriptFailure(String.valueOf(message));
    }

    /** Default timeout for expect/receive/waitUntil, e.g. {@code timeout 10.s}. */
    public void timeout(Object duration) {
        run.setDefaultTimeoutMs(run.toMillis(duration));
    }

    /** Text encoding for Strings and regexes: "UTF-8" (default) or "ASCII". */
    public void encoding(String name) throws ScriptFailure {
        switch (name.toUpperCase()) {
            case "UTF-8", "UTF8" -> run.setCharset(StandardCharsets.UTF_8);
            case "ASCII", "US-ASCII" -> run.setCharset(StandardCharsets.US_ASCII);
            default -> throw new ScriptFailure("unsupported encoding '" + name + "' (use UTF-8 or ASCII)");
        }
    }

    // ------------------------------------------------------------------ data helpers

    /** {@code hex("02 41 03")} -> raw bytes. */
    public byte[] hex(String text) throws ScriptFailure {
        List<String> parts = Arrays.stream(text.trim().split("[\\s,]+")).filter(s -> !s.isEmpty()).toList();
        String[] bad = new String[1];
        byte[] out = parts.isEmpty() ? null : Bytes.parseHex(parts, bad);
        if (out == null) throw new ScriptFailure(parts.isEmpty() ? "hex needs at least one byte" : "invalid hex byte '" + bad[0] + "'");
        return out;
    }

    /** Matches when the text occurs anywhere in the buffer; consumes up to its end. */
    public ByteMatcher contains(Object what) {
        return ByteMatcher.contains(run.toBytes(what));
    }

    /** Matches any data; consumes everything buffered. */
    public ByteMatcher any() {
        return ByteMatcher.any();
    }

    /** Regex from a String (the same as {@code ~/.../}). */
    public ByteMatcher regex(String regex) {
        return ByteMatcher.regex(Pattern.compile(regex), run.charset());
    }

    // ------------------------------------------------------------------ manager / workers

    /** Creates a worker without starting it; start it with start() or parallel(...). */
    public Worker worker(String name, Closure<?> body) {
        Worker w = new Worker(name, body, run);
        run.registerWorker(w);
        return w;
    }

    /** Creates and starts a worker. */
    public Worker spawn(String name, Closure<?> body) {
        return worker(name, body).start();
    }

    public Worker spawn(Closure<?> body) {
        return spawn("worker" + (System.nanoTime() % 10_000), body);
    }

    /** Starts the workers (if needed) and waits until all of them finished. */
    public void parallel(Worker... workers) throws InterruptedException {
        for (Worker w : workers) w.start();
        for (Worker w : workers) w.join();
    }

    public void parallel(List<Worker> workers) throws InterruptedException {
        parallel(workers.toArray(new Worker[0]));
    }

    /** Named queue for passing values between workers: {@code mailbox("m").put(x)} / {@code .take()}. */
    public BlockingQueue<Object> mailbox(String name) {
        return run.mailbox(name);
    }

    public CountDownLatch latch(int count) {
        return new CountDownLatch(count);
    }

    /** Polls the closure until it returns true; fails after the default timeout. */
    public void waitUntil(Closure<?> condition) throws ScriptFailure, InterruptedException {
        waitUntil(Map.of(), condition);
    }

    public void waitUntil(Map<String, Object> options, Closure<?> condition) throws ScriptFailure, InterruptedException {
        Object t = options.get("timeout");
        long timeoutMs = t == null ? run.defaultTimeoutMs() : run.toMillis(t);
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        while (!DefaultGroovyMethods.asBoolean(condition.call())) {
            if (System.nanoTime() > deadline) throw new ScriptFailure("waitUntil timed out after " + timeoutMs + " ms");
            Thread.sleep(20);
        }
    }

    // ------------------------------------------------------------------ simulations

    /** Keeps the script (and its handlers/workers) alive for the given time. */
    public void runFor(Object duration) throws InterruptedException {
        try {
            Thread.sleep(run.toMillis(duration));
        } catch (InterruptedException e) {
            endSimulationOrRethrow(e);
        }
    }

    /** Keeps the script alive until Stop is pressed (which then counts as a normal end). */
    public void keepRunning() throws InterruptedException {
        try {
            while (true) Thread.sleep(1000);
        } catch (InterruptedException e) {
            endSimulationOrRethrow(e);
        }
    }

    private void endSimulationOrRethrow(InterruptedException e) throws InterruptedException {
        if (run.onMainThread() && run.isStopRequested()) throw new GroovyRunner.StopSimulation();
        throw e;
    }
}
