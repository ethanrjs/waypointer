package com.babbur.waypointer.commands;

import com.babbur.waypointer.codec.UniversalShareCodec;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

final class WaypointCommandImport {

    private WaypointCommandImport() {
    }

    static Result readAndDecode(Path path, int maxBytes) {
        if (maxBytes < 1 || maxBytes == Integer.MAX_VALUE) {
            throw new IllegalArgumentException("maxBytes must be between 1 and 2147483646");
        }

        if (!Files.isRegularFile(path)) {
            return Result.failure(Component.translatable(
                    "waypointer.command.import.no_file", path));
        }
        try {
            long size = Files.size(path);
            if (size > maxBytes) {
                return Result.failure(Component.translatable(
                        "waypointer.command.import.file_too_large", size, maxBytes));
            }
            byte[] bytes;
            try (InputStream input = Files.newInputStream(path)) {
                bytes = input.readNBytes(maxBytes + 1);
            }
            if (bytes.length > maxBytes) {
                return Result.failure(Component.translatable(
                        "waypointer.command.import.file_too_large", bytes.length, maxBytes));
            }
            return decode(new String(bytes, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return Result.failure(Component.translatable(
                    "waypointer.command.import.read_failed", path, failureMessage(e)));
        }
    }

    static Result decode(String payload) {
        try {
            return Result.success(UniversalShareCodec.decode(payload));
        } catch (IllegalArgumentException e) {
            return Result.failure(Component.translatable(
                    "waypointer.command.import.failed", failureMessage(e)));
        }
    }

    private static String failureMessage(Exception failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? "invalid import data" : message;
    }

    record Result(UniversalShareCodec.Decoded decoded, Component error) {
        private static Result success(UniversalShareCodec.Decoded decoded) {
            return new Result(decoded, null);
        }

        private static Result failure(Component error) {
            return new Result(null, error);
        }
    }
}
