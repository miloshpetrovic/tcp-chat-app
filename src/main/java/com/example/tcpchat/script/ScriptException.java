package com.example.tcpchat.script;

/** Syntax error in a script. */
public final class ScriptException extends Exception {
    private final int line;

    public ScriptException(int line, String message) {
        super("Line " + line + ": " + message);
        this.line = line;
    }

    public int line() {
        return line;
    }
}
