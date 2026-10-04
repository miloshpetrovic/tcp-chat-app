package com.example.tcpchat.ui;

import java.io.IOException;

import com.example.tcpchat.net.ConnectionStatus;
import com.example.tcpchat.net.TcpEvents;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

/**
 * Common skeleton of a panel: title, connection controls (provided by the subclass), status line
 * and chat. Receives network events on background threads and re-dispatches them to the JavaFX thread.
 */
abstract class ChannelPanel extends VBox implements TcpEvents {

    protected final ChatView chat = new ChatView(this::sendBytes);
    private final Label statusLabel = new Label();

    protected void build(String title, Node controls) {
        Label titleLabel = new Label(title);
        titleLabel.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");
        setSpacing(8);
        setPadding(new Insets(10));
        setStyle("-fx-border-color: #b0b0b0; -fx-border-radius: 4;");
        getChildren().addAll(titleLabel, controls, statusLabel, chat);
        // chat grows vertically (VBox.setVgrow is set in ChatView)
        setMinWidth(0);
        showStatus(initialStatus(), initialMessage());
    }

    protected abstract void sendBytes(byte[] data) throws IOException;

    /** Called on the JavaFX thread: update buttons/fields for the new status. */
    protected abstract void applyStatus(ConnectionStatus status);

    protected abstract ConnectionStatus initialStatus();

    protected abstract String initialMessage();

    public abstract void shutdown();

    @Override
    public void statusChanged(ConnectionStatus status, String message) {
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
        chat.setSendEnabled(status == ConnectionStatus.CONNECTED || status == ConnectionStatus.CLIENT_CONNECTED);
        applyStatus(status);
    }
}
