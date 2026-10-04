package com.example.tcpchat.script;

import java.io.IOException;

/** What the runner needs from a panel. Implementations must be callable from any thread. */
public interface ScriptChannel {

    /** Sends exactly these bytes. */
    void send(byte[] data) throws IOException;

    /** Closes the connection on this channel (the client disconnects / the server drops its client). */
    void closeChannel();
}
