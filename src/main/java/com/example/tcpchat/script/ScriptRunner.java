package com.example.tcpchat.script;

import java.io.IOException;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Executes a script step by step on its own thread; never touches the GUI. The owner feeds it
 * incoming bytes with {@link #onData} and channel loss with {@link #onClosed}.
 */
public final class ScriptRunner implements ProtocolRun {

    /** Called on the runner thread. */
    public interface Listener {
        void stepStarted(Step step, int index, int total);

        void stepPassed(Step step);

        void stepFailed(Step step, String message);

        void finished(boolean passed, String summary);
    }

    private final List<Step> steps;
    private final Map<Channel, ScriptChannel> channels;
    private final Map<Channel, ReceiveBuffer> buffers = new EnumMap<>(Channel.class);
    private final Listener listener;
    private Thread thread;

    public ScriptRunner(Script script, Map<Channel, ScriptChannel> channels, Listener listener) {
        this.steps = script.steps();
        this.channels = channels;
        this.listener = listener;
        for (Channel c : Channel.values()) buffers.put(c, new ReceiveBuffer());
    }

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
        if (thread != null) throw new IllegalStateException("already started");
        thread = new Thread(this::run, "script-runner");
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public synchronized void stop() {
        if (thread != null) thread.interrupt();
    }

    private void run() {
        int total = steps.size();
        try {
            for (int i = 0; i < total; i++) {
                Step step = steps.get(i);
                listener.stepStarted(step, i + 1, total);
                try {
                    execute(step);
                } catch (ScriptFailure f) {
                    listener.stepFailed(step, f.getMessage());
                    listener.finished(false, "FAILED at line " + step.lineNo());
                    return;
                }
                listener.stepPassed(step);
            }
            listener.finished(true, "PASSED (" + total + " steps)");
        } catch (InterruptedException e) {
            listener.finished(false, "Stopped by user");
        }
    }

    private void execute(Step step) throws ScriptFailure, InterruptedException {
        switch (step) {
            case Step.Send s -> {
                try {
                    channels.get(s.channel()).send(s.payload());
                } catch (IOException e) {
                    throw new ScriptFailure("Unable to send on " + s.channel() + ": " + e.getMessage());
                }
            }
            case Step.Expect e -> buffers.get(e.channel()).await(e.matcher(), e.timeout().toMillis());
            case Step.Pause p -> Thread.sleep(p.duration().toMillis());
            case Step.Close c -> channels.get(c.channel()).closeChannel();
        }
    }
}
