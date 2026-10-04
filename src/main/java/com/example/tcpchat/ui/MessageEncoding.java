package com.example.tcpchat.ui;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/** Text encodings offered in the GUI. Always explicit, never the platform default. */
public enum MessageEncoding {
    UTF_8("UTF-8", StandardCharsets.UTF_8),
    ASCII("ASCII", StandardCharsets.US_ASCII);

    private final String label;
    private final Charset charset;

    MessageEncoding(String label, Charset charset) {
        this.label = label;
        this.charset = charset;
    }

    public Charset charset() {
        return charset;
    }

    @Override
    public String toString() {
        return label;
    }
}
