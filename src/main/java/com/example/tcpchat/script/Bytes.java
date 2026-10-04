package com.example.tcpchat.script;

import java.util.List;

/** Small byte helpers shared by parser, matchers and messages. */
final class Bytes {
    private static final int PREVIEW_LIMIT = 120;

    private Bytes() {
    }

    /** Printable ASCII as is, everything else as \n, \r, \t, \\ or \xNN. Truncated for long input. */
    static String escape(byte[] data, int len) {
        StringBuilder sb = new StringBuilder("\"");
        int shown = Math.min(len, PREVIEW_LIMIT);
        for (int i = 0; i < shown; i++) {
            int b = data[i] & 0xFF;
            switch (b) {
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                default -> {
                    if (b >= 0x20 && b < 0x7F) sb.append((char) b);
                    else sb.append(String.format("\\x%02X", b));
                }
            }
        }
        sb.append('"');
        if (len > shown) sb.append("... (").append(len).append(" bytes)");
        return sb.toString();
    }

    /** Parses tokens such as "02", "4A", "0x41". Returns null (bad token in {@code bad[0]}) on error. */
    static byte[] parseHex(List<String> tokens, String[] bad) {
        byte[] out = new byte[tokens.size()];
        for (int i = 0; i < out.length; i++) {
            String t = tokens.get(i);
            if (t.startsWith("0x") || t.startsWith("0X")) t = t.substring(2);
            try {
                if (t.length() != 2 || t.startsWith("-") || t.startsWith("+")) throw new NumberFormatException();
                out[i] = (byte) Integer.parseInt(t, 16);
            } catch (NumberFormatException e) {
                bad[0] = tokens.get(i);
                return null;
            }
        }
        return out;
    }
}
