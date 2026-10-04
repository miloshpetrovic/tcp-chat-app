package com.example.tcpchat.script;

import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.regex.Pattern;

/**
 * Decides whether the unconsumed receive buffer satisfies an expectation.
 * {@link #match} returns the number of bytes to consume (>= 0), {@link #NEED_MORE} or {@link #MISMATCH}.
 */
public interface ByteMatcher {
    int NEED_MORE = -1;
    int MISMATCH = -2;

    int match(byte[] data, int len);

    String describe();

    /** The buffer must START with these bytes; exactly these bytes are consumed. */
    static ByteMatcher exact(byte[] expected) {
        return new ByteMatcher() {
            @Override
            public int match(byte[] data, int len) {
                int common = Math.min(len, expected.length);
                if (!Arrays.equals(data, 0, common, expected, 0, common)) return MISMATCH;
                return len < expected.length ? NEED_MORE : expected.length;
            }

            @Override
            public String describe() {
                return Bytes.escape(expected, expected.length);
            }
        };
    }

    /** Waits until the bytes appear anywhere in the buffer; consumes everything up to the end of the first occurrence. */
    static ByteMatcher contains(byte[] needle) {
        return new ByteMatcher() {
            @Override
            public int match(byte[] data, int len) {
                for (int i = 0; i + needle.length <= len; i++) {
                    if (Arrays.equals(data, i, i + needle.length, needle, 0, needle.length)) {
                        return i + needle.length;
                    }
                }
                return NEED_MORE;
            }

            @Override
            public String describe() {
                return "contains " + Bytes.escape(needle, needle.length);
            }
        };
    }

    /** The whole currently buffered text must match the regex; everything buffered is consumed. */
    static ByteMatcher regex(Pattern pattern, Charset charset) {
        return new ByteMatcher() {
            @Override
            public int match(byte[] data, int len) {
                if (len == 0) return NEED_MORE;
                java.util.regex.Matcher m = pattern.matcher(new String(data, 0, len, charset));
                if (m.matches()) return len;
                return m.hitEnd() ? NEED_MORE : MISMATCH;
            }

            @Override
            public String describe() {
                return "/" + pattern.pattern() + "/";
            }
        };
    }

    /** Any non-empty data; consumes everything buffered. */
    static ByteMatcher any() {
        return new ByteMatcher() {
            @Override
            public int match(byte[] data, int len) {
                return len > 0 ? len : NEED_MORE;
            }

            @Override
            public String describe() {
                return "any data";
            }
        };
    }
}
