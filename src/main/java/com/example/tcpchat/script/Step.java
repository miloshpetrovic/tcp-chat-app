package com.example.tcpchat.script;

import java.time.Duration;

/** One executable script line. {@code text} is the original line, used in the log. */
public sealed interface Step {
    int lineNo();

    String text();

    record Send(int lineNo, String text, Channel channel, byte[] payload) implements Step {
    }

    record Expect(int lineNo, String text, Channel channel, ByteMatcher matcher, Duration timeout) implements Step {
    }

    record Pause(int lineNo, String text, Duration duration) implements Step {
    }

    record Close(int lineNo, String text, Channel channel) implements Step {
    }
}
