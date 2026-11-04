package com.babbur.waypointer;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourceEncodingTest {

    private static final Path SOURCE_DIR = Path.of("src");

    private static final Pattern MOJIBAKE = Pattern.compile(
            "[ÂÃ�\u0080-\u009f]|â€");

    @Test
    void shippedJavaSourcesContainNoMojibake() throws IOException {
        List<String> problems = new ArrayList<>();
        List<Path> roots = shippedSourceRoots();
        assertTrue(roots.contains(SOURCE_DIR.resolve("main").resolve("java")));
        assertTrue(roots.contains(SOURCE_DIR.resolve("client").resolve("java")));

        int scanned = 0;
        for (Path root : roots) {
            try (Stream<Path> files = Files.walk(root)) {
                for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                    problems.addAll(mojibakeIn(file));
                    scanned++;
                }
            }
        }

        assertTrue(scanned > 100, "expected to scan the mod's sources, found " + scanned);
        assertTrue(problems.isEmpty(), () -> "Mis-encoded text in sources:\n"
                + String.join("\n", problems));
    }

    @Test
    void detectorFlagsCommonMisDecodingsButNotRealText() {
        assertTrue(hasMojibake("Secret Â· Item"));
        assertTrue(hasMojibake("Tout dÃ©sactiver"));
        assertTrue(hasMojibake("Secret Route â€” A"));
        assertTrue(hasMojibake("broken � text"));
        assertTrue(hasMojibake("latin-1 \u0092 quote"));
        assertTrue(hasMojibake("\" \\u00C2\\u00B7 \""));

        assertFalse(hasMojibake("Secret · Item — Khazad-dûm §c ✓"));
        assertFalse(hasMojibake("\" \\u00b7 \\u00a7 \""));
        assertFalse(hasMojibake("\"\\\\u00c3\""));
    }

    @Test
    void unicodeEscapesDecodeLikeTheJavaCompiler() {
        assertEquals("a·b", decodeUnicodeEscapes("a\\u00b7b"));
        assertEquals("é", decodeUnicodeEscapes("\\uuu00E9"));
        assertEquals("\\\\u00e9", decodeUnicodeEscapes("\\\\u00e9"));
        assertEquals("\\u00zz", decodeUnicodeEscapes("\\u00zz"));
    }

    private static List<Path> shippedSourceRoots() throws IOException {
        List<Path> roots = new ArrayList<>();
        roots.add(SOURCE_DIR.resolve("main").resolve("java"));
        roots.add(SOURCE_DIR.resolve("client").resolve("java"));
        try (Stream<Path> children = Files.list(SOURCE_DIR)) {
            children.filter(Files::isDirectory)
                    .filter(dir -> dir.getFileName().toString().startsWith("client-"))
                    .map(dir -> dir.resolve("java"))
                    .filter(Files::isDirectory)
                    .sorted()
                    .forEach(roots::add);
        }
        return roots;
    }

    private static List<String> mojibakeIn(Path file) throws IOException {
        String source;
        try {
            source = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(Files.readAllBytes(file)))
                    .toString();
        } catch (CharacterCodingException invalid) {
            return List.of(file + ": not valid UTF-8");
        }
        List<String> problems = new ArrayList<>();
        String[] lines = source.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (hasMojibake(lines[i])) {
                problems.add(file + ":" + (i + 1) + ": " + lines[i].strip());
            }
        }
        return problems;
    }

    private static boolean hasMojibake(String line) {
        Matcher matcher = MOJIBAKE.matcher(decodeUnicodeEscapes(line));
        return matcher.find();
    }

    private static String decodeUnicodeEscapes(String line) {
        StringBuilder out = new StringBuilder(line.length());
        int i = 0;
        while (i < line.length()) {
            char c = line.charAt(i);
            if (c != '\\') {
                out.append(c);
                i++;
                continue;
            }
            int backslashes = 0;
            while (i + backslashes < line.length() && line.charAt(i + backslashes) == '\\') {
                backslashes++;
            }
            if (backslashes % 2 == 0) {
                out.append("\\".repeat(backslashes));
                i += backslashes;
                continue;
            }
            out.append("\\".repeat(backslashes - 1));
            i += backslashes - 1;
            int cursor = i + 1;
            if (cursor < line.length() && line.charAt(cursor) == 'u') {
                while (cursor < line.length() && line.charAt(cursor) == 'u') cursor++;
                if (cursor + 4 <= line.length() && isHex(line, cursor)) {
                    out.append((char) Integer.parseInt(line.substring(cursor, cursor + 4), 16));
                    i = cursor + 4;
                    continue;
                }
            }
            out.append('\\');
            i++;
        }
        return out.toString();
    }

    private static boolean isHex(String text, int start) {
        for (int i = start; i < start + 4; i++) {
            if (Character.digit(text.charAt(i), 16) < 0) return false;
        }
        return true;
    }
}
