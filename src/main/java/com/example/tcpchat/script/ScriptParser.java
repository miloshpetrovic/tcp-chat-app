package com.example.tcpchat.script;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;

/**
 * Parses the line-based protocol script language (see examples/demo.tcps).
 *
 * <p>The script is first read into a tree (lines and repeat blocks), then unrolled into a flat list of
 * steps: loop variables and array elements are substituted at that point, so every error (bad index,
 * unknown variable, missing brace) is reported at load time, with its line number, before anything is sent.
 * Strings are converted to bytes with the encoding in effect at that line (directive "encoding").
 */
public final class ScriptParser {

    private static final int MAX_STEPS = 10_000;
    private static final Pattern DURATION = Pattern.compile("(\\d+)(ms|s|m)");
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_]\\w*");
    /** name, any number of [index] parts, optional .length */
    private static final Pattern PATH = Pattern.compile("([A-Za-z_]\\w*)((?:\\s*\\[[^\\]]*])*)(\\.length)?");
    private static final Pattern INDEX = Pattern.compile("\\[([^\\]]*)]");
    private static final Pattern INT_EXPR = Pattern.compile("([A-Za-z_]\\w*|\\d+)\\s*(?:([+\\-%])\\s*(\\d+))?");

    private enum Kind { WORD, STRING, REGEX }

    /** For STRING and REGEX tokens {@code text} is the raw body: escapes and ${...} are still unprocessed. */
    private record Token(Kind kind, String text) {
        boolean isWord(String w) {
            return kind == Kind.WORD && text.equalsIgnoreCase(w);
        }
    }

    private sealed interface Node permits Line, Repeat {
    }

    private record Line(int no, String text, List<Token> tokens) implements Node {
    }

    private record Repeat(int no, String text, String count, String var, List<Node> body) implements Node {
    }

    /** Array contents: a string constant or a (possibly nested) list. Strings keep their escapes until used. */
    private sealed interface Value permits Str, Arr {
    }

    private record Str(String raw) implements Value {
    }

    private record Arr(List<Value> items) implements Value {
    }

    /** Result of evaluating a ${...} expression. Array elements still contain escapes (escaped = true). */
    private record Resolved(String text, boolean escaped) {
    }

    private final Map<String, Arr> arrays = new LinkedHashMap<>();
    private final Map<String, Integer> vars = new LinkedHashMap<>(); // active loop variables, outermost first
    private final List<Step> steps = new ArrayList<>();
    private Charset charset = StandardCharsets.UTF_8;
    private Duration defaultTimeout = Duration.ofSeconds(5);

    public static Script parse(String source) throws ScriptException {
        return new ScriptParser().doParse(source);
    }

    // ---------------------------------------------------------------- structure

    private Script doParse(String source) throws ScriptException {
        List<Node> root = new ArrayList<>();
        Deque<Repeat> open = new ArrayDeque<>();
        String[] lines = source.split("\\R", -1);
        for (int i = 0; i < lines.length; i++) {
            int no = i + 1;
            List<Token> t = tokenize(lines[i], no);
            if (t.isEmpty()) continue;
            String text = lines[i].strip();
            Token head = t.get(0);
            if (head.isWord("}")) {
                if (open.isEmpty()) throw new ScriptException(no, "unexpected '}'");
                if (t.size() > 1) throw new ScriptException(no, "nothing may follow '}' on the same line");
                open.pop();
                continue;
            }
            List<Node> target = open.isEmpty() ? root : open.peek().body();
            if (head.isWord("repeat")) {
                Repeat r = parseRepeatHeader(t, no, text);
                target.add(r);
                open.push(r);
            } else if (head.isWord("array")) {
                if (!open.isEmpty()) throw new ScriptException(no, "arrays must be defined outside repeat blocks");
                parseArray(t, no);
            } else {
                target.add(new Line(no, text, t));
            }
        }
        if (!open.isEmpty()) throw new ScriptException(open.peek().no(), "repeat block is missing its closing '}'");
        expand(root);
        return new Script(charset, List.copyOf(steps));
    }

    private Repeat parseRepeatHeader(List<Token> t, int no, String text) throws ScriptException {
        String usage = "usage: repeat <N|array|array[i]|array.length> [as <name>] {";
        int n = t.size();
        if (n < 3 || !t.get(n - 1).isWord("{")) throw new ScriptException(no, usage);
        String var = "i";
        int countEnd = n - 1; // exclusive
        if (n >= 5 && t.get(n - 3).isWord("as")) {
            Token name = t.get(n - 2);
            if (name.kind() != Kind.WORD || !IDENTIFIER.matcher(name.text()).matches()) throw new ScriptException(no, usage);
            var = name.text();
            countEnd = n - 3;
        }
        StringBuilder count = new StringBuilder();
        for (int i = 1; i < countEnd; i++) {
            if (t.get(i).kind() != Kind.WORD) throw new ScriptException(no, usage);
            count.append(t.get(i).text()); // tokens "structure", "[", "r", "]" -> structure[r]
        }
        if (count.isEmpty()) throw new ScriptException(no, usage);
        return new Repeat(no, text, count.toString(), var, new ArrayList<>());
    }

    private void parseArray(List<Token> t, int no) throws ScriptException {
        String usage = "usage: array <name> [\"a\", [\"b\", \"c\"], ...]";
        if (t.size() < 4 || t.get(1).kind() != Kind.WORD) throw new ScriptException(no, usage);
        String name = t.get(1).text();
        if (!IDENTIFIER.matcher(name).matches()) throw new ScriptException(no, "invalid array name '" + name + "'");
        if (arrays.containsKey(name)) throw new ScriptException(no, "array '" + name + "' is already defined");
        int[] pos = {2};
        Arr value = parseList(t, pos, no, usage);
        if (pos[0] != t.size()) throw new ScriptException(no, usage);
        arrays.put(name, value);
    }

    /** Recursive descent over [ item, item, ... ] where an item is a string or another list. */
    private Arr parseList(List<Token> t, int[] pos, int no, String usage) throws ScriptException {
        if (pos[0] >= t.size() || !t.get(pos[0]).isWord("[")) throw new ScriptException(no, usage);
        pos[0]++;
        List<Value> items = new ArrayList<>();
        if (pos[0] < t.size() && t.get(pos[0]).isWord("]")) {
            pos[0]++;
            return new Arr(List.copyOf(items));
        }
        while (true) {
            if (pos[0] >= t.size()) throw new ScriptException(no, usage);
            Token tok = t.get(pos[0]);
            if (tok.kind() == Kind.STRING) {
                items.add(new Str(tok.text()));
                pos[0]++;
            } else if (tok.isWord("[")) {
                items.add(parseList(t, pos, no, usage));
            } else {
                throw new ScriptException(no, usage);
            }
            if (pos[0] >= t.size()) throw new ScriptException(no, usage);
            Token sep = t.get(pos[0]++);
            if (sep.isWord("]")) return new Arr(List.copyOf(items));
            if (!sep.isWord(",")) throw new ScriptException(no, usage);
        }
    }

    // ---------------------------------------------------------------- unrolling

    private void expand(List<Node> nodes) throws ScriptException {
        for (Node node : nodes) {
            switch (node) {
                case Line l -> executeLine(l);
                case Repeat r -> {
                    int count = evalCount(r);
                    if (vars.containsKey(r.var())) {
                        throw new ScriptException(r.no(), "loop variable '" + r.var() + "' is already in use; name it with: as <name>");
                    }
                    for (int k = 0; k < count; k++) {
                        vars.put(r.var(), k);
                        expand(r.body());
                    }
                    vars.remove(r.var());
                }
            }
        }
    }

    private int evalCount(Repeat r) throws ScriptException {
        String c = r.count();
        long value;
        Matcher path = PATH.matcher(c);
        if (c.matches("\\d+")) {
            try {
                value = Long.parseLong(c);
            } catch (NumberFormatException e) {
                throw new ScriptException(r.no(), "repeat count too large");
            }
        } else if (path.matches()) {
            boolean hasIndex = !path.group(2).isEmpty();
            if (!hasIndex && path.group(3) == null && !arrays.containsKey(c) && vars.containsKey(c)) {
                value = vars.get(c); // repeat <loop variable>
            } else {
                Value v = lookup(path.group(1), path.group(2), r.no());
                if (!(v instanceof Arr a)) throw new ScriptException(r.no(), "'" + c + "' is a string, not an array");
                value = a.items().size(); // "repeat arr" iterates it; "repeat arr.length" has the same count
            }
        } else {
            throw new ScriptException(r.no(), "repeat needs a number, an array, array[i] or array.length, got '" + c + "'");
        }
        if (value > MAX_STEPS) throw new ScriptException(r.no(), "repeat count too large (max " + MAX_STEPS + ")");
        return (int) value;
    }

    /** Walks name[idx][idx]... through the array tree. {@code indices} is the raw "[a][b]" text. */
    private Value lookup(String name, String indices, int no) throws ScriptException {
        Value v = arrays.get(name);
        if (v == null) throw new ScriptException(no, "unknown array '" + name + "'");
        StringBuilder shown = new StringBuilder(name);
        Matcher m = INDEX.matcher(indices);
        while (m.find()) {
            if (!(v instanceof Arr a)) throw new ScriptException(no, "'" + shown + "' is a string and cannot be indexed");
            int idx = evalInt(m.group(1), no);
            if (idx < 0 || idx >= a.items().size()) {
                throw new ScriptException(no, "index " + idx + " out of range for '" + shown
                        + "' (size " + a.items().size() + ")");
            }
            v = a.items().get(idx);
            shown.append('[').append(idx).append(']');
        }
        return v;
    }

    private void add(Step step) throws ScriptException {
        if (steps.size() >= MAX_STEPS) {
            throw new ScriptException(step.lineNo(), "script expands to more than " + MAX_STEPS + " steps");
        }
        steps.add(step);
    }

    /** Original line, plus the loop variables and (when interpolation happened) the resolved value. */
    private String label(String text, String resolved) {
        StringBuilder sb = new StringBuilder(text);
        if (!vars.isEmpty()) {
            sb.append("  [").append(vars.entrySet().stream()
                    .map(e -> e.getKey() + "=" + e.getValue()).collect(Collectors.joining(", "))).append(']');
        }
        if (resolved != null && text.contains("${")) sb.append("  -> ").append(resolved);
        return sb.toString();
    }

    private void executeLine(Line line) throws ScriptException {
        List<Token> t = line.tokens();
        int no = line.no();
        Token head = t.get(0);
        if (head.kind() != Kind.WORD) throw new ScriptException(no, "a command word is expected first");
        switch (head.text().toLowerCase(Locale.ROOT)) {
            case "encoding" -> {
                needCount(t, 2, no, "encoding <UTF-8|ASCII>");
                charset = parseCharset(t.get(1).text(), no);
            }
            case "timeout" -> {
                needCount(t, 2, no, "timeout <duration>");
                defaultTimeout = parseDuration(t.get(1), no);
            }
            case "pause" -> {
                needCount(t, 2, no, "pause <duration>");
                add(new Step.Pause(no, label(line.text(), null), parseDuration(t.get(1), no)));
            }
            case "client", "server" -> add(parseChannelStep(t, no, line.text()));
            default -> throw new ScriptException(no, "unknown command '" + head.text() + "'");
        }
    }

    private Step parseChannelStep(List<Token> t, int no, String text) throws ScriptException {
        Channel channel = t.get(0).text().equalsIgnoreCase("client") ? Channel.CLIENT : Channel.SERVER;
        if (t.size() < 2 || t.get(1).kind() != Kind.WORD) {
            throw new ScriptException(no, "expected send, expect or close after '" + channel + "'");
        }
        List<Token> rest = t.subList(2, t.size());
        switch (t.get(1).text().toLowerCase(Locale.ROOT)) {
            case "send": {
                byte[] payload = parsePayload(rest, no);
                return new Step.Send(no, label(text, Bytes.escape(payload, payload.length)), channel, payload);
            }
            case "close":
                if (!rest.isEmpty()) throw new ScriptException(no, "'close' takes no arguments");
                return new Step.Close(no, label(text, null), channel);
            case "expect": {
                Duration timeout = defaultTimeout;
                int n = rest.size();
                if (n >= 2 && rest.get(n - 2).isWord("timeout")) {
                    timeout = parseDuration(rest.get(n - 1), no);
                    rest = rest.subList(0, n - 2);
                }
                ByteMatcher matcher = parseMatcher(rest, no);
                return new Step.Expect(no, label(text, matcher.describe()), channel, matcher, timeout);
            }
            default:
                throw new ScriptException(no, "unknown action '" + t.get(1).text() + "' (use send, expect or close)");
        }
    }

    private byte[] parsePayload(List<Token> rest, int no) throws ScriptException {
        if (rest.size() == 1 && rest.get(0).kind() == Kind.STRING) {
            return encode(rest.get(0).text(), no);
        }
        if (!rest.isEmpty() && rest.get(0).isWord("hex")) {
            return parseHex(rest.subList(1, rest.size()), no);
        }
        throw new ScriptException(no, "send needs \"text\" or: hex 41 42 ...");
    }

    private ByteMatcher parseMatcher(List<Token> rest, int no) throws ScriptException {
        if (rest.size() == 1 && rest.get(0).kind() == Kind.STRING) {
            return ByteMatcher.exact(encode(rest.get(0).text(), no));
        }
        if (rest.size() == 1 && rest.get(0).kind() == Kind.REGEX) {
            String regex = regexText(rest.get(0).text(), no);
            try {
                return ByteMatcher.regex(Pattern.compile(regex), charset);
            } catch (PatternSyntaxException e) {
                throw new ScriptException(no, "invalid regex: " + e.getDescription());
            }
        }
        if (rest.size() == 1 && rest.get(0).isWord("any")) {
            return ByteMatcher.any();
        }
        if (rest.size() == 2 && rest.get(0).isWord("contains") && rest.get(1).kind() == Kind.STRING) {
            return ByteMatcher.contains(encode(rest.get(1).text(), no));
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
            if (t.kind() != Kind.WORD) throw new ScriptException(no, "hex bytes must be plain two-digit values");
            words.add(t.text());
        }
        String[] bad = new String[1];
        byte[] out = Bytes.parseHex(words, bad);
        if (out == null) throw new ScriptException(no, "invalid hex byte '" + bad[0] + "'");
        return out;
    }

    private static Duration parseDuration(Token t, int no) throws ScriptException {
        Matcher m = DURATION.matcher(t.kind() == Kind.WORD ? t.text() : "");
        if (!m.matches()) throw new ScriptException(no, "invalid duration '" + t.text() + "' (examples: 500ms, 2s, 1m)");
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

    // ---------------------------------------------------------------- strings, regexes, ${...}

    /** Raw string body -> bytes: processes escapes and substitutes ${...}. */
    private byte[] encode(String raw, int no) throws ScriptException {
        return encode(raw, no, true);
    }

    private void encodeInto(String raw, int no, boolean interpolate, ByteArrayOutputStream out, StringBuilder text)
            throws ScriptException {
        int n = raw.length();
        int i = 0;
        while (i < n) {
            char c = raw.charAt(i++);
            if (c == '$' && interpolate && i < n && raw.charAt(i) == '{') {
                int close = raw.indexOf('}', i);
                if (close < 0) throw new ScriptException(no, "unterminated ${");
                Resolved r = evaluate(raw.substring(i + 1, close), no);
                if (r.escaped()) {
                    encodeInto(r.text(), no, false, out, text); // array element: same escape rules, no nested ${}
                } else {
                    text.append(r.text());
                }
                i = close + 1;
            } else if (c != '\\') {
                text.append(c);
            } else {
                char e = raw.charAt(i++); // tokenizer guarantees a character follows
                switch (e) {
                    case 'n' -> text.append('\n');
                    case 'r' -> text.append('\r');
                    case 't' -> text.append('\t');
                    case '0' -> text.append('\0');
                    case '\\' -> text.append('\\');
                    case '"' -> text.append('"');
                    case '$' -> text.append('$');
                    case 'x' -> {
                        try {
                            if (i + 2 > n) throw new NumberFormatException();
                            int v = Integer.parseInt(raw.substring(i, i + 2), 16);
                            flush(text, out);
                            out.write(v); // raw byte, not re-encoded
                        } catch (NumberFormatException ex) {
                            throw new ScriptException(no, "\\x needs two hex digits");
                        }
                        i += 2;
                    }
                    default -> throw new ScriptException(no, "unknown escape \\" + e);
                }
            }
        }
    }

    private void flush(StringBuilder text, ByteArrayOutputStream out) {
        out.writeBytes(text.toString().getBytes(charset));
        text.setLength(0);
    }

    /** Regex body: left to the regex engine, except ${...} which is substituted (array elements as literal text). */
    private String regexText(String raw, int no) throws ScriptException {
        StringBuilder sb = new StringBuilder();
        int n = raw.length();
        int i = 0;
        while (i < n) {
            char c = raw.charAt(i++);
            if (c == '\\' && i < n) {
                sb.append(c).append(raw.charAt(i++));
            } else if (c == '$' && i < n && raw.charAt(i) == '{') {
                int close = raw.indexOf('}', i);
                if (close < 0) throw new ScriptException(no, "unterminated ${");
                Resolved r = evaluate(raw.substring(i + 1, close), no);
                sb.append(r.escaped() ? Pattern.quote(new String(encode(r.text(), no, false), charset)) : r.text());
                i = close + 1;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private byte[] encode(String raw, int no, boolean interpolate) throws ScriptException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        StringBuilder text = new StringBuilder();
        encodeInto(raw, no, interpolate, out, text);
        flush(text, out);
        return out.toByteArray();
    }

    private Resolved evaluate(String expr, int no) throws ScriptException {
        String e = expr.strip();
        Matcher path = PATH.matcher(e);
        if (path.matches() && (arrays.containsKey(path.group(1)) || !path.group(2).isEmpty() || path.group(3) != null)) {
            Value v = lookup(path.group(1), path.group(2), no);
            if (path.group(3) != null) {
                if (!(v instanceof Arr a)) throw new ScriptException(no, "'" + e + "' is a string and has no length");
                return new Resolved(String.valueOf(a.items().size()), false);
            }
            if (v instanceof Arr) {
                throw new ScriptException(no, "'" + e + "' is an array and needs an index, e.g. ${" + e + "[i]}");
            }
            return new Resolved(((Str) v).raw(), true);
        }
        return new Resolved(String.valueOf(evalInt(e, no)), false);
    }

    /** integer-or-variable, optionally followed by + n, - n or % n. */
    private int evalInt(String expr, int no) throws ScriptException {
        Matcher m = INT_EXPR.matcher(expr.strip());
        if (!m.matches()) throw new ScriptException(no, "invalid expression '" + expr.strip() + "'");
        long value;
        String term = m.group(1);
        if (Character.isDigit(term.charAt(0))) {
            value = parseLong(term, no);
        } else if (vars.containsKey(term)) {
            value = vars.get(term);
        } else if (arrays.containsKey(term)) {
            throw new ScriptException(no, "array '" + term + "' needs an index, e.g. ${" + term + "[i]}");
        } else {
            throw new ScriptException(no, "unknown variable '" + term + "'");
        }
        if (m.group(2) != null) {
            long operand = parseLong(m.group(3), no);
            switch (m.group(2)) {
                case "+" -> value += operand;
                case "-" -> value -= operand;
                default -> {
                    if (operand == 0) throw new ScriptException(no, "modulo by zero");
                    value %= operand;
                }
            }
        }
        if (value > Integer.MAX_VALUE || value < Integer.MIN_VALUE) throw new ScriptException(no, "number too large");
        return (int) value;
    }

    private static long parseLong(String s, int no) throws ScriptException {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            throw new ScriptException(no, "number too large: " + s);
        }
    }

    // ---------------------------------------------------------------- tokenizer

    private static boolean isPunct(char c) {
        return c == '{' || c == '}' || c == '[' || c == ']' || c == ',';
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
            } else if (isPunct(c)) {
                tokens.add(new Token(Kind.WORD, String.valueOf(c)));
                i++;
            } else if (c == '"' || c == '/') {
                int start = ++i;
                while (i < n && line.charAt(i) != c) {
                    if (line.charAt(i) == '\\') i++; // an escaped character never terminates the token
                    i++;
                }
                if (i >= n) throw new ScriptException(no, c == '"' ? "unterminated string" : "unterminated /regex/");
                tokens.add(new Token(c == '"' ? Kind.STRING : Kind.REGEX, line.substring(start, i)));
                i++;
            } else {
                int start = i;
                while (i < n && !Character.isWhitespace(line.charAt(i)) && !isPunct(line.charAt(i))) i++;
                tokens.add(new Token(Kind.WORD, line.substring(start, i)));
            }
        }
        return tokens;
    }
}
