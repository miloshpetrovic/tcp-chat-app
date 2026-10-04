package com.example.tcpchat.ui;

import java.io.IOException;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import com.example.tcpchat.net.ConnectionStatus;
import com.example.tcpchat.net.TcpEvents;
import com.example.tcpchat.script.ScriptChannel;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

/**
 * Common skeleton of a panel: title, connection controls (provided by the subclass), status line
 * and chat. Receives network events on background threads and re-dispatches them to the JavaFX thread.
 * Also acts as a {@link ScriptChannel} so a protocol script can drive it.
 */
abstract class ChannelPanel extends VBox implements TcpEvents, ScriptChannel {

    protected final ChatView chat = new ChatView(this::sendBytes);
    private final Label statusLabel = new Label();
    private final BooleanProperty connected = new SimpleBooleanProperty(false);
    private boolean manualSendBlocked;

    // Optional observers (the script runner); invoked on the network thread, before the GUI update.
    private volatile Consumer<byte[]> dataTap;
    private volatile BiConsumer<ConnectionStatus, String> statusTap;

    protected void build(String title, Node controls) {
        Label titleLabel = new Label(title);
        titleLabel.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");
        setSpacing(8);
        setPadding(new Insets(10));
        setStyle("-fx-border-color: #b0b0b0; -fx-border-radius: 4;");
        getChildren().addAll(titleLabel, controls, statusLabel, chat);
        setMinWidth(0);
        showStatus(initialStatus(), initialMessage());
    }

    protected abstract void sendBytes(byte[] data) throws IOException;

    /** Called on the JavaFX thread: update buttons/fields for the new status. */
    protected abstract void applyStatus(ConnectionStatus status);

    protected abstract ConnectionStatus initialStatus();

    protected abstract String initialMessage();

    public abstract void shutdown();

    /** True while a client is connected (JavaFX thread property). */
    public BooleanProperty connectedProperty() {
        return connected;
    }

    /** JavaFX thread only. Disables manual sending while a script owns the channel. */
    public void setManualSendBlocked(boolean blocked) {
        manualSendBlocked = blocked;
        chat.setSendEnabled(connected.get() && !blocked);
    }

    /** Observe raw network events; pass nulls to detach. */
    public void setTaps(Consumer<byte[]> dataTap, BiConsumer<ConnectionStatus, String> statusTap) {
        this.dataTap = dataTap;
        this.statusTap = statusTap;
    }

    /** Thread-safe: sends exactly these bytes and records them in the chat history as SENT. */
    @Override
    public void send(byte[] data) throws IOException {
        sendBytes(data);
        Platform.runLater(() -> chat.appendSent(data));
    }

    @Override
    public void statusChanged(ConnectionStatus status, String message) {
        BiConsumer<ConnectionStatus, String> tap = statusTap;
        if (tap != null) tap.accept(status, message);
        Platform.runLater(() -> {
            showStatus(status, message);
            if (status == ConnectionStatus.ERROR) {
                chat.appendError(message);
            } else {
                chat.appendInfo(message);
            }
        });
    }

    @Override
    public void dataReceived(byte[] data) {
        Consumer<byte[]> tap = dataTap;
        if (tap != null) tap.accept(data);
        Platform.runLater(() -> chat.appendReceived(data));
    }

    private void showStatus(ConnectionStatus status, String message) {
        statusLabel.setText("Status: " + message);
        statusLabel.setStyle("-fx-font-weight: bold; -fx-text-fill: " + switch (status) {
            case CONNECTED, CLIENT_CONNECTED -> "#1b8a2f";
            case LISTENING -> "#1565c0";
            case CONNECTING, STARTING -> "#c77700";
            case ERROR -> "#c62828";
            case DISCONNECTED, STOPPED -> "#666666";
        } + ";");
        connected.set(status == ConnectionStatus.CONNECTED || status == ConnectionStatus.CLIENT_CONNECTED);
        chat.setSendEnabled(connected.get() && !manualSendBlocked);
        applyStatus(status);
    }
}
