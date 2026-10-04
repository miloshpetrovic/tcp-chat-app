package com.example.tcpchat;

import com.example.tcpchat.ui.ClientPanel;
import com.example.tcpchat.ui.ServerPanel;
import javafx.application.Application;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.stage.Stage;

public class Main extends Application {

    private ClientPanel clientPanel;
    private ServerPanel serverPanel;

    @Override
    public void start(Stage stage) {
        clientPanel = new ClientPanel();
        serverPanel = new ServerPanel();

        HBox.setHgrow(clientPanel, Priority.ALWAYS);
        HBox.setHgrow(serverPanel, Priority.ALWAYS);
        HBox root = new HBox(10, clientPanel, serverPanel);
        root.setPadding(new Insets(10));

        stage.setTitle("TCP Chat");
        stage.setScene(new Scene(root, 1100, 650));
        stage.show();
    }

    @Override
    public void stop() {
        // Called when the last window closes: release all sockets and executors.
        if (clientPanel != null) clientPanel.shutdown();
        if (serverPanel != null) serverPanel.shutdown();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
