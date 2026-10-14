package com.example.tcpchat.script.groovy;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A received message as seen by a Groovy script: the exact bytes, their text, and, when it was matched
 * with a regex, its capture groups ({@code m[1]}; {@code m[0]} is the whole match).
 */
public final class Msg {
    private final byte[] bytes;
    private final Charset charset;
    private final List<String> groups;

    private Msg(byte[] bytes, Charset charset, List<String> groups) {
        this.bytes = bytes;
        this.charset = charset;
        this.groups = groups;
    }

    static Msg of(byte[] bytes, Charset charset, Pattern pattern) {
        List<String> groups = List.of();
        if (pattern != null) {
            Matcher m = pattern.matcher(new String(bytes, charset));
            if (m.lookingAt()) {
                groups = new ArrayList<>();
                for (int i = 0; i <= m.groupCount(); i++) groups.add(m.group(i));
            }
        }
        return new Msg(bytes, charset, groups);
    }

    public String getText() {
        return new String(bytes, charset);
    }

    public byte[] getBytes() {
        return bytes.clone();
    }

    public List<String> getGroups() {
        return groups;
    }

    /** Groovy subscript: {@code m[1]}. */
    public String getAt(int group) {
        return groups.get(group);
    }

    public int size() {
        return bytes.length;
    }

    @Override
    public String toString() {
        return getText();
    }
}
