package com.example.tcpchat.script.groovy;

import groovy.lang.Closure;

/** A closure running on its own thread (a "worker" of the script that spawned it). */
public final class Worker {
    private final String name;
    private final Closure<?> body;
    private final GroovyRunner run;
    private Thread thread;
    private volatile boolean done;

    Worker(String name, Closure<?> body, GroovyRunner run) {
        this.name = name;
        this.body = body;
        this.run = run;
    }

    public String getName() {
        return name;
    }

    public boolean isDone() {
        return done;
    }

    /** Starts the worker (no effect if already started). */
    public synchronized Worker start() {
        if (thread != null) return this;
        thread = new Thread(this::runBody, "script-worker-" + name);
        thread.setDaemon(true);
        run.register(thread);
        thread.start();
        return this;
    }

    private void runBody() {
        try {
            body.call();
        } catch (Throwable t) {
            if (!(t instanceof InterruptedException) && !run.isCancelled()) {
                run.fail("worker " + name, t);
            }
        } finally {
            done = true;
        }
    }

    public void join() throws InterruptedException {
        Thread t;
        synchronized (this) {
            t = thread;
        }
        if (t != null) t.join();
    }

    /** @return true if the worker finished within the timeout (ms, Duration or "2s"). */
    public boolean join(Object timeout) throws InterruptedException {
        Thread t;
        synchronized (this) {
            t = thread;
        }
        if (t == null) return true;
        t.join(run.toMillis(timeout));
        return !t.isAlive();
    }
}
