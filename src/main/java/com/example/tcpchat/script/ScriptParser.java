package com.example.tcpchat.script;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Parses the line-based protocol script language. See examples/demo.tcps for the syntax.
 * Strings are converted to bytes with the encoding in effect at that line (directive "encoding").
 */
public final class ScriptParser {

    private enum Kind { WORD, STRING, REGEX }

    private record Token(Kind kind, String text, byte[] bytes) {
        boolean isWord(String w) {
            return kind == Kind.WORD && text.equalsIgnoreCase(w);
        }
    }

    private static final Pattern DURATION = Pattern.compile("(\\d+)(ms|s|m)");

    private Charset charset = StandardCharsets.UTF_8;
    private Duration defaultTimeout = Duration.ofSeconds(5);

    public static Script parse(String source) throws ScriptException {
        return new ScriptParser().doParse(source);
    }

    private Script doParse(String source) throws ScriptException {
        List<Step> steps = new ArrayList<>();
        String[] lines = source.split("\\R", -1);
        for (int i = 0; i < lines.length; i++) {
            int no = i + 1;
            List<Token> t = tokenize(lines[i], no);
            if (t.isEmpty()) continue;
            String text = lines[i].strip();
            Token head = t.get(0);
            if (head.kind != Kind.WORD) throw new ScriptException(no, "a command word is expected first");
            switch (head.text.toLowerCase(Locale.ROOT)) {
                case "encoding" -> {
                    needCount(t, 2, no, "encoding <UTF-8|ASCII>");
                    charset = parseCharset(t.get(1).text, no);
                }
                case "timeout" -> {
                    needCount(t, 2, no, "timeout <duration>");
                    defaultTimeout = parseDuration(t.get(1), no);
                }
                case "pause" -> {
                    needCount(t, 2, no, "pause <duration>");
                    steps.add(new Step.Pause(no, text, parseDuration(t.get(1), no)));
                }
                case "client", "server" -> steps.add(parseChannelStep(t, no, text));
                default -> throw new ScriptException(no, "unknown command '" + head.text + "'");
            }
        }
        return new Script(charset, List.copyOf(steps));
    }

    private Step parseChannelStep(List<Token> t, int no, String text) throws ScriptException {
        Channel channel = t.get(0).text.equalsIgnoreCase("client") ? Channel.CLIENT : Channel.SERVER;
        if (t.size() < 2 || t.get(1).kind != Kind.WORD) {
            throw new ScriptException(no, "expected send, expect or close after '" + channel + "'");
        }
        List<Token> rest = t.subList(2, t.size());
        switch (t.get(1).text.toLowerCase(Locale.ROOT)) {
            case "send":
                return new Step.Send(no, text, channel, parsePayload(rest, no));
            case "close":
                if (!rest.isEmpty()) throw new ScriptException(no, "'close' takes no arguments");
                return new Step.Close(no, text, channel);
            case "expect":
                Duration timeout = defaultTimeout;
                int n = rest.size();
                if (n >= 2 && rest.get(n - 2).isWord("timeout")) {
                    timeout = parseDuration(rest.get(n - 1), no);
                    rest = rest.subList(0, n - 2);
                }
                return new Step.Expect(no, text, channel, parseMatcher(rest, no), timeout);
            default:
                throw new ScriptException(no, "unknown action '" + t.get(1).text + "' (use send, expect or close)");
        }
    }

    private byte[] parsePayload(List<Token> rest, int no) throws ScriptException {
        if (rest.size() == 1 && rest.get(0).kind == Kind.STRING) {
            return rest.get(0).bytes;
        }
        if (!rest.isEmpty() && rest.get(0).isWord("hex")) {
            return parseHex(rest.subList(1, rest.size()), no);
        }
        throw new ScriptException(no, "send needs \"text\" or: hex 41 42 ...");
    }

    private ByteMatcher parseMatcher(List<Token> rest, int no) throws ScriptException {
        if (rest.size() == 1 && rest.get(0).kind == Kind.STRING) {
            return ByteMatcher.exact(rest.get(0).bytes);
        }
        if (rest.size() == 1 && rest.get(0).kind == Kind.REGEX) {
            try {
                return ByteMatcher.regex(Pattern.compile(rest.get(0).text), charset);
            } catch (PatternSyntaxException e) {
                throw new ScriptException(no, "invalid regex: " + e.getDescription());
            }
        }
        if (rest.size() == 1 && rest.get(0).isWord("any")) {
            return ByteMatcher.any();
        }
        if (rest.size() == 2 && rest.get(0).isWord("contains") && rest.get(1).kind == Kind.STRING) {
            return ByteMatcher.contains(rest.get(1).bytes);
        }
        if (!rest.isEmpty() && rest.get(0).isWord("hex")) {
            return ByteMatcher.exact(parseHex(rest.subList(1, rest.size()), no));
        }
        throw new ScriptException(no, "expect needs \"text\", contains \"text\", /regex/, hex .. or any");
    }

    private static byte[] parseHex(List<Token> tokens, int no) throws ScriptException {
        if (tokens.isEmpty()) throw new ScriptException(no, "hex needs at least one byte");
        List<String> words = new ArrayList<>();
        for (Token t : tokens) {
            if (t.kind != Kind.WORD) throw new ScriptException(no, "hex bytes must be plain two-digit values");
            words.add(t.text);
        }
        String[] bad = new String[1];
        byte[] out = Bytes.parseHex(words, bad);
        if (out == null) throw new ScriptException(no, "invalid hex byte '" + bad[0] + "'");
        return out;
    }

    private static Duration parseDuration(Token t, int no) throws ScriptException {
        var m = DURATION.matcher(t.kind == Kind.WORD ? t.text : "");
        if (!m.matches()) throw new ScriptException(no, "invalid duration '" + t.text + "' (examples: 500ms, 2s, 1m)");
        long v = Long.parseLong(m.group(1));
        return switch (m.group(2)) {
            case "ms" -> Duration.ofMillis(v);
            case "s" -> Duration.ofSeconds(v);
            default -> Duration.ofMinutes(v);
        };
    }

    private static Charset parseCharset(String name, int no) throws ScriptException {
        return switch (name.toUpperCase(Locale.ROOT)) {
            case "UTF-8", "UTF8" -> StandardCharsets.UTF_8;
            case "ASCII", "US-ASCII" -> StandardCharsets.US_ASCII;
            default -> throw new ScriptException(no, "unsupported encoding '" + name + "' (use UTF-8 or ASCII)");
        };
    }

    private static void needCount(List<Token> t, int n, int no, String usage) throws ScriptException {
        if (t.size() != n) throw new ScriptException(no, "usage: " + usage);
    }

    private List<Token> tokenize(String line, int no) throws ScriptException {
        List<Token> tokens = new ArrayList<>();
        int i = 0;
        int n = line.length();
        while (i < n) {
            char c = line.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '#') {
                break;
            } else if (c == '"') {
                i = readString(line, i + 1, no, tokens);
            } else if (c == '/') {
                int start = ++i;
                while (i < n && line.charAt(i) != '/') {
                    if (line.charAt(i) == '\\') i++; // keep escapes for the regex engine
                    i++;
                }
                if (i >= n) throw new ScriptException(no, "unterminated /regex/");
                tokens.add(new Token(Kind.REGEX, line.substring(start, i), null));
                i++;
            } else {
                int start = i;
                while (i < n && !Character.isWhitespace(line.charAt(i))) i++;
                tokens.add(new Token(Kind.WORD, line.substring(start, i), null));
            }
        }
        return tokens;
    }

    /** Reads a string body starting after the opening quote; returns the index after the closing quote. */
    private int readString(String line, int i, int no, List<Token> out) throws ScriptException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        StringBuilder text = new StringBuilder();
        StringBuilder shown = new StringBuilder();
        int n = line.length();
        while (i < n) {
            char c = line.charAt(i++);
            if (c == '"') {
                flush(text, bytes);
                out.add(new Token(Kind.STRING, shown.toString(), bytes.toByteArray()));
                return i;
            }
            shown.append(c);
            if (c != '\\') {
                text.append(c);
                continue;
            }
            if (i >= n) break;
            char e = line.charAt(i++);
            switch (e) {
                case 'n' -> text.append('\n');
                case 'r' -> text.append('\r');
                case 't' -> text.append('\t');
                case '0' -> text.append('\0');
                case '\\' -> text.append('\\');
                case '"' -> text.append('"');
                case 'x' -> {
                    if (i + 2 > n) throw new ScriptException(no, "\\x needs two hex digits");
                    try {
                        int v = Integer.parseInt(line.substring(i, i + 2), 16);
                        flush(text, bytes);
                        bytes.write(v); // raw byte, not re-encoded
                    } catch (NumberFormatException ex) {
                        throw new ScriptException(no, "\\x needs two hex digits");
                    }
                    i += 2;
                }
                default -> throw new ScriptException(no, "unknown escape \\" + e);
            }
        }
        throw new ScriptException(no, "unterminated string");
    }

    private void flush(StringBuilder text, ByteArrayOutputStream bytes) {
        bytes.writeBytes(text.toString().getBytes(charset));
        text.setLength(0);
    }
}
