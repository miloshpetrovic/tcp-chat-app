package com.example.tcpchat.script;

/** A running (or runnable) protocol script, whatever language it is written in. */
public interface ProtocolRun {

    /** Raw bytes arrived on a channel. Callable from any thread. */
    void onData(Channel channel, byte[] data);

    /** The channel's peer connection is gone. Callable from any thread. */
    void onClosed(Channel channel, String reason);

    void start();

    /** Requests the run to end; the listener is still notified when it actually has. */
    void stop();
}
