package com.example.tcpchat.ui;

import java.io.IOException;

import com.example.tcpchat.net.ConnectionStatus;
import com.example.tcpchat.net.TcpClient;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

public final class ClientPanel extends ChannelPanel {

    private final TcpClient client = new TcpClient(this);
    private final TextField hostField = new TextField("127.0.0.1");
    private final TextField portField = new TextField("9000");
    private final Button connectButton = new Button("Connect");
    private final Button disconnectButton = new Button("Disconnect");

    public ClientPanel() {
        GridPane form = new GridPane();
        form.setHgap(8);
        form.setVgap(6);
        form.addRow(0, new Label("Host:"), hostField);
        form.addRow(1, new Label("Port:"), portField);
        portField.setPrefColumnCount(6);

        connectButton.setOnAction(e -> connect());
        disconnectButton.setOnAction(e -> client.disconnect());
        HBox buttons = new HBox(8, connectButton, disconnectButton);
        buttons.setAlignment(Pos.CENTER_LEFT);

        build("TCP CLIENT", new VBox(8, form, buttons));
    }

    private void connect() {
        String host = hostField.getText().trim();
        if (host.isEmpty()) {
            chat.appendError("Invalid host: host must not be empty");
            return;
        }
        int port;
        try {
            port = Integer.parseInt(portField.getText().trim());
            if (port < 1 || port > 65535) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            chat.appendError("Invalid port: enter a number between 1 and 65535");
            return;
        }
        client.connect(host, port);
    }

    @Override
    protected void sendBytes(byte[] data) throws IOException {
        client.send(data);
    }

    @Override
    protected void applyStatus(ConnectionStatus status) {
        boolean idle = status == ConnectionStatus.DISCONNECTED || status == ConnectionStatus.ERROR;
        connectButton.setDisable(!idle);
        disconnectButton.setDisable(idle);
        hostField.setDisable(!idle);
        portField.setDisable(!idle);
    }

    @Override
    protected ConnectionStatus initialStatus() {
        return ConnectionStatus.DISCONNECTED;
    }

    @Override
    protected String initialMessage() {
        return "Disconnected";
    }

    @Override
    public void shutdown() {
        client.shutdown();
    }
}
