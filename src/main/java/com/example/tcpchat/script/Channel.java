package com.example.tcpchat.script;

/** The two panels a script can talk through. */
public enum Channel {
    CLIENT, SERVER;

    @Override
    public String toString() {
        return name().toLowerCase();
    }
}
