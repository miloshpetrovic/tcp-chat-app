package com.example.tcpchat.ui;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Set;

import com.example.tcpchat.net.ConnectionStatus;
import com.example.tcpchat.script.Channel;
import com.example.tcpchat.script.Script;
import com.example.tcpchat.script.ScriptException;
import com.example.tcpchat.script.ScriptParser;
import com.example.tcpchat.script.ScriptRunner;
import com.example.tcpchat.script.Step;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

/** Loads a protocol script and runs it against the two panels. GUI only; the logic lives in the script package. */
public final class ScriptPanel extends VBox {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    /** Statuses that mean the channel's peer connection is gone. */
    private static final Set<ConnectionStatus> LOST = Set.of(
            ConnectionStatus.DISCONNECTED, ConnectionStatus.ERROR,
            ConnectionStatus.STOPPED, ConnectionStatus.LISTENING);

    private final ChannelPanel client;
    private final ChannelPanel server;
    private final ObjectProperty<Path> scriptPath = new SimpleObjectProperty<>();
    private final BooleanProperty running = new SimpleBooleanProperty(false);
    private final TextField pathField = new TextField();
    private final Label stateLabel = new Label("No script loaded");
    private final TextArea log = new TextArea();
    private ScriptRunner runner;

    public ScriptPanel(ClientPanel client, ServerPanel server) {
        this.client = client;
        this.server = server;

        pathField.setEditable(false);
        pathField.setPromptText("Script file (.tcps)");
        HBox.setHgrow(pathField, Priority.ALWAYS);
        Button loadButton = new Button("Load...");
        Button startButton = new Button("Start protocol");
        Button stopButton = new Button("Stop");
        loadButton.setOnAction(e -> chooseFile());
        startButton.setOnAction(e -> start());
        stopButton.setOnAction(e -> {
            if (runner != null) runner.stop();
        });

        loadButton.disableProperty().bind(running);
        stopButton.disableProperty().bind(running.not());
        // Blocked until both channels are connected.
        startButton.disableProperty().bind(Bindings.createBooleanBinding(
                () -> running.get() || scriptPath.get() == null
                        || !client.connectedProperty().get() || !server.connectedProperty().get(),
                running, scriptPath, client.connectedProperty(), server.connectedProperty()));

        HBox row = new HBox(8, new Label("Script:"), pathField, loadButton, startButton, stopButton);
        row.setAlignment(Pos.CENTER_LEFT);

        log.setEditable(false);
        log.setPrefRowCount(7);
        log.setStyle("-fx-font-family: 'Consolas', 'Monospaced';");

        setSpacing(6);
        setPadding(new Insets(10));
        setStyle("-fx-border-color: #b0b0b0; -fx-border-radius: 4;");
        getChildren().addAll(row, stateLabel, log);
    }

    private void chooseFile() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Load protocol script");
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Protocol scripts (*.tcps)", "*.tcps"),
                new FileChooser.ExtensionFilter("All files", "*.*"));
        Path current = scriptPath.get();
        if (current != null && current.getParent() != null) {
            chooser.setInitialDirectory(current.getParent().toFile());
        }
        File file = chooser.showOpenDialog(getScene().getWindow());
        if (file == null) return;
        scriptPath.set(file.toPath());
        pathField.setText(file.getAbsolutePath());
        try {
            Script s = read(file.toPath());
            stateLabel.setText("Loaded: " + s.steps().size() + " steps");
            log("Loaded " + file.getName() + " (" + s.steps().size() + " steps)");
        } catch (ScriptException | IOException e) {
            stateLabel.setText("Script error");
            log("ERROR " + e.getMessage());
        }
    }

    private static Script read(Path path) throws IOException, ScriptException {
        return ScriptParser.parse(Files.readString(path, StandardCharsets.UTF_8));
    }

    private void start() {
        Script script;
        try {
            script = read(scriptPath.get()); // re-read so external edits take effect
        } catch (ScriptException | IOException e) {
            stateLabel.setText("Script error");
            log("ERROR " + e.getMessage());
            return;
        }
        log.clear();
        ScriptRunner r = new ScriptRunner(script,
                Map.of(Channel.CLIENT, client, Channel.SERVER, server), new RunListener());
        runner = r;
        attach(client, Channel.CLIENT, r);
        attach(server, Channel.SERVER, r);
        client.setManualSendBlocked(true);
        server.setManualSendBlocked(true);
        running.set(true);
        log("Protocol started: " + script.steps().size() + " steps");
        r.start();
    }

    private static void attach(ChannelPanel panel, Channel channel, ScriptRunner r) {
        panel.setTaps(data -> r.onData(channel, data), (status, msg) -> {
            if (LOST.contains(status)) r.onClosed(channel, msg);
        });
    }

    private void log(String text) {
        log.appendText("[" + LocalTime.now().format(TIME) + "] " + text + "\n");
    }

    /** Runner callbacks arrive on the runner thread; hop to the JavaFX thread. */
    private final class RunListener implements ScriptRunner.Listener {
        @Override
        public void stepStarted(Step step, int index, int total) {
            Platform.runLater(() -> stateLabel.setText("Step " + index + "/" + total + ": " + step.text()));
        }

        @Override
        public void stepPassed(Step step) {
            Platform.runLater(() -> log("OK    line " + step.lineNo() + ": " + step.text()));
        }

        @Override
        public void stepFailed(Step step, String message) {
            Platform.runLater(() -> {
                log("FAIL  line " + step.lineNo() + ": " + step.text());
                log("      " + message);
            });
        }

        @Override
        public void finished(boolean passed, String summary) {
            Platform.runLater(() -> {
                client.setTaps(null, null);
                server.setTaps(null, null);
                client.setManualSendBlocked(false);
                server.setManualSendBlocked(false);
                running.set(false);
                stateLabel.setText(summary);
                log(summary);
            });
        }
    }
}
