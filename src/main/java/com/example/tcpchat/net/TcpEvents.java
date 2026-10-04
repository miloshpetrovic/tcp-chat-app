package com.example.tcpchat.net;

/**
 * Notifications from the networking layer. Methods are invoked on background threads;
 * implementations that touch the GUI must hop to the JavaFX thread themselves.
 */
public interface TcpEvents {

    /** Status changed (connected, listening, error, ...). {@code message} is human readable. */
    void statusChanged(ConnectionStatus status, String message);

    /** Raw bytes arrived. Chunk boundaries have no meaning: TCP is a byte stream. */
    void dataReceived(byte[] data);
}
