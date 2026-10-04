package com.example.tcpchat.ui;

import java.io.IOException;

import com.example.tcpchat.net.ConnectionStatus;
import com.example.tcpchat.net.TcpServer;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

public final class ServerPanel extends ChannelPanel {

    private final TcpServer server = new TcpServer(this);
    private final TextField portField = new TextField("9000");
    private final Button startButton = new Button("Start Server");
    private final Button stopButton = new Button("Stop Server");

    public ServerPanel() {
        portField.setPrefColumnCount(6);
        HBox portRow = new HBox(8, new Label("Port:"), portField);
        portRow.setAlignment(Pos.CENTER_LEFT);

        startButton.setOnAction(e -> start());
        stopButton.setOnAction(e -> server.stop());
        HBox buttons = new HBox(8, startButton, stopButton);
        buttons.setAlignment(Pos.CENTER_LEFT);

        build("TCP SERVER", new VBox(8, portRow, buttons));
    }

    private void start() {
        int port;
        try {
            port = Integer.parseInt(portField.getText().trim());
            if (port < 1 || port > 65535) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            chat.appendError("Invalid port: enter a number between 1 and 65535");
            return;
        }
        server.start(port);
    }

    @Override
    protected void sendBytes(byte[] data) throws IOException {
        server.send(data);
    }

    @Override
    public void closeChannel() {
        server.disconnectClient();
    }

    @Override
    protected void applyStatus(ConnectionStatus status) {
        boolean idle = status == ConnectionStatus.STOPPED || status == ConnectionStatus.ERROR;
        startButton.setDisable(!idle);
        stopButton.setDisable(idle);
        portField.setDisable(!idle);
    }

    @Override
    protected ConnectionStatus initialStatus() {
        return ConnectionStatus.STOPPED;
    }

    @Override
    protected String initialMessage() {
        return "Stopped";
    }

    @Override
    public void shutdown() {
        server.shutdown();
    }
}
