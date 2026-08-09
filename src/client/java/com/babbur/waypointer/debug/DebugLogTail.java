package com.babbur.waypointer.debug;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class DebugLogTail {

    private static final int MAX_TAIL_BYTES = 256 * 1024;
    private static final int MAX_LINE_CHARS = 500;
    private static final int MAX_STACK_CONTINUATION_LINES = 24;

    private DebugLogTail() {
    }

    public static List<String> capture(int maxLines) {
        Path latestLog = FabricLoader.getInstance().getGameDir().resolve("logs").resolve("latest.log");
        return readRelevant(latestLog, maxLines);
    }

    static List<String> readRelevant(Path file, int maxLines) {
        if (file == null || maxLines <= 0 || !Files.isRegularFile(file)) return List.of();
        byte[] tail;
        boolean startsMidFile;
        try (RandomAccessFile input = new RandomAccessFile(file.toFile(), "r")) {
            long length = input.length();
            long start = Math.max(0L, length - MAX_TAIL_BYTES);
            startsMidFile = start > 0L;
            input.seek(start);
            tail = new byte[(int) (length - start)];
            input.readFully(tail);
        } catch (IOException | RuntimeException ignored) {
            return List.of();
        }

        String text = new String(tail, StandardCharsets.UTF_8);
        if (startsMidFile) {
            int firstNewline = text.indexOf('\n');
            text = firstNewline < 0 ? "" : text.substring(firstNewline + 1);
        }
        String[] lines = text.split("\\R");
        List<String> relevant = new ArrayList<>();
        int stackContinuationRemaining = 0;
        for (String line : lines) {
            boolean directlyRelevant = isRelevant(line);
            boolean stackContinuation = stackContinuationRemaining > 0
                    && isStackTraceContinuation(line);
            if (!directlyRelevant && !stackContinuation) {
                stackContinuationRemaining = 0;
                continue;
            }
            String sanitized = sanitize(line);
            if (!sanitized.isBlank()) relevant.add(sanitized);
            if (directlyRelevant) {
                stackContinuationRemaining = MAX_STACK_CONTINUATION_LINES;
            } else {
                stackContinuationRemaining--;
            }
        }
        int from = Math.max(0, relevant.size() - maxLines);
        return List.copyOf(relevant.subList(from, relevant.size()));
    }

    private static boolean isRelevant(String line) {
        if (line == null || line.isBlank()) return false;
        String lower = line.toLowerCase(Locale.ROOT);
        return lower.contains("waypointer")
                || lower.contains("exception")
                || lower.contains("caused by:")
                || lower.contains("[error]")
                || lower.contains("[warn]")
                || isThrowableHeader(line);
    }

    private static boolean isStackTraceContinuation(String line) {
        if (line == null || line.isBlank()) return false;
        String trimmed = line.stripLeading();
        return trimmed.startsWith("at ")
                || trimmed.startsWith("Caused by:")
                || trimmed.startsWith("Suppressed:")
                || (trimmed.startsWith("...") && trimmed.endsWith(" more"))
                || isThrowableHeader(trimmed);
    }

    private static boolean isThrowableHeader(String line) {
        if (line == null) return false;
        String trimmed = line.strip();
        int separator = trimmed.indexOf(':');
        String type = separator < 0 ? trimmed : trimmed.substring(0, separator);
        return type.indexOf(' ') < 0
                && type.indexOf('.') > 0
                && (type.endsWith("Exception") || type.endsWith("Error"));
    }

    private static String sanitize(String line) {
        StringBuilder sanitized = new StringBuilder(Math.min(line.length(), MAX_LINE_CHARS));
        for (int i = 0; i < line.length() && sanitized.length() < MAX_LINE_CHARS; i++) {
            char c = line.charAt(i);
            sanitized.append(Character.isISOControl(c) ? ' ' : c);
        }
        if (line.length() > MAX_LINE_CHARS) sanitized.append("...");
        return sanitized.toString().trim();
    }
}
