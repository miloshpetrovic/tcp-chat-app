package com.example.tcpchat.ui;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * Single chat-style history (SENT and RECEIVED together) plus the message input row.
 * Must only be used from the JavaFX thread. Knows nothing about sockets: it hands bytes to a {@link Sender}.
 */
final class ChatView extends VBox {

    @FunctionalInterface
    interface Sender {
        void send(byte[] data) throws IOException;
    }

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final Sender sender;
    private final TextArea history = new TextArea();
    private final TextField input = new TextField();
    private final Button sendButton = new Button("Send");
    private final Button clearButton = new Button("Clear");
    private final ComboBox<MessageEncoding> encodingBox = new ComboBox<>();

    // Incremental decoder: a multi-byte character may be split across two socket reads.
    private CharsetDecoder decoder;
    private ByteBuffer leftover = ByteBuffer.allocate(0);

    ChatView(Sender sender) {
        this.sender = sender;

        history.setEditable(false);
        history.setWrapText(true);
        history.setStyle("-fx-font-family: 'Consolas', 'Monospaced';");
        VBox.setVgrow(history, Priority.ALWAYS);

        encodingBox.getItems().setAll(MessageEncoding.values());
        encodingBox.setValue(MessageEncoding.UTF_8);
        encodingBox.valueProperty().addListener((obs, old, now) -> resetDecoder());
        resetDecoder();

        input.setPromptText("Message (sent exactly as typed, no newline added)");
        HBox.setHgrow(input, Priority.ALWAYS);
        // Enter only triggers sending; it is never part of the payload.
        input.setOnAction(e -> sendCurrentMessage());
        sendButton.setOnAction(e -> sendCurrentMessage());
        clearButton.setOnAction(e -> history.clear());
        setSendEnabled(false);

        HBox inputRow = new HBox(8, new Label("Message:"), input, sendButton);
        inputRow.setAlignment(Pos.CENTER_LEFT);
        HBox bottomRow = new HBox(8, new Label("Encoding:"), encodingBox, clearButton);
        bottomRow.setAlignment(Pos.CENTER_RIGHT);

        setSpacing(8);
        setPadding(new Insets(0));
        getChildren().addAll(history, inputRow, bottomRow);
        VBox.setVgrow(this, Priority.ALWAYS);
    }

    void setSendEnabled(boolean enabled) {
        input.setDisable(!enabled);
        sendButton.setDisable(!enabled);
    }

    void appendInfo(String text) {
        line("INFO", text);
    }

    void appendError(String text) {
        line("ERROR", text);
    }

    /** Decodes received bytes with the selected encoding and shows them unmodified. */
    void appendReceived(byte[] data) {
        ByteBuffer in = ByteBuffer.allocate(leftover.remaining() + data.length);
        in.put(leftover).put(data).flip();
        CharBuffer out = CharBuffer.allocate((int) (in.remaining() * decoder.maxCharsPerByte()) + 1);
        decoder.decode(in, out, false);
        leftover = ByteBuffer.allocate(in.remaining()).put(in).flip();
        out.flip();
        if (out.hasRemaining()) {
            line("RECEIVED", out.toString());
        }
    }

    private void resetDecoder() {
        decoder = encodingBox.getValue().charset().newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        leftover = ByteBuffer.allocate(0);
    }

    private void sendCurrentMessage() {
        String message = input.getText(); // deliberately not trimmed
        if (message.isEmpty()) {
            return;
        }
        byte[] data;
        try {
            ByteBuffer encoded = encodingBox.getValue().charset().newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(message));
            // the backing array may be larger than the encoded content: copy exactly the encoded bytes
            data = new byte[encoded.remaining()];
            encoded.get(data);
        } catch (CharacterCodingException e) {
            appendError("Message contains characters not representable in " + encodingBox.getValue());
            return;
        }
        try {
            sender.send(data);
        } catch (IOException e) {
            appendError("Unable to send message: " + e.getMessage());
            return;
        }
        line("SENT", message);
        input.clear();
    }

    private void line(String kind, String text) {
        history.appendText("[" + LocalTime.now().format(TIME) + "] " + kind + ": " + text + "\n");
    }
}
