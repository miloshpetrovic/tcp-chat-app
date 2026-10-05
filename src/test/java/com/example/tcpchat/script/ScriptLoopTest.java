package com.example.tcpchat.script;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

class ScriptLoopTest {

    private static List<String> sent(String source) throws ScriptException {
        return ScriptParser.parse(source).steps().stream()
                .filter(s -> s instanceof Step.Send)
                .map(s -> new String(((Step.Send) s).payload(), StandardCharsets.UTF_8))
                .toList();
    }

    @Test
    void repeatWithArrayInterpolation() throws Exception {
        assertEquals(List.of("this is message for A", "this is message for B", "this is message for C"),
                sent("""
                        array palettes ["A", "B", "C"]
                        repeat 3 {
                            client send "this is message for ${palettes[i]}"   # comment
                        }
                        """));
    }

    @Test
    void iterateOverArrayWithNamedVariable() throws Exception {
        assertEquals(List.of("0:x", "1:y"), sent("""
                array a ["x", "y"]
                repeat a as k {
                  client send "${k}:${a[k]}"
                }
                """));
        assertEquals(2, ScriptParser.parse("array a [\"x\",\"y\"]\nrepeat a.length {\nclient close\n}").steps().size());
    }

    @Test
    void nestedLoopsAndExpressions() throws Exception {
        assertEquals(List.of("0-0-A", "0-1-B", "1-0-A", "1-1-B"), sent("""
                array a ["A", "B"]
                repeat 2 as row {
                  repeat 2 as col {
                    client send "${row}-${col}-${a[col]}"
                  }
                }
                """));
        assertEquals(List.of("B", "C"), sent("""
                array a ["A", "B", "C"]
                repeat 2 {
                  client send "${a[i+1]}"
                }
                """));
        assertEquals(List.of("A", "B", "A"), sent("""
                array a ["A", "B", "C"]
                repeat 3 {
                  client send "${a[i % 2]}"
                }
                """));
    }

    @Test
    void escapesAndRawBytesSurviveInterpolation() throws Exception {
        Script s = ScriptParser.parse("array a [\"x\\x80\\n\"]\nclient send \"\\${lit}${a[0]}\"");
        assertArrayEquals(new byte[] {'$', '{', 'l', 'i', 't', '}', 'x', (byte) 0x80, '\n'},
                ((Step.Send) s.steps().get(0)).payload());
    }

    @Test
    void interpolationInExpectAndRegex() throws Exception {
        Script s = ScriptParser.parse("""
                array a ["A.B", "C"]
                repeat 2 {
                  client expect "got ${a[i]}"
                  client expect /ID\\d{2}${a[i]}/
                }
                """);
        assertEquals(4, s.steps().size());
        ByteMatcher regex = ((Step.Expect) s.steps().get(1)).matcher();
        assertEquals(7, regex.match("ID12A.B".getBytes(), 7));
        assertEquals(ByteMatcher.MISMATCH, regex.match("ID12AxB".getBytes(), 7)); // element is literal
    }

    @Test
    void stepTextShowsIterationAndResolvedValue() throws Exception {
        Step s = ScriptParser.parse("array a [\"A\", \"B\"]\nrepeat 2 {\nclient send \"m ${a[i]}\"\n}").steps().get(1);
        assertEquals(3, s.lineNo());
        assertTrue(s.text().contains("[i=1]") && s.text().contains("\"m B\""), s.text());
    }

    @Test
    void loadTimeErrors() {
        record Case(String src, int line, String part) { }
        List<Case> cases = List.of(
                new Case("array a [\"A\"]\nrepeat 2 {\nclient send \"${a[i]}\"\n}", 3, "out of range"),
                new Case("client send \"${nope}\"", 1, "unknown variable"),
                new Case("array a [\"A\"]\nclient send \"${a}\"", 2, "needs an index"),
                new Case("client send \"${x[0]}\"", 1, "unknown array"),
                new Case("repeat 2 {\nclient close", 1, "missing"),
                new Case("client close\n}", 2, "unexpected"),
                new Case("repeat 2 {\nrepeat 2 {\nclient close\n}\n}", 2, "already in use"),
                new Case("repeat x {\n}", 1, "unknown array"),
                new Case("repeat 2 + 1 {\n}", 1, "repeat needs"),
                new Case("repeat 99999 {\nclient close\n}", 1, "too large"),
                new Case("repeat 2 {\narray a [\"x\"]\n}", 2, "outside repeat"),
                new Case("array a [\"x\",]", 1, "usage"),
                new Case("array a [\"x\"]\narray a [\"y\"]", 2, "already defined"),
                new Case("client send \"${1 % 0}\"", 1, "modulo"));
        for (Case c : cases) {
            ScriptException e = assertThrows(ScriptException.class, () -> ScriptParser.parse(c.src()), c.src());
            assertEquals(c.line(), e.line(), c.src() + " -> " + e.getMessage());
            assertTrue(e.getMessage().contains(c.part()), c.src() + " -> " + e.getMessage());
        }
    }

    @Test
    void stepCapStopsRunawayLoops() {
        ScriptException e = assertThrows(ScriptException.class, () -> ScriptParser.parse("""
                repeat 5000 as a {
                  repeat 5000 as b {
                    client close
                  }
                }
                """));
        assertTrue(e.getMessage().contains("10000"), e.getMessage());
    }

    @Test
    void nestedArrays() throws Exception {
        assertEquals(List.of("a1", "a2", "b1", "b2", "b3"), sent("""
                array structure [["a1", "a2"], ["b1", "b2", "b3"]]
                repeat structure as r {
                  repeat structure[r] as c {
                    client send "${structure[r][c]}"
                  }
                }
                """));
        assertEquals(List.of("2/3", "3:b1"), sent("""
                array s [["a1", "a2"], ["b1", "b2", "b3"], []]
                client send "${s[0].length}/${s.length}"
                client send "${s.length}:${s[1][0]}"
                """));
        assertEquals(List.of("x", "y", "z"), sent("""
                array deep [["x", ["y"]], "z"]
                client send "${deep[0][0]}"
                client send "${deep[0][1][0]}"
                client send "${deep[1]}"
                """));
        assertEquals(2, ScriptParser.parse("array s [[\"a\"],[\"b\"]]\nrepeat s.length {\nclient close\n}").steps().size());
        assertEquals(2, ScriptParser.parse("array s [[\"a\", \"b\"]]\nrepeat s[0].length as k {\nclient close\n}").steps().size());
    }

    @Test
    void nestedArrayErrors() {
        record Case(String src, int line, String part) { }
        String decl = "array s [[\"a\"], \"b\"]\n";
        List<Case> cases = List.of(
                new Case(decl + "client send \"${s[0]}\"", 2, "needs an index"),
                new Case(decl + "client send \"${s[0][1]}\"", 2, "out of range for 's[0]'"),
                new Case(decl + "client send \"${s[1][0]}\"", 2, "cannot be indexed"),
                new Case(decl + "repeat s[1] {\nclient close\n}", 2, "not an array"),
                new Case(decl + "client send \"${s[2]}\"", 2, "out of range"),
                new Case("array s [[\"a\", ]]", 1, "usage"),
                new Case("array s [[\"a\"]", 1, "usage"),
                new Case("array s [[\"a\"]] x", 1, "usage"),
                new Case("array s [\"a\" \"b\"]", 1, "usage"));
        for (Case c : cases) {
            ScriptException e = assertThrows(ScriptException.class, () -> ScriptParser.parse(c.src()), c.src());
            assertEquals(c.line(), e.line(), c.src() + " -> " + e.getMessage());
            assertTrue(e.getMessage().contains(c.part()), c.src() + " -> " + e.getMessage());
        }
    }

    @Test
    void exampleScriptsParse() throws Exception {
        for (String f : List.of("demo.tcps", "loops.tcps")) {
            String src = java.nio.file.Files.readString(java.nio.file.Path.of("examples", f));
            assertTrue(!ScriptParser.parse(src).steps().isEmpty(), f);
        }
    }
}
