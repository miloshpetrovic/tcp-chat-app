package com.example.tcpchat.script;

/** A step failed while running (mismatch, timeout, channel closed, send error). */
public final class ScriptFailure extends Exception {
    public ScriptFailure(String message) {
        super(message);
    }
}
